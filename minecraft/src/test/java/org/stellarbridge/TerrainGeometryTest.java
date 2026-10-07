package org.stellarbridge;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

public final class TerrainGeometryTest {
    private static double volume(List<BridgeProtocol.Box> boxes) {
        return boxes.stream().mapToDouble(box -> (box.maxX() - box.minX()) * (box.maxY() - box.minY()) * (box.maxZ() - box.minZ())).sum();
    }

    public static void main(String[] args) {
        BridgeProtocol.Box bounds = new BridgeProtocol.Box(-4.5, -2, -4.5, 4.5, 4, 4.5);
        List<VoxelShape> blocks = new ArrayList<>();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) blocks.add(VoxelShapes.cuboid(x - 0.5, -1, z - 0.5, x + 0.5, 0, z + 0.5));
        }
        // Origin (0.5, 64, 0.5): the world's cell line x = 0, z = 0 lies at -0.5 relative.
        assert TerrainGeometry.merge(blocks, bounds, 0.5, 64, 0.5).equals(List.of(
                new BridgeProtocol.Box(-4.5, -1, -4.5, -0.5, 0, -0.5), new BridgeProtocol.Box(-4.5, -1, -0.5, -0.5, 0, 4.5),
                new BridgeProtocol.Box(-0.5, -1, -4.5, 4.5, 0, -0.5), new BridgeProtocol.Box(-0.5, -1, -0.5, 4.5, 0, 4.5)))
                : "An 81-block floor must coalesce into one exact box per world-aligned cell";
        blocks.add(VoxelShapes.cuboid(-0.5, 0, -0.5, 0.5, 0.5, 0.5));
        var slab = TerrainGeometry.merge(blocks, bounds, 0.5, 64, 0.5);
        assert slab.stream().anyMatch(box -> box.maxY() == 0.5) : "Slabs keep their actual collision height";
        BridgeProtocol.Box small = new BridgeProtocol.Box(-2, -2, -2, 2, 2, 2); // World 2..6: inside one cell.
        assert TerrainGeometry.merge(List.of(VoxelShapes.cuboid(-8, -8, -8, 8, 8, 8)), small, 4, 4, 4).equals(List.of(small))
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
        var before = TerrainGeometry.merge(blocks, large, 0.5, 64, 0.5);
        assert before.size() < BridgeProtocol.MAX_BOXES / 2 : "Rolling hills must leave room under the box limit: " + before.size();
        assert Math.abs(volume(before) - blocks.stream().mapToDouble(shape -> {
            var box = shape.getBoundingBox();
            return Math.max(0, Math.min(box.maxY, 8) - Math.max(box.minY, -4)) * (Math.min(box.maxX, 16.5) - Math.max(box.minX, -16.5))
                    * (Math.min(box.maxZ, 16.5) - Math.max(box.minZ, -16.5));
        }).filter(v -> v > 0).sum()) < 1e-9 : "Merging must preserve the clipped volume exactly";
        blocks.add(VoxelShapes.cuboid(9.5, 5, 9.5, 10.5, 6, 10.5)); // World block (10, 69, 10): cell (1, 8, 1).
        Set<BridgeProtocol.Box> changed = new HashSet<>(TerrainGeometry.merge(blocks, large, 0.5, 64, 0.5));
        Set<BridgeProtocol.Box> removed = new HashSet<>(before);
        removed.removeAll(changed);
        changed.removeAll(before);
        assert !changed.isEmpty() : "The edit must change its cell";
        for (BridgeProtocol.Box box : changed) assert box.minX() >= 7.5 && box.maxX() <= 15.5 && box.minY() >= 0 && box.maxY() <= 8
                && box.minZ() >= 7.5 && box.maxZ() <= 15.5 : "Only the edited cell may gain boxes: " + box;
        for (BridgeProtocol.Box box : removed) assert box.minX() >= 7.5 && box.maxX() <= 15.5 && box.minY() >= 0 && box.maxY() <= 8
                && box.minZ() >= 7.5 && box.maxZ() <= 15.5 : "Only the edited cell may lose boxes: " + box;

        blocks.clear();
        for (int i = 0; i < BridgeProtocol.MAX_BOXES + 1; i++) {
            double x = -16 + (i % 33), z = -16 + ((i / 33) % 33);
            blocks.add(VoxelShapes.cuboid(x, 0, z, x + 0.4, 0.4, z + 0.4));
        }
        try { TerrainGeometry.merge(blocks, large, 0.5, 64, 0.5); throw new AssertionError("Excess shapes were silently truncated"); }
        catch (IllegalArgumentException expected) { }
        // A fluid's wall stops under the first solid block above it (ice, a floor over cave water) or at the patch's top.
        assert TerrainGeometry.fluidWallEnd(60, 72, y -> y == 62) == 62 : "Under a solid block";
        assert TerrainGeometry.fluidWallEnd(60, 72, y -> false) == 72 : "An open pond walls up to the top";
        assert TerrainGeometry.fluidWallEnd(60, 72, y -> y == 61) == 61 : "Ice right above";
        assert TerrainGeometry.fluidWallEnd(71, 72, y -> false) == 72 && TerrainGeometry.fluidWallEnd(72, 72, y -> false) == 73 : "At the top: one block";
        System.out.println("Terrain geometry tests passed");
    }
}
