package org.stellarbridge;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Stellar Blade's lock-on for Minecraft's entities, as Stellar Blade's own needs its own enemies: the lock-on key picks
 * the living entity nearest the crosshair within CONE of it, RANGE blocks and in sight, outlines it, and turns
 * Minecraft's camera toward it horizontally each frame. Stellar Blade's camera follows Minecraft's, and its own lock-on holds EVE
 * facing the target (her strafing locomotion): the target's centre goes to Lua every SEND nanoseconds while locked,
 * none once released. Pressing the key again, losing the target (dead, gone, beyond KEEP blocks), losing the bridge
 * link or leaving EVE mode releases it. Render thread only.
 */
public final class AvatarLock {
    static final double RANGE = 20, KEEP = 28, CONE = Math.toRadians(40);
    static final double TURN = 12; // Per second: the camera closes this share of the gap, about 0.25 s to settle.
    static final long SEND = 50_000_000L; // Heartbeat of the target sent to Stellar Blade, whose lease lasts 0.6 s.
    private static Entity target;
    private static long lastNanos, lastSent;

    static void toggle() {
        if (target != null) release();
        else target = pick();
        lastNanos = lastSent = 0;
    }
    static void release() {
        if (target == null) return;
        target = null;
        StellarBridge.lockTarget(null);
    }

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
            release();
            return;
        }
        double seconds = lastNanos == 0 ? 0 : Math.min((nanos - lastNanos) / 1e9, 0.1);
        lastNanos = nanos;
        // Where both are drawn this frame, not where the last tick left them: tick positions step 20 times a second.
        float delta = client.getRenderTickCounter().getTickDelta(true);
        Vec3d feet = AvatarView.cameraFeet(), eye = feet != null ? feet.add(0, player.getStandingEyeHeight(), 0) : player.getCameraPosVec(delta);
        Vec3d center = target.getLerpedPos(delta).add(0, target.getHeight() / 2, 0), toward = center.subtract(eye);
        if (lastSent == 0 || nanos - lastSent >= SEND) {
            lastSent = nanos;
            StellarBridge.lockTarget(center);
        }
        float[] wanted = angles(toward.x, toward.y, toward.z);
        double yaw = MathHelper.wrapDegrees(wanted[0] - player.getYaw()) * (1 - Math.exp(-TURN * seconds));
        // Yaw only: Stellar Blade's camera height follows Minecraft's pitch open-loop, so pitch stays the mouse's.
        player.changeLookDirection(yaw / 0.15, 0); // As the mouse turns it (0.15 degrees per unit), interpolation included.
    }
}
