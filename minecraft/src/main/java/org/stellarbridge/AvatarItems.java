package org.stellarbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Monster;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.potion.Potions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.slf4j.LoggerFactory;

/**
 * Stellar Blade's HUD item slots and item uses, from Lua's HUD file (eve-hud.0/.1, SBMC_HUD 1). The render thread reads
 * it each client tick while EVE replaces the player (AvatarHudFile), keeps it for AvatarHud's heal, util, ammo and dash
 * slots, and sends each new use of a combat consumable to the server (BridgeNet.ItemUse), which applies its effect around
 * the player and advances Smart Mines each tick. Stellar Blade decides every use; Minecraft converts it.
 */
final class AvatarItems {
    record Slot(String kind, int count, int max, float cooldown, boolean usable) { }
    /**
     * One publication: heal, util and ammoAlias null and dash NaN for none; uses of shock, pulse, mine, sonic since Lua
     * loaded. The ammo's and the dash's values are ready shares (1 = ready), the util slot's cooldown a remaining share.
     */
    record Hud(String session, long seq, long time, Slot heal, Slot util, String ammoAlias, int ammo, float ammoReady,
               float dash, int[] uses) { }
    static final long FRESH_MS = 2500; // Lua's wall clock has whole seconds.
    static final int SHOCK = 0, PULSE = 1, MINE = 2, SONIC = 3;
    static final String[] USES = {"shock", "pulse", "mine", "sonic"};
    // Stellar Blade's 325 cm at 100 Unreal units a block; Smart Mines: at most 3 per owner, half a block ahead, a monster
    // within 2 blocks arms one and it goes off 10 ticks later, sparks every 10 ticks, gone after a minute.
    static final double RADIUS = 3.25, PUSH = 1, ARM = 2, AHEAD = 0.5;
    static final int MINES = 3, MINE_LIFE = 1200, MINE_FUSE = 10, SPARKS = 10, LOGGED = 20;
    private static final String KIND = "(tumbler|potion|lingering|field|shock|pulse|mine|sonic|pump|other)", COUNT = "(\\d{1,4})",
            SHARE = "(0\\.\\d\\d|1\\.00)", USE = "(\\d{1,10})";
    private static final Pattern FORMAT = Pattern.compile("SBMC_HUD 1\nSESSION ([0-9a-f]{32})\nSEQ (\\d{1,18})\nTIME (\\d{1,18})\n"
            + "HEAL (?:none|" + KIND + " " + COUNT + " " + COUNT + " ([01]))\n"
            + "UTIL (?:none|" + KIND + " " + COUNT + " " + SHARE + " ([01]))\n"
            + "AMMO (?:none|([A-Za-z0-9_]{1,64}) " + COUNT + " " + SHARE + ")\n"
            + "DASH (?:none|" + SHARE + ")\n"
            + "USES " + USE + " " + USE + " " + USE + " " + USE + "\nEND (\\d{1,18})\n");

    private static final List<Mine> mines = new ArrayList<>(); // Server thread, oldest first, as logged.
    private static int logged;

    static final class Mine {
        final ServerWorld world;
        final Vec3d at;
        final UUID owner;
        int age, armed = -1;
        Mine(ServerWorld world, Vec3d at, UUID owner) { this.world = world; this.at = at; this.owner = owner; }
    }

