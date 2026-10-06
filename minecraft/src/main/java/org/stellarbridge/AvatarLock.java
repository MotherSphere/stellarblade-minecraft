package org.stellarbridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Stellar Blade's lock-on for Minecraft's entities, as Stellar Blade's own needs its own enemies: the lock-on key picks
 * the living entity nearest the crosshair within CONE of it, RANGE blocks and in sight, outlines it, and turns
 * Minecraft's camera toward it each frame. Stellar Blade's camera follows Minecraft's, so EVE runs and strikes toward
 * the target. Pressing the key again, losing the target (dead, gone, beyond KEEP blocks), losing the bridge link or
 * leaving EVE mode releases it. Render thread only.
 */
public final class AvatarLock {
    static final double RANGE = 20, KEEP = 28, CONE = Math.toRadians(40);
    static final double TURN = 12; // Per second: the camera closes this share of the gap, about 0.25 s to settle.
    static final float PITCH_DOWN = 8; // Degrees the camera looks below the target, as Stellar Blade frames it over EVE.
    private static Entity target;
    private static long lastNanos;

    static void toggle() {
        target = target != null ? null : pick();
        lastNanos = 0;
    }
    static void release() { target = null; }

    /** True for the locked entity: Minecraft outlines it as it does glowing entities. */
    public static boolean outlines(Entity entity) { return entity != null && entity == target; }

    private static Entity pick() {
        var client = MinecraftClient.getInstance();
        var player = client.player;
        if (player == null || client.world == null) return null;
        Vec3d eye = player.getEyePos(), look = player.getRotationVector();
        Entity best = null;
        double bestScore = Double.MAX_VALUE;
        for (Entity entity : client.world.getOtherEntities(player, player.getBoundingBox().expand(RANGE), e -> AvatarCombat.strikable(e, player))) {
            Vec3d toward = entity.getBoundingBox().getCenter().subtract(eye);
            double distance = toward.length(), angle = Math.acos(MathHelper.clamp(look.dotProduct(toward) / Math.max(distance, 1e-6), -1, 1));
            if (distance > RANGE || angle > CONE || !player.canSee(entity)) continue;
            double score = angle + 0.3 * distance / RANGE; // Mostly the crosshair, then the nearer.
            if (score < bestScore) { bestScore = score; best = entity; }
        }
        return best;
    }

    /** Minecraft yaw and pitch (degrees) looking along (dx, dy, dz). */
    static float[] angles(double dx, double dy, double dz) {
        return new float[] {(float) Math.toDegrees(Math.atan2(dz, dx)) - 90, (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)))};
    }

    /** Each frame before the camera: turn Minecraft's camera toward the target, or release a lost one. */
    static void frame(long nanos) {
        var client = MinecraftClient.getInstance();
        var player = client.player;
        if (target == null) return;
        if (player == null || !target.isAlive() || target.isRemoved() || target.getWorld() != client.world || target.distanceTo(player) > KEEP) {
            target = null;
            return;
        }
        double seconds = lastNanos == 0 ? 0 : Math.min((nanos - lastNanos) / 1e9, 0.1);
        lastNanos = nanos;
        Vec3d toward = target.getBoundingBox().getCenter().subtract(player.getEyePos());
        float[] wanted = angles(toward.x, toward.y, toward.z);
        double share = 1 - Math.exp(-TURN * seconds);
        double yaw = MathHelper.wrapDegrees(wanted[0] - player.getYaw()) * share;
        double pitch = (MathHelper.clamp(wanted[1] + PITCH_DOWN, -90, 90) - player.getPitch()) * share;
        player.changeLookDirection(yaw / 0.15, pitch / 0.15); // As the mouse turns it (0.15 degrees per unit), interpolation included.
    }
}
