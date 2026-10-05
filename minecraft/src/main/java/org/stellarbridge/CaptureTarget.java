package org.stellarbridge;

import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/** Pick from the displayed camera, retaining the owner's normal interaction reach. */
public final class CaptureTarget {
    public static HitResult find(Entity owner, CaptureProjection.CameraPose camera,
                                 double blockRange, double entityRange, float delta) {
        Vec3d eyes = owner.getCameraPosVec(delta);
        Vec3d start = new Vec3d(camera.x(), camera.y(), camera.z());
        Vec3d end = rayEnd(camera, eyes, Math.max(blockRange, entityRange));
        if (end == null) return miss(eyes, eyes);
        HitResult block = owner.getWorld().raycast(new RaycastContext(start, end,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, owner));
        double limit = start.squaredDistanceTo(block.getPos());
        var entity = ProjectileUtil.raycast(owner, start, end, new Box(start, end).expand(1),
                candidate -> !candidate.isSpectator() && candidate.canHit(), limit);
        HitResult hit = entity != null && start.squaredDistanceTo(entity.getPos()) < limit ? entity : block;
        double range = hit == entity ? entityRange : blockRange;
        return withinReach(eyes, hit.getPos(), range) ? hit : miss(eyes, hit.getPos());
    }

    static Vec3d rayEnd(CaptureProjection.CameraPose camera, Vec3d eyes, double range) {
        Vec3d start = new Vec3d(camera.x(), camera.y(), camera.z());
        double offset = start.distanceTo(eyes);
        // ponytail: bound voxel traversal to this local lab; larger cameras/ranges need a chunk-aware picker.
        if (!Double.isFinite(offset) || offset > 64 || !Double.isFinite(range) || range < 0 || range > 64
                || !Float.isFinite(camera.pitch()) || !Float.isFinite(camera.yaw())) return null;
        return start.add(Vec3d.fromPolar(camera.pitch(), camera.yaw()).multiply(range + offset));
    }

    static boolean withinReach(Vec3d eyes, Vec3d target, double range) {
        return Double.isFinite(range) && range >= 0 && target.squaredDistanceTo(eyes) < range * range;
    }

    private static BlockHitResult miss(Vec3d eyes, Vec3d target) {
        Vec3d direction = target.subtract(eyes);
        return BlockHitResult.createMissed(target, Direction.getFacing(direction.x, direction.y, direction.z),
                BlockPos.ofFloored(target));
    }
}
