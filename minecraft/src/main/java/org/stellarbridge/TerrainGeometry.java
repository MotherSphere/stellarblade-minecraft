package org.stellarbridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;

final class TerrainGeometry {
    /** Edge of the world-aligned merge cells in blocks: a block edit only changes the boxes of its own cell. */
    static final int CELL = 8;
    /**
     * Blocks EVE's feet keep from the window's floor, and from its ceiling beyond her height, before the window
     * recentres vertically; both stay under a cell, so a recentre always moves it.
     */
    static final double MARGIN = 4, HEIGHT = 2;
    /** Merged cells kept for reuse: about three windows, so a held move and the current window both stay cached. */
    private static final int MEMO_CELLS = 256;

    /** One merged cell: its clip, the collision boxes of the shapes reaching into it, and their merge. */
    record Cell(Box clip, List<Box> parts, List<BridgeProtocol.Box> boxes) { }

    private TerrainGeometry() { }

    /** The world cell of a block coordinate. */
    static int cell(double coordinate) { return Math.floorDiv((int) Math.floor(coordinate), CELL); }

    /** The 5 × 3 × 5-cell window centred on world cell centre (x, y, z), relative to the origin. */
    static BridgeProtocol.Box window(int[] centre, Vec3d origin) {
        return new BridgeProtocol.Box((centre[0] - 2) * CELL - origin.x, (centre[1] - 1) * CELL - origin.y,
                (centre[2] - 2) * CELL - origin.z, (centre[0] + 3) * CELL - origin.x, (centre[1] + 2) * CELL - origin.y,
                (centre[2] + 3) * CELL - origin.z);
    }

    /**
     * The window centre for EVE's feet (world): the current one while she stays within one cell of it horizontally and
     * MARGIN from its floor and ceiling, else her own cell. Steps, jumps and fights along a cell line keep the window.
     */
    static int[] follow(int[] centre, Vec3d feet) {
        double floor = (centre[1] - 1) * CELL, ceiling = (centre[1] + 2) * CELL;
        int x = cell(feet.x), z = cell(feet.z);
        return Math.abs(x - centre[0]) < 2 && Math.abs(z - centre[2]) < 2
                && feet.y - floor >= MARGIN && ceiling - feet.y >= MARGIN + HEIGHT ? centre : new int[] {x, cell(feet.y), z};
    }

    /**
     * Where a fluid's wall ends (exclusive): the first block above the fluid at y that blocks (solid tells), or top.
     * Fluid and air above it stay inside the wall.
     */
    static int fluidWallEnd(int y, int top, java.util.function.IntPredicate solid) {
        for (int above = y + 1; above < top; above++) if (solid.test(above)) return above;
        return Math.max(top, y + 1);
    }

    /**
     * Collision shapes relative to the origin, merged per cell and clipped to bounds. memo keeps merged cells between
     * calls: a cell whose clip and shapes' boxes are unchanged reuses its merge.
     */
    static List<BridgeProtocol.Box> merge(List<VoxelShape> shapes, BridgeProtocol.Box bounds,
                                          double originX, double originY, double originZ, Map<Long, Cell> memo) {
        int x0 = first(bounds.minX(), originX), y0 = first(bounds.minY(), originY), z0 = first(bounds.minZ(), originZ);
        int x1 = last(bounds.maxX(), originX), y1 = last(bounds.maxY(), originY), z1 = last(bounds.maxZ(), originZ);
        // Each shape goes to the cells its extent reaches into, as Box.intersects tells (open faces).
        Map<Long, List<VoxelShape>> buckets = new HashMap<>();
        for (VoxelShape shape : shapes) {
            Box extent = shape.getBoundingBox();
            for (int x = Math.max(x0, first(extent.minX, originX)); x <= Math.min(x1, last(extent.maxX, originX)); x++)
                for (int y = Math.max(y0, first(extent.minY, originY)); y <= Math.min(y1, last(extent.maxY, originY)); y++)
                    for (int z = Math.max(z0, first(extent.minZ, originZ)); z <= Math.min(z1, last(extent.maxZ, originZ)); z++)
                        buckets.computeIfAbsent(BlockPos.asLong(x, y, z), key -> new ArrayList<>()).add(shape);
        }
        List<BridgeProtocol.Box> boxes = new ArrayList<>();
        Map<Long, Cell> merged = new HashMap<>();
        buckets.forEach((key, inside) -> {
            int x = BlockPos.unpackLongX(key), y = BlockPos.unpackLongY(key), z = BlockPos.unpackLongZ(key);
            Box clip = new Box(Math.max(bounds.minX(), x * CELL - originX), Math.max(bounds.minY(), y * CELL - originY),
                    Math.max(bounds.minZ(), z * CELL - originZ), Math.min(bounds.maxX(), (x + 1) * CELL - originX),
                    Math.min(bounds.maxY(), (y + 1) * CELL - originY), Math.min(bounds.maxZ(), (z + 1) * CELL - originZ));
            List<Box> parts = inside.stream().flatMap(shape -> shape.getBoundingBoxes().stream()).toList();
            Cell cell = memo.get(key);
            if (cell == null || !cell.clip.equals(clip) || !cell.parts.equals(parts)) {
                VoxelShape clipped = VoxelShapes.combineAndSimplify(union(inside, 0, inside.size()), VoxelShapes.cuboid(clip),
                        BooleanBiFunction.AND);
                cell = new Cell(clip, parts, clipped.getBoundingBoxes().stream()
                        .map(box -> new BridgeProtocol.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)).toList());
            }
            merged.put(key, cell);
            boxes.addAll(cell.boxes);
        });
        // Kept before the cap check: a move held by the cap retries from cache.
        memo.putAll(merged);
        if (memo.size() > MEMO_CELLS) memo.keySet().retainAll(merged.keySet());
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
