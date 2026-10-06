package org.stellarbridge;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicIntegerArray;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3d;

/**
 * EVE against Minecraft's entities, whatever mod adds them. Her blade strikes the living entities it sweeps through,
 * through Minecraft's own damage, so every mob reacts with its own hurt, knockback, sounds and loot. Every damage the
 * bridged player takes is judged at the moment of impact against her latest dodge and guard presses, so any melee
 * swing or projectile counts, however long its animation: a dodge just before the impact is perfect (no damage, and
 * Minecraft's world slows while Stellar Blade keeps animating EVE at full speed), a later dodge still avoids it, a guard
 * pressed just before parries (no damage, a melee attacker staggers), a held guard lets a quarter through.
 */
public final class AvatarCombat {
    // Strike damage per Stellar Blade trail style (SWING: no effect data, the swing heuristic), doubled for a charged
    // attack (a HoldBurst effect within CHARGE_HOLD seconds); extra knockback on top of Minecraft's own.
    static final float LIGHT = 6, STRONG = 9, THRUST = 8, SWING = 6;
    static final double CHARGE_HOLD = 1.5, REHIT = 0.25, BLADE_RADIUS = 0.2, REACH = 5;
    // Defense windows before the impact, in nanoseconds; a held guard's share of the damage.
    static final long PERFECT_PARRY = 250_000_000L, PERFECT_DODGE = 300_000_000L, DODGE = 500_000_000L;
    static final float GUARD_SHARE = 0.25f;
    // A perfect dodge runs Minecraft's world at SLOW_RATE ticks per second for SLOW_NANOS of real time.
    static final float SLOW_RATE = 6;
    static final long SLOW_NANOS = 1_500_000_000L;

    enum Defense { PERFECT_PARRY, PERFECT_DODGE, DODGE, GUARD, HIT }

    // Latest dodge and guard presses (System.nanoTime) and whether the guard is held: written by ControlBridge on the
    // render thread while EVE's keys drive Stellar Blade, read on the server thread at impact.
    private static volatile long dodged = Long.MIN_VALUE, guarded = Long.MIN_VALUE;
    private static volatile boolean guarding;
    private static final AtomicIntegerArray counts = new AtomicIntegerArray(1 + Defense.values().length); // Strikes, then per Defense.
    private static boolean defending; // Server thread: inside a guarded hit's own damage call.
    private static MinecraftServer slowed; // Server thread: the server whose tick rate a perfect dodge lowered.
    private static float normalRate;
    private static long slowUntil;

    static void inputs(long dodgePressed, long guardPressed, boolean guardHeld) {
        dodged = dodgePressed;
        guarded = guardPressed;
        guarding = guardHeld;
    }

    /** The defense for an impact at now, from the latest presses (nanoseconds) and the held guard. */
    static Defense defense(long now, long dodgePressed, long guardPressed, boolean guardHeld) {
        long sinceGuard = now - guardPressed, sinceDodge = now - dodgePressed;
        if (guardPressed != Long.MIN_VALUE && sinceGuard >= 0 && sinceGuard <= PERFECT_PARRY) return Defense.PERFECT_PARRY;
        if (dodgePressed != Long.MIN_VALUE && sinceDodge >= 0 && sinceDodge <= PERFECT_DODGE) return Defense.PERFECT_DODGE;
        if (dodgePressed != Long.MIN_VALUE && sinceDodge >= 0 && sinceDodge <= DODGE) return Defense.DODGE;
        return guardHeld ? Defense.GUARD : Defense.HIT;
    }

