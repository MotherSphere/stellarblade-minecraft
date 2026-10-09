package org.stellarbridge;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.FloatBuffer;
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
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.GameRenderer;
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
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
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
    // Stellar Blade stopped granting this run's forwarded input for HALT_NANOS (paused, a cutscene): EVE cannot answer.
    // Written on the render thread into BridgeServer.halted, read by the server thread's damage hook.
    static final long HALT_NANOS = 400_000_000L; // Longer than Lua's 200 ms permit renewal.

    private record Replay(ClientWorld world, AvatarRecording recording, AvatarModel model, AvatarRenderer renderer,
                          AvatarRecording.Pose pose, Vec3d anchor, Vector3d origin, long started) { }

    // A rebuild uploads its decoded textures for up to UPLOAD_NANOS a frame; meshes stay cached up to MESH_BUDGET bytes.
    // ponytail: a frame uploads at least one whole texture (up to about 25 ms for a 2048² one through the driver's copy) and the
    // install frame creates the renderer's mesh buffers (about 20 ms for a full outfit); banded uploads or mesh buffers cached
    // like the textures if those frames show.
    static final long UPLOAD_NANOS = 4_000_000L, MESH_BUDGET = 256L << 20;

    /** A model built off the render thread and its decoded textures still to upload (uploads take them out). */
    private record Prepared(AvatarModel model, Map<String, AvatarTextures.Decoded> textures) {
        void free() { textures.values().forEach(AvatarTextures.Decoded::free); }
    }

    /**
     * A live stream; the model and renderer follow the newest component table. A table needing new meshes is built off the
     * render thread (meshes read, textures decoded), its textures uploaded over the next frames, then the model, its
     * renderer and that table are installed together; until then EVE keeps the previous model, table and pose.
     */
    private static final class Live {
        final AvatarStream stream;
        final ClientWorld world;
        final Path pack;
        final Vec3d relativeAnchor;
        final Map<String, AvatarMesh> meshes = new java.util.LinkedHashMap<>(16, 0.75f, true); // The build worker's only.
        final AvatarTextures textures = new AvatarTextures(); // Shared by the stream's renderers.
        final AvatarRebuild<Prepared> rebuild = new AvatarRebuild<>(this::build, Prepared::free);
        AvatarRebuild.Built<Prepared> uploading; // The wanted table's build while its textures upload.
        long wantedNanos; // When the wanted table arrived.
        AvatarStream.Table table;
        AvatarModel model;
        AvatarRenderer renderer;
        // Recent poses with Stellar Blade's publication times. The displayed pose is interpolated one sample interval
        // in the past on those timestamps, so Minecraft's frame timing and polling add no jitter.
        static final int RING = 4;
        AvatarRecording.Pose pose;
        AvatarRecording.Pose[] ring;
        // Per component: shown in the newest sample, a spare for the next decode, and drawn (shown in the two newest
        // samples, which the displayed pose interpolates between: a component back from Stellar Blade's hiding or
        // from missing data never blends with stale floats).
        boolean[] shown, spare, drawn;
        final double[] times = new double[RING];
        int newest = -1, count;
        float[] rotation; // Stellar Blade's controller Pitch, Yaw, Roll from the newest sample, or null.
        boolean lookApplied; // The newest sample says the adapter turned that controller's camera toward Minecraft's.
        long grantedNanos; // When a sample last said Stellar Blade granted this run's input (or control was off).
        long gapSamples, turned, synced; // Controlled poses, those the adapter turned, those whose camera dolly it set.
        float glow; // Highest streamed material Opacity so far (the sword edge's glow while the game raises it).
        float[] stats; // EVE's latest streamed stats, or null.
        List<AvatarStream.Effect> effects = List.of(); // The game's active effects on EVE in the newest sample.
        final AvatarEffects particles = new AvatarEffects();
        final AvatarWeapons.Blade blade = new AvatarWeapons.Blade();
        final AvatarWeapons.Gun gun = new AvatarWeapons.Gun();
        long effectSamples; // Samples with at least one active effect.
        final AvatarStream.ClothWatch cloth = new AvatarStream.ClothWatch(); // Whether Stellar Blade's cloth moves, for the whole run.
        long clothPoses; // Poses drawing Stellar Blade's cloth.
        double gapSum, gapMax; // Yaw gap in degrees between Minecraft's camera and the published controller rotation.
        double pitchSum, pitchMax; // Pitch gap in degrees between Minecraft's camera and the camera pitch of synced poses.
        final long[] refusals = new long[AvatarStream.DOLLY_REFUSALS.length + 1]; // Controlled poses by dolly refusal.
        Vector3d relativeOrigin;
        boolean posed, anchored, controlled;
        long poses, incomplete, lastPoseNanos, windowPoses, windowNanos;
        double rate, interval = 1000.0 / 60; // Milliseconds between publications.
        Live(AvatarStream stream, ClientWorld world, Path pack, Vec3d relativeAnchor) {
            this.stream = stream; this.world = world; this.pack = pack; this.relativeAnchor = relativeAnchor;
            windowNanos = System.nanoTime();
        }
        /** On the build worker: the table's model and the textures its renderer needs that are not uploaded yet. */
        private Prepared build(AvatarStream.Table table) throws IOException {
            AvatarModel model = AvatarModel.load(pack, table.components(), meshes);
            AvatarModel.trim(meshes, model, MESH_BUDGET);
            if (model.parts.isEmpty()) throw new IOException("No streamed EVE mesh is in the avatar pack " + pack);
            return new Prepared(model, textures.decode(pack, AvatarRenderer.textures(model, pack)));
        }
    }

    static void initialize() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommandManager.literal("stellaravatar")
                        .then(ClientCommandManager.literal("replay").executes(context -> replay(context.getSource())))
                        .then(ClientCommandManager.literal("live").executes(context -> live(context.getSource())))
                        .then(ClientCommandManager.literal("play").executes(context -> play(context.getSource())))
                        .then(ClientCommandManager.literal("status").executes(context -> {
                            context.getSource().sendFeedback(Text.literal(status()));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("off").executes(context -> {
                            off();
                            context.getSource().sendFeedback(Text.literal("EVE avatar removed."));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("shading")
                                .then(ClientCommandManager.literal("auto").executes(context -> shading(context.getSource(), false)))
                                .then(ClientCommandManager.literal("entity").executes(context -> shading(context.getSource(), true))))));
        WorldRenderEvents.AFTER_ENTITIES.register(AvatarView::render);
        // While a perfect dodge slows Minecraft's world, a cold tint shows it.
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((context, tick) -> {
            var client = MinecraftClient.getInstance();
            if (client.world != null && AvatarCombat.slowMotion() && replaces(client.player))
                context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), 0x302050A0);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(AvatarView::off));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { off(); reap(); });
        WardrobeScreen.initialize();
    }

    static Path bridgeDirectory() {
        return Path.of(System.getProperty("stellarbridge.directory",
                Path.of(System.getProperty("user.home"), ".local", "share", "stellar-blade-minecraft", "bridge").toString()));
    }
    static Path packDirectory() {
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

    /** EVE driven from Minecraft without capture: Minecraft's camera, the pose stream gates and steers the controls. */
    private static int play(FabricClientCommandSource source) {
        var link = StellarBridge.renderingContext();
        if (link == null) return error(source, "Run /stellar start, then F5 and F8 in Stellar Blade, before /stellaravatar play.");
        if (CaptureView.viewing()) return error(source, "Close the captured view first with /stellarview off.");
        if (live == null && live(source) == 0) return 0;
        try {
            ControlBridge.startAvatar(bridgeDirectory(), live.stream.run, link.session());
            live.controlled = true;
        } catch (IOException failure) {
            return error(source, "EVE avatar control failed: " + failure.getMessage());
        }
        var options = source.getClient().options;
        if (options.getPerspective().isFirstPerson()) options.setPerspective(net.minecraft.client.option.Perspective.THIRD_PERSON_BACK);
        source.sendFeedback(Text.literal("EVE avatar control requested. Release held controls; forwarding starts once her pose is fresh and Stellar Blade"
                + " grants input. Minecraft owns the camera; Stellar Blade's camera follows its yaw and, through its camera dolly, its pitch."
                + " B switches EVE/build mode."));
        return 1;
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
            live.stream.keepAlive(AvatarStream.KEEP_ALIVE_LIMIT_NANOS);
            // The pack against the game files it was built from (mods included): a warning only, EVE is drawn either way.
            String stale = Wardrobe.stalePack(live.pack);
            if (stale != null) {
                LoggerFactory.getLogger("stellar_bridge").warn("{}", stale);
                source.sendFeedback(Text.literal(stale));
            }
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

    /** Once per Minecraft frame before the camera: newest pose, then the displayed pose shared by camera and drawing. */
    public static void beginFrame() {
        Live current = live;
        if (current == null) return;
        if (MinecraftClient.getInstance().world != current.world) { stopLive("the world changed"); return; }
        long nanos = System.nanoTime();
        try {
            AvatarStream.Sample sample = current.stream.poll(current.table, System.currentTimeMillis(), nanos);
            if (sample != null && (current.table == null || sample.generation() != current.table.generation())) {
                AvatarStream.Table wanted = current.rebuild.wanted();
                if (wanted == null || wanted.generation() != sample.generation()) {
                    AvatarStream.Table table = current.stream.table();
                    if (table != null && table.generation() == sample.generation()) want(current, table);
                }
            } else if (sample != null && current.model != null) {
                int slot = (current.newest + 1) % Live.RING;
                if (AvatarStream.decode(current.table, sample, current.model, current.ring[slot], current.spare, current.cloth)) {
                    int clothed = current.table.cloth();
                    if (clothed >= 0 && current.ring[slot].cloth()[clothed] != null) current.clothPoses++;
                    AvatarStream.steady(current.drawn, current.spare, current.shown);
                    boolean[] newest = current.spare;
                    current.spare = current.shown;
                    current.shown = newest;
                    double time = sample.publication();
                    if (current.count > 0) {
                        double previous = current.times[current.newest];
                        if (time <= previous) time = previous + 0.5; // Same-millisecond publications stay ordered.
                        if (time - previous < 100) current.interval = current.interval * 0.9 + (time - previous) * 0.1;
                    }
                    current.times[slot] = time;
                    current.newest = slot;
                    current.count = Math.min(current.count + 1, Live.RING);
                    current.rotation = sample.rotation();
                    current.effects = AvatarStream.effects(current.table, sample);
                    if (!current.effects.isEmpty()) current.effectSamples++;
                    var parameters = AvatarStream.parameters(current.table, sample);
                    current.renderer.parameters(parameters);
                    for (var entry : parameters.entrySet())
                        if (entry.getKey().endsWith("/Opacity")) current.glow = Math.max(current.glow, entry.getValue()[0]);
                    current.lookApplied = sample.lookApplied();
                    if (sample.inputGranted() || !current.controlled || current.rotation == null) current.grantedNanos = nanos;
                    BridgeServer.halted = nanos - current.grantedNanos > HALT_NANOS;
                    var player = MinecraftClient.getInstance().player;
                    if (current.controlled && current.rotation != null && player != null) {
                        double gap = Math.abs(Math.IEEEremainder(player.getYaw() + 90 - current.rotation[1], 360));
                        current.gapSamples++;
                        current.gapSum += gap;
                        current.gapMax = Math.max(current.gapMax, gap);
                        if (current.lookApplied) current.turned++;
                        if (!Float.isNaN(sample.cameraPitch())) {
                            double pitchGap = Math.abs(-player.getPitch() - sample.cameraPitch());
                            current.synced++;
                            current.pitchSum += pitchGap;
                            current.pitchMax = Math.max(current.pitchMax, pitchGap);
                        } else current.refusals[sample.dollyRefusal()]++;
                    }
                    current.posed = true;
                    current.poses++;
                    current.windowPoses++;
                    current.lastPoseNanos = nanos;
                } else current.incomplete++;
            }
            rebuild(current);
            if (nanos - current.windowNanos >= 2_000_000_000L) {
                current.rate = current.windowPoses * 1e9 / (nanos - current.windowNanos);
                current.windowPoses = 0;
                current.windowNanos = nanos;
            }
            if (!current.posed) return;
            AvatarCombat.watch(current.stream.gauges(), nanos);
            interpolate(current.ring, current.times, current.newest, current.count, wallMillis() - current.interval, current.pose);
            var link = StellarBridge.renderingContext();
            current.anchored = current.table.anchor() != null && link != null && current.table.session().equals(link.session());
            // Her stats while she is the bridged player; a frame the adapter could not read them keeps the last ones.
            float[] stats = current.stream.stats();
            if (stats != null) current.stats = stats;
            AvatarStats.publish(current.anchored ? current.stats : null);
            if (current.controlled) {
                var player = MinecraftClient.getInstance().player;
                // A locked target turns Minecraft's camera first; Stellar Blade's follows it. Without the link no EVE aims.
                if (current.anchored) AvatarLock.frame(nanos); else AvatarLock.release();
                // Minecraft yaw + 90 and negated pitch are Unreal's; the adapter turns toward them while input is forwarded.
                // Minecraft's yaw keeps counting turns; the adapter takes one turn. Its health and gains words go first, so
                // the camera's fresh time covers them.
                if (player != null) {
                    AvatarStats.reportHeals(current.stream.healed()); // An unanchored frame keeps the count: heals wait.
                    var health = current.anchored ? AvatarStats.mirror() : AvatarStats.Mirror.OFF;
                    int[] earned = AvatarCombat.earned();
                    current.stream.feedback(health.share(), health.acknowledged(), earned[0], earned[1]);
                    current.stream.look(-player.getPitch(), (float) Math.IEEEremainder(player.getYaw() + 90.0, 360), System.currentTimeMillis());
                }
                ControlBridge.avatarFrame();
            }
        } catch (IOException | RuntimeException failure) {
            stopLive(failure.getMessage());
            if (failure instanceof RuntimeException) LoggerFactory.getLogger("stellar_bridge").error("EVE live avatar stopped", failure);
        }
    }

    private static void renderLive(WorldRenderContext context) {
        Live current = live;
        if (current.model == null || !current.posed) return;
        if (context.world() != current.world) { stopLive("the world changed"); return; }
        try {
            Vec3d camera = context.camera().getPos();
            var link = StellarBridge.renderingContext();
            Matrix4d placement;
            if (current.anchored && link != null) {
                placement = anchored(current, link, camera);
                // In first person the camera is inside EVE's head, as Minecraft hides its own player there; her shots leave her eyes.
                if (MinecraftClient.getInstance().options.getPerspective().isFirstPerson()) {
                    current.gun.update(current.stream.shots(), true, () -> null);
                    return;
                }
            } else {
                float[] root = current.pose.world()[current.model.parts.get(0).component()];
                if (current.relativeOrigin == null) current.relativeOrigin = new Vector3d(root[4], root[5], root[6]);
                Vec3d at = current.relativeAnchor;
                placement = new Matrix4d().translation(at.x - camera.x, at.y - camera.y, at.z - camera.z)
                        .mul(AvatarModel.UNREAL_TO_MINECRAFT).translate(-current.relativeOrigin.x, -current.relativeOrigin.y, -current.relativeOrigin.z);
            }
            current.model.palettes(current.pose, placement, current.renderer.palettes, true);
            // The game's effects at absolute positions; Minecraft particles stand in when the pack has no effect sprites.
            Map<String, Vector3d> effects = null;
            if (current.table.effectRecords()) {
                effects = new HashMap<>();
                for (AvatarStream.Effect effect : current.effects)
                    effects.put(effect.name(), placement.transformPosition(new Vector3d(effect.x(), effect.y(), effect.z())).add(camera.x, camera.y, camera.z));
            }
            long nanos = System.nanoTime();
            if (!current.renderer.effects(effects, new Vector3d(camera.x, camera.y, camera.z), context.camera().getRotation(), nanos / 1e9))
                current.particles.spawn(current.effects, placement, camera, nanos);
            // At the bridged position EVE is the player: she flashes red when hurt, her blade and her drone's shots strike entities.
            var player = MinecraftClient.getInstance().player;
            boolean bridged = current.anchored && link != null && player != null;
            current.renderer.hurt(bridged && player.hurtTime > 0);
            draw(context, current.model, current.renderer, current.pose, placement, camera);
            if (bridged) current.blade.update(current.renderer, current.effects, nanos / 1e9);
            current.gun.update(current.stream.shots(), bridged,
                    () -> AvatarWeapons.muzzle(current.model, current.table.components(), current.drawn, current.pose, placement, camera));
        } catch (RuntimeException failure) {
            stopLive(failure.getMessage());
            LoggerFactory.getLogger("stellar_bridge").error("EVE live avatar stopped", failure);
        }
    }

    /**
     * The studio camera of AvatarRenderCheck and the wardrobe's preview: EVE's feet (root) at the origin, the eye distance
     * blocks in front of her body (its +X) turned by degrees around her and height + 0.1 up, looking at height. Fills
     * eye; returns the placement of her pose relative to the eye.
     */
    static Matrix4d studio(float[] root, double distance, double height, double degrees, Vector3d eye) {
        Matrix4d body = AvatarMesh.transform(root, 0, new Matrix4d());
        Vector3d forward = AvatarModel.UNREAL_TO_MINECRAFT.transformDirection(body.transformDirection(new Vector3d(1, 0, 0))).setComponent(1, 0).normalize();
        eye.set(forward.rotateY(Math.toRadians(degrees))).mul(distance).add(0, height + 0.1, 0);
        return new Matrix4d().translation(-eye.x, -eye.y, -eye.z).mul(AvatarModel.UNREAL_TO_MINECRAFT).translate(-root[4], -root[5], -root[6]);
    }

    private static final float[] PREVIEW_FOG = {0, 0, 0, 1}, PREVIEW_SKY = {0.81f, 0.87f, 1}; // Stellar Blade's studio light, CEDDFF.

    /**
     * EVE's live pose drawn into a GUI rectangle with the studio camera (40 degrees), over what the GUI drew so far, lit as
     * at full block light with no sun; false without a posed live avatar. Her trail and effect sprites stay in the world
     * pass. Under /stellaravatar shading entity she goes through Minecraft's entity programs: the fallback should a shader
     * pack hide her own programs' output in the GUI.
     */
    static boolean drawPreview(DrawContext context, int x0, int y0, int x1, int y1, double distance, double height, double degrees) {
        Live current = live;
        if (current == null || current.model == null || !current.posed || x1 <= x0 || y1 <= y0) return false;
        Vector3d eye = new Vector3d();
        Matrix4d placement = studio(current.pose.world()[current.model.parts.get(0).component()], distance, height, degrees, eye);
        // The world pass computes its own palettes again before it draws: sharing them is safe.
        current.model.palettes(current.pose, placement, current.renderer.palettes, true);
        var client = MinecraftClient.getInstance();
        var window = client.getWindow();
        double scale = window.getScaleFactor();
        int px = (int) (x0 * scale), py = window.getFramebufferHeight() - (int) (y1 * scale), pw = (int) ((x1 - x0) * scale), ph = (int) ((y1 - y0) * scale);
        Matrix4f view = new Matrix4f().lookAt(0, 0, 0, (float) -eye.x, (float) (height - eye.y), (float) -eye.z, 0, 1, 0);
        Matrix4f projection = new Matrix4f().perspective((float) Math.toRadians(40), (float) pw / ph, 0.05f, 100);
        context.draw(); // What the GUI drew so far lands beneath her.
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        context.enableScissor(x0, y0, x1, y1);
        try {
            RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC); // Scissored: her rectangle only.
            GlStateManager._viewport(px, py, pw, ph);
            current.renderer.hurt(false); // The world pass sets it again each frame.
            int lightmap = lightmap(client.gameRenderer.getLightmapTextureManager());
            if (ShaderPacks.forced) {
                DiffuseLighting.enableForLevel(); // Her normals are world-aligned, as in the world pass.
                current.renderer.drawEntity(view, projection, lightmap, overlay(), 15, 15, GameRenderer.getRenderTypeEntityCutoutNoNullProgram(),
                        GameRenderer.getRenderTypeEntityTranslucentProgram(), null, 2); // ponytail: no glow pass; the sword's edge stays dark.
                DiffuseLighting.enableGuiDepthLighting();
            } else current.renderer.draw(new Matrix4f(projection).mul(view), lightmap, 15, 15, 1000, 2000, PREVIEW_FOG, PREVIEW_SKY,
                    AvatarShadows.NONE, false);
        } finally {
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            context.disableScissor();
        }
        return true;
    }

    /** The terrain link's mapping, camera-relative: Minecraft origin + (X, Z, Y) / scale relative to the lab anchor. */
    private static Matrix4d anchored(Live current, StellarBridge.RenderingContext link, Vec3d camera) {
        double[] anchor = current.table.anchor();
        var origin = link.origin();
        return new Matrix4d().translation(origin.x() - camera.x, origin.y() - camera.y, origin.z() - camera.z)
                .mul(AvatarModel.unrealToMinecraft(anchor[3])).translate(-anchor[0], -anchor[1], -anchor[2]);
    }

    /** Whether EVE replaces the player in the shader pack's shadow pass now running. */
    public static boolean castsShadow() { return ShaderPacks.shadowPass(); }

    /** In Iris's shadow pass, pose being its shadow view: EVE's opaque and cut-out sections into the pack's shadow map. */
    public static void drawShadow(Matrix4f pose) {
        Live current = live;
        var link = StellarBridge.renderingContext();
        if (current == null || current.model == null || !current.posed || link == null) return;
        try {
            var client = MinecraftClient.getInstance();
            current.model.palettes(current.pose, anchored(current, link, client.gameRenderer.getCamera().getPos()),
                    current.renderer.palettes, true);
            // ponytail: palettes and skinning run again in the main pass; computing them once at WorldRenderEvents.START,
            // which precedes Iris's shadow pass, would save that if it ever shows in profiles.
            current.renderer.drawShadow(new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose), RenderSystem.getProjectionMatrix(),
                    lightmap(client.gameRenderer.getLightmapTextureManager()), overlay(), GameRenderer.getRenderTypeEntityCutoutNoNullProgram());
        } catch (RuntimeException failure) {
            stopLive(failure.getMessage());
            LoggerFactory.getLogger("stellar_bridge").error("EVE live avatar stopped", failure);
        }
    }

    /** A new component table: taken as is when only the placement changed, else built off the render thread. */
    private static void want(Live current, AvatarStream.Table table) {
        if (current.uploading != null) { current.uploading.result().free(); current.uploading = null; } // Superseded.
        current.wantedNanos = System.nanoTime();
        if (current.table != null && sameComposition(current.table.components(), table.components())) {
            current.rebuild.want(null);
            current.table = table;
        } else current.rebuild.want(table);
    }

    /** Each frame: takes a finished build, uploads its textures for at most UPLOAD_NANOS, then installs it. */
    private static void rebuild(Live current) throws IOException {
        if (current.uploading == null) {
            AvatarRebuild.Built<Prepared> built = current.rebuild.take();
            if (built == null) return;
            if (built.failure() != null)
                throw built.failure() instanceof IOException failure ? failure : new IOException(built.failure().getMessage(), built.failure());
            current.uploading = built;
        }
        if (!current.textures.upload(current.uploading.result().textures(), UPLOAD_NANOS)) return;
        AvatarRebuild.Built<Prepared> built = current.uploading;
        current.uploading = null;
        install(current, built.table(), built.result().model());
    }

    /** A built model with its table: its renderer (textures uploaded already) replaces the previous one. */
    private static void install(Live current, AvatarStream.Table table, AvatarModel model) throws IOException {
        AvatarRenderer renderer = new AvatarRenderer(model, current.pack, current.textures);
        if (current.renderer != null) current.renderer.close();
        current.textures.trim(renderer.textureKeys());
        current.table = table;
        current.model = model;
        current.renderer = renderer;
        current.pose = AvatarStream.emptyPose(table);
        current.ring = new AvatarRecording.Pose[Live.RING];
        for (int i = 0; i < Live.RING; i++) current.ring[i] = AvatarStream.emptyPose(table);
        current.newest = -1;
        current.count = 0;
        current.posed = false;
        current.cloth.rebuild(table.generation());
        int n = table.components().size();
        current.shown = new boolean[n];
        current.spare = new boolean[n];
        current.drawn = new boolean[n];
        renderer.shown(current.drawn);
        int[] sections = renderer.sectionCounts();
        report(String.format("EVE live avatar: generation %d, %d meshes, %d/%d material sections, ready %.0f ms after its table (%d MB of textures kept)%s.",
                table.generation(), model.parts.size(), sections[0], sections[1], (System.nanoTime() - current.wantedNanos) / 1e6,
                current.textures.bytes() >> 20, model.skipped.isEmpty() ? "" : "; skipped " + String.join(", ", model.skipped)), null);
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

    private static int shading(FabricClientCommandSource source, boolean entity) {
        ShaderPacks.forced = entity;
        source.sendFeedback(Text.literal(entity ? "EVE is drawn through Minecraft's entity programs, as under a shader pack."
                : "EVE uses her own shaders unless a shader pack is in use."));
        return 1;
    }

    private static void draw(WorldRenderContext context, AvatarModel model, AvatarRenderer renderer, AvatarRecording.Pose pose,
                             Matrix4d placement, Vec3d camera) {
        float[] root = pose.world()[model.parts.get(0).component()];
        Vector3d feet = placement.transformPosition(new Vector3d(root[4], root[5], root[6])).add(camera.x, camera.y, camera.z);
        int light = WorldRenderer.getLightmapCoordinates(context.world(), BlockPos.ofFloored(feet.x, feet.y + 1, feet.z));
        Matrix4f projectionView = new Matrix4f(context.projectionMatrix()).mul(context.positionMatrix());
        renderer.sampleTrail(pose, placement, camera.x, camera.y, camera.z, System.nanoTime() / 1e9);
        if (ShaderPacks.active()) {
            // A shader pack resolves the frame from its own buffers: only Minecraft's programs, which it replaces, reach them.
            renderer.drawEntity(context.positionMatrix(), context.projectionMatrix(), lightmap(context.lightmapTextureManager()), overlay(),
                    LightmapTextureManager.getBlockLightCoordinates(light), LightmapTextureManager.getSkyLightCoordinates(light),
                    GameRenderer.getRenderTypeEntityCutoutNoNullProgram(), GameRenderer.getRenderTypeEntityTranslucentProgram(),
                    GameRenderer.getRenderTypeEyesProgram());
            return;
        }
        float tickDelta = context.tickCounter().getTickDelta(false);
        Vec3d sky = context.world().getSkyColor(context.camera().getPos(), tickDelta);
        // The sun lights EVE and casts her shadow, fitted to her bones, onto the faces around her before she is drawn.
        AvatarShadows.Light key = eased(AvatarShadows.sky(context.world(), tickDelta, LightmapTextureManager.getSkyLightCoordinates(light)));
        boolean cast = MinecraftClient.getInstance().options.getEntityShadows().getValue();
        if (key.strength() > 0 || cast) {
            Vector3f toward = AvatarShadows.toward(key);
            renderer.bounds(pose, placement, LOW, HIGH);
            renderer.castShadow(SHADOW, AvatarShadows.fit(LOW, HIGH, toward, SHADOW));
            if (cast) {
                int vertices = AvatarShadows.faces(AvatarShadows.cells(context.world()), LOW, HIGH, toward, SHADOW, camera.x, camera.y, camera.z, FACES);
                renderer.drawCast(projectionView, FACES.flip(), vertices, toward, RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd());
            }
        }
        renderer.draw(projectionView, lightmap(context.lightmapTextureManager()),
                LightmapTextureManager.getBlockLightCoordinates(light), LightmapTextureManager.getSkyLightCoordinates(light),
                RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(), RenderSystem.getShaderFogColor(),
                new float[] {(float) sky.x, (float) sky.y, (float) sky.z}, key);
    }

    private static final Vector3d LOW = new Vector3d(), HIGH = new Vector3d();
    private static final Matrix4f SHADOW = new Matrix4f();
    private static final FloatBuffer FACES = BufferUtils.createFloatBuffer(7 * 6 * 2048);
    private static float keyStrength;
    private static long keyNanos;
    /** The sun with its strength eased over a quarter second: sky light steps by whole levels under trees and roofs. */
    private static AvatarShadows.Light eased(AvatarShadows.Light target) {
        long nanos = System.nanoTime();
        float ease = keyNanos == 0 ? 1 : (float) Math.min(1, (nanos - keyNanos) / 0.25e9);
        keyNanos = nanos;
        keyStrength += (target.strength() - keyStrength) * ease;
        return new AvatarShadows.Light(target.direction(), keyStrength);
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

    /** The hurt overlay's GL texture, as Minecraft gives it to entity programs. */
    private static int overlay() {
        int previous = RenderSystem.getShaderTexture(1);
        MinecraftClient.getInstance().gameRenderer.getOverlayTexture().setupOverlayColor();
        int id = RenderSystem.getShaderTexture(1);
        RenderSystem.setShaderTexture(1, previous);
        return id;
    }

    /**
     * The pose at a publication time: interpolated between the bracketing samples, the oldest before them, and at most
     * half an interval extrapolated past the newest.
     */
    static void interpolate(AvatarRecording.Pose[] ring, double[] times, int newest, int count, double at, AvatarRecording.Pose out) {
        if (count == 1) { copy(ring[newest], out); return; }
        int size = ring.length, b = newest, a = (newest + size - 1) % size;
        for (int k = 2; k < count && at < times[a]; k++) { b = a; a = (a + size - 1) % size; }
        double span = times[b] - times[a];
        blend(ring[a], ring[b], (float) Math.max(0, Math.min(1.5, (at - times[a]) / span)), out);
    }
    private static double wallMillis() {
        var now = java.time.Instant.now();
        return now.getEpochSecond() * 1000.0 + now.getNano() / 1e6;
    }
    private static void copy(AvatarRecording.Pose source, AvatarRecording.Pose target) {
        for (int i = 0; i < source.world().length; i++) {
            System.arraycopy(source.world()[i], 0, target.world()[i], 0, source.world()[i].length);
            if (source.local()[i] != null) System.arraycopy(source.local()[i], 0, target.local()[i], 0, source.local()[i].length);
            if (source.weights()[i] != null) System.arraycopy(source.weights()[i], 0, target.weights()[i], 0, source.weights()[i].length);
            float[] cloth = source.cloth()[i];
            target.cloth()[i] = cloth == null ? null : lerp(cloth, cloth, 0, target.cloth()[i]);
        }
    }
    /**
     * Blends two poses; cloth positions interpolate linearly where both ends carry cloth, else the part takes bones for the
     * interval (as a part shown again waits for steady). Arrays are reused, new only when the cloth comes back.
     */
    static void blend(AvatarRecording.Pose a, AvatarRecording.Pose b, float alpha, AvatarRecording.Pose out) {
        for (int i = 0; i < a.world().length; i++) {
            AvatarRecording.blend(a.world()[i], b.world()[i], alpha, out.world()[i]);
            if (a.local()[i] != null) AvatarRecording.blend(a.local()[i], b.local()[i], alpha, out.local()[i]);
            if (a.weights()[i] != null)
                for (int k = 0; k < a.weights()[i].length; k++) out.weights()[i][k] = a.weights()[i][k] + (b.weights()[i][k] - a.weights()[i][k]) * alpha;
            float[] from = a.cloth()[i], to = b.cloth()[i];
            out.cloth()[i] = from == null || to == null || from.length != to.length ? null : lerp(from, to, alpha, out.cloth()[i]);
        }
    }
    private static float[] lerp(float[] a, float[] b, float alpha, float[] reuse) {
        float[] out = reuse != null && reuse.length == a.length ? reuse : new float[a.length];
        for (int k = 0; k < a.length; k++) out[k] = a[k] + (b[k] - a[k]) * alpha;
        return out;
    }

    /** Minecraft-world feet position of the displayed EVE when she stands at the bridged position, else null. */
    public static Vec3d cameraFeet() {
        Live current = live;
        var link = StellarBridge.renderingContext();
        if (current == null || !current.posed || current.table == null || current.table.anchor() == null || link == null
                || !current.table.session().equals(link.session())) return null;
        float[] root = current.pose.world()[current.model.parts.get(0).component()];
        double[] anchor = current.table.anchor();
        var origin = link.origin();
        // Unreal (X, Y, Z) to Minecraft (X, Z, Y) relative to the lab anchor, as the terrain link maps EVE's feet.
        return new Vec3d(origin.x() + (root[4] - anchor[0]) / anchor[3], origin.y() + (root[6] - anchor[2]) / anchor[3],
                origin.z() + (root[5] - anchor[1]) / anchor[3]);
    }

    /** Controls may follow Minecraft only while this run's anchored pose is fresh. */
    static boolean controlReady(String run, String session, long nanos) {
        Live current = live;
        return current != null && current.controlled && current.stream.run.equals(run) && current.anchored && current.posed
                && current.table.session().equals(session) && nanos - current.lastPoseNanos <= 500_000_000L;
    }
    /** Whether the adapter turns Stellar Blade's camera toward Minecraft's through look input. */
    static boolean lookApplied() {
        Live current = live;
        return current != null && current.lookApplied;
    }
    /** Stellar Blade's controller Pitch, Yaw, Roll (Unreal degrees) from the newest pose, or null. */
    static float[] controllerRotation() {
        Live current = live;
        return current == null ? null : current.rotation;
    }

    /** Draws Stellar Blade's HUD in place of the hearts while EVE replaces the player; false otherwise. Vanilla's x, y and lines go unused. */
    public static boolean drawsHud(net.minecraft.client.gui.DrawContext context, Entity player, int x, int y, int lines, float maxHealth,
                                   int health, int shown, int absorption, boolean blinking) {
        Live current = live;
        if (!replaces(player)) return false;
        AvatarHud.render(context, maxHealth, health, shown, absorption, blinking, current.stream.gauges(), current.stream.actorStats());
        return true;
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
        return String.format("EVE live avatar: %s; generation %s; %d poses (%.1f/s, %.1f ms apart), %d incomplete; last %s; native read %d us last, %d max, %d mean over %d published.",
                current.table == null ? "waiting for Lua's component table" : current.anchored ? "anchored to the bridge link" : "two blocks ahead (no entered link)",
                current.table == null ? "-" : Long.toString(current.table.generation()), current.poses, current.rate, current.interval, current.incomplete,
                current.posed ? String.format("%.0f ms ago", (System.nanoTime() - current.lastPoseNanos) / 1e6) : "never",
                native_[1], native_[2], native_[3], native_[0])
                + (current.gapSamples == 0 ? "" : String.format(" Camera: turned by look input in %.0f%% of controlled poses; yaw gap %.1f deg mean, %.0f max;"
                        + " dolly set from Minecraft's pitch in %.0f%%, pitch gap %.1f deg mean, %.0f max.",
                        100.0 * current.turned / current.gapSamples, current.gapSum / current.gapSamples, current.gapMax,
                        100.0 * current.synced / current.gapSamples, current.synced == 0 ? 0 : current.pitchSum / current.synced, current.pitchMax)
                        + refusalStatus(current))
                + (current.table == null || current.table.parameters().isEmpty() ? " No streamed material parameters."
                        : String.format(" %d streamed material parameters; highest glow opacity %.2f.", current.table.parameters().size(), current.glow))
                + (current.table == null || !current.table.effectRecords() ? " Effects not streamed."
                        : String.format(" %d known effects; effects active in %d poses.", current.table.effects().size(), current.effectSamples))
                + clothStatus(current) + AvatarHudFile.status() + AvatarHud.status()
                + hiddenStatus(current) + AvatarCombat.status() + current.gun.status(current.stream.shots())
                + evadeStatus(current.stream.evades()) + gaugeStatus(current.stream.gauges())
                + AvatarStats.status(current.stats) + reportStatus(current.stream.report())
                + (ShaderPacks.active() ? " Drawn through Minecraft's entity programs." : " Drawn with her own shaders.");
    }

    /**
     * How many poses drew Stellar Blade's ponytail cloth, its stale samples (particles unchanged while her head moved), then
     * why the pose took bones instead, the cloth's update cadence, its stale runs and its jumps.
     */
    private static String clothStatus(Live current) {
        if (current.table == null || current.table.cloth() < 0) return " Cloth: none in the component table.";
        float[] shown = current.pose == null ? null : current.pose.cloth()[current.table.cloth()];
        return String.format(" Cloth: %d of %d poses used Stellar Blade's ponytail cloth%s; %d stale samples (particles unchanged while her"
                + " head moved).", current.clothPoses, current.poses, shown == null ? ", bones now" : " (" + shown.length / 3 + " particles)",
                current.cloth.stale) + current.cloth.status();
    }

    private static String reportStatus(long[] report) {
        return String.format(" Gauge gains: the adapter granted Beta %d, Burst %d. Heals: %d Stellar Blade HP converted; mirror %s;"
                + " %d Stellar Blade losses overwritten.", report[3], report[4], report[0], new String[] {"off", "on", "held"}[(int) report[1]], report[2]);
    }

    /** The streamed meshes left out of the newest pose: hidden in Stellar Blade (EVE's drone between appearances) or without data. */
    private static String hiddenStatus(Live current) {
        if (current.model == null || !current.posed) return "";
        StringBuilder names = new StringBuilder();
        for (var part : current.model.parts)
            if (!current.drawn[part.component()]) names.append(names.isEmpty() ? "" : ", ").append(current.table.components().get(part.component()).name());
        return names.isEmpty() ? " Every streamed mesh drawn." : " Not drawn now: " + names + ".";
    }

    /** The commonest reasons the adapter left the camera's pitch to pitch input, by share of controlled poses. */
    private static String refusalStatus(Live current) {
        var reasons = java.util.stream.IntStream.range(1, current.refusals.length).filter(i -> current.refusals[i] > 0).boxed()
                .sorted((a, b) -> Long.compare(current.refusals[b], current.refusals[a])).limit(3)
                .map(i -> String.format("%s %.0f%%", AvatarStream.DOLLY_REFUSALS[i - 1], 100.0 * current.refusals[i] / current.gapSamples)).toList();
        return reasons.isEmpty() ? "" : " Pitch input instead: " + String.join(", ", reasons) + ".";
    }

    static String evadeStatus(int[] evades) {
        return evades == null ? "" : String.format(" Evades: unlocked %d first, %d double, %d cut; locked %d first, %d double, %d cut;"
                + " %d evade commands, %d lock evade rows changed by the lock.", evades[0], evades[1], evades[2], evades[3], evades[4],
                evades[5], evades[6], evades[7]);
    }

    private static String gaugeStatus(float[] gauges) {
        return gauges == null ? " Gauges not read." : String.format(" Gauges: Beta %.0f/%.0f, Burst %.0f/%.0f%s.", gauges[0], gauges[1], gauges[2],
                gauges[3], gauges[4] > 0 ? "" : " (locked)");
    }

    /** Stops the live stream; a null reason is the user's own off. */
    private static void stopLive(String reason) {
        Live current = live;
        if (current == null) return;
        live = null;
        BridgeServer.halted = false;
        AvatarStats.publish(null);
        AvatarStats.reportHeals(-1);
        AvatarLock.release();
        if (current.controlled) {
            current.stream.lookOff();
            ControlBridge.stop();
        }
        String summary = liveStatus(current);
        current.rebuild.close();
        if (current.uploading != null) current.uploading.result().free();
        if (current.renderer != null) current.renderer.close();
        current.textures.close();
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
