package org.stellarbridge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

public final class TerrainGeometryTest {
    private static double volume(List<BridgeProtocol.Box> boxes) {
        return boxes.stream().mapToDouble(box -> (box.maxX() - box.minX()) * (box.maxY() - box.minY()) * (box.maxZ() - box.minZ())).sum();
    }

    private static List<BridgeProtocol.Box> merge(List<VoxelShape> shapes, BridgeProtocol.Box bounds, double x, double y, double z) {
        return TerrainGeometry.merge(shapes, bounds, x, y, z, new HashMap<>());
    }

    public static void main(String[] args) {
        BridgeProtocol.Box bounds = new BridgeProtocol.Box(-4.5, -2, -4.5, 4.5, 4, 4.5);
        List<VoxelShape> blocks = new ArrayList<>();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) blocks.add(VoxelShapes.cuboid(x - 0.5, -1, z - 0.5, x + 0.5, 0, z + 0.5));
        }
        // Origin (0.5, 64, 0.5): the world's cell line x = 0, z = 0 lies at -0.5 relative.
        assert merge(blocks, bounds, 0.5, 64, 0.5).equals(List.of(
                new BridgeProtocol.Box(-4.5, -1, -4.5, -0.5, 0, -0.5), new BridgeProtocol.Box(-4.5, -1, -0.5, -0.5, 0, 4.5),
                new BridgeProtocol.Box(-0.5, -1, -4.5, 4.5, 0, -0.5), new BridgeProtocol.Box(-0.5, -1, -0.5, 4.5, 0, 4.5)))
                : "An 81-block floor must coalesce into one exact box per world-aligned cell";
        blocks.add(VoxelShapes.cuboid(-0.5, 0, -0.5, 0.5, 0.5, 0.5));
        var slab = merge(blocks, bounds, 0.5, 64, 0.5);
        assert slab.stream().anyMatch(box -> box.maxY() == 0.5) : "Slabs keep their actual collision height";
        BridgeProtocol.Box small = new BridgeProtocol.Box(-2, -2, -2, 2, 2, 2); // World 2..6: inside one cell.
        assert merge(List.of(VoxelShapes.cuboid(-8, -8, -8, 8, 8, 8)), small, 4, 4, 4).equals(List.of(small))
                : "Protruding shapes must be clipped to the declared patch";

        // A block edit changes only the boxes of its own 8-block cell.
        BridgeProtocol.Box large = new BridgeProtocol.Box(-16.5, -4, -16.5, 16.5, 8, 16.5);
        blocks.clear();
        for (int x = -17; x <= 17; x++) {
            for (int z = -17; z <= 17; z++) {
                int height = (int) Math.floor(2.5 * Math.sin(x * 0.35) + 2 * Math.cos(z * 0.27));
                for (int y = -5; y <= height; y++) blocks.add(VoxelShapes.cuboid(x - 0.5, y, z - 0.5, x + 0.5, y + 1, z + 0.5));
            }
        }
        Map<Long, TerrainGeometry.Cell> memo = new HashMap<>();
        var before = TerrainGeometry.merge(blocks, large, 0.5, 64, 0.5, memo);
        assert before.size() < BridgeProtocol.MAX_BOXES / 2 : "Rolling hills must leave room under the box limit: " + before.size();
        assert Math.abs(volume(before) - blocks.stream().mapToDouble(shape -> {
            var box = shape.getBoundingBox();
            return Math.max(0, Math.min(box.maxY, 8) - Math.max(box.minY, -4)) * (Math.min(box.maxX, 16.5) - Math.max(box.minX, -16.5))
                    * (Math.min(box.maxZ, 16.5) - Math.max(box.minZ, -16.5));
        }).filter(v -> v > 0).sum()) < 1e-9 : "Merging must preserve the clipped volume exactly";
        Map<Long, TerrainGeometry.Cell> cached = new HashMap<>(memo);
        blocks.add(VoxelShapes.cuboid(9.5, 5, 9.5, 10.5, 6, 10.5)); // World block (10, 69, 10): cell (1, 8, 1).
        List<BridgeProtocol.Box> edited = TerrainGeometry.merge(blocks, large, 0.5, 64, 0.5, memo);
        assert edited.equals(merge(blocks, large, 0.5, 64, 0.5)) : "Reused cells must merge as a fresh merge does";
        long edit = net.minecraft.util.math.BlockPos.asLong(1, 8, 1);
        assert memo.get(edit) != cached.get(edit) : "The edited cell is merged again";
        cached.forEach((key, cell) -> {
            if (key != edit) assert memo.get(key) == cell : "An unchanged cell reuses its merge";
        });
        Set<BridgeProtocol.Box> changed = new HashSet<>(edited);
        Set<BridgeProtocol.Box> removed = new HashSet<>(before);
        removed.removeAll(changed);
        changed.removeAll(before);
        assert !changed.isEmpty() : "The edit must change its cell";
        for (BridgeProtocol.Box box : changed) assert box.minX() >= 7.5 && box.maxX() <= 15.5 && box.minY() >= 0 && box.maxY() <= 8
                && box.minZ() >= 7.5 && box.maxZ() <= 15.5 : "Only the edited cell may gain boxes: " + box;
        for (BridgeProtocol.Box box : removed) assert box.minX() >= 7.5 && box.maxX() <= 15.5 && box.minY() >= 0 && box.maxY() <= 8
                && box.minZ() >= 7.5 && box.maxZ() <= 15.5 : "Only the edited cell may lose boxes: " + box;
        // Clipping is part of a cell's identity: another patch never reuses a cell clipped to the first one.
        assert TerrainGeometry.merge(blocks, bounds, 0.5, 64, 0.5, memo).equals(merge(blocks, bounds, 0.5, 64, 0.5))
                : "A cell clipped differently must merge again";

        blocks.clear();
        for (int i = 0; i < BridgeProtocol.MAX_BOXES + 1; i++) {
            double x = -16 + (i % 33), z = -16 + ((i / 33) % 33);
            blocks.add(VoxelShapes.cuboid(x, 0, z, x + 0.4, 0.4, z + 0.4));
        }
        try { merge(blocks, large, 0.5, 64, 0.5); throw new AssertionError("Excess shapes were silently truncated"); }
        catch (IllegalArgumentException expected) { }
        // A fluid's wall stops under the first solid block above it (ice, a floor over cave water) or at the patch's top.
        assert TerrainGeometry.fluidWallEnd(60, 72, y -> y == 62) == 62 : "Under a solid block";
        assert TerrainGeometry.fluidWallEnd(60, 72, y -> false) == 72 : "An open pond walls up to the top";
        assert TerrainGeometry.fluidWallEnd(60, 72, y -> y == 61) == 61 : "Ice right above";
        assert TerrainGeometry.fluidWallEnd(71, 72, y -> false) == 72 && TerrainGeometry.fluidWallEnd(72, 72, y -> false) == 73 : "At the top: one block";

        // World cells, and the 5 x 3 x 5-cell window around one, relative to the origin.
        assert TerrainGeometry.cell(0) == 0 && TerrainGeometry.cell(7.99) == 0 && TerrainGeometry.cell(8) == 1
                && TerrainGeometry.cell(-0.01) == -1 && TerrainGeometry.cell(-8) == -1 && TerrainGeometry.cell(-8.5) == -2 : "Cells floor";
        Vec3d origin = new Vec3d(100.5, 64, -20.5);
        int[] start = {TerrainGeometry.cell(origin.x), TerrainGeometry.cell(origin.y), TerrainGeometry.cell(origin.z)};
        BridgeProtocol.Box window = TerrainGeometry.window(start, origin);
        assert window.equals(new BridgeProtocol.Box(80 - 100.5, 56 - 64, -40 + 20.5, 120 - 100.5, 80 - 64, 0 + 20.5)) : window;
        assert window.minX() < 0 && window.maxX() > 0 && window.minY() < 0 && window.maxY() > 0 && window.minZ() < 0 && window.maxZ() > 0
                : "The starting window holds the origin";
        // The window keeps still while EVE stays within a cell of its centre and away from its floor and ceiling.
        assert TerrainGeometry.follow(start, origin) == start : "The start keeps its window";
        for (double dx = -15.9; dx <= 15.9; dx += 0.7) {
            for (double dz = -15.9; dz <= 15.9; dz += 0.7) {
                // Within the centre cell's neighbours: world x 88..111.99, z -32..-8.01.
                Vec3d feet = new Vec3d(100 + dx / 1.4, 64 + (dx + dz) / 8, -20 + dz / 1.4);
                if (TerrainGeometry.cell(feet.x) < 11 || TerrainGeometry.cell(feet.x) > 13
                        || TerrainGeometry.cell(feet.z) < -4 || TerrainGeometry.cell(feet.z) > -2) continue;
                assert TerrainGeometry.follow(start, feet) == start : "No move within one cell: " + feet;
            }
        }
        assert TerrainGeometry.follow(start, new Vec3d(110, 63.5, -9)) == start : "Zig-zag across cell lines, a step down";
        assert TerrainGeometry.follow(start, new Vec3d(100, 64 + 9.9, -20)) == start : "A jump into the next cell up";
        assert java.util.Arrays.equals(TerrainGeometry.follow(start, new Vec3d(112, 64, -20)), new int[] {14, 8, -3})
                : "Two cells away horizontally recentres on EVE's cell";
        assert java.util.Arrays.equals(TerrainGeometry.follow(start, new Vec3d(100, 64, -33)), new int[] {12, 8, -5});
        assert java.util.Arrays.equals(TerrainGeometry.follow(start, new Vec3d(100, 59.9, -20)), new int[] {12, 7, -3})
                : "Under four blocks above the window's floor (56) recentres down";
        assert TerrainGeometry.follow(start, new Vec3d(100, 60, -20)) == start;
        assert java.util.Arrays.equals(TerrainGeometry.follow(start, new Vec3d(100, 74.1, -20)), new int[] {12, 9, -3})
                : "Within six blocks of the window's ceiling (80) recentres up";
        assert TerrainGeometry.follow(start, new Vec3d(100, 74, -20)) == start;
        // Once EVE has left the link, Minecraft follows the origin: a window far away returns to the start cell, whose
        // window holds the anchor Stellar Blade imports again.
        assert java.util.Arrays.equals(TerrainGeometry.follow(new int[] {40, 8, -3}, origin), start) : "Back to the start";
        // After a recentre EVE is in the new centre cell, so the window does not move back across the line she crossed.
        int[] moved = TerrainGeometry.follow(start, new Vec3d(112, 64, -20));
        assert TerrainGeometry.follow(moved, new Vec3d(111.5, 64, -20)) == moved : "Hysteresis: one step back keeps the moved window";
        System.out.println("Terrain geometry tests passed");
    }
}
