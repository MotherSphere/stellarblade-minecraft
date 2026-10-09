package org.stellarbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Vector3d;

/**
 * EVE against Minecraft's entities, whatever mod adds them. Her blade strikes the living entities it sweeps through,
 * through Minecraft's own damage, so every mob reacts with its own hurt, knockback, sounds and loot. Every damage the
 * bridged player takes is judged at the moment of impact against her latest dodge and guard presses, so any melee
 * swing or projectile counts, however long its animation: a dodge just before the impact is perfect (no damage, and
 * Minecraft's world slows for 1.5 s while she is alone on the server, Stellar Blade's for the moment of the dodge), a
 * later dodge still avoids it, a guard pressed just before is a perfect parry (no damage; a melee attacker staggers, an
 * arrow flies back at its shooter), a little earlier a parry (no damage), and a held guard lets a fifth through, all as
 * in Stellar Blade; Minecraft's unblockable damage goes through any guard. While the bridge session lasts but EVE cannot
 * answer (a pause menu over a running world, no live link, Stellar Blade paused), nothing hurts her in singleplayer, and
 * for GRACE in multiplayer. Her Stellar Blade stats (AvatarStats) scale her strikes and reduce, approximately, the damage
 * she takes. Her body is solid to them: each tick a living entity inside it is moved out to its edge. Her drone's shots
 * strike the first living entity Minecraft's crosshair aims at within the gun's range. Landed strikes, parries and kills
 * earn Stellar Blade's Beta and Burst points, which the adapter adds to her gauges through the pose stream. Each player
 * has their own Fighter on the server; their client sends strikes, presses, item uses and stats as payloads (BridgeNet),
 * judged against that player's own state.
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

    // Network timing (internet play): a remote player's press reaches the server a round trip after they saw the attack
    // it answers. So it counts as made their allowance (the latency, at most MAX_ALLOWANCE) plus its own wait on their
    // client before it arrived, and a hit no press answers yet waits for the allowance before it is judged, once that is
    // HOLD_FROM or more. Below that (a LAN) the hit is judged at once: a press it misses was made within a few milliseconds
    // of the impact, and a hit judged at once keeps the attacker's own on-hit effects (poison, fire), which a held hit
    // loses. The singleplayer host's client shares the server's clock, so its presses count when they happened, with no
    // allowance and no hold. A wait on the client beyond MAX_WAIT refuses the press.
    static final long MAX_WAIT = 250_000_000L, MAX_ALLOWANCE = 150_000_000L, HOLD_FROM = 20_000_000L;
    // A press is kept KEPT for held hits; a repeat of the same attack origin within REPEAT server ticks keeps its verdict.
    static final long KEPT = 2_000_000_000L;
    static final int REPEAT = 10;
    // In multiplayer, a silent EVE (Stellar Blade paused or in a cutscene, or the link lapsed) is spared for GRACE, then
    // hits land unjudged; full immunity stays the singleplayer host's.
    static final long GRACE = 2_000_000_000L;
    // Server validation, co-op grade (it stops bugs and desync, not a determined cheater): melee reach REACH + SLACK from
    // the player's feet (the target moved during the round trip), a target struck again after REHIT_NANOS (REHIT less a
    // tick of jitter, in real time so a slowed world refuses nothing), and per-player rates per second: strikes, drone
    // shots, defense edges, uses of each item kind and link requests (/stellar start, stop, status).
    static final double SLACK = 1;
    static final long REHIT_NANOS = 200_000_000L;
    static final double STRIKES = 40, SHOTS = 10, EDGES = 20, USES = 3, LINKS = 2;

    // Server thread: perfectly parried projectiles to send back once Minecraft's own deflection has run this tick.
    private static final List<Runnable> returning = new ArrayList<>();
    private static boolean defending; // Server thread: inside a guarded hit's own damage call.
    private static final Map<UUID, Fighter> fighters = new HashMap<>(); // Server thread: each connected player's combat.
    private static MinecraftServer slowed; // Server thread: the server whose tick rate a perfect dodge lowered.
    private static float normalRate;
    private static long slowUntil;
    // Render thread: the end of the quiet window after a skill spent a gauge, and the gauges it saw last.
    private static long quietUntil = System.nanoTime();
    private static float[] lastGauges;

    /**
     * One player's combat on the server, server thread only: their defense presses on the server's clock, the verdict
     * memory, held hits, stats, heals, gauge points, rate limits and counts. Created at the first use, dropped when the
     * player leaves, so nothing one player does reaches another's verdicts, stats or points.
     */
    static final class Fighter {
        private record Press(byte edge, long at, boolean counts) { }
        private record Held(DamageSource source, float amount, long impact, long allowance, boolean unguardable, int origin) { }
        private final List<Press> presses = new ArrayList<>(); // On the server's clock, in arrival order.
        private boolean guardedBefore; // Whether the guard was held as of the oldest press kept.
        private long guardPrevious = Long.MIN_VALUE, guardCounted = Long.MIN_VALUE;
        private final List<Held> held = new ArrayList<>();
        private int lastOrigin = -1, lastJudged;
        private Defense lastDefense = Defense.HIT; // Null while that attack's hit is held.
        AvatarStats.Values stats = AvatarStats.Values.BASE;
        final AvatarStats.Heals heals = new AvatarStats.Heals();
        boolean halted; // From the player's status: Stellar Blade withholds EVE's input.
        private long silentSince = Long.MIN_VALUE; // When EVE stopped answering; MIN_VALUE while she answers.
        int beta, burst; // Beta and Burst base points earned on this connection, cumulative and wrapping.
        BridgeNet.Feedback sent; // The feedback last sent to the player's client.
        private final Map<Integer, Long> struck = new HashMap<>(); // Target entity id to its last accepted strike.
        private final Bucket strikes = new Bucket(STRIKES), shots = new Bucket(SHOTS), edges = new Bucket(EDGES);
        final Bucket links = new Bucket(LINKS);
        private final Bucket[] uses = {new Bucket(USES), new Bucket(USES), new Bucket(USES), new Bucket(USES)};
        private final int[] counts = new int[1 + Defense.values().length]; // Strikes, then per Defense.
        int rejected; // Payloads validation refused.

        /** The player's status payload: stats (BASE without them), heal count and whether Stellar Blade withholds input. */
        void status(BridgeNet.Status status) {
            stats = status.stats() == null ? AvatarStats.Values.BASE : status.stats();
            heals.reported = status.heals();
            halted = status.halted();
        }

        /**
         * A defense edge (BridgeNet.Defense) placed at at on the server's clock. A guard press counts after Stellar
         * Blade's cooldown from the previous one; presses older than KEPT go, the guard's held state kept.
         */
        void press(byte edge, long at) {
            boolean counts = true;
            if (edge == BridgeNet.Defense.GUARD_DOWN) {
                guardCounted = counted(guardCounted, guardPrevious, at);
                counts = guardCounted == at;
                guardPrevious = at;
            }
            presses.add(new Press(edge, at, counts));
            while (!presses.isEmpty() && at - presses.getFirst().at() > KEPT) {
                Press old = presses.removeFirst();
                if (old.edge() != BridgeNet.Defense.DODGE) guardedBefore = old.edge() == BridgeNet.Defense.GUARD_DOWN;
            }
        }

        /** The defense at an impact (server nanoseconds) from the presses placed at or before it. */
        Defense judge(long impact, boolean unguardable) {
            long dodge = Long.MIN_VALUE, guard = Long.MIN_VALUE, guardEdge = Long.MIN_VALUE;
            boolean guarding = guardedBefore;
            for (Press press : presses) {
                if (press.at() > impact) continue;
                if (press.edge() == BridgeNet.Defense.DODGE) dodge = Math.max(dodge, press.at());
                else {
                    if (press.counts() && press.edge() == BridgeNet.Defense.GUARD_DOWN) guard = Math.max(guard, press.at());
                    if (press.at() >= guardEdge) {
                        guardEdge = press.at();
                        guarding = press.edge() == BridgeNet.Defense.GUARD_DOWN;
                    }
                }
            }
            return defense(impact, dodge, unguardable ? Long.MIN_VALUE : guard, guarding && !unguardable);
        }

        /** Whether an attack from origin at a server tick repeats the last judged one, whose verdict then holds. */
        boolean repeats(int origin, int tick) { return origin == lastOrigin && tick - lastJudged < REPEAT; }

        /** Records the verdict of an attack from origin at a server tick (null: its hit is held). */
        void judged(int origin, int tick, Defense defense) {
            lastOrigin = origin;
            lastJudged = tick;
            lastDefense = defense;
        }

        /**
         * Whether a hit at now is refused for EVE's silence (silent: she cannot answer it): during the first GRACE of
         * that silence. Called each tick too, so the grace starts when the silence does.
         */
        boolean graced(boolean silent, long now) {
            if (!silent) {
                silentSince = Long.MIN_VALUE;
                return false;
            }
            if (silentSince == Long.MIN_VALUE) silentSince = now;
            return now - silentSince < GRACE;
        }

        /** Whether a strike (or drone shot) on a target at now passes the player's rate and, for a strike, the rehit. */
        boolean admits(int target, boolean shot, long now) {
            if (!(shot ? shots : strikes).take(now)) return false;
            if (shot) return true;
            struck.values().removeIf(time -> now - time >= REHIT_NANOS);
            return struck.putIfAbsent(target, now) == null;
        }
    }

    /** A token bucket refilled with perSecond tokens a second, holding at most a second's worth. */
    static final class Bucket {
        private final double perSecond;
        private double tokens;
        private long last = Long.MIN_VALUE;

        Bucket(double perSecond) {
            this.perSecond = perSecond;
            tokens = perSecond;
        }

        boolean take(long nanos) {
            if (last != Long.MIN_VALUE) tokens = Math.min(perSecond, tokens + (nanos - last) * perSecond / 1e9);
            last = nanos;
            if (tokens < 1) return false;
            tokens--;
            return true;
        }
    }

    /** Server thread: the player's combat, created at the first use. */
    static Fighter fighter(ServerPlayerEntity player) { return fighters.computeIfAbsent(player.getUuid(), owner -> new Fighter()); }

    /** Server thread: a player left; their combat goes with them, held hits unjudged. */
    static void forget(UUID player) { fighters.remove(player); }

    /** Whether the player's client runs in this JVM (the singleplayer or LAN host), so its System.nanoTime is the server's. */
    static boolean sameClock(ServerPlayerEntity player) {
        MinecraftServer server = player.getServer();
        return !server.isDedicated() && server.isHost(player.getGameProfile());
    }

    /** A client's latency (milliseconds, the server's smoothed keep-alive round trip) as an allowance, at most MAX_ALLOWANCE. */
    static long allowance(int latencyMillis) { return Math.clamp(latencyMillis, 0, MAX_ALLOWANCE / 1_000_000) * 1_000_000L; }

    /** The player's allowance: none for the host, whose presses carry the server's own clock. */
    static long allowance(ServerPlayerEntity player) { return sameClock(player) ? 0 : allowance(player.networkHandler.getLatency()); }

    /**
     * Where a defense edge goes on the server's clock, or MIN_VALUE to refuse it: it arrived at server nanoseconds
     * arrival, made at at and sent at sent on its client's System.nanoTime. With the server's own clock (sameClock) at
     * itself; else arrival less its wait on the client (sent - at) and the allowance. A wait outside 0 to MAX_WAIT (a
     * stale or future stamp) is refused.
     */
    static long placed(long arrival, long at, long sent, long allowance, boolean sameClock) {
        long wait = sameClock ? arrival - at : sent - at;
        if (wait < 0 || wait > MAX_WAIT) return Long.MIN_VALUE;
        return sameClock ? at : arrival - wait - allowance;
    }

    /** Server thread: a defense edge from the player's client, placed on the server's clock; refused past the edge rate. */
    static void press(ServerPlayerEntity player, BridgeNet.Defense defense) {
        Fighter fighter = fighter(player);
        long now = System.nanoTime();
        long at = fighter.edges.take(now) ? placed(now, defense.at(), defense.sent(), allowance(player), sameClock(player)) : Long.MIN_VALUE;
        if (at == Long.MIN_VALUE) fighter.rejected++;
        else fighter.press(defense.edge(), at);
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
        // No PvP in this version, whatever vanilla's pvp setting (a server switch of its own later): what another player
        // deals or causes, melee or a projectile, neither hurts an EVE player nor is judged.
        if (fromPlayer(source.getAttacker(), player)) return false;
        Fighter fighter = fighter(player);
        // A multiplayer silence past its grace (BridgeServer.defenseless): Stellar Blade cannot answer, the hit lands unjudged.
        if (fighter.halted) return applied(player, fighter, source, amount);
        // Some attacks repeat within vanilla's half-second immunity (contact damage, a deflected arrow): the first
        // verdict holds for them, as immunity would after a hit.
        Entity origin = source.getSource() != null ? source.getSource() : source.getAttacker();
        int tick = player.getServer().getTicks();
        if (fighter.repeats(origin.getId(), tick)) return fighter.lastDefense == null ? false : switch (fighter.lastDefense) {
            case HIT -> applied(player, fighter, source, amount);
            case GUARD -> applied(player, fighter, source, amount * GUARD_SHARE);
            default -> false;
        };
        // Minecraft's unblockable damage (sonic boom, magic, a piercing arrow) goes through a guard as through a shield.
        boolean unguardable = source.isIn(DamageTypeTags.BYPASSES_SHIELD)
                || source.getSource() instanceof PersistentProjectileEntity arrow && arrow.getPierceLevel() > 0;
        long now = System.nanoTime(), allowance = allowance(player);
        Defense defense = fighter.judge(now, unguardable);
        if ((defense == Defense.HIT || defense == Defense.GUARD) && allowance >= HOLD_FROM) {
            // A press made before this impact as the player saw it may still be on its way: judged once it could have come.
            fighter.judged(origin.getId(), tick, null);
            fighter.held.add(new Fighter.Held(source, amount, now, allowance, unguardable, origin.getId()));
            return false;
        }
        fighter.judged(origin.getId(), tick, defense);
        return answer(player, fighter, source, amount, defense);
    }

    /** Whether damage with this attacker (a projectile's owner) comes from another player. */
    static boolean fromPlayer(Entity attacker, PlayerEntity player) { return attacker instanceof PlayerEntity && attacker != player; }

    /**
     * Server thread, at each tick's start, after the packets that arrived since the last tick: each held hit whose
     * allowance has passed is judged against the presses placed before its impact. A player who left or died loses it.
     */
    static void judgeHeld(MinecraftServer server) {
        long now = System.nanoTime();
        for (var entry : fighters.entrySet()) {
            Fighter fighter = entry.getValue();
            if (fighter.held.isEmpty()) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            for (var hit : List.copyOf(fighter.held)) {
                if (now - hit.impact() < hit.allowance()) continue;
                fighter.held.remove(hit);
                if (player == null || !player.isAlive()) continue;
                Defense defense = fighter.judge(hit.impact(), hit.unguardable());
                if (fighter.lastOrigin == hit.origin() && fighter.lastDefense == null) fighter.lastDefense = defense;
                answer(player, fighter, hit.source(), hit.amount(), defense);
            }
        }
    }

    /** A verdict's effects: Stellar Blade's answer, the action bar, the attacker's reaction and gauge points; the damage call's result. */
    private static boolean answer(ServerPlayerEntity player, Fighter fighter, DamageSource source, float amount, Defense defense) {
        if (defense == Defense.HIT) {
            // Minecraft decides (immunity, difficulty); only a hit that lands reaches Stellar Blade.
            boolean hurt = applied(player, fighter, source, amount);
            if (hurt) {
                fighter.counts[1 + defense.ordinal()]++;
                BridgeServer.combatEvent(player, event(defense, amount), source.getPosition());
            }
            return hurt;
        }
        fighter.counts[1 + defense.ordinal()]++;
        // Stellar Blade answers with its own time and effects; the action bar names the verdict.
        String name = defense.name().toLowerCase(java.util.Locale.ROOT);
        if (defense != Defense.DODGE) BridgeServer.combatEvent(player, event(defense, amount), source.getPosition());
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
                fighter.beta += PERFECT_PARRY_BETA;
                return false;
            }
            case PARRY -> { // A projectile bounces off, as Minecraft deflects any refused one.
                sound(player, SoundEvents.ITEM_SHIELD_BLOCK, 1.1f);
                fighter.beta += PARRY_BETA;
                return false;
            }
            case PERFECT_DODGE -> {
                // Alone on the server, Minecraft's world slows. With others online only Stellar Blade's own slowdown and
                // the dodger's tint play (BridgeNet.Verdict); ponytail: a slow bubble around the dodger would slow her mobs only.
                if (player.getServer().getCurrentPlayerCount() == 1) slow(player.getServer());
                sound(player, SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.2f);
                return false;
            }
            case DODGE -> { return false; }
            default -> { // GUARD
                sound(player, SoundEvents.ITEM_SHIELD_BLOCK, 0.9f);
                return applied(player, fighter, source, amount * GUARD_SHARE);
            }
        }
    }

    /** Minecraft's own damage, from inside the hook, after EVE's Stellar Blade damage reductions. */
    private static boolean applied(ServerPlayerEntity player, Fighter fighter, DamageSource source, float amount) {
        defending = true;
        try { return player.damage(source, amount * fighter.stats.taken(source.isIn(DamageTypeTags.IS_PROJECTILE))); }
        finally { defending = false; }
    }

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
     * Other players are never moved (their own client or Stellar Blade owns their position): they stay walls in her game.
     */
    static void shove(ServerPlayerEntity player) {
        Box body = body(player.getPos());
        for (Entity entity : player.getServerWorld().getOtherEntities(player, body, shoved(player).and(e -> !(e instanceof PlayerEntity)))) {
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

    /**
     * Living entities EVE's blade, drone, grenades and lock-on may target: not players (no PvP in this version), armor
     * stands, anyone's pets, or anything a player rides.
     */
    static boolean strikable(Entity entity, PlayerEntity player) {
        return entity instanceof LivingEntity && entity != player && !(entity instanceof PlayerEntity)
                && !(entity instanceof ArmorStandEntity) && entity.isAlive() && entity.isAttackable()
                && !(entity instanceof Tameable pet && pet.getOwnerUuid() != null) && !entity.hasPlayerRider();
    }

    /**
     * Server thread: a strike from the player's client, on an entity it saw EVE's blade or drone hit. Refused (counted)
     * unless EVE drives the player and the target is strikable in the player's world, within reach (a shot's hit point
     * within a block of the target's box with no block on the way from her eyes), and the rates and rehit allow it. The
     * damage comes from the style, never from the client.
     */
    static void strike(ServerPlayerEntity player, BridgeNet.Strike strike) {
        Fighter fighter = fighter(player);
        boolean shot = strike.style() == BridgeNet.Strike.SHOT;
        Entity target = player.getServerWorld().getEntityById(strike.entity());
        if (!BridgeServer.overrides(player) || target == null || !strikable(target, player)
                || !reaches(target.getBoundingBox().squaredMagnitude(player.getPos()), shot) || shot && !aimed(player, target, strike.hit())
                || !fighter.admits(strike.entity(), shot, System.nanoTime())) {
            fighter.rejected++;
            return;
        }
        boolean charged = (strike.flags() & BridgeNet.Strike.CHARGED) != 0;
        AvatarTrail.Style style = shot ? null : strike.trail();
        strike(player, fighter, target, shot ? SHOT : damage(style, charged), shot ? 0 : knockback(style, charged), charged,
                (strike.flags() & BridgeNet.Strike.FIRST) != 0, (strike.flags() & BridgeNet.Strike.QUIET) != 0, shot);
    }

    /** Whether a target this far (squared, from the player's feet to its box) is within reach: REACH + SLACK, a shot's RANGE + REACH. */
    static boolean reaches(double squaredDistance, boolean shot) {
        double reach = shot ? RANGE + REACH : REACH + SLACK;
        return squaredDistance <= reach * reach;
    }

    /** Whether a shot's hit point lies within a block of the target's box, with no block before it from the player's eyes (a block of slack). */
    private static boolean aimed(ServerPlayerEntity player, Entity target, Vec3d hit) {
        if (!target.getBoundingBox().expand(1).contains(hit)) return false;
        var block = player.getServerWorld().raycast(new RaycastContext(player.getEyePos(), hit, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, player));
        return block.getType() == HitResult.Type.MISS || block.getPos().squaredDistanceTo(hit) <= 1;
    }

    /**
     * Server thread: one strike of EVE's blade, or her drone's shot (with an arrow's hit sound and not counted as a
     * strike), validated. A landed strike earns its gauge points (first: the first strike of a charged attack on this
     * entity; quiet: within the quiet window after a skill, by the client's gauges), a kill its Burst.
     */
    private static void strike(ServerPlayerEntity player, Fighter fighter, Entity target, float amount, double knockback, boolean charged,
                               boolean first, boolean quiet, boolean shot) {
        target.timeUntilRegen = 0; // Each strike is its own attack, often sooner than Minecraft's half-second immunity.
        // EVE's attack power scales the strike; her critical rate rolls Stellar Blade's critical damage.
        var stats = fighter.stats;
        boolean critical = stats.crits(player.getRandom().nextFloat());
        if (!target.damage(player.getDamageSources().playerAttack(player), amount * stats.strikes() * (critical ? stats.critMultiplier() : 1))) return;
        var living = (LivingEntity) target;
        int[] gain = gains(charged, first, critical, shot, quiet);
        // Stellar Blade's AttackerEffectWhenDead goes to the attacker, so EVE's sword and drone kills earn Burst; grenade and mine
        // kills do not (Stellar Blade credits a mine's kill to the mine, and a Pulse only drains shields).
        if (living.isDead()) gain[1] += killBurst(target.getType().isIn(ConventionalEntityTypeTags.BOSSES), target instanceof Monster, living.getMaxHealth());
        fighter.beta += gain[0];
        fighter.burst += gain[1];
        if (knockback > 0) living.takeKnockback(knockback, player.getX() - target.getX(), player.getZ() - target.getZ());
        player.onAttacking(target);
        sound(player, shot ? SoundEvents.ENTITY_ARROW_HIT : charged || critical ? SoundEvents.ENTITY_PLAYER_ATTACK_CRIT
                : SoundEvents.ENTITY_PLAYER_ATTACK_STRONG, 1);
        impact(player, target, knockback > 0, charged || critical);
        if (!shot) fighter.counts[0]++;
    }

    /** Server thread: one use of a combat consumable from the player's client, applied while EVE drives the player, within the rate. */
    static void use(ServerPlayerEntity player, int kind) {
        Fighter fighter = fighter(player);
        if (BridgeServer.overrides(player) && fighter.uses[kind].take(System.nanoTime())) AvatarItems.use(player, kind);
        else fighter.rejected++;
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

    /** Render thread, each frame with AvatarStream.gauges(): a spent gauge opens the quiet window (a strike's QUIET flag). */
    static void watch(float[] gauges, long nanos) {
        if (spent(lastGauges, gauges)) quietUntil = nanos + QUIET;
        if (gauges != null) lastGauges = gauges;
    }
    /** Render thread: whether a strike at nanos falls in the quiet window. */
    static boolean quiet(long nanos) { return nanos - quietUntil < 0; }

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

    /** Server thread: one player's combat counts for /stellar status. */
    static String status(Fighter f) {
        return String.format(" Combat: %d strikes; %d perfect parries, %d parries, %d perfect dodges, %d dodges, %d guarded, %d hits taken;"
                + " %d payloads refused. Minecraft earned Beta %d, Burst %d.", f.counts[0], f.counts[1], f.counts[2], f.counts[3], f.counts[4],
                f.counts[5], f.counts[6], f.rejected, Integer.toUnsignedLong(f.beta), Integer.toUnsignedLong(f.burst));
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