    /**
     * Server thread, at the head of the bridged player's damage: the result to return instead of Minecraft's, or null
     * to let Minecraft apply the damage.
     */
    public static Boolean defend(ServerPlayerEntity player, DamageSource source, float amount) {
        if (defending || !StellarBridge.overrides(player)) return null;
        // Stellar Blade owns EVE's position; Minecraft's collisions with it never hurt her.
        if (source.isOf(DamageTypes.IN_WALL) || source.isOf(DamageTypes.FALL) || source.isOf(DamageTypes.FLY_INTO_WALL)
                || source.isOf(DamageTypes.CRAMMING)) return false;
        if (source.getAttacker() == null && source.getSource() == null || player.isInvulnerableTo(source)) return null;
        Defense defense = defense(System.nanoTime(), dodged, guarded, guarding);
        counts.incrementAndGet(1 + defense.ordinal());
        switch (defense) {
            case PERFECT_PARRY -> {
                if (source.isDirect() && source.getAttacker() instanceof LivingEntity attacker) {
                    attacker.takeKnockback(1.0, player.getX() - attacker.getX(), player.getZ() - attacker.getZ());
                    attacker.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 4), player);
                }
                sound(player, SoundEvents.ITEM_SHIELD_BLOCK, 1.3f);
                return false;
            }
            case PERFECT_DODGE -> {
                slow(player.getServer());
                sound(player, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f);
                return false;
            }
            case DODGE -> { return false; }
            case GUARD -> {
                sound(player, SoundEvents.ITEM_SHIELD_BLOCK, 0.9f);
                defending = true;
                try { return player.damage(source, amount * GUARD_SHARE); }
                finally { defending = false; }
            }
            default -> { return null; }
        }
    }

    private static void slow(MinecraftServer server) {
        if (slowed == null) {
            slowed = server;
            normalRate = server.getTickManager().getTickRate();
        }
        server.getTickManager().setTickRate(SLOW_RATE);
        slowUntil = System.nanoTime() + SLOW_NANOS;
    }

    /** Server thread, each tick and when the bridge stops: Minecraft's tick rate back once the slow motion is over. */
    static void restoreTime(boolean now) {
        if (slowed != null && (now || System.nanoTime() - slowUntil >= 0)) {
            slowed.getTickManager().setTickRate(normalRate);
            slowed = null;
        }
    }

    /** Living entities EVE's blade may strike: not players, armor stands or the player's own pets. */
    static boolean strikable(Entity entity, PlayerEntity player) {
        return entity instanceof LivingEntity && entity != player && !(entity instanceof PlayerEntity)
                && !(entity instanceof ArmorStandEntity) && entity.isAlive() && entity.isAttackable()
                && !(entity instanceof Tameable pet && player.getUuid().equals(pet.getOwnerUuid()));
    }

    /** Server thread: one strike of EVE's blade on an entity the client saw it sweep through. */
    static void strike(ServerPlayerEntity player, int entityId, float amount, double knockback, boolean charged) {
        Entity target = player.getServerWorld().getEntityById(entityId);
        if (target == null || !strikable(target, player) || target.getBoundingBox().squaredMagnitude(player.getPos()) > REACH * REACH) return;
        target.timeUntilRegen = 0; // Each strike is its own attack, often sooner than Minecraft's half-second immunity.
        if (!target.damage(player.getDamageSources().playerAttack(player), amount)) return;
        if (knockback > 0) ((LivingEntity) target).takeKnockback(knockback, player.getX() - target.getX(), player.getZ() - target.getZ());
        player.onAttacking(target);
        sound(player, charged ? SoundEvents.ENTITY_PLAYER_ATTACK_CRIT : SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 1);
        counts.incrementAndGet(0);
    }

    private static void sound(ServerPlayerEntity player, SoundEvent sound, float pitch) {
        player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(), sound, player.getSoundCategory(), 1, pitch);
    }

    /** Strike damage for a trail style (null: the swing heuristic) and whether the attack is charged. */
    static float damage(AvatarTrail.Style style, boolean charged) {
        float base = style == null ? SWING : switch (style) { case LIGHT -> LIGHT; case STRONG -> STRONG; case THRUST -> THRUST; };
        return charged ? base * 2 : base;
    }
    static double knockback(AvatarTrail.Style style, boolean charged) {
        return (style == AvatarTrail.Style.STRONG ? 0.4 : style == AvatarTrail.Style.THRUST ? 0.6 : 0) + (charged ? 0.8 : 0);
    }

    /** Whether a blade moving from (lastHilt, lastTip) to (hilt, tip) passes through a box: four blade positions and the tip's path. */
    static boolean sweeps(Box box, Vector3d lastHilt, Vector3d lastTip, Vector3d hilt, Vector3d tip) {
        for (int k = 0; k <= 3; k++) {
            double s = k / 3.0;
            if (crosses(box, lerp(lastHilt, hilt, s), lerp(lastTip, tip, s))) return true;
        }
        return crosses(box, lerp(lastTip, tip, 0), lerp(lastTip, tip, 1));
    }
    private static Vec3d lerp(Vector3d a, Vector3d b, double s) {
        return new Vec3d(a.x + (b.x - a.x) * s, a.y + (b.y - a.y) * s, a.z + (b.z - a.z) * s);
    }
    private static boolean crosses(Box box, Vec3d a, Vec3d b) {
        return box.contains(a) || box.raycast(a, b).isPresent();
    }

    static String status() {
        return String.format(" Combat: %d strikes; %d perfect parries, %d perfect dodges, %d dodges, %d guarded, %d hits taken.",
                counts.get(0), counts.get(1), counts.get(2), counts.get(3), counts.get(4), counts.get(5));
    }

    /** Render thread: EVE's displayed blade, swept against the client's entities each frame while it swings. */
    static final class Blade {
        private final Vector3d hilt = new Vector3d(), tip = new Vector3d(), lastHilt = new Vector3d(), lastTip = new Vector3d();
        private boolean known;
        private double chargedUntil = Double.NEGATIVE_INFINITY;
        private final Map<Integer, Double> struck = new HashMap<>(); // Entity id to its last strike time.

        void update(AvatarRenderer renderer, List<AvatarStream.Effect> effects, double seconds) {
            var client = MinecraftClient.getInstance();
            if (client.player == null || client.world == null || !renderer.blade(hilt, tip)) { known = false; return; }
            AvatarTrail.Style style = null;
            for (AvatarStream.Effect effect : effects) {
                if (effect.name().contains("HoldBurst")) chargedUntil = seconds + CHARGE_HOLD;
                AvatarTrail.Style playing = AvatarTrail.Style.of(effect.name());
                if (playing != null) style = playing;
            }
            struck.values().removeIf(time -> seconds - time > REHIT);
            if (known && renderer.trailStrength() > 0.5) {
                boolean charged = seconds < chargedUntil;
                float amount = damage(style, charged);
                double push = knockback(style, charged);
                Box sweep = new Box(new Vec3d(hilt.x, hilt.y, hilt.z), new Vec3d(tip.x, tip.y, tip.z))
                        .union(new Box(new Vec3d(lastHilt.x, lastHilt.y, lastHilt.z), new Vec3d(lastTip.x, lastTip.y, lastTip.z)))
                        .expand(BLADE_RADIUS);
                for (Entity entity : client.world.getOtherEntities(client.player, sweep, e -> strikable(e, client.player))) {
                    if (struck.containsKey(entity.getId()) || !sweeps(entity.getBoundingBox().expand(BLADE_RADIUS), lastHilt, lastTip, hilt, tip)) continue;
                    struck.put(entity.getId(), seconds);
                    StellarBridge.strike(entity.getId(), amount, push, charged);
                }
            }
            lastHilt.set(hilt);
            lastTip.set(tip);
            known = true;
        }
    }
}
