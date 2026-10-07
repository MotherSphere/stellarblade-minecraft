package org.stellarbridge;

import java.util.ArrayList;
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
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.projectile.TridentEntity;
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
 * approximately, the damage she takes.
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
    // and the attack damage from which EVE takes it with her strong guarded-hit reaction.
    static final float GUARD_SHARE = 0.2f, STRONG_HIT = 8;
    // A perfect dodge runs Minecraft's world at SLOW_RATE ticks per second for SLOW_NANOS of real time.
    static final float SLOW_RATE = 6;
    static final long SLOW_NANOS = 1_500_000_000L;

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

    /** The combat event Stellar Blade answers for a verdict: a guarded strong attack has its own reaction. */
    static String event(Defense defense, float amount) {
        return defense == Defense.GUARD && amount >= STRONG_HIT ? "guard_strong" : defense.name().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Server thread, at the head of the bridged player's damage: the result to return instead of Minecraft's, or null
     * to let Minecraft apply the damage.
     */
    public static Boolean defend(ServerPlayerEntity player, DamageSource source, float amount) {
        if (defending || source.isIn(DamageTypeTags.BYPASSES_INVULNERABILITY)) return null;
        if (StellarBridge.defenseless(player)) return false;
        if (!StellarBridge.overrides(player)) return null;
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
                StellarBridge.combatEvent("hit", source.getPosition());
            }
            return hurt;
        }
        counts.incrementAndGet(1 + defense.ordinal());
        // Stellar Blade answers with its own time and effects; the action bar names the verdict.
        String name = defense.name().toLowerCase(java.util.Locale.ROOT);
        if (defense != Defense.DODGE) StellarBridge.combatEvent(event(defense, amount), source.getPosition());
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
                return false;
            }
            case PARRY -> { // A projectile bounces off, as Minecraft deflects any refused one.
                sound(player, SoundEvents.ITEM_SHIELD_BLOCK, 1.1f);
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
        // EVE's attack power scales the strike; her critical rate rolls Stellar Blade's critical damage.
        var stats = AvatarStats.current();
        boolean critical = stats.crits(player.getRandom().nextFloat());
        if (!target.damage(player.getDamageSources().playerAttack(player), amount * stats.strikes() * (critical ? stats.critMultiplier() : 1))) return;
        if (knockback > 0) ((LivingEntity) target).takeKnockback(knockback, player.getX() - target.getX(), player.getZ() - target.getZ());
        player.onAttacking(target);
        sound(player, charged || critical ? SoundEvents.ENTITY_PLAYER_ATTACK_CRIT : SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 1);
        impact(player, target, knockback > 0, charged || critical);
        counts.incrementAndGet(0);
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
        return String.format(" Combat: %d strikes; %d perfect parries, %d parries, %d perfect dodges, %d dodges, %d guarded, %d hits taken.",
                counts.get(0), counts.get(1), counts.get(2), counts.get(3), counts.get(4), counts.get(5), counts.get(6));
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
