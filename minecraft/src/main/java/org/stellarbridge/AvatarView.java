package org.stellarbridge;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.slf4j.LoggerFactory;

/**
 * EVE drawn by Minecraft from her bone poses, either a looping pose probe recording or the native pose stream.
 * Minecraft keeps its own camera and frame rate. Render thread only.
 */
public final class AvatarView {
    private static Replay replay;
    private static Live live;
    private static final List<AvatarStream> unreaped = new ArrayList<>();

    private record Replay(ClientWorld world, AvatarRecording recording, AvatarModel model, AvatarRenderer renderer,
                          AvatarRecording.Pose pose, Vec3d anchor, Vector3d origin, long started) { }

    /** A live stream; the model and renderer follow the newest component table. */
    private static final class Live {
        final AvatarStream stream;
        final ClientWorld world;
        final Path pack;
        final Vec3d relativeAnchor;
        final Map<String, AvatarMesh> meshes = new HashMap<>();
        AvatarStream.Table table;
        AvatarModel model;
        AvatarRenderer renderer;
        AvatarRecording.Pose pose;
        Vector3d relativeOrigin;
        boolean posed, anchored;
        long poses, incomplete, lastPoseNanos, windowPoses, windowNanos;
        double rate;
        Live(AvatarStream stream, ClientWorld world, Path pack, Vec3d relativeAnchor) {
            this.stream = stream; this.world = world; this.pack = pack; this.relativeAnchor = relativeAnchor;
            windowNanos = System.nanoTime();
        }
    }

