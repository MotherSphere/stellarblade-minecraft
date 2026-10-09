package org.stellarbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicIntegerArray;
import net.fabricmc.fabric.api.tag.convention.v2.ConventionalEntityTypeTags;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.damage.DamageTypes;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.Monster;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.TridentEntity;
import net.minecraft.predicate.entity.EntityPredicates;
import net.minecraft.particle.ParticleTypes;
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
 * Minecraft's world slows for 1.5 s, Stellar Blade's for the moment of the dodge), a later dodge still avoids it, a guard
 * pressed just before is a perfect parry (no damage; a melee attacker staggers, an arrow flies back at its shooter),
 * a little earlier a parry (no damage), and a held guard lets a fifth through, all as in Stellar Blade; Minecraft's
 * unblockable damage goes through any guard. While the bridge session lasts but EVE cannot answer (a pause menu over a
 * running world, no live link), nothing hurts her. Her Stellar Blade stats (AvatarStats) scale her strikes and reduce,
 * approximately, the damage she takes. Her
 * body is solid to them: each tick a living entity inside it is moved out to its edge. Her drone's shots strike the
 * first living entity Minecraft's crosshair aims at within the gun's range. Landed strikes, parries and kills earn
 * Stellar Blade's Beta and Burst points, which the adapter adds to her gauges through the pose stream.
 */
public final class AvatarCombat {
    // Strike damage per Stellar Blade trail style (SWING: no effect data, the swing heuristic), doubled for a charged
    // attack (a HoldBurst effect within CHARGE_HOLD seconds); extra knockback on top of Minecraft's own.
    static final float LIGHT = 6, STRONG = 9, THRUST = 8, SWING = 6;
    static final double CHARGE_HOLD = 1.5, REHIT = 0.25, BLADE_RADIUS = 0.2, REACH = 5;
    // Defense windows before the impact, in nanoseconds: Stellar Blade's just parry (0.15 s, SkillTable
    // P_Eve_Sword_Normal_Guard2_1 JustActionTime) and parry (0.3 s, its Parry1 step) from the guard press, each plus
    // 0.1 s for Minecraft's server tick and the client's interpolation behind the impact; then the dodges'.
    static final long PERFECT_PARRY = 250_000_000L, PARRY = 400_000_000L, PERFECT_DODGE = 300_000_000L, DODGE = 500_000_000L;
    // Stellar Blade's guard cooldown (CoolTime 0.4 s): a press sooner than that after the previous press does not count,
    // so mashing the guard parries nothing, while a deliberate tap always does.
    static final long GUARD_COOLDOWN = 400_000_000L;
    // A held guard's share of the damage (Stellar Blade's SkillDamageWhenGuard deals 0.2 of an attack, SkillDamage 1),
    // and the attack damage (before EVE's reductions) from which she answers a guarded or landed hit with its strong
    // reaction.
    static final float GUARD_SHARE = 0.2f, STRONG_HIT = 8;
    // A perfect dodge runs Minecraft's world at SLOW_RATE ticks per second for SLOW_NANOS of real time.
    static final float SLOW_RATE = 6;
    static final long SLOW_NANOS = 1_500_000_000L;
    // EVE's body: Stellar Blade's movement capsule (CH_P_EVE_01_Blueprint CollisionCylinder, radius 40 cm, half height
    // 95 cm) standing on her feet, in blocks; an entity inside it moves out by at most SHOVE blocks a tick.
    static final double BODY_RADIUS = 0.4, BODY_HEIGHT = 1.9, SHOVE = 0.5;
    // A drone shot's damage: Stellar Blade's normal slug (SkillTable P_Eve_Gun_ShootSlug1_1 AttackDamageRate 0.6) against
    // her light attacks' rate of about 1. RANGE in blocks from her eyes; at most BURST shots fire in one frame.
    static final float SHOT = 0.6f * LIGHT;
    static final double RANGE = 30;
    static final int BURST = 4;
    // The drone's normal bullet socket (CH_NPC_Drone_Skeleton SOC_Bullet_Normal: bone and offset in Unreal units), and
    // EVE's right hand, where the shots leave while no drone part is drawn.
    static final String DRONE_MUZZLE = "Ab_ALegR_Fr", HAND = "Bip001-R-Hand";
    static final Vector3d DRONE_SOCKET = new Vector3d(0.163, -11.05, 0.133);
    // Stellar Blade's gauge points (SkillResultTable): a sword hit's Beta, a critical's extra Beta, a charged (Ex)
    // finisher's Burst in place of Beta, a perfect parry's and a parry's Beta. The adapter applies EVE's rates and caps.
    // ponytail: a mob is struck again every REHIT seconds, so a long swing can count twice; per-swing ids if it over-grants.
    static final int STRIKE_BETA = 15, CRITICAL_BETA = 4, CHARGED_BURST = 200, PERFECT_PARRY_BETA = 65, PARRY_BETA = 25;
    // A Beta or Burst drop of SPENT or more between frames is a skill (Beta skills cost 400, Burst ones 600 or 800; the Ex
    // finisher and combo extensions 200): strikes earn no points of their own for QUIET nanoseconds after it (kill Burst and parry Beta still count).
    // ponytail: a gauge-drop heuristic; EVE's command node (ESBSkillCommandState 30-39) would gate exactly, if it misses.
    static final int SPENT = 300;
    static final long QUIET = 2_500_000_000L;

