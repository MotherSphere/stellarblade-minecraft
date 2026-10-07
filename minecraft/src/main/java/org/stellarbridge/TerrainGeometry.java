package org.stellarbridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.Box;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

final class TerrainGeometry {
    /** Edge of the world-aligned merge cells in blocks: a block edit only changes the boxes of its own cell. */
    static final int CELL = 8;

    private TerrainGeometry() { }

    /**
     * Where a fluid's wall ends (exclusive): the first block above the fluid at y that blocks (solid tells), or top.
     * Fluid and air above it stay inside the wall.
     */
    static int fluidWallEnd(int y, int top, java.util.function.IntPredicate solid) {
        for (int above = y + 1; above < top; above++) if (solid.test(above)) return above;
        return Math.max(top, y + 1);
    }

    /** Collision shapes relative to the origin, merged per cell and clipped to bounds. */
    static List<BridgeProtocol.Box> merge(List<VoxelShape> shapes, BridgeProtocol.Box bounds,
                                          double originX, double originY, double originZ) {
        List<Box> extents = shapes.stream().map(VoxelShape::getBoundingBox).toList();
        List<BridgeProtocol.Box> boxes = new ArrayList<>();
        for (int x = first(bounds.minX(), originX); x <= last(bounds.maxX(), originX); x++) {
            for (int y = first(bounds.minY(), originY); y <= last(bounds.maxY(), originY); y++) {
                for (int z = first(bounds.minZ(), originZ); z <= last(bounds.maxZ(), originZ); z++) {
                    Box cell = new Box(Math.max(bounds.minX(), x * CELL - originX), Math.max(bounds.minY(), y * CELL - originY),
                            Math.max(bounds.minZ(), z * CELL - originZ), Math.min(bounds.maxX(), (x + 1) * CELL - originX),
                            Math.min(bounds.maxY(), (y + 1) * CELL - originY), Math.min(bounds.maxZ(), (z + 1) * CELL - originZ));
                    List<VoxelShape> inside = new ArrayList<>();
                    for (int i = 0; i < shapes.size(); i++) if (extents.get(i).intersects(cell)) inside.add(shapes.get(i));
                    if (inside.isEmpty()) continue;
                    VoxelShape clipped = VoxelShapes.combineAndSimplify(union(inside, 0, inside.size()), VoxelShapes.cuboid(cell),
                            BooleanBiFunction.AND);
                    for (Box box : clipped.getBoundingBoxes())
                        boxes.add(new BridgeProtocol.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ));
                }
            }
        }
        if (boxes.size() > BridgeProtocol.MAX_BOXES)
            throw new IllegalArgumentException("Terrain exceeds " + BridgeProtocol.MAX_BOXES + " collision boxes; simplify the test area");
        boxes.sort(Comparator.comparingDouble(BridgeProtocol.Box::minX)
                .thenComparingDouble(BridgeProtocol.Box::minY).thenComparingDouble(BridgeProtocol.Box::minZ)
                .thenComparingDouble(BridgeProtocol.Box::maxX).thenComparingDouble(BridgeProtocol.Box::maxY)
                .thenComparingDouble(BridgeProtocol.Box::maxZ));
        return List.copyOf(boxes);
    }

    private static int first(double min, double origin) { return (int) Math.floor((min + origin) / CELL); }
    private static int last(double max, double origin) { return (int) Math.ceil((max + origin) / CELL) - 1; }

    /** Pairwise union: each merge stays as small as its two halves, unlike folding every shape into one growing grid. */
    private static VoxelShape union(List<VoxelShape> shapes, int from, int to) {
        if (to - from == 1) return shapes.get(from);
        int middle = (from + to) >>> 1;
        return VoxelShapes.combineAndSimplify(union(shapes, from, middle), union(shapes, middle, to), BooleanBiFunction.OR);
    }
}
