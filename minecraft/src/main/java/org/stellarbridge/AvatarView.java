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
    // Sent to the server with the player's status (StellarBridge.halted), read by its damage hook.
    static final long HALT_NANOS = 400_000_000L; // Longer than Lua's 200 ms permit renewal.

    private record Replay(ClientWorld world, AvatarRecording recording, AvatarModel model, AvatarRenderer renderer,
                          AvatarRecording.Pose pose, Vec3d anchor, Vector3d origin, long started) { }

    // Rebuilds upload their decoded textures for up to UPLOAD_NANOS a frame in all; meshes stay cached up to MESH_BUDGET bytes.
    // ponytail: a frame uploads at least one whole texture (up to about 25 ms for a 2048² one through the driver's copy) and the
    // install frame creates the renderer's mesh buffers (about 20 ms for a full outfit); banded uploads or mesh buffers cached
    // like the textures if those frames show.
    static final long UPLOAD_NANOS = 4_000_000L, MESH_BUDGET = 256L << 20;

    /**
     * A model built off the render thread, its decoded textures still to upload (uploads take them out) and what the
     * build changed from the table (another player's EVE: substituted or left-out meshes), or "".
     */
    record Prepared(AvatarModel model, Map<String, AvatarTextures.Decoded> textures, String note) {
        void free() { textures.values().forEach(AvatarTextures.Decoded::free); }
    }

    // Shared by every EVE drawn: pack meshes (the build worker's only, which every rebuild shares) and GL textures (render
    // thread), so several EVEs in one outfit read and upload it once.
    private static final Map<String, AvatarMesh> MESHES = new java.util.LinkedHashMap<>(16, 0.75f, true);
    static final AvatarTextures TEXTURES = new AvatarTextures();

    /**
     * One EVE drawn: this player's from the local stream, or another player's from the network (RemoteEves). Its model
     * and renderer follow the newest component table. A table needing new meshes is built off the render thread (meshes
     * read, textures decoded), its textures uploaded over the next frames, then the model, its renderer and that table
     * are installed together; until then EVE keeps the previous model, table and pose. Render thread.
     */
    static final class Eve implements AutoCloseable {
        final Path pack;
        // Another player's: lenient model, half-resolution textures it alone uses, the HairB filter, no chat lines.
        final boolean remote;
        final double intervalCap; // Milliseconds: longer gaps between publications leave the interval average alone.
        final AvatarRebuild<Prepared> rebuild;
        AvatarRebuild.Built<Prepared> uploading; // The wanted table's build while its textures upload.
        long wantedNanos; // When the wanted table arrived.
        AvatarStream.Table table;
        AvatarModel model;
        AvatarRenderer renderer;
        AvatarModel.HairFilter hair; // Remote ponytails, drawn from bones; null without a tail chain.
        String note = "";
        Vec3d origin, wantedOrigin; // Another player's: where the installed and the wanted table's anchor stands.
        // Recent poses with the sender's publication times. The displayed pose is interpolated in the past on those
        // timestamps (one interval for this player's EVE, RemoteEves.displayTime for others), so Minecraft's frame timing,
        // polling and network jitter add none.
        AvatarRecording.Pose pose;
        AvatarRecording.Pose[] ring;
        // Per component: shown in the newest sample, a spare for the next decode, and drawn (shown in the two newest
        // samples, which the displayed pose interpolates between: a component back from Stellar Blade's hiding or
        // from missing data never blends with stale floats).
        boolean[] shown, spare, drawn;
        final double[] times;
        int newest = -1, count;
        float glow; // Highest streamed material Opacity so far (the sword edge's glow while the game raises it).
        List<AvatarStream.Effect> effects = List.of(); // The game's active effects on EVE in the newest sample.
        final AvatarEffects particles = new AvatarEffects();
        final AvatarStream.ClothWatch cloth = new AvatarStream.ClothWatch(); // Whether Stellar Blade's cloth moves, for the whole run.
        long clothPoses; // Poses drawing Stellar Blade's cloth.
        long effectSamples; // Samples with at least one active effect.
        long poses, incomplete, lastPoseNanos, extrapolated; // extrapolated: frames drawn past the newest pose.
        boolean posed;
        double interval = 1000.0 / 60; // Milliseconds between publications.

        /** builder makes the Prepared of a table on the shared build worker. */
        Eve(Path pack, boolean remote, int ring, double intervalCap, AvatarRebuild.Builder<Prepared> builder) {
            this.pack = pack;
            this.remote = remote;
            this.intervalCap = intervalCap;
            times = new double[ring];
            rebuild = new AvatarRebuild<>(builder, Prepared::free);
        }

        /** On the build worker: model's meshes kept in the shared cache and the textures its renderer still needs. */
        static Prepared prepare(Path pack, AvatarModel model, boolean half, String note) throws IOException {
            AvatarModel.trim(MESHES, model, MESH_BUDGET);
            if (model.parts.isEmpty()) throw new IOException("No streamed EVE mesh is in the avatar pack " + pack);
            return new Prepared(model, TEXTURES.decode(pack, AvatarRenderer.textures(model, pack, half)), note);
        }

        /**
         * A new component table and, for another player's EVE, the Minecraft position of its anchor: taken as is when only
         * the placement changed, else built off the render thread.
         */
        void want(AvatarStream.Table wanted, Vec3d at) {
            // The same generation again (another player's table resent on tracking, a relink): only the placement moved,
            // so the build uploading goes on; AvatarRebuild would not build it twice.
            if (uploading != null && uploading.table().generation() == wanted.generation()) {
                wantedOrigin = at;
                return;
            }
            if (uploading != null) { uploading.result().free(); uploading = null; } // Superseded.
            wantedNanos = System.nanoTime();
            if (table != null && sameComposition(table.components(), wanted.components())) {
                rebuild.want(null);
                table = wanted;
                origin = at;
            } else {
                rebuild.want(wanted);
                wantedOrigin = at;
            }
        }

        /** Each frame: takes a finished build, uploads its textures within the frame's UPLOAD_NANOS, then installs it. */
        void rebuild() throws IOException {
            if (uploading == null) {
                AvatarRebuild.Built<Prepared> built = rebuild.take();
                if (built == null) return;
                if (built.failure() != null)
                    throw built.failure() instanceof IOException failure ? failure : new IOException(built.failure().getMessage(), built.failure());
                uploading = built;
            }
            Map<String, AvatarTextures.Decoded> pending = uploading.result().textures();
            long left = uploadUntil - System.nanoTime(); // Shared by every EVE uploading this frame.
            if (!pending.isEmpty() && (left <= 0 || !TEXTURES.upload(pending, left))) return;
            AvatarRebuild.Built<Prepared> built = uploading;
            uploading = null;
            install(built.table(), built.result());
        }

        /** A built model with its table: its renderer (textures uploaded already) replaces the previous one. */
        private void install(AvatarStream.Table installed, Prepared prepared) throws IOException {
            AvatarModel built = prepared.model();
            AvatarRenderer next = new AvatarRenderer(built, pack, TEXTURES, remote);
            if (renderer != null) renderer.close();
            renderer = next;
            table = installed;
            model = built;
            note = prepared.note();
            origin = wantedOrigin;
            TEXTURES.trim(texturesInUse());
            hair = remote ? AvatarModel.HairFilter.of(built, installed.components()) : null;
            pose = AvatarStream.emptyPose(installed);
            ring = new AvatarRecording.Pose[times.length];
            for (int i = 0; i < ring.length; i++) ring[i] = AvatarStream.emptyPose(installed);
            newest = -1;
            count = 0;
            posed = false;
            cloth.rebuild(installed.generation());
            int n = installed.components().size();
            shown = new boolean[n];
            spare = new boolean[n];
            drawn = new boolean[n];
            next.shown(drawn);
            if (remote) return;
            int[] sections = next.sectionCounts();
            report(String.format("EVE live avatar: generation %d, %d meshes, %d/%d material sections, ready %.0f ms after its table (%d MB of textures kept)%s.",
                    installed.generation(), built.parts.size(), sections[0], sections[1], (System.nanoTime() - wantedNanos) / 1e6,
                    TEXTURES.bytes() >> 20, built.skipped.isEmpty() ? "" : "; skipped " + String.join(", ", built.skipped)), null);
        }

        /**
         * A sample of the installed table decoded into the ring at nanos (false: another generation, no model yet, or
         * incomplete data); its publication time, the interval average, effects and material parameters follow.
         */
        boolean accept(AvatarStream.Sample sample, long nanos) {
            if (model == null || sample.generation() != table.generation()) return false;
            int slot = (newest + 1) % ring.length;
            if (!AvatarStream.decode(table, sample, model, ring[slot], spare, cloth)) { incomplete++; return false; }
            if (hair != null) hair.apply(ring[slot], count > 0 ? sample.publication() - times[newest] : 0);
            int clothed = table.cloth();
            if (clothed >= 0 && ring[slot].cloth()[clothed] != null) clothPoses++;
            AvatarStream.steady(drawn, spare, shown);
            boolean[] newestShown = spare;
            spare = shown;
            shown = newestShown;
            double time = sample.publication();
            if (count > 0) {
                double previous = times[newest];
                if (time <= previous) time = previous + 0.5; // Same-millisecond publications stay ordered.
                if (time - previous < intervalCap) interval = interval * 0.9 + (time - previous) * 0.1;
            }
            times[slot] = time;
            newest = slot;
            count = Math.min(count + 1, ring.length);
            effects = AvatarStream.effects(table, sample);
            if (!effects.isEmpty()) effectSamples++;
            var parameters = AvatarStream.parameters(table, sample);
            renderer.parameters(parameters);
            for (var entry : parameters.entrySet())
                if (entry.getKey().endsWith("/Opacity")) glow = Math.max(glow, entry.getValue()[0]);
            posed = true;
            poses++;
            lastPoseNanos = nanos;
            return true;
        }

        /** The displayed pose at a publication time of the sender's clock. */
        void display(double at) {
            if (at > times[newest]) extrapolated++;
            interpolate(ring, times, newest, count, at, pose);
        }

        /** Whether the newest model draws a body (its first part is the table's first component): else no vanilla body hides. */
        boolean hasBody() { return model != null && !model.parts.isEmpty() && model.parts.get(0).component() == 0; }
        float[] root() { return pose.world()[model.parts.get(0).component()]; }
        /**
         * The texture keys to keep: its renderer's and, while a build uploads, every one its next renderer may take from the
         * cache (the build skipped decoding those the cache held).
         */
        java.util.Set<String> textureKeys() {
            java.util.Set<String> keys = new java.util.HashSet<>(renderer == null ? java.util.Set.of() : renderer.textureKeys());
            if (uploading != null)
                for (AvatarTextures.Texture texture : AvatarRenderer.textures(uploading.result().model(), pack, remote)) {
                    keys.add(texture.key());
                    keys.add(texture.full().key());
                }
            return keys;
        }
        /** Whether a build of another table than the installed one is under way, on the build worker or uploading. */
        boolean building() {
            AvatarStream.Table wanted = rebuild.wanted();
            return uploading != null || wanted != null && (table == null || wanted.generation() != table.generation());
        }

        @Override public void close() {
            rebuild.close();
            if (uploading != null) uploading.result().free();
            uploading = null;
            if (renderer != null) renderer.close();
            renderer = null;
            model = null;
            posed = false;
        }
    }

    /**
     * After an EVE went: every texture no EVE uses goes, unless a build is under way, which may count on the cache holding
     * textures it skipped; then only those beyond the budget go. ponytail: an install's trim over the budget can still drop
     * a texture a build on the worker skipped, which its renderer then decodes on the render thread; pin the skipped keys
     * in the build's result if that hitch shows.
     */
    static void releaseTextures() {
        boolean building = live != null && live.eve.building();
        for (RemoteEves.Remote remote : RemoteEves.remotes()) building |= remote.eve != null && remote.eve.building();
        if (building) TEXTURES.trim(texturesInUse());
        else TEXTURES.trim(texturesInUse(), 0);
    }

    /** The texture keys of every EVE drawn, which a trim of the shared textures keeps. */
    static java.util.Set<String> texturesInUse() {
        java.util.Set<String> used = new java.util.HashSet<>();
        if (live != null) used.addAll(live.eve.textureKeys());
        for (RemoteEves.Remote remote : RemoteEves.remotes()) if (remote.eve != null) used.addAll(remote.eve.textureKeys());
        return used;
    }

    /**
     * A live stream drawing this player's EVE; its own: Stellar Blade's controller rotation and input grants, the camera
     * gaps, her stats, blade and drone gun.
     */
    private static final class Live {
        static final int RING = 4;
        final AvatarStream stream;
        final ClientWorld world;
        final Path pack;
        final Vec3d relativeAnchor;
        final Eve eve;
        float[] rotation; // Stellar Blade's controller Pitch, Yaw, Roll from the newest sample, or null.
        boolean lookApplied; // The newest sample says the adapter turned that controller's camera toward Minecraft's.
        long grantedNanos; // When a sample last said Stellar Blade granted this run's input (or control was off).
        long gapSamples, turned, synced; // Controlled poses, those the adapter turned, those whose camera dolly it set.
        float[] stats; // EVE's latest streamed stats, or null.
        final AvatarWeapons.Blade blade = new AvatarWeapons.Blade();
        final AvatarWeapons.Gun gun = new AvatarWeapons.Gun();
        double gapSum, gapMax; // Yaw gap in degrees between Minecraft's camera and the published controller rotation.
        double pitchSum, pitchMax; // Pitch gap in degrees between Minecraft's camera and the camera pitch of synced poses.
        final long[] refusals = new long[AvatarStream.DOLLY_REFUSALS.length + 1]; // Controlled poses by dolly refusal.
        Vector3d relativeOrigin;
        boolean anchored, controlled;
        long windowPoses, windowNanos;
        double rate;
        Live(AvatarStream stream, ClientWorld world, Path pack, Vec3d relativeAnchor) {
            this.stream = stream; this.world = world; this.pack = pack; this.relativeAnchor = relativeAnchor;
            eve = new Eve(pack, false, RING, 100, table -> Eve.prepare(pack, AvatarModel.load(pack, table.components(), MESHES), false, ""));
            windowNanos = System.nanoTime();
        }
    }

    static void initialize() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> {
            var command = ClientCommandManager.literal("stellaravatar");
            // Development tools for the one-PC network test (docs/setup.md), with -Dstellarbridge.netTools=true only.
            if (RemoteEves.TOOLS) command
                    .then(ClientCommandManager.literal("echo").executes(context -> {
                        context.getSource().sendFeedback(Text.literal(RemoteEves.toggleEcho()
                                ? "EVE echo on: your own network poses come back as another EVE, 3 blocks along +X and 1 s late."
                                : "EVE echo off."));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("netrecord").executes(context -> {
                        context.getSource().sendFeedback(Text.literal(RemoteEves.toggleRecording()));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("netplay").executes(context -> netplay(context.getSource(), RemoteEves.recordingFile()))
                            .then(ClientCommandManager.argument("file", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                    .executes(context -> netplay(context.getSource(),
                                            Path.of(com.mojang.brigadier.arguments.StringArgumentType.getString(context, "file"))))));
            dispatcher.register(command
                    .then(ClientCommandManager.literal("replay").executes(context -> replay(context.getSource())))
                    .then(ClientCommandManager.literal("live").executes(context -> live(context.getSource())))
                    .then(ClientCommandManager.literal("play").executes(context -> play(context.getSource())))
                    .then(ClientCommandManager.literal("status").executes(context -> {
                        context.getSource().sendFeedback(Text.literal(status() + RemoteEves.status()));
                        RemoteEves.requestRelayStatus(); // The server's relay counts follow as a chat line.
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("off").executes(context -> {
                        off();
                        context.getSource().sendFeedback(Text.literal("EVE avatar removed."));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("shading")
                            .then(ClientCommandManager.literal("auto").executes(context -> shading(context.getSource(), false)))
                            .then(ClientCommandManager.literal("entity").executes(context -> shading(context.getSource(), true)))));
        });
        WorldRenderEvents.AFTER_ENTITIES.register(AvatarView::render);
        // For a perfect dodge's slow motion (Minecraft's world's while alone on the server, else Stellar Blade's), a cold tint.
        net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback.EVENT.register((context, tick) -> {
            var client = MinecraftClient.getInstance();
            if (client.world != null && StellarBridge.slowMotion() && replaces(client.player))
                context.fill(0, 0, context.getScaledWindowWidth(), context.getScaledWindowHeight(), 0x302050A0);
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(AvatarView::off));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { off(); reap(); });
        WardrobeScreen.initialize();
        RemoteEves.initialize();
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

    private static int netplay(FabricClientCommandSource source, Path file) {
        try {
            source.sendFeedback(Text.literal(RemoteEves.togglePlayback(file)));
            return 1;
        } catch (IOException | RuntimeException failure) {
            return error(source, "EVE network playback failed: " + failure.getMessage());
        }
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
        renderRemotes(context);
    }

    /**
     * Other players' EVEs once per frame, before the camera: the echo's and a playback's due payloads, each remote's
     * build (a failure leaves that player a vanilla model until its next generation), then its displayed pose on the
     * sender's clock (RemoteEves.displayTime).
     */
    private static void frameRemotes(long nanos) {
        RemoteEves.frame(nanos);
        double now = RemoteEves.clock();
        for (RemoteEves.Remote remote : RemoteEves.remotes()) {
            if (remote.table == null || remote.failed == remote.table.generation()) continue;
            try {
                if (remote.eve == null) {
                    remote.eve = remoteEve(remote);
                    remote.changed = true;
                }
                if (remote.changed) {
                    remote.changed = false;
                    remote.eve.want(remote.stream, remote.origin());
                }
                remote.eve.rebuild();
                if (remote.eve.posed) remote.eve.display(RemoteEves.displayTime(now, remote.floor, remote.interval));
            } catch (IOException | RuntimeException failure) {
                remote.drop(failure.getMessage());
            }
        }
    }

    /**
     * Another player's EVE from this pack: their meshes this pack holds in the same version, the others replaced by role
     * (RemoteEves.substitute) and driven by bone name, textures this player's EVE does not hold at half resolution. A model
     * without a body fails: that player stays a vanilla model.
     */
    private static Eve remoteEve(RemoteEves.Remote remote) {
        Path pack = packDirectory();
        return new Eve(pack, true, RemoteEves.RING, RemoteEves.INTERVAL_CAP, table -> {
            NetPose.Table wanted = remote.table;
            if (wanted == null || wanted.generation() != table.generation()) throw new IOException("superseded");
            List<String> notes = new ArrayList<>();
            List<AvatarRecording.Component> components = RemoteEves.substitute(wanted, mesh -> RemoteEves.fingerprint(pack, mesh), notes);
            AvatarModel model = AvatarModel.load(pack, components, MESHES, true);
            if (model.parts.isEmpty() || model.parts.get(0).component() != 0)
                throw new IOException("neither their body mesh nor a stand-in is in this avatar pack");
            notes.addAll(model.skipped);
            return Eve.prepare(pack, model, true, String.join("; ", notes));
        });
    }

    /**
     * Whether a remote's EVE is drawn now: built with a body, posed within SILENT_NANOS, in this dimension, and not of a
     * player invisible to this one (an Invisibility effect, spectator mode), whom vanilla would not draw either.
     */
    private static boolean drawable(RemoteEves.Remote remote, long nanos, String dimension) {
        Eve eve = remote.eve;
        if (eve == null || !eve.posed || !eve.hasBody() || eve.origin == null || nanos - remote.lastNanos > RemoteEves.SILENT_NANOS
                || !remote.table.dimension().equals(dimension)) return false;
        var client = MinecraftClient.getInstance();
        var player = remote.echo() || client.world == null ? null : client.world.getPlayerByUuid(remote.owner);
        return player == null || client.player == null || !player.isInvisibleTo(client.player);
    }
    private static String dimension(net.minecraft.world.World world) { return world.getRegistryKey().getValue().toString(); }

    // Past DETAIL blocks another player's EVE loses her face's morphs, her cast shadow, trail and effects. ponytail: one
    // distance, a guess of the design's 16 to 24 blocks; tune it from the grouped test.
    static final double DETAIL = 20;

    /** Draws every drawable remote EVE in view; far ones at less detail. */
    private static void renderRemotes(WorldRenderContext context) {
        if (RemoteEves.remotes().isEmpty()) return;
        long nanos = System.nanoTime();
        Vec3d camera = context.camera().getPos();
        String dimension = dimension(context.world());
        for (RemoteEves.Remote remote : RemoteEves.remotes()) {
            remote.drawn = false;
            if (!drawable(remote, nanos, dimension)) continue;
            Eve eve = remote.eve;
            try {
                Matrix4d placement = placement(eve.table.anchor(), eve.origin, camera);
                float[] root = eve.root();
                Vector3d feet = placement.transformPosition(new Vector3d(root[4], root[5], root[6])).add(camera.x, camera.y, camera.z);
                remote.feet = new Vec3d(feet.x, feet.y, feet.z);
                var frustum = context.frustum();
                remote.culled = frustum != null && !frustum.isVisible(new net.minecraft.util.math.Box(feet.x - 1.5, feet.y - 0.5, feet.z - 1.5,
                        feet.x + 1.5, feet.y + 2.5, feet.z + 1.5));
                if (remote.culled) continue;
                remote.near = feet.distanceSquared(camera.x, camera.y, camera.z) <= DETAIL * DETAIL;
                if (!remote.near) for (float[] weights : eve.pose.weights()) if (weights != null) java.util.Arrays.fill(weights, 0);
                eve.model.palettes(eve.pose, placement, eve.renderer.palettes, true);
                if (remote.near) {
                    if (!effects(context, eve, placement, camera, nanos)) eve.particles.spawn(eve.effects, placement, camera, nanos);
                } else eve.renderer.effects(Map.of(), new Vector3d(camera.x, camera.y, camera.z), context.camera().getRotation(), nanos / 1e9);
                var player = context.world().getPlayerByUuid(remote.owner);
                eve.renderer.hurt(player != null && player.hurtTime > 0);
                draw(context, eve.model, eve.renderer, eve.pose, placement, camera, remote.near);
                remote.drawn = true;
            } catch (RuntimeException failure) {
                remote.drop(failure.getMessage());
            }
        }
    }

    /**
     * The game's effects on an EVE at absolute positions for its renderer's trail and sprites; false when the pack has no
     * effect sprites (Minecraft particles stand in).
     */
    private static boolean effects(WorldRenderContext context, Eve eve, Matrix4d placement, Vec3d camera, long nanos) {
        Map<String, Vector3d> effects = null;
        if (eve.table.effectRecords()) {
            effects = new HashMap<>();
            for (AvatarStream.Effect effect : eve.effects)
                effects.put(effect.name(), placement.transformPosition(new Vector3d(effect.x(), effect.y(), effect.z())).add(camera.x, camera.y, camera.z));
        }
        return eve.renderer.effects(effects, new Vector3d(camera.x, camera.y, camera.z), context.camera().getRotation(), nanos / 1e9);
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
            draw(context, current.model, current.renderer, current.pose, placement, camera, true);
        } catch (RuntimeException failure) {
            off();
            report("EVE avatar renderer stopped: " + failure.getMessage(), failure);
        }
    }

    /**
     * Once per Minecraft frame before the camera: newest pose, then the displayed pose shared by camera and drawing; this
     * player's EVE first, then the others, all sharing one texture upload budget.
     */
    public static void beginFrame() {
        long nanos = System.nanoTime();
        uploadUntil = nanos + UPLOAD_NANOS;
        beginLive(nanos);
        frameRemotes(nanos);
    }
    private static long uploadUntil; // This frame's texture uploads stop at this System.nanoTime.

    private static void beginLive(long nanos) {
        Live current = live;
        if (current == null) return;
        if (MinecraftClient.getInstance().world != current.world) { stopLive("the world changed"); return; }
        try {
            AvatarStream.Sample sample = current.stream.poll(current.eve.table, System.currentTimeMillis(), nanos);
            boolean decoded = false; // A new pose of the drawn table this frame, which other players may get.
            if (sample != null && (current.eve.table == null || sample.generation() != current.eve.table.generation())) {
                AvatarStream.Table wanted = current.eve.rebuild.wanted();
                if (wanted == null || wanted.generation() != sample.generation()) {
                    AvatarStream.Table table = current.stream.table();
                    if (table != null && table.generation() == sample.generation()) current.eve.want(table, null);
                }
            } else if (sample != null && current.eve.model != null) {
                if (current.eve.accept(sample, nanos)) {
                    current.rotation = sample.rotation();
                    current.lookApplied = sample.lookApplied();
                    if (sample.inputGranted() || !current.controlled || current.rotation == null) current.grantedNanos = nanos;
                    StellarBridge.halted(nanos - current.grantedNanos > HALT_NANOS);
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
                    decoded = true;
                    current.windowPoses++;
                }
            }
            current.eve.rebuild();
            if (nanos - current.windowNanos >= 2_000_000_000L) {
                current.rate = current.windowPoses * 1e9 / (nanos - current.windowNanos);
                current.windowPoses = 0;
                current.windowNanos = nanos;
            }
            if (!current.eve.posed) return;
            AvatarCombat.watch(current.stream.gauges(), nanos);
            current.eve.display(wallMillis() - current.eve.interval);
            var link = StellarBridge.renderingContext();
            current.anchored = current.eve.table.anchor() != null && link != null && current.eve.table.session().equals(link.session());
            if (decoded && current.anchored && sample.generation() == current.eve.table.generation()) RemoteEves.send(current.eve.table, sample, link);
            // Her stats while she is the bridged player; a frame the adapter could not read them keeps the last ones.
            float[] stats = current.stream.stats();
            if (stats != null) current.stats = stats;
            StellarBridge.stats(current.anchored ? current.stats : null);
            if (current.controlled) {
                var player = MinecraftClient.getInstance().player;
                // A locked target turns Minecraft's camera first; Stellar Blade's follows it. Without the link no EVE aims.
                if (current.anchored) AvatarLock.frame(nanos); else AvatarLock.release();
                // Minecraft yaw + 90 and negated pitch are Unreal's; the adapter turns toward them while input is forwarded.
                // Minecraft's yaw keeps counting turns; the adapter takes one turn. Its health and gains words go first, so
                // the camera's fresh time covers them.
                if (player != null) {
                    StellarBridge.heals(current.stream.healed()); // An unanchored frame keeps the count: heals wait.
                    var feedback = StellarBridge.feedback();
                    current.stream.feedback(current.anchored ? feedback.share() : 0, current.anchored ? feedback.acknowledged() : 0,
                            feedback.beta(), feedback.burst());
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
        if (current.eve.model == null || !current.eve.posed) return;
        if (context.world() != current.world) { stopLive("the world changed"); return; }
        try {
            Vec3d camera = context.camera().getPos();
            var link = StellarBridge.renderingContext();
            Matrix4d placement;
            boolean firstPerson = false;
            if (current.anchored && link != null) {
                placement = anchored(current, link, camera);
                firstPerson = MinecraftClient.getInstance().options.getPerspective().isFirstPerson();
            } else {
                float[] root = current.eve.pose.world()[current.eve.model.parts.get(0).component()];
                if (current.relativeOrigin == null) current.relativeOrigin = new Vector3d(root[4], root[5], root[6]);
                Vec3d at = current.relativeAnchor;
                placement = new Matrix4d().translation(at.x - camera.x, at.y - camera.y, at.z - camera.z)
                        .mul(AvatarModel.UNREAL_TO_MINECRAFT).translate(-current.relativeOrigin.x, -current.relativeOrigin.y, -current.relativeOrigin.z);
            }
            current.eve.model.palettes(current.eve.pose, placement, current.eve.renderer.palettes, true);
            // The game's effects at absolute positions; Minecraft particles stand in when the pack has no effect sprites.
            long nanos = System.nanoTime();
            boolean sprites = effects(context, current.eve, placement, camera, nanos);
            if (firstPerson) {
                // The camera is inside EVE's head, as Minecraft hides its own player there: she is not drawn, but her blade
                // still strikes from her pose and her shots leave her eyes.
                current.eve.renderer.sampleTrail(current.eve.pose, placement, camera.x, camera.y, camera.z, nanos / 1e9);
                current.blade.update(current.eve.renderer, current.eve.effects, nanos / 1e9);
                current.gun.update(current.stream.shots(), true, () -> null);
                return;
            }
            if (!sprites) current.eve.particles.spawn(current.eve.effects, placement, camera, nanos);
            // At the bridged position EVE is the player: she flashes red when hurt, her blade and her drone's shots strike entities.
            var player = MinecraftClient.getInstance().player;
            boolean bridged = current.anchored && link != null && player != null;
            current.eve.renderer.hurt(bridged && player.hurtTime > 0);
            draw(context, current.eve.model, current.eve.renderer, current.eve.pose, placement, camera, true);
            if (bridged) current.blade.update(current.eve.renderer, current.eve.effects, nanos / 1e9);
            current.gun.update(current.stream.shots(), bridged,
                    () -> AvatarWeapons.muzzle(current.eve.model, current.eve.table.components(), current.eve.drawn, current.eve.pose, placement, camera));
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
        if (current == null || current.eve.model == null || !current.eve.posed || x1 <= x0 || y1 <= y0) return false;
        Vector3d eye = new Vector3d();
        Matrix4d placement = studio(current.eve.pose.world()[current.eve.model.parts.get(0).component()], distance, height, degrees, eye);
        // The world pass computes its own palettes again before it draws: sharing them is safe.
        current.eve.model.palettes(current.eve.pose, placement, current.eve.renderer.palettes, true);
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
            current.eve.renderer.hurt(false); // The world pass sets it again each frame.
            int lightmap = lightmap(client.gameRenderer.getLightmapTextureManager());
            if (ShaderPacks.forced) {
                DiffuseLighting.enableForLevel(); // Her normals are world-aligned, as in the world pass.
                current.eve.renderer.drawEntity(view, projection, lightmap, overlay(), 15, 15, GameRenderer.getRenderTypeEntityCutoutNoNullProgram(),
                        GameRenderer.getRenderTypeEntityTranslucentProgram(), null, 2); // ponytail: no glow pass; the sword's edge stays dark.
                DiffuseLighting.enableGuiDepthLighting();
            } else current.eve.renderer.draw(new Matrix4f(projection).mul(view), lightmap, 15, 15, 1000, 2000, PREVIEW_FOG, PREVIEW_SKY,
                    AvatarShadows.NONE, false);
        } finally {
            GlStateManager._viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            context.disableScissor();
        }
        return true;
    }

    /**
     * The terrain link's mapping, camera-relative: Minecraft origin + (X, Z, Y) / scale relative to the lab anchor (Unreal
     * X, Y, Z and units per block).
     */
    private static Matrix4d placement(double[] anchor, Vec3d origin, Vec3d camera) {
        return new Matrix4d().translation(origin.x - camera.x, origin.y - camera.y, origin.z - camera.z)
                .mul(AvatarModel.unrealToMinecraft(anchor[3])).translate(-anchor[0], -anchor[1], -anchor[2]);
    }
    private static Matrix4d anchored(Live current, StellarBridge.RenderingContext link, Vec3d camera) {
        var origin = link.origin();
        return placement(current.eve.table.anchor(), new Vec3d(origin.x(), origin.y(), origin.z()), camera);
    }

    /** Whether EVE replaces the player in the shader pack's shadow pass now running. */
    public static boolean castsShadow() { return ShaderPacks.shadowPass(); }

    /**
     * In Iris's shadow pass, pose being its shadow view: the opaque and cut-out sections of the EVE drawn for that player
     * (draws) into the pack's shadow map.
     */
    public static void drawShadow(Entity entity, Matrix4f pose) {
        var client = MinecraftClient.getInstance();
        Vec3d camera = client.gameRenderer.getCamera().getPos();
        Eve eve;
        Matrix4d placement;
        RemoteEves.Remote remote = null;
        if (replaces(entity)) {
            var link = StellarBridge.renderingContext();
            if (link == null) return;
            eve = live.eve;
            placement = anchored(live, link, camera);
        } else {
            remote = RemoteEves.get(entity.getUuid());
            if (remote == null || remote.eve == null || remote.eve.origin == null) return;
            eve = remote.eve;
            placement = placement(eve.table.anchor(), eve.origin, camera);
        }
        try {
            eve.model.palettes(eve.pose, placement, eve.renderer.palettes, true);
            // ponytail: palettes and skinning run again in the main pass; computing them once at WorldRenderEvents.START,
            // which precedes Iris's shadow pass, would save that if it ever shows in profiles.
            eve.renderer.drawShadow(new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose), RenderSystem.getProjectionMatrix(),
                    lightmap(client.gameRenderer.getLightmapTextureManager()), overlay(), GameRenderer.getRenderTypeEntityCutoutNoNullProgram());
        } catch (RuntimeException failure) {
            if (remote != null) {
                remote.drop(failure.getMessage());
                return;
            }
            stopLive(failure.getMessage());
            LoggerFactory.getLogger("stellar_bridge").error("EVE live avatar stopped", failure);
        }
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

    /**
     * Draws a posed EVE; without full (another player's, far away) no shadow cast on the blocks. The trail samples either
     * way: a far EVE's samples have no strength (renderRemotes gives her no effects), so a ribbon she swung before going
     * far ages out instead of hanging where she was.
     */
    private static void draw(WorldRenderContext context, AvatarModel model, AvatarRenderer renderer, AvatarRecording.Pose pose,
                             Matrix4d placement, Vec3d camera, boolean full) {
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
        AvatarShadows.Light key = eased(renderer, AvatarShadows.sky(context.world(), tickDelta, LightmapTextureManager.getSkyLightCoordinates(light)));
        boolean cast = full && MinecraftClient.getInstance().options.getEntityShadows().getValue();
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
    /**
     * The sun with its strength eased over a quarter second, for each EVE apart (on her renderer): sky light steps by whole
     * levels under trees and roofs.
     */
    private static AvatarShadows.Light eased(AvatarRenderer renderer, AvatarShadows.Light target) {
        long nanos = System.nanoTime();
        float ease = renderer.keyNanos == 0 ? 1 : (float) Math.min(1, (nanos - renderer.keyNanos) / 0.25e9);
        renderer.keyNanos = nanos;
        renderer.keyStrength += (target.strength() - renderer.keyStrength) * ease;
        return new AvatarShadows.Light(target.direction(), renderer.keyStrength);
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
    static double wallMillis() {
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
        if (current == null || !current.eve.posed || current.eve.table == null || current.eve.table.anchor() == null || link == null
                || !current.eve.table.session().equals(link.session())) return null;
        float[] root = current.eve.pose.world()[current.eve.model.parts.get(0).component()];
        double[] anchor = current.eve.table.anchor();
        var origin = link.origin();
        // Unreal (X, Y, Z) to Minecraft (X, Z, Y) relative to the lab anchor, as the terrain link maps EVE's feet.
        return new Vec3d(origin.x() + (root[4] - anchor[0]) / anchor[3], origin.y() + (root[6] - anchor[2]) / anchor[3],
                origin.z() + (root[5] - anchor[1]) / anchor[3]);
    }

    /** Controls may follow Minecraft only while this run's anchored pose is fresh. */
    static boolean controlReady(String run, String session, long nanos) {
        Live current = live;
        return current != null && current.controlled && current.stream.run.equals(run) && current.anchored && current.eve.posed
                && current.eve.table.session().equals(session) && nanos - current.eve.lastPoseNanos <= 500_000_000L;
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
        return current != null && current.anchored && current.eve.posed && entity == MinecraftClient.getInstance().player;
    }

    /**
     * Whether an EVE stands in for this entity's vanilla body: this player's (replaces), or another player's EVE drawn from
     * this pack (built with a body, posed lately, in view last frame) showing her body within STANDS_IN blocks of that
     * player. Any other player keeps the vanilla model, so poses that hide her or place her elsewhere hide no one.
     */
    public static boolean draws(Entity entity) {
        if (replaces(entity)) return true;
        if (!(entity instanceof net.minecraft.entity.player.PlayerEntity) || entity == MinecraftClient.getInstance().player) return false;
        RemoteEves.Remote remote = RemoteEves.get(entity.getUuid());
        return remote != null && !remote.culled && drawable(remote, System.nanoTime(), dimension(entity.getWorld())) && remote.eve.drawn[0]
                && remote.feet != null && remote.feet.squaredDistanceTo(entity.getPos()) <= STANDS_IN * STANDS_IN;
    }
    // Blocks between another player's entity and their EVE's feet within which she stands in for their body: her pose lags
    // the entity by the display delay, and a sprinting player covers about 6 blocks a second.
    static final double STANDS_IN = 8;

    private static String status() {
        if (replay != null) return "EVE avatar: replaying " + replay.model.parts.size() + " meshes from the pose probe recording.";
        return live == null ? "EVE avatar is off." : liveStatus(live);
    }
    private static String liveStatus(Live current) {
        long[] native_ = current.stream.statistics();
        return String.format("EVE live avatar: %s; generation %s; %d poses (%.1f/s, %.1f ms apart), %d incomplete; last %s; native read %d us last, %d max, %d mean over %d published.",
                current.eve.table == null ? "waiting for Lua's component table" : current.anchored ? "anchored to the bridge link" : "two blocks ahead (no entered link)",
                current.eve.table == null ? "-" : Long.toString(current.eve.table.generation()), current.eve.poses, current.rate, current.eve.interval, current.eve.incomplete,
                current.eve.posed ? String.format("%.0f ms ago", (System.nanoTime() - current.eve.lastPoseNanos) / 1e6) : "never",
                native_[1], native_[2], native_[3], native_[0])
                + (current.gapSamples == 0 ? "" : String.format(" Camera: turned by look input in %.0f%% of controlled poses; yaw gap %.1f deg mean, %.0f max;"
                        + " dolly set from Minecraft's pitch in %.0f%%, pitch gap %.1f deg mean, %.0f max.",
                        100.0 * current.turned / current.gapSamples, current.gapSum / current.gapSamples, current.gapMax,
                        100.0 * current.synced / current.gapSamples, current.synced == 0 ? 0 : current.pitchSum / current.synced, current.pitchMax)
                        + refusalStatus(current))
                + (current.eve.table == null || current.eve.table.parameters().isEmpty() ? " No streamed material parameters."
                        : String.format(" %d streamed material parameters; highest glow opacity %.2f.", current.eve.table.parameters().size(), current.eve.glow))
                + (current.eve.table == null || !current.eve.table.effectRecords() ? " Effects not streamed."
                        : String.format(" %d known effects; effects active in %d poses.", current.eve.table.effects().size(), current.eve.effectSamples))
                + clothStatus(current) + AvatarHudFile.status() + AvatarHud.status()
                + hiddenStatus(current) + current.gun.status(current.stream.shots())
                + evadeStatus(current.stream.evades()) + gaugeStatus(current.stream.gauges())
                + AvatarStats.status(current.stats) + reportStatus(current.stream.report())
                + (ShaderPacks.active() ? " Drawn through Minecraft's entity programs." : " Drawn with her own shaders.");
    }

    /**
     * How many poses drew Stellar Blade's ponytail cloth, its stale samples (particles unchanged while her head moved), then
     * why the pose took bones instead, the cloth's update cadence, its stale runs and its jumps.
     */
    private static String clothStatus(Live current) {
        if (current.eve.table == null || current.eve.table.cloth() < 0) return " Cloth: none in the component table.";
        float[] shown = current.eve.pose == null ? null : current.eve.pose.cloth()[current.eve.table.cloth()];
        return String.format(" Cloth: %d of %d poses used Stellar Blade's ponytail cloth%s; %d stale samples (particles unchanged while her"
                + " head moved).", current.eve.clothPoses, current.eve.poses, shown == null ? ", bones now" : " (" + shown.length / 3 + " particles)",
                current.eve.cloth.stale) + current.eve.cloth.status();
    }

    private static String reportStatus(long[] report) {
        return String.format(" Gauge gains: the adapter granted Beta %d, Burst %d. Heals: %d Stellar Blade HP converted; mirror %s;"
                + " %d Stellar Blade losses overwritten.", report[3], report[4], report[0], new String[] {"off", "on", "held"}[(int) report[1]], report[2]);
    }

    /** The streamed meshes left out of the newest pose: hidden in Stellar Blade (EVE's drone between appearances) or without data. */
    private static String hiddenStatus(Live current) {
        if (current.eve.model == null || !current.eve.posed) return "";
        StringBuilder names = new StringBuilder();
        for (var part : current.eve.model.parts)
            if (!current.eve.drawn[part.component()]) names.append(names.isEmpty() ? "" : ", ").append(current.eve.table.components().get(part.component()).name());
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
        StellarBridge.halted(false);
        StellarBridge.stats(null);
        StellarBridge.heals(-1);
        AvatarLock.release();
        if (current.controlled) {
            current.stream.lookOff();
            ControlBridge.stop();
        }
        String summary = liveStatus(current);
        current.eve.close();
        releaseTextures(); // Only other players' EVEs keep theirs.
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

    static void report(String message, Throwable failure) {
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