    enum Defense { PERFECT_PARRY, PARRY, PERFECT_DODGE, DODGE, GUARD, HIT }

    // Latest dodge and guard presses (System.nanoTime) and whether the guard is held: written by ControlBridge on the
    // render thread while EVE's keys drive Stellar Blade, read on the server thread at impact.
    private static volatile long dodged = Long.MIN_VALUE, guarded = Long.MIN_VALUE;
    private static volatile boolean guarding;
    private static long guardPress = Long.MIN_VALUE; // Render thread: the latest guard press seen, counted or not.
    // Server thread: perfectly parried projectiles to send back once Minecraft's own deflection has run this tick.
    private static final List<Runnable> returning = new ArrayList<>();
    private static final AtomicIntegerArray counts = new AtomicIntegerArray(1 + Defense.values().length); // Strikes, then per Defense.
    private static boolean defending; // Server thread: inside a guarded hit's own damage call.
    // Server thread: the last judged attack's origin entity, the server tick then and its verdict.
    private static int lastOrigin = -1, lastJudged;
    private static Defense lastDefense = Defense.HIT;
    private static volatile MinecraftServer slowed; // The server whose tick rate a perfect dodge lowered; the HUD reads it.
    private static float normalRate;
    private static long slowUntil;
    // Beta and Burst base points Minecraft counted since the JVM started (wrapping), added on the server thread; the end
    // of the quiet window after a skill spent a gauge, written on the render thread; the gauges it saw last.
    private static final AtomicIntegerArray points = new AtomicIntegerArray(2);
    private static volatile long quietUntil = System.nanoTime();
    private static float[] lastGauges;

    static void inputs(long dodgePressed, long guardPressed, boolean guardHeld) {
        dodged = dodgePressed;
        guarded = counted(guarded, guardPress, guardPressed);
        guardPress = guardPressed;
        guarding = guardHeld;
    }

    /**
     * The guard press that counts after last, given the previous press seen: a new press counts when it comes
     * GUARD_COOLDOWN or more after the previous one (any first press too); released input (MIN_VALUE) clears it.
     */
    static long counted(long last, long previous, long pressed) {
        if (pressed == Long.MIN_VALUE) return Long.MIN_VALUE;
        if (pressed == previous) return last; // The same press again.
        return previous == Long.MIN_VALUE || pressed - previous >= GUARD_COOLDOWN ? pressed : last;
    }

    /** The defense for an impact at now, from the latest presses (nanoseconds) and the held guard. */
    static Defense defense(long now, long dodgePressed, long guardPressed, boolean guardHeld) {
        long sinceGuard = now - guardPressed, sinceDodge = now - dodgePressed;
        boolean guard = guardPressed != Long.MIN_VALUE && sinceGuard >= 0, dodge = dodgePressed != Long.MIN_VALUE && sinceDodge >= 0;
        if (guard && sinceGuard <= PERFECT_PARRY) return Defense.PERFECT_PARRY;
        if (dodge && sinceDodge <= PERFECT_DODGE) return Defense.PERFECT_DODGE;
        if (dodge && sinceDodge <= DODGE) return Defense.DODGE;
        if (guard && sinceGuard <= PARRY) return Defense.PARRY;
        return guardHeld ? Defense.GUARD : Defense.HIT;
    }

