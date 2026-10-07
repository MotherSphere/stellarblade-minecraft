package org.stellarbridge;

import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * EVE's Stellar Blade stats as Minecraft's while she replaces the bridged player. Her maximum HP scales the player's
 * maximum health (her base 2000 is vanilla's 20) through a transient modifier, never saved and removed with the bridge,
 * the health keeping its share of the maximum across each change; her physical attack power scales her strikes (her base
 * 450 is the strike table's); her critical rate and damage roll on each strike; and each hit she takes is reduced as
 * Stellar Blade reduces it, by her melee or ranged damage reduction, her final HP damage reduction and her shield's 17.5 %
 * a shield block (her shield full: Stellar Blade's own damage never reaches her), before Minecraft applies its armor once.
 * The render thread publishes, the server thread applies.
 */
final class AvatarStats {
    // Stellar Blade's CharacterTable row Player: MaxHP 2000, PhysicAttackPower 450, DamageReductionPerShieldBock 0.175.
    static final float BASE_HP = 2000, BASE_ATTACK = 450, SHIELD_BLOCK = 0.175f;
    // ponytail: fixed bounds so a strange read can neither make EVE invulnerable nor one-shot everything; tune with the game.
    static final float MAX_REDUCTION = 0.9f, MIN_SCALE = 0.5f, MAX_SCALE = 10;
    static final Identifier HEALTH = Identifier.of("stellar_bridge", "eve_max_health");

    /** AvatarStream.stats() by name. */
    record Values(float maxHp, float shieldBlocks, float attack, float critPercent, float critRate, float melee, float ranged,
                  float finalHp) {
        static final Values BASE = new Values(BASE_HP, 0, BASE_ATTACK, 0, 1, 0, 0, 0); // No stats: Minecraft's own rules.

        static Values of(float[] s) { return s == null ? null : new Values(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7]); }
        /** The multiplier of the player's base maximum health. */
        float health() { return Math.clamp(maxHp / BASE_HP, MIN_SCALE, MAX_SCALE); }
        float strikes() { return Math.clamp(attack / BASE_ATTACK, MIN_SCALE, MAX_SCALE); }
        /** Whether a strike with this roll (0 to 1) is critical, and a critical strike's multiplier. */
        boolean crits(float roll) { return roll * 100 < critPercent; }
        float critMultiplier() { return Math.clamp(critRate, 1, MAX_SCALE); }
        /** The share of a hit's damage EVE takes; a negative final reduction adds damage, up to double. */
        float taken(boolean projectile) {
            return (1 - reduction(projectile ? ranged : melee)) * (1 - Math.clamp(finalHp, -1, MAX_REDUCTION))
                    * (1 - reduction(shieldBlocks * SHIELD_BLOCK));
        }
        private static float reduction(float rate) { return Math.clamp(rate, 0, MAX_REDUCTION); }
    }

    private static volatile Values published;

    /** Render thread, each frame: AvatarStream.stats() while EVE replaces the bridged player, else null. */
    static void publish(float[] stats) { published = Values.of(stats); }
    /** Any thread: the latest stats, or BASE without them. */
    static Values current() {
        Values stats = published;
        return stats == null ? Values.BASE : stats;
    }

    /**
     * Server thread: the stats' maximum health on the player (BASE removes the modifier), its health keeping its share of
     * the maximum. Only a changed value touches the player, and is logged.
     */
    static void apply(ServerPlayerEntity player, Values stats) {
        var health = player.getAttributeInstance(EntityAttributes.GENERIC_MAX_HEALTH);
        if (health == null) return;
        double value = stats.health() - 1;
        var modifier = health.getModifier(HEALTH);
        if (modifier == null ? value == 0 : modifier.value() == value) return;
        float share = player.getHealth() / player.getMaxHealth();
        health.removeModifier(HEALTH);
        if (value != 0) health.addTemporaryModifier(new EntityAttributeModifier(HEALTH, value, EntityAttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        if (player.isAlive()) player.setHealth(share * player.getMaxHealth());
        var log = org.slf4j.LoggerFactory.getLogger("stellar_bridge");
        if (stats == Values.BASE) log.info("Stats: maximum health {} (EVE's stats removed)", player.getMaxHealth());
        else log.info("Stats: maximum health {} for EVE's max HP {}", player.getMaxHealth(), stats.maxHp());
    }

    /**
     * Stellar Blade's heals as Minecraft's: the adapter mirrors the player's health share into Stellar Blade's HP, so the
     * game accepts and spends its own heals, and counts the HP they add. The render thread reports that count (-1: none),
     * the server heals the same share of the player's maximum health and publishes the share after it with the count it
     * applied, and the adapter writes nothing more until that acknowledgement arrives.
     */
    record Mirror(float share, int acknowledged) { static final Mirror OFF = new Mirror(0, 0); }
    static final long MAX_HEAL_STEP = 100000; // A larger step of the count is not a heal: adopted, not applied.
    private static volatile long reported = -1;
    private static volatile Mirror mirror = Mirror.OFF;
    private static long applied = -1; // Server thread: the count last applied, -1 before a baseline.

    /** Render thread: AvatarStream.healed() each controlled frame, -1 once the stream stops. */
    static void reportHeals(long counter) { reported = counter; }
    /** Any thread: the health word's share and acknowledged count. */
    static Mirror mirror() { return mirror; }

    /** Server thread, each tick: the heals counted since the last tick, applied while active (EVE drives the living player). */
    static void heal(ServerPlayerEntity player, boolean active) {
        boolean alive = player.isAlive();
        long points = due(active, alive);
        if (points > 0) player.heal(healthFor(points, current().maxHp(), player.getMaxHealth()));
        if (applied >= 0) mirror = new Mirror(active && alive ? Math.clamp(player.getHealth() / player.getMaxHealth(), 0f, 1f) : 0, (int) applied);
    }
    /**
     * Server thread: the heal points of the reported count to apply now. Taken (the count becomes the one applied) while
     * active or once the player is dead; while the player lives undriven (a lapsed link) they wait unacknowledged, so the
     * adapter keeps Stellar Blade's healed HP, unwritten, until Minecraft applies them.
     */
    static long due(boolean active, boolean alive) {
        long counter = reported;
        if (counter < 0) stopHeals();
        if (counter < 0 || !active && alive) return 0;
        long points = heals(applied, counter);
        applied = counter;
        return alive ? points : 0;
    }
    /** Server thread: forgets the count, so the next one is a baseline. */
    static void stopHeals() {
        applied = -1;
        mirror = Mirror.OFF;
    }

    /** Heal points from the count applied (-1: none, a baseline) to counter, as u32 counts; a step above MAX_HEAL_STEP gives none. */
    static long heals(long applied, long counter) {
        long step = (counter - applied) & 0xFFFFFFFFL;
        return applied < 0 || step > MAX_HEAL_STEP ? 0 : step;
    }
    /** Minecraft health for Stellar Blade HP points: the same share of the maximum. */
    static float healthFor(long points, float maxHp, float maxHealth) { return points * maxHealth / Math.max(maxHp, 1); }

    static String status(float[] stats) {
        Values v = Values.of(stats);
        return v == null ? " Stats not read." : String.format(" Stats: max HP %.0f (health x%.2f), attack %.0f (strikes x%.2f),"
                + " critical %.0f%% x%.2f, %.0f shield blocks; hits taken x%.2f melee, x%.2f ranged.", v.maxHp(), v.health(),
                v.attack(), v.strikes(), v.critPercent(), v.critMultiplier(), v.shieldBlocks(), v.taken(false), v.taken(true));
    }
}