    static void initialize() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommandManager.literal("stellaravatar")
                        .then(ClientCommandManager.literal("replay").executes(context -> replay(context.getSource())))
                        .then(ClientCommandManager.literal("live").executes(context -> live(context.getSource())))
                        .then(ClientCommandManager.literal("status").executes(context -> {
                            context.getSource().sendFeedback(Text.literal(status()));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("off").executes(context -> {
                            off();
                            context.getSource().sendFeedback(Text.literal("EVE avatar removed."));
                            return 1;
                        }))));
        WorldRenderEvents.AFTER_ENTITIES.register(AvatarView::render);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(AvatarView::off));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { off(); reap(); });
    }

    private static Path bridgeDirectory() {
        return Path.of(System.getProperty("stellarbridge.directory",
                Path.of(System.getProperty("user.home"), ".local", "share", "stellar-blade-minecraft", "bridge").toString()));
    }
    private static Path packDirectory() {
        return Path.of(System.getProperty("stellarbridge.avatar",
                Path.of(System.getProperty("user.home"), ".local", "share", "stellar-blade-minecraft", "avatar").toString()));
    }
    private static Vec3d ahead(net.minecraft.client.network.ClientPlayerEntity player) {
        return player.getPos().add(Vec3d.fromPolar(0, player.getYaw()).multiply(2));
    }

    private static int replay(FabricClientCommandSource source) {
        off();
        var player = source.getClient().player;
        if (player == null || source.getClient().world == null) return error(source, "Enter a world first.");
        Path pack = packDirectory();
        AvatarRenderer renderer = null;
        try {
            long loading = System.nanoTime();
            AvatarRecording recording = AvatarRecording.read(bridgeDirectory());
            AvatarModel model = AvatarModel.load(pack, recording.components, new HashMap<>());
            if (model.parts.isEmpty()) return error(source, "No recorded EVE mesh is in the avatar pack " + pack + ".");
            renderer = new AvatarRenderer(model, pack);
            float[] root = recording.frames[0].world()[model.parts.get(0).component()];
            replay = new Replay(source.getClient().world, recording, model, renderer, recording.emptyPose(), ahead(player),
                    new Vector3d(root[4], root[5], root[6]), System.nanoTime());
            int[] sections = renderer.sectionCounts();
            renderer = null;
            String message = String.format("EVE replay: %d meshes, %d/%d material sections drawn, %.1f s loop, loaded in %.0f ms.%s",
                    model.parts.size(), sections[0], sections[1], recording.duration(), (System.nanoTime() - loading) / 1e6,
                    model.skipped.isEmpty() ? "" : " Skipped: " + String.join(", ", model.skipped) + ".");
            LoggerFactory.getLogger("stellar_bridge").info("{}", message);
            source.sendFeedback(Text.literal(message + " /stellaravatar off removes her."));
            return 1;
        } catch (IOException | RuntimeException failure) {
            LoggerFactory.getLogger("stellar_bridge").warn("EVE avatar replay failed", failure);
            return error(source, "EVE replay failed: " + failure.getMessage());
        } finally {
            if (renderer != null) renderer.close();
        }
    }

    private static int live(FabricClientCommandSource source) {
        off();
        reap();
        var player = source.getClient().player;
        if (player == null || source.getClient().world == null) return error(source, "Enter a world first.");
        try {
            String run = UUID.randomUUID().toString().replace("-", "");
            live = new Live(new AvatarStream(bridgeDirectory(), run, System.currentTimeMillis()), source.getClient().world,
                    packDirectory(), ahead(player));
            source.sendFeedback(Text.literal("EVE live avatar requested: Stellar Blade must run with EVE loaded and the matching adapter."
                    + " After /stellar start, F5 and F8 she stands where the bridge places EVE; otherwise two blocks ahead."
                    + " /stellaravatar status shows the stream; /stellaravatar off stops it."));
            return 1;
        } catch (IOException | RuntimeException failure) {
            return error(source, "EVE live avatar failed: " + failure.getMessage());
        }
    }

    private static void tick() {
        reap();
        Live current = live;
        if (current == null) return;
        try {
            current.stream.heartbeat(System.currentTimeMillis());
        } catch (IOException failure) {
            stopLive("stream heartbeat failed: " + failure.getMessage());
        }
    }

    private static void render(WorldRenderContext context) {
        if (replay != null) renderReplay(context);
        else if (live != null) renderLive(context);
    }

    private static void renderReplay(WorldRenderContext context) {
        Replay current = replay;
        if (context.world() != current.world) { off(); return; }
        try {
            current.recording.sample((System.nanoTime() - current.started) / 1e9, current.pose);
            Vec3d camera = context.camera().getPos();
            Matrix4d placement = new Matrix4d().translation(current.anchor.x - camera.x, current.anchor.y - camera.y, current.anchor.z - camera.z)
                    .mul(AvatarModel.UNREAL_TO_MINECRAFT).translate(-current.origin.x, -current.origin.y, -current.origin.z);
            current.model.palettes(current.pose, placement, current.renderer.palettes, false);
            draw(context, current.model, current.renderer, current.pose, placement, camera);
        } catch (RuntimeException failure) {
            off();
            report("EVE avatar renderer stopped: " + failure.getMessage(), failure);
        }
    }

    private static void renderLive(WorldRenderContext context) {
        Live current = live;
        if (context.world() != current.world) { stopLive("the world changed"); return; }
        long nanos = System.nanoTime();
        try {
            AvatarStream.Sample sample = current.stream.poll(current.table, System.currentTimeMillis(), nanos);
            if (sample != null && (current.table == null || sample.generation() != current.table.generation())) {
                AvatarStream.Table table = current.stream.table();
                if (table != null && table.generation() == sample.generation()) install(current, table);
            } else if (sample != null && current.model != null) {
                if (AvatarStream.decode(current.table, sample, current.model, current.pose)) {
                    current.posed = true;
                    current.poses++;
                    current.windowPoses++;
                    current.lastPoseNanos = nanos;
                } else current.incomplete++;
            }
            if (nanos - current.windowNanos >= 2_000_000_000L) {
                current.rate = current.windowPoses * 1e9 / (nanos - current.windowNanos);
                current.windowPoses = 0;
                current.windowNanos = nanos;
            }
            if (!current.posed) return;
            Vec3d camera = context.camera().getPos();
            var link = StellarBridge.renderingContext();
            double[] anchor = current.table.anchor();
            current.anchored = anchor != null && link != null && current.table.session().equals(link.session());
            Matrix4d placement;
            if (current.anchored) {
                // The terrain link's mapping: Minecraft origin + (X, Z, Y) / scale relative to the lab anchor.
                var origin = link.origin();
                placement = new Matrix4d().translation(origin.x() - camera.x, origin.y() - camera.y, origin.z() - camera.z)
                        .mul(AvatarModel.unrealToMinecraft(anchor[3])).translate(-anchor[0], -anchor[1], -anchor[2]);
                // In first person the camera is inside EVE's head, as Minecraft hides its own player there.
                if (MinecraftClient.getInstance().options.getPerspective().isFirstPerson()) return;
            } else {
                float[] root = current.pose.world()[current.model.parts.get(0).component()];
                if (current.relativeOrigin == null) current.relativeOrigin = new Vector3d(root[4], root[5], root[6]);
                Vec3d at = current.relativeAnchor;
                placement = new Matrix4d().translation(at.x - camera.x, at.y - camera.y, at.z - camera.z)
                        .mul(AvatarModel.UNREAL_TO_MINECRAFT).translate(-current.relativeOrigin.x, -current.relativeOrigin.y, -current.relativeOrigin.z);
            }
            current.model.palettes(current.pose, placement, current.renderer.palettes, true);
            draw(context, current.model, current.renderer, current.pose, placement, camera);
        } catch (IOException | RuntimeException failure) {
            stopLive(failure.getMessage());
            if (failure instanceof RuntimeException) LoggerFactory.getLogger("stellar_bridge").error("EVE live avatar stopped", failure);
        }
    }

    /** A new component table: rebuild the model and GPU resources unless only the placement changed. */
    private static void install(Live current, AvatarStream.Table table) throws IOException {
        if (current.table != null && sameComposition(current.table.components(), table.components())) {
            current.table = table;
            return;
        }
        AvatarModel model = AvatarModel.load(current.pack, table.components(), current.meshes);
        if (model.parts.isEmpty()) throw new IOException("No streamed EVE mesh is in the avatar pack " + current.pack);
        AvatarRenderer renderer = new AvatarRenderer(model, current.pack);
        if (current.renderer != null) current.renderer.close();
        current.table = table;
        current.model = model;
        current.renderer = renderer;
        current.pose = AvatarStream.emptyPose(table);
        current.posed = false;
        int[] sections = renderer.sectionCounts();
        report(String.format("EVE live avatar: generation %d, %d meshes, %d/%d material sections%s.", table.generation(),
                model.parts.size(), sections[0], sections[1], model.skipped.isEmpty() ? "" : "; skipped " + String.join(", ", model.skipped)), null);
    }

    private static boolean sameComposition(List<AvatarRecording.Component> a, List<AvatarRecording.Component> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            var x = a.get(i);
            var y = b.get(i);
            if (!x.name().equals(y.name()) || !x.mesh().equals(y.mesh()) || !java.util.Objects.equals(x.leader(), y.leader())
                    || !java.util.Arrays.equals(x.bones(), y.bones()) || !java.util.Arrays.equals(x.morphs(), y.morphs())
                    || x.weights() != y.weights()) return false;
        }
        return true;
    }

    private static void draw(WorldRenderContext context, AvatarModel model, AvatarRenderer renderer, AvatarRecording.Pose pose,
                             Matrix4d placement, Vec3d camera) {
        float[] root = pose.world()[model.parts.get(0).component()];
        Vector3d feet = placement.transformPosition(new Vector3d(root[4], root[5], root[6])).add(camera.x, camera.y, camera.z);
        int light = WorldRenderer.getLightmapCoordinates(context.world(), BlockPos.ofFloored(feet.x, feet.y + 1, feet.z));
        Matrix4f projectionView = new Matrix4f(context.projectionMatrix()).mul(context.positionMatrix());
        renderer.draw(projectionView, lightmap(context.lightmapTextureManager()),
                LightmapTextureManager.getBlockLightCoordinates(light), LightmapTextureManager.getSkyLightCoordinates(light),
                RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(), RenderSystem.getShaderFogColor());
    }

    /** The lightmap's GL texture; enable() also binds it on the active unit, so that binding is restored. */
    private static int lightmap(LightmapTextureManager manager) {
        int previousShaderTexture = RenderSystem.getShaderTexture(2);
        int bound = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        manager.enable();
        int id = RenderSystem.getShaderTexture(2);
        manager.disable();
        RenderSystem.setShaderTexture(2, previousShaderTexture);
        GlStateManager._bindTexture(bound);
        return id;
    }

    /** True while the live avatar stands in for the local player at the bridged position. */
    public static boolean replaces(Entity entity) {
        Live current = live;
        return current != null && current.anchored && current.posed && entity == MinecraftClient.getInstance().player;
    }

    private static String status() {
        if (replay != null) return "EVE avatar: replaying " + replay.model.parts.size() + " meshes from the pose probe recording.";
        return live == null ? "EVE avatar is off." : liveStatus(live);
    }
    private static String liveStatus(Live current) {
        long[] native_ = current.stream.statistics();
        return String.format("EVE live avatar: %s; generation %s; %d poses (%.1f/s), %d incomplete; last %s; native read %d us last, %d max, %d mean over %d published.",
                current.table == null ? "waiting for Lua's component table" : current.anchored ? "anchored to the bridge link" : "two blocks ahead (no entered link)",
                current.table == null ? "-" : Long.toString(current.table.generation()), current.poses, current.rate, current.incomplete,
                current.posed ? String.format("%.0f ms ago", (System.nanoTime() - current.lastPoseNanos) / 1e6) : "never",
                native_[1], native_[2], native_[3], native_[0]);
    }

    /** Stops the live stream; a null reason is the user's own off. */
    private static void stopLive(String reason) {
        Live current = live;
        if (current == null) return;
        live = null;
        String summary = liveStatus(current);
        if (current.renderer != null) current.renderer.close();
        current.stream.stop();
        unreaped.add(current.stream);
        reap();
        if (reason == null) LoggerFactory.getLogger("stellar_bridge").info("{}", summary);
        else report("EVE live avatar stopped: " + reason + ". " + summary, null);
    }

    private static void reap() {
        unreaped.removeIf(stream -> {
            try { return stream.reap(); }
            catch (IOException failure) { return false; }
        });
    }

    private static void off() {
        Replay previous = replay;
        replay = null;
        if (previous != null) previous.renderer.close();
        stopLive(null);
    }

    private static void report(String message, Throwable failure) {
        if (failure != null) LoggerFactory.getLogger("stellar_bridge").error("{}", message, failure);
        else LoggerFactory.getLogger("stellar_bridge").info("{}", message);
        var player = MinecraftClient.getInstance().player;
        if (player != null) player.sendMessage(Text.literal(message), false);
    }

    private static int error(FabricClientCommandSource source, String message) {
        source.sendError(Text.literal(message));
        return 0;
    }
}