    /** The combat event Stellar Blade answers for a verdict: a strong attack guarded or landed has its own reaction. */
    static String event(Defense defense, float amount) {
        String name = defense.name().toLowerCase(java.util.Locale.ROOT);
        return (defense == Defense.GUARD || defense == Defense.HIT) && amount >= STRONG_HIT ? name + "_strong" : name;
    }

    /**
     * Server thread, at the head of the bridged player's damage: the result to return instead of Minecraft's, or null
     * to let Minecraft apply the damage.
     */
    public static Boolean defend(ServerPlayerEntity player, DamageSource source, float amount) {
        if (defending || source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) return null;
        if (BridgeServer.defenseless(player)) return false;
        if (!BridgeServer.overrides(player)) return null;
        // Stellar Blade owns EVE's position; Minecraft's collisions with it never hurt her.
        if (source.isOf(DamageTypes.IN_WALL) || source.isOf(DamageTypes.FALL) || source.isOf(DamageTypes.FLY_INTO_WALL)
                || source.isOf(DamageTypes.CRAMMING)) return false;
        if (source.getAttacker() == null && source.getSource() == null || player.isInvulnerableTo(source)
                || player.getAbilities().invulnerable) return null;
        // Some attacks repeat within vanilla's half-second immunity (contact damage, a deflected arrow): the first
        // verdict holds for them, as immunity would after a hit.
        Entity origin = source.getSource() != null ? source.getSource() : source.getAttacker();
        int now = player.getServer().getTicks();
        if (origin.getId() == lastOrigin && now - lastJudged < 10) return switch (lastDefense) {
            case HIT -> applied(player, source, amount);
            case GUARD -> applied(player, source, amount * GUARD_SHARE);
            default -> false;
        };
        // Minecraft's unblockable damage (sonic boom, magic, a piercing arrow) goes through a guard as through a shield.
        boolean unguardable = source.isIn(DamageTypeTags.BYPASSES_SHIELD)
                || source.getSource() instanceof PersistentProjectileEntity arrow && arrow.getPierceLevel() > 0;
        Defense defense = defense(System.nanoTime(), dodged, unguardable ? Long.MIN_VALUE : guarded, guarding && !unguardable);
        lastOrigin = origin.getId();
        lastJudged = now;
        lastDefense = defense;
        if (defense == Defense.HIT) {
            // Minecraft decides (immunity, difficulty); only a hit that lands reaches Stellar Blade.
            boolean hurt = applied(player, source, amount);
            if (hurt) {
                counts.incrementAndGet(1 + defense.ordinal());
                BridgeServer.combatEvent(event(defense, amount), source.getPosition());
            }
            return hurt;
        }
        counts.incrementAndGet(1 + defense.ordinal());
        // Stellar Blade answers with its own time and effects; the action bar names the verdict.
        String name = defense.name().toLowerCase(java.util.Locale.ROOT);
        if (defense != Defense.DODGE) BridgeServer.combatEvent(event(defense, amount), source.getPosition());
        player.sendMessage(net.minecraft.text.Text.translatable("stellar_bridge.combat." + name), true);
        switch (defense) {
            case PERFECT_PARRY -> {
                if (source.getSource() instanceof PersistentProjectileEntity arrow && !(arrow instanceof TridentEntity)
                        && source.getAttacker() != null) {
                    // A trident's hit marks it as dealt and it would pass through its thrower: it just bounces off.
                    returnToSender(player, arrow, source.getAttacker());
                } else if (source.isDirect() && source.getAttacker() instanceof LivingEntity attacker) {
                    // Stellar Blade's just-parried enemies recoil and cannot act for 2 s (their BlockAction 2).
                    attacker.takeKnockback(1.0, player.getX() - attacker.getX(), player.getZ() - attacker.getZ());
                    attacker.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 40, 4), player);
                }
                sound(player, SoundEvents.ITEM_SHIELD_BLOCK, 1.3f);
                points.addAndGet(0, PERFECT_PARRY_BETA);
                return false;
            }
            case PARRY -> { // A projectile bounces off, as Minecraft deflects any refused one.
                sound(player, SoundEvents.ITEM_SHIELD_BLOCK, 1.1f);
                points.addAndGet(0, PARRY_BETA);
                return false;
            }
            case PERFECT_DODGE -> {
                slow(player.getServer());
                sound(player, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f);
                return false;
            }
            case DODGE -> { return false; }
            default -> { // GUARD
                sound(player, SoundEvents.ITEM_SHIELD_BLOCK, 0.9f);
                return applied(player, source, amount * GUARD_SHARE);
            }
        }
    }

    /** Minecraft's own damage, from inside the hook, after EVE's Stellar Blade damage reductions. */
    private static boolean applied(ServerPlayerEntity player, DamageSource source, float amount) {
        defending = true;
        try { return player.damage(source, amount * AvatarStats.current().taken(source.isIn(DamageTypeTags.IS_PROJECTILE))); }
        finally { defending = false; }
    }

    /** Whether a perfect dodge's slow motion runs; any thread. */
    static boolean slowMotion() { return slowed != null; }

    private static void slow(MinecraftServer server) {
        if (slowed == null) {
            slowed = server;
            normalRate = server.getTickManager().getTickRate();
        }
        server.getTickManager().setTickRate(SLOW_RATE);
        slowUntil = System.nanoTime() + SLOW_NANOS;
    }

    /**
     * Server thread: a perfectly parried projectile flies back at its shooter's eyes as the player's, at its own speed.
     * Minecraft deflects the refused hit itself after the damage call, so the turn waits for the end of the tick; a
     * projectile its hit destroyed (a fireball, a shulker bullet) is gone by then.
     */
    private static void returnToSender(ServerPlayerEntity player, ProjectileEntity projectile, Entity shooter) {
        float speed = (float) Math.max(projectile.getVelocity().length(), 1);
        returning.add(() -> {
            if (projectile.isRemoved() || shooter.isRemoved() || shooter.getWorld() != projectile.getWorld()) return;
            Vec3d to = shooter.getEyePos().subtract(projectile.getPos()); // ponytail: straight aim, drops short beyond ~25 blocks.
            projectile.setOwner(player);
            projectile.setVelocity(to.x, to.y, to.z, speed, 0);
            projectile.velocityModified = true;
        });
    }

    /**
     * Server thread, each tick and when the bridge stops: parried projectiles sent back (dropped on stop), then
     * Minecraft's tick rate back once the slow motion is over.
     */
    static void restoreTime(boolean now) {
        if (!now) returning.forEach(Runnable::run);
        returning.clear();
        if (slowed != null && (now || System.nanoTime() - slowUntil >= 0)) {
            slowed.getTickManager().setTickRate(normalRate);
            slowed = null;
        }
    }

    /**
     * Server thread, each tick Stellar Blade's position is applied: the living entities Minecraft would let push a player
     * (their own pushability, the team collision rule) are moved out of EVE's body with block collision, as an opening
     * shulker box moves them, before any client sees them inside her. Stellar Blade owns her position, so she never yields.
     */
    static void shove(ServerPlayerEntity player) {
        Box body = body(player.getPos());
        for (Entity entity : player.getServerWorld().getOtherEntities(player, body, shoved(player))) {
            Vec3d out = shove(body, entity.getBoundingBox(), SHOVE);
            if (out.lengthSquared() == 0) continue;
            boolean ground = entity.isOnGround();
            try {
                entity.move(MovementType.SHULKER_BOX, out);
                entity.setOnGround(ground); // A level move reads as airborne until the entity's own next move.
            } catch (RuntimeException failure) { // A mod's entity must never end the session.
                if (!shoveFailed) org.slf4j.LoggerFactory.getLogger("stellar_bridge").warn("EVE's body could not move {}: {}", entity, failure.toString());
                shoveFailed = true;
            }
        }
    }
    private static boolean shoveFailed; // Server thread: a failed shove was logged once.

    /** The entities EVE's body shoves and Stellar Blade's walls stand for: living, unmounted, pushable by a player. */
    static java.util.function.Predicate<Entity> shoved(PlayerEntity player) {
        return EntityPredicates.canBePushedBy(player).and(entity -> entity instanceof LivingEntity && !entity.hasVehicle());
    }

    /** EVE's body standing on feet. */
    static Box body(Vec3d feet) {
        return new Box(feet.x - BODY_RADIUS, feet.y, feet.z - BODY_RADIUS, feet.x + BODY_RADIUS, feet.y + BODY_HEIGHT, feet.z + BODY_RADIUS);
    }

    /**
     * The level move, at most max long, that takes box out of EVE's body: the cylinder of body's half width around its
     * vertical axis, over its height (Stellar Blade's capsule; body is its bounding box). With her axis outside the
     * box, along the line from the axis to the box's nearest point; with it inside, along the box's shortest way out
     * (+X first on a tie). A tenth of a millimetre more for rounding.
     */
    static Vec3d shove(Box body, Box box, double max) {
        if (!body.intersects(box)) return Vec3d.ZERO; // Above, below or clear of the square around her.
        double cx = (body.minX + body.maxX) / 2, cz = (body.minZ + body.maxZ) / 2, r = body.getLengthX() / 2;
        double dx = Math.clamp(cx, box.minX, box.maxX) - cx, dz = Math.clamp(cz, box.minZ, box.maxZ) - cz, d = Math.hypot(dx, dz);
        if (d > 1e-9) {
            if (d >= r) return Vec3d.ZERO; // A corner in the square but outside the cylinder.
            double distance = Math.min(r - d + 1e-4, max);
            return new Vec3d(dx / d * distance, 0, dz / d * distance);
        }
        double[] exits = {cx + r - box.minX, box.maxX - (cx - r), cz + r - box.minZ, box.maxZ - (cz - r)}; // +X, -X, +Z, -Z.
        int way = 0;
        for (int i = 1; i < exits.length; i++) if (exits[i] < exits[way]) way = i;
        double distance = Math.min(exits[way] + 1e-4, max);
        return new Vec3d(way == 0 ? distance : way == 1 ? -distance : 0, 0, way == 2 ? distance : way == 3 ? -distance : 0);
    }

    /** Living entities EVE's blade may strike: not players, armor stands or the player's own pets. */
    static boolean strikable(Entity entity, PlayerEntity player) {
        return entity instanceof LivingEntity && entity != player && !(entity instanceof PlayerEntity)
                && !(entity instanceof ArmorStandEntity) && entity.isAlive() && entity.isAttackable()
                && !(entity instanceof Tameable pet && player.getUuid().equals(pet.getOwnerUuid()));
    }

    /**
     * Server thread: one strike of EVE's blade, or her drone's shot (within the gun's range, with an arrow's hit sound and
     * not counted as a strike), on an entity the client saw it hit. A landed strike earns its gauge points (first: the
     * first strike of a charged attack on this entity), a kill its Burst.
     */
    static void strike(ServerPlayerEntity player, int entityId, float amount, double knockback, boolean charged, boolean first, boolean shot) {
        Entity target = player.getServerWorld().getEntityById(entityId);
        double reach = shot ? RANGE + REACH : REACH;
        if (target == null || !strikable(target, player) || target.getBoundingBox().squaredMagnitude(player.getPos()) > reach * reach) return;
        target.timeUntilRegen = 0; // Each strike is its own attack, often sooner than Minecraft's half-second immunity.
        // EVE's attack power scales the strike; her critical rate rolls Stellar Blade's critical damage.
        var stats = AvatarStats.current();
        boolean critical = stats.crits(player.getRandom().nextFloat());
        if (!target.damage(player.getDamageSources().playerAttack(player), amount * stats.strikes() * (critical ? stats.critMultiplier() : 1))) return;
        var living = (LivingEntity) target;
        int[] gain = gains(charged, first, critical, shot, quiet(System.nanoTime()));
        // Stellar Blade's AttackerEffectWhenDead goes to the attacker, so EVE's sword and drone kills earn Burst; grenade and mine
        // kills do not (Stellar Blade credits a mine's kill to the mine, and a Pulse only drains shields).
        if (living.isDead()) gain[1] += killBurst(target.getType().isIn(ConventionalEntityTypeTags.BOSSES), target instanceof Monster, living.getMaxHealth());
        points.addAndGet(0, gain[0]);
        points.addAndGet(1, gain[1]);
        if (knockback > 0) living.takeKnockback(knockback, player.getX() - target.getX(), player.getZ() - target.getZ());
        player.onAttacking(target);
        sound(player, shot ? SoundEvents.ENTITY_ARROW_HIT : charged || critical ? SoundEvents.ENTITY_PLAYER_ATTACK_CRIT
                : SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 1);
        impact(player, target, knockback > 0, charged || critical);
        if (!shot) counts.incrementAndGet(0);
    }

    /** The hit as Minecraft shows a player's: crit sparks on the target, a sweep for a heavy strike, more when charged. */
    private static void impact(ServerPlayerEntity player, Entity target, boolean heavy, boolean charged) {
        var world = player.getServerWorld();
        double x = target.getX(), y = target.getBodyY(0.5), z = target.getZ(), w = target.getWidth() / 2;
        world.spawnParticles(charged ? ParticleTypes.ENCHANTED_HIT : ParticleTypes.CRIT, x, y, z, charged ? 16 : 8, w, target.getHeight() / 4, w, 0.3);
        if (heavy) world.spawnParticles(ParticleTypes.SWEEP_ATTACK, x, y, z, 1, 0, 0, 0, 0);
    }

    private static void sound(ServerPlayerEntity player, SoundEvent sound, float pitch) {
        player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(), sound, player.getSoundCategory(), 1, pitch);
    }

    /**
     * A landed strike's {Beta, Burst} points: a sword hit's Beta, a charged one's Burst instead on its first entity per
     * activation, a critical's extra Beta either way; nothing for a drone shot or within the quiet window.
     */
    static int[] gains(boolean charged, boolean first, boolean critical, boolean shot, boolean quiet) {
        if (shot || quiet) return new int[2];
        int extra = critical ? CRITICAL_BETA : 0;
        return charged ? new int[] {extra, first ? CHARGED_BURST : 0} : new int[] {STRIKE_BETA + extra, 0};
    }

    /**
     * A kill's Burst, after Stellar Blade's AttackerEffectWhenDead ranks: a boss (c:bosses) 200, a monster with 50 or more
     * maximum health (an elite) 100, another monster 30, anything else (its animals) 6.
     */
    static int killBurst(boolean boss, boolean monster, float maxHealth) {
        return boss ? 200 : monster ? maxHealth >= 50 ? 100 : 30 : 6;
    }

    /** Whether a skill spent Beta or Burst between two gauge reads (AvatarStream.gauges()). */
    static boolean spent(float[] before, float[] after) {
        return before != null && after != null && (before[0] - after[0] >= SPENT || before[2] - after[2] >= SPENT);
    }

    /** Render thread, each frame with AvatarStream.gauges(): a spent gauge opens the quiet window. */
    static void watch(float[] gauges, long nanos) {
        if (spent(lastGauges, gauges)) quietUntil = nanos + QUIET;
        if (gauges != null) lastGauges = gauges;
    }
    /** Any thread: whether a strike at nanos falls in the quiet window. */
    static boolean quiet(long nanos) { return nanos - quietUntil < 0; }

    /** Any thread: the Beta and Burst base points Minecraft counted, cumulative and wrapping. */
    static int[] earned() { return new int[] {points.get(0), points.get(1)}; }

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
        return String.format(" Combat: %d strikes; %d perfect parries, %d parries, %d perfect dodges, %d dodges, %d guarded, %d hits taken."
                + " Minecraft earned Beta %d, Burst %d.", counts.get(0), counts.get(1), counts.get(2), counts.get(3), counts.get(4),
                counts.get(5), counts.get(6), Integer.toUnsignedLong(points.get(0)), Integer.toUnsignedLong(points.get(1)));
    }

    /** The index of the box the segment from..to meets first (a box around from counts at once), or -1. */
    static int firstHit(Vec3d from, Vec3d to, List<Box> boxes) {
        int first = -1;
        double nearest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            double distance = box.contains(from) ? 0 : box.raycast(from, to).map(from::squaredDistanceTo).orElse(Double.POSITIVE_INFINITY);
            if (distance < nearest) { nearest = distance; first = i; }
        }
        return first;
    }

    /** New shots from the adapter's count last seen (-1: none yet, so none fire) to counter, at most BURST. */
    static int shots(long seen, long counter) {
        return seen < 0 || counter <= seen ? 0 : (int) Math.min(counter - seen, BURST);
    }
}
