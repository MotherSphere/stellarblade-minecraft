package org.stellarbridge;

import java.util.Comparator;
import java.util.List;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

final class TerrainGeometry {
    private TerrainGeometry() { }

    static List<BridgeProtocol.Box> merge(List<VoxelShape> shapes, BridgeProtocol.Box bounds) {
        VoxelShape merged = VoxelShapes.empty();
        for (VoxelShape shape : shapes) merged = VoxelShapes.combine(merged, shape, BooleanBiFunction.OR);
        VoxelShape clipped = VoxelShapes.combineAndSimplify(merged,
                VoxelShapes.cuboid(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ()),
                BooleanBiFunction.AND);
        List<BridgeProtocol.Box> boxes = clipped.getBoundingBoxes().stream()
                .map(box -> new BridgeProtocol.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ))
                .sorted(Comparator.comparingDouble(BridgeProtocol.Box::minX)
                        .thenComparingDouble(BridgeProtocol.Box::minY).thenComparingDouble(BridgeProtocol.Box::minZ)
                        .thenComparingDouble(BridgeProtocol.Box::maxX).thenComparingDouble(BridgeProtocol.Box::maxY)
                        .thenComparingDouble(BridgeProtocol.Box::maxZ))
                .toList();
        if (boxes.size() > 256) throw new IllegalArgumentException("Terrain exceeds 256 collision boxes; simplify the test area");
        return boxes;
    }
}
