package org.stellarbridge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;

/** The server half of the native scene: block snapshots around the anchor, which NativeSceneExport prepares on the client. */
final class NativeSceneCapture {
    record Snapshot(MinecraftServer server, UUID owner, NativeScene.Binding binding,
                    List<NativeScene.Cell> cells, String error, long updatedNanos) { }
    private static volatile Snapshot snapshot;

    private NativeSceneCapture() { }

    /** Called only by the server thread, after publishing this collision revision. */
    static void capture(MinecraftServer server, ServerWorld world, UUID owner, NativeScene.Binding binding) {
        List<NativeScene.Cell> cells = new ArrayList<>();
        String error = null;
        int ox = (int) Math.floor(binding.origin().x()), oy = (int) binding.origin().y(), oz = (int) Math.floor(binding.origin().z());
        scan: for (int x = -4; x <= 4; x++) {
            for (int y = -2; y <= 3; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos position = new BlockPos(ox + x, oy + y, oz + z);
                    var state = world.getBlockState(position);
                    if (state.isAir()) continue;
                    String material = state.isOf(Blocks.STONE) ? "stone" : state.isOf(Blocks.DIRT) ? "dirt"
                            : state.isOf(Blocks.WHITE_WOOL) ? "white_wool" : state.isOf(Blocks.BEDROCK) ? "bedrock"
                            : state.isOf(Blocks.GRASS_BLOCK) && !state.get(Properties.SNOWY) ? "grass_block" : null;
                    if (material == null) {
                        error = "Unsupported native scene block at " + position.toShortString() + ": " + state;
                        break scan;
                    }
                    cells.add(new NativeScene.Cell(x, y, z, material));
                }
            }
        }
        snapshot = new Snapshot(server, owner, binding, List.copyOf(cells), error, System.nanoTime());
    }

    static void stop() { snapshot = null; }

    /** Any thread: the latest snapshot, or null. */
    static Snapshot snapshot() { return snapshot; }
}
