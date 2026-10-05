package org.stellarbridge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.slf4j.LoggerFactory;

/** Server block snapshots, client resource/tint preparation, and one bounded file worker. */
final class NativeSceneExport {
    private record Snapshot(IntegratedServer server, UUID owner, NativeScene.Binding binding,
                            List<NativeScene.Cell> cells, String error, long updatedNanos) { }
    private record Plan(NativeScene.Binding binding, List<NativeScene.Face> faces, Map<String, byte[]> resources) { }
    private record Desired(Plan plan, long updatedNanos) { }
    private static volatile Snapshot snapshot;
    private static volatile Desired desired;
    private static final Object publicationGate = new Object();
    private static final Map<String, byte[]> resources = new HashMap<>(); // Client only; refreshed on resource reload.
    private static final AtomicReference<String> failure = new AtomicReference<>();
    private static ScheduledExecutorService worker;
    private static NativeSceneFiles files; // Worker only.
    private static Plan prepared;
    private static String boundSession;
    private static ClientWorld boundWorld;
    private static String reportedError;
    private static int ticks;

    static void initialize() {
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "stellar-scene-writer");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(NativeSceneExport::write, 0, 200, TimeUnit.MILLISECONDS);
        ClientTickEvents.END_CLIENT_TICK.register(NativeSceneExport::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(NativeSceneExport::invalidate));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            invalidate();
            worker.execute(NativeSceneExport::write);
            worker.shutdown();
            try {
                if (!worker.awaitTermination(2, TimeUnit.SECONDS))
                    LoggerFactory.getLogger("stellar_bridge").warn("Native scene cleanup did not finish before the shutdown deadline");
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override public Identifier getFabricId() { return Identifier.of("stellar_bridge", "native_scene_resources"); }
            @Override public void reload(ResourceManager manager) {
                MinecraftClient.getInstance().execute(() -> {
                    resources.clear();
                    prepared = null;
                    offer(null);
                });
            }
        });
    }

    /** Called only by the integrated server, after publishing this collision revision. */
    static void capture(IntegratedServer server, ServerWorld world, UUID owner, NativeScene.Binding binding) {
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

    static void stop() {
        snapshot = null;
        offer(null);
    }

    private static void invalidate() {
        stop();
        prepared = null;
        boundSession = null;
        boundWorld = null;
        resources.clear();
    }

    private static void offer(Plan plan) {
        synchronized (publicationGate) { desired = plan == null ? null : new Desired(plan, System.nanoTime()); }
    }

    private static void tick(MinecraftClient client) {
        if (++ticks % 4 != 0) return;
        Snapshot current = snapshot;
        if (current == null || System.nanoTime() - current.updatedNanos > 1_000_000_000L || client.world == null
                || client.player == null || client.getServer() != current.server || !client.player.getUuid().equals(current.owner)
                || !client.world.getRegistryKey().getValue().toString().equals(current.binding.dimension())) {
            offer(null);
            prepared = null;
            return;
        }
        if (!current.binding.session().equals(boundSession)) {
            boundSession = current.binding.session();
            boundWorld = client.world;
        }
        try {
            if (boundWorld != client.world) throw new IOException("Native scene client world changed; restart the bridge");
            if (current.error != null) throw new IOException(current.error);
            int ox = (int) Math.floor(current.binding.origin().x()), oy = (int) current.binding.origin().y(), oz = (int) Math.floor(current.binding.origin().z());
            // Block colors are world-dependent; the worker receives only the resolved RGB values.
            var faces = NativeScene.faces(current.cells, cell -> client.getBlockColors().getColor(Blocks.GRASS_BLOCK.getDefaultState(),
                    client.world, new BlockPos(ox + cell.x(), oy + cell.y(), oz + cell.z()), 0));
            Map<String, byte[]> selected = new HashMap<>();
            for (var face : faces) {
                selected.put(face.texture().base(), resource(client, face.texture().base()));
                if (face.texture().overlay() != null)
                    selected.put(face.texture().overlay(), resource(client, face.texture().overlay()));
            }
            Plan next = new Plan(current.binding, faces, Map.copyOf(selected));
            if (!next.equals(prepared)) prepared = next;
            if (snapshot != current) return; // Do not publish a client preparation across a server binding change.
            offer(prepared);
            report(client, failure.get());
        } catch (IOException | RuntimeException problem) {
            offer(null);
            prepared = null;
            report(client, problem.getMessage());
        }
    }

    private static byte[] resource(MinecraftClient client, String name) throws IOException {
        byte[] known = resources.get(name);
        if (known != null) return known;
        Identifier id = Identifier.of("minecraft", "textures/block/" + name + ".png");
        try (var input = client.getResourceManager().getResourceOrThrow(id).getInputStream()) {
            byte[] bytes = input.readNBytes(NativeScene.MAX_BYTES + 1);
            if (bytes.length > NativeScene.MAX_BYTES) throw new IOException("Native scene source PNG exceeds 1 MiB: " + id);
            resources.put(name, bytes);
            return bytes;
        }
    }

    private static void write() {
        Desired request = desired;
        try {
            if (request == null || System.nanoTime() - request.updatedNanos > 1_000_000_000L) {
                if (files != null) files.stop();
                return;
            }
            if (files == null) files = new NativeSceneFiles(Path.of(System.getProperty("stellarbridge.directory",
                    Path.of(System.getProperty("user.home"), ".local", "share", "stellar-blade-minecraft", "bridge").toString())));
            Plan plan = request.plan;
            try (var publication = files.stage(plan.binding, plan.faces, plan.resources, System.currentTimeMillis() / 1000)) {
                // Encoding and bulk writes have finished. Only the atomic descriptor rename shares this gate.
                synchronized (publicationGate) {
                    Desired latest = desired;
                    Snapshot server = snapshot;
                    if (latest != null && latest.plan == plan && server != null && server.binding.equals(plan.binding)
                            && System.nanoTime() - latest.updatedNanos <= 1_000_000_000L
                            && System.nanoTime() - server.updatedNanos <= 1_000_000_000L) publication.commit();
                }
            }
            failure.set(null);
        } catch (IOException | RuntimeException problem) {
            failure.set(problem.getMessage());
            if (worker.isShutdown()) LoggerFactory.getLogger("stellar_bridge").warn("Native scene cleanup failed on shutdown", problem);
        }
    }

    private static void report(MinecraftClient client, String message) {
        if (message != null && !message.equals(reportedError)) {
            LoggerFactory.getLogger("stellar_bridge").warn("Native scene export paused: {}", message);
            client.player.sendMessage(Text.literal("Native scene export paused: " + message), false);
        }
        reportedError = message;
    }
}
