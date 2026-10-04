package org.stellarbridge;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

public final class TerrainGeometryTest {
    public static void main(String[] args) {
        BridgeProtocol.Box bounds = new BridgeProtocol.Box(-4.5, -2, -4.5, 4.5, 4, 4.5);
        List<VoxelShape> blocks = new ArrayList<>();
        for (int x = -4; x <= 4; x++) {
            for (int z = -4; z <= 4; z++) blocks.add(VoxelShapes.cuboid(x - 0.5, -1, z - 0.5, x + 0.5, 0, z + 0.5));
        }
        assert TerrainGeometry.merge(blocks, bounds).equals(List.of(new BridgeProtocol.Box(-4.5, -1, -4.5, 4.5, 0, 4.5)))
                : "An 81-block floor must coalesce into one exact box";
        blocks.add(VoxelShapes.cuboid(-0.5, 0, -0.5, 0.5, 0.5, 0.5));
        var slab = TerrainGeometry.merge(blocks, bounds);
        assert slab.stream().anyMatch(box -> box.maxY() == 0.5) : "Slabs keep their actual collision height";
        assert TerrainGeometry.merge(List.of(VoxelShapes.cuboid(-8, -8, -8, 8, 8, 8)), bounds).equals(List.of(bounds))
                : "Protruding shapes must be clipped to the declared patch";
        blocks.clear();
        for (int i = 0; i < 257; i++) {
            double x = -4 + (i % 9), y = -1 + ((i / 81) * 1.2), z = -4 + ((i / 9) % 9);
            blocks.add(VoxelShapes.cuboid(x, y, z, x + 0.4, y + 0.4, z + 0.4));
        }
        try { TerrainGeometry.merge(blocks, bounds); throw new AssertionError("Excess shapes were silently truncated"); }
        catch (IllegalArgumentException expected) { }
        System.out.println("Terrain geometry tests passed");
    }
}