    /** Null unless the exact SBMC_HUD 1 grammar and bounds hold. */
    static Hud parse(String text) {
        var m = text == null ? null : FORMAT.matcher(text);
        if (m == null || !m.matches() || !m.group(2).equals(m.group(20))) return null;
        int[] uses = new int[USES.length];
        for (int k = 0; k < uses.length; k++) {
            long n = Long.parseLong(m.group(16 + k));
            if (n > Integer.MAX_VALUE) return null;
            uses[k] = (int) n;
        }
        boolean ammo = m.group(12) != null;
        return new Hud(m.group(1), Long.parseLong(m.group(2)), Long.parseLong(m.group(3)),
                m.group(4) == null ? null : new Slot(m.group(4), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)), 0, m.group(7).equals("1")),
                m.group(8) == null ? null : new Slot(m.group(8), Integer.parseInt(m.group(9)), 0, Float.parseFloat(m.group(10)), m.group(11).equals("1")),
                m.group(12), ammo ? Integer.parseInt(m.group(13)) : 0, ammo ? Float.parseFloat(m.group(14)) : 0,
                m.group(15) == null ? Float.NaN : Float.parseFloat(m.group(15)), uses);
    }

    /** The newer SEQ of the two slots for that session, or null. */
    static Hud select(String slot0, String slot1, String session) {
        Hud newest = null;
        for (String text : new String[] {slot0, slot1}) {
            Hud hud = parse(text);
            if (hud != null && hud.session().equals(session) && (newest == null || hud.seq() > newest.seq())) newest = hud;
        }
        return newest;
    }

    static boolean fresh(long time, long nowMs) { return Math.abs(nowMs - time) <= FRESH_MS; }

    /** New uses per kind since seen: a step of 1 to 3; none at a first sighting, a reset (Lua reloaded) or a larger step. */
    static int[] used(int[] seen, int[] uses) {
        int[] steps = new int[uses.length];
        for (int k = 0; seen != null && k < uses.length; k++) {
            long step = (long) uses[k] - seen[k];
            steps[k] = step >= 1 && step <= 3 ? (int) step : 0;
        }
        return steps;
    }

    /** Places a mine; its owner's oldest goes beyond MINES, other owners' mines stay. */
    static void place(List<Mine> mines, Mine mine) {
        if (mines.stream().filter(other -> other.owner.equals(mine.owner)).count() >= MINES)
            mines.remove(mines.stream().filter(other -> other.owner.equals(mine.owner)).findFirst().orElseThrow());
        mines.add(mine);
    }

    /** Server side: Smart Mines each tick. AvatarHudFile reads the HUD file on the client. */
    static void initialize() {
        ServerTickEvents.END_SERVER_TICK.register(AvatarItems::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> mines.clear()); // No tick drops them after the last one; they hold its world.
    }

    /** A vanilla item standing in for Stellar Blade's icon of an item kind or bullet alias. */
    static ItemStack icon(String name) {
        return switch (name) {
            case "tumbler" -> PotionContentsComponent.createStack(Items.POTION, Potions.HEALING);
            case "potion" -> PotionContentsComponent.createStack(Items.SPLASH_POTION, Potions.HEALING);
            case "lingering" -> PotionContentsComponent.createStack(Items.LINGERING_POTION, Potions.REGENERATION);
            case "field" -> new ItemStack(Items.BEACON);
            case "shock" -> new ItemStack(Items.FIRE_CHARGE);
            case "pulse" -> new ItemStack(Items.ECHO_SHARD);
            case "mine" -> new ItemStack(Items.TRIPWIRE_HOOK);
            case "sonic" -> new ItemStack(Items.BELL);
            case "pump" -> new ItemStack(Items.TOTEM_OF_UNDYING);
            case "other" -> new ItemStack(Items.PAPER);
            case "dash" -> new ItemStack(Items.FEATHER);
            case "Bullet_Railgun" -> new ItemStack(Items.SPECTRAL_ARROW);
            case "Bullet_Missile" -> new ItemStack(Items.FIREWORK_ROCKET);
            case "Bullet_Scatter" -> new ItemStack(Items.PRISMARINE_SHARD);
            case "Bullet_Explosive" -> new ItemStack(Items.TNT);
            default -> new ItemStack(Items.ARROW); // Bullet_Normal, Bullet_Nikke and unknown bullets.
        };
    }

    /**
     * Server thread: one use of a combat consumable around the player. Shock knocks back and holds still, Sonic slows,
     * Pulse breaks the shield (Absorption, Resistance) then takes 12 % of the maximum health past armor; a Smart Mine is
     * placed. Never block or self damage.
     */
    static void use(ServerPlayerEntity player, int kind) {
        ServerWorld world = player.getServerWorld();
        Vec3d at = player.getPos();
        if (kind == MINE) {
            place(mines, new Mine(world, at.add(Vec3d.fromPolar(0, player.getYaw()).multiply(AHEAD)), player.getUuid()));
            world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_TRIPWIRE_ATTACH, player.getSoundCategory(), 1, 1);
            return;
        }
        List<Entity> struck = near(player, world, at);
        for (Entity entity : struck) {
            LivingEntity target = (LivingEntity) entity;
            target.takeKnockback(PUSH, at.x - target.getX(), at.z - target.getZ());
            if (kind == SHOCK) {
                target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 30, 6), player);
                target.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 30, 9), player);
            } else if (kind == SONIC) {
                target.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 1), player);
                target.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 100, 1), player);
            } else {
                target.removeStatusEffect(StatusEffects.ABSORPTION);
                target.removeStatusEffect(StatusEffects.RESISTANCE);
                target.timeUntilRegen = 0;
                target.damage(player.getDamageSources().indirectMagic(player, player), 0.12f * target.getMaxHealth());
            }
        }
        world.spawnParticles(ParticleTypes.FLASH, at.x, at.y + 1, at.z, 1, 0, 0, 0, 0);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_FIREWORK_ROCKET_BLAST, player.getSoundCategory(), 1, 1);
        log(kind, struck.size());
    }

    /** Server thread: Smart Mines spark, arm and go off; they leave with their owner's override (bridge stop, link loss, world change). */
    private static void tick(MinecraftServer server) {
        mines.removeIf(mine -> {
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(mine.owner);
            if (owner == null || owner.getServerWorld() != mine.world || !BridgeServer.overrides(owner) || ++mine.age > MINE_LIFE) return true;
            Vec3d at = mine.at;
            if (mine.age % SPARKS == 0) mine.world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, at.x, at.y + 0.1, at.z, 3, 0.1, 0.05, 0.1, 0);
            if (mine.armed < 0 && !mine.world.getOtherEntities(null, Box.of(at, 2 * ARM, 2 * ARM, 2 * ARM),
                    e -> e instanceof Monster && e.isAlive() && e.squaredDistanceTo(at) <= ARM * ARM).isEmpty()) mine.armed = mine.age;
            if (mine.armed < 0 || mine.age - mine.armed < MINE_FUSE) return false;
            List<Entity> struck = near(owner, mine.world, at);
            float amount = AvatarCombat.STRONG * AvatarCombat.fighter(owner).stats.strikes();
            for (Entity entity : struck) {
                entity.timeUntilRegen = 0;
                if (entity.damage(owner.getDamageSources().playerAttack(owner), amount))
                    ((LivingEntity) entity).takeKnockback(PUSH, at.x - entity.getX(), at.z - entity.getZ());
            }
            mine.world.spawnParticles(ParticleTypes.EXPLOSION, at.x, at.y + 0.5, at.z, 1, 0, 0, 0, 0);
            mine.world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_GENERIC_EXPLODE, owner.getSoundCategory(), 1, 1);
            log(MINE, struck.size());
            return true;
        });
    }

    /** Entities EVE may strike (AvatarCombat.strikable) within RADIUS of a point. */
    private static List<Entity> near(ServerPlayerEntity player, ServerWorld world, Vec3d at) {
        return world.getOtherEntities(player, Box.of(at, 2 * RADIUS, 2 * RADIUS, 2 * RADIUS),
                e -> AvatarCombat.strikable(e, player) && e.squaredDistanceTo(at) <= RADIUS * RADIUS);
    }

    private static void log(int kind, int entities) {
        if (logged++ < LOGGED) LoggerFactory.getLogger("stellar_bridge").info("EVE item: {}, {} entities", USES[kind], entities);
    }

    /** With the HUD file's state and the uses the client sent, per kind. */
    static String status(String state, int[] sent) {
        return String.format(" Items: HUD file %s; uses sent: shock %d, pulse %d, mine %d, sonic %d.", state,
                sent[SHOCK], sent[PULSE], sent[MINE], sent[SONIC]);
    }
}
