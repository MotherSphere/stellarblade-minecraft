package org.stellarbridge;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.CoreShaderRegistrationCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlProgramManager;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.slf4j.LoggerFactory;

/** Opt-in depth composition. Native EVE movement remains owned by the existing bridge. */
public final class CaptureView {
    private static ShaderProgram shader;
    private static View active;
    private static View rendering; // One immutable choice for camera, FOV and composition of a Minecraft frame.
    private static View presented;
    private static CaptureStream stream;
    private static Binding liveBinding;

    static void initialize() {
        ControlBridge.initialize();
        CoreShaderRegistrationCallback.EVENT.register(context -> context.register(
                Identifier.of("stellar_bridge", "eve_composite"), VertexFormats.POSITION_TEXTURE, program -> shader = program));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommandManager.literal("stellarview")
                    .then(ClientCommandManager.literal("load").executes(context -> load(context.getSource())))
                    .then(ClientCommandManager.literal("live").executes(context -> live(context.getSource(), false)))
                    .then(ClientCommandManager.literal("livehd").executes(context -> live(context.getSource(), true)))
                    .then(ClientCommandManager.literal("play").executes(context -> play(context.getSource())))
                    .then(ClientCommandManager.literal("mode")
                            .then(ClientCommandManager.literal("eve").executes(context -> mode(context.getSource(), false)))
                            .then(ClientCommandManager.literal("build").executes(context -> mode(context.getSource(), true))))
                    .then(ClientCommandManager.literal("gain").then(ClientCommandManager.argument("value",
                            com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(1, 32767)).executes(context -> {
                                ControlBridge.gain(com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "value"));
                                context.getSource().sendFeedback(Text.literal(ControlBridge.status()));
                                return 1;
                            })))
                    .then(ClientCommandManager.literal("status").executes(context -> {
                        context.getSource().sendFeedback(Text.literal(stream == null ? "EVE live diagnostic has not run."
                                : stream.mailbox.status(System.nanoTime())));
                        context.getSource().sendFeedback(Text.literal(ControlBridge.status()));
                        return 1;
                    }))
                    .then(ClientCommandManager.literal("off").executes(context -> {
                        off();
                        context.getSource().sendFeedback(Text.literal("EVE view closed; normal camera restored."));
                        return 1;
                    }))));
        WorldRenderEvents.END.register(CaptureView::composite);
        ClientTickEvents.END_CLIENT_TICK.register(client -> update());
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(CaptureView::off));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            off();
            if (stream != null) stream.close();
        });
    }

    public static void beginFrame() {
        update();
        if (liveBinding != null) adopt();
        rendering = active;
    }
    public static void endFrame() { rendering = null; }
    public static CaptureProjection.CameraPose camera() { return rendering == null ? null : rendering.camera; }
    public static CaptureProjection.CameraPose targetCamera() {
        View view = rendering != null ? rendering : presented;
        return view != null && valid(view) ? view.camera : null;
    }
    static boolean controlFrameReady(String run, String session, long nowNanos) {
        return controlCamera(run, session, nowNanos) != null;
    }
    static CaptureProjection.CameraPose controlCamera(String run, String session, long nowNanos) {
        View view = presented;
        return view != null && view.live && view.metadata.version() == 4 && run.equals(view.metadata.run())
                && session.equals(view.metadata.link().session()) && valid(view)
                && controlFrameFresh(view.receivedNanos, nowNanos) ? view.camera : null;
    }
    static boolean controlFrameFresh(long receivedNanos, long nowNanos) {
        return nowNanos >= receivedNanos && nowNanos - receivedNanos <= 500_000_000L;
    }

    private static int play(FabricClientCommandSource source) {
        if (live(source, true) == 0) return 0;
        try {
            var request = stream.mailbox.request();
            ControlBridge.start(directory(), request.run(), request.session());
            source.sendFeedback(Text.literal("EVE control requested. Release held controls; forwarding starts after a fresh image and native acknowledgement. Use the EVE/build binding to switch modes."));
            return 1;
        } catch (IOException failure) {
            off();
            source.sendError(Text.literal("EVE control failed: " + failure.getMessage()));
            return 0;
        }
    }
    private static int mode(FabricClientCommandSource source, boolean build) {
        ControlBridge.build(build);
        source.sendFeedback(Text.literal(ControlBridge.status()));
        return 1;
    }

    private static boolean supported(MinecraftClient client) {
        return client.player != null && shader != null && !MinecraftClient.isFabulousGraphicsOrBetter()
                && !FabricLoader.getInstance().isModLoaded("iris") && client.getFramebuffer().useDepthAttachment
                && client.player.hurtTime == 0 && client.player.deathTime == 0 && !client.player.isSleeping()
                && client.player.getStatusEffects().isEmpty() && client.player.nauseaIntensity == 0
                && client.player.prevNauseaIntensity == 0;
    }
    private static boolean valid(View view) {
        MinecraftClient client = MinecraftClient.getInstance();
        var context = StellarBridge.renderingContext();
        return supported(client) && context != null && client.world == view.world && context.owner().equals(view.owner)
                && context.dimension().equals(view.dimension) && context.origin().equals(view.origin)
                && CaptureProjection.matches(view.metadata, context.session(), context.revision(), now())
                && (!view.live || CaptureStream.fresh(view.metadata.time(), now()));
    }

    private static Path directory() {
        return Path.of(System.getProperty("stellarbridge.directory",
                Path.of(System.getProperty("user.home"), ".local", "share", "stellar-blade-minecraft", "bridge").toString()));
    }

    private static int live(FabricClientCommandSource source, boolean hd) {
        off();
        MinecraftClient client = source.getClient();
        var context = StellarBridge.renderingContext();
        if (context == null || !supported(client)) {
            source.sendError(Text.literal("Enter the linked private creative terrain first. Use Fast/Fancy, no Iris, and a calm player without effects."));
            return 0;
        }
        if (stream == null) stream = new CaptureStream(directory());
        try {
            if (hd) stream.startHd(context.session(), context.revision(), context.origin(), System.nanoTime());
            else stream.mailbox.start(context.session(), context.revision(), context.origin(), System.nanoTime());
        } catch (IOException failure) {
            source.sendError(Text.literal("EVE HD request failed: " + failure.getMessage()));
            return 0;
        }
        liveBinding = new Binding(client.world, context.session(), context.owner(), context.dimension(), context.origin());
        source.sendFeedback(Text.literal(hd
                ? "EVE HD stream requested. Native controls remain in Stellar Blade. /stellarview off stops the stream and restores the camera."
                : "EVE live diagnostic waiting for native capture. /stellarview off restores the camera; /stellarview status shows observed cadence."));
        return 1;
    }

    private static void update() {
        if (liveBinding != null) {
            MinecraftClient client = MinecraftClient.getInstance();
            var context = StellarBridge.renderingContext();
            if (!supported(client) || context == null || !liveBinding.matches(client.world, context)) {
                stopLive("bridge binding or supported render state changed", true);
                return;
            }
            var request = stream.mailbox.request();
            if (request == null) {
                stopLive(stream.mailbox.reason(), true);
                return;
            }
            if (request.revision() != context.revision()) {
                stream.mailbox.revision(context.revision());
                clearView();
            }
            if (stream.mailbox.expired(System.nanoTime())) {
                stopLive("no complete fresh generation before the deadline", true);
                return;
            }
            try {
                stream.heartbeat();
            } catch (IOException failure) {
                stopLive("capture request heartbeat failed", true);
                LoggerFactory.getLogger("stellar_bridge").warn("EVE capture heartbeat failed", failure);
                return;
            }
            if (active != null && !valid(active)) clearView();
        } else if (active != null && !valid(active)) clearView();
    }

    private static void adopt() {
        CaptureStream.Prepared frame = stream.mailbox.take();
        if (frame == null) return;
        NativeImageBackedTexture texture = null;
        int depthTexture = 0;
        int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try (frame) {
            if (!stream.mailbox.current(frame) || !CaptureStream.fresh(frame.metadata.time(), now())) return;
            texture = new NativeImageBackedTexture(frame.color);
            frame.color = null; // Ownership passes only after the texture constructor succeeds.
            depthTexture = uploadDepth(frame.depth, frame.metadata.width(), frame.metadata.height());
            View next = new View(frame.metadata, frame.camera, liveBinding.world, liveBinding.owner,
                    liveBinding.dimension, liveBinding.origin, texture, depthTexture, true, frame.receivedNanos);
            if (!stream.mailbox.current(frame) || !valid(next)) return;
            View previous = active;
            active = next;
            texture = null;
            depthTexture = 0;
            stream.mailbox.accepted(frame);
            close(previous);
        } catch (RuntimeException failure) {
            stopLive("texture upload failed", true);
            LoggerFactory.getLogger("stellar_bridge").error("EVE live upload stopped", failure);
        } finally {
            if (texture != null) texture.close();
            if (depthTexture != 0) GlStateManager._deleteTexture(depthTexture);
            RenderSystem.bindTexture(previousTexture == 0 || GL11.glIsTexture(previousTexture) ? previousTexture : 0);
        }
    }

    private static int load(FabricClientCommandSource source) {
        off();
        MinecraftClient client = source.getClient();
        var context = StellarBridge.renderingContext();
        if (context == null || !supported(client)) {
            source.sendError(Text.literal("Enter the linked private creative terrain first. Use Fast/Fancy, no Iris, and a calm player without effects."));
            return 0;
        }
        NativeImage color = null;
        NativeImageBackedTexture texture = null;
        int depthTexture = 0;
        try {
            Path directory = directory();
            long loadStarted = System.nanoTime();
            var loaded = CaptureFrame.read(directory, now());
            long readDepthNanos = System.nanoTime() - loadStarted;
            if (!CaptureProjection.matches(loaded.metadata(), context.session(), context.revision(), now()))
                throw new IOException("Capture LINK does not match the active terrain session and revision");
            var camera = CaptureProjection.camera(loaded.metadata(), context.origin());
            long decodeStarted = System.nanoTime();
            color = CaptureImages.decodeMasked(loaded);
            long decodeNanos = System.nanoTime() - decodeStarted;
            CaptureFrame.verifyUnchanged(directory, loaded.descriptor());
            long uploadStarted = System.nanoTime();
            texture = new NativeImageBackedTexture(color);
            color = null;
            depthTexture = uploadDepth(loaded.depth(), loaded.metadata().width(), loaded.metadata().height());
            long uploadNanos = System.nanoTime() - uploadStarted;
            var next = new View(loaded.metadata(), camera, client.world, context.owner(), context.dimension(), context.origin(), texture, depthTexture, false, System.nanoTime());
            if (!valid(next)) throw new IOException("The active bridge changed while loading");
            active = next;
            texture = null;
            depthTexture = 0;
            LoggerFactory.getLogger("stellar_bridge").info(
                    "EVE still accepted frame={} version={} width={} height={} read_depth_ms={} color_mask_ms={} upload_submit_ms={} total_ms={}",
                    loaded.metadata().id(), loaded.metadata().version(), loaded.metadata().width(), loaded.metadata().height(), readDepthNanos / 1_000_000.0,
                    decodeNanos / 1_000_000.0, uploadNanos / 1_000_000.0, (System.nanoTime() - loadStarted) / 1_000_000.0);
            source.sendFeedback(Text.literal("EVE still view loaded (" + loaded.metadata().width() + "x" + loaded.metadata().height()
                    + "): camera and depth from one capture, expires after 30 seconds. /stellarview off restores the camera."));
            return 1;
        } catch (IOException | RuntimeException failure) {
            source.sendError(Text.literal("EVE still view failed: " + failure.getMessage()));
            return 0;
        } finally {
            if (color != null) color.close();
            if (texture != null) texture.close();
            if (depthTexture != 0) GlStateManager._deleteTexture(depthTexture);
        }
    }

    private static int uploadDepth(float[] values, int width, int height) {
        if (!CaptureFrame.supportedSize(width, height) || values.length != width * height)
            throw new IllegalArgumentException("Capture depth upload dimensions do not match the decoded pixels");
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int texture = GlStateManager._genTexture();
        boolean complete = false;
        try {
            RenderSystem.bindTexture(texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, width, height, 0, GL11.GL_RED, GL11.GL_FLOAT, values);
            complete = true;
            return texture;
        } finally {
            RenderSystem.bindTexture(previous);
            if (!complete) GlStateManager._deleteTexture(texture);
        }
    }

    private static void off() {
        stopLive("off", false);
    }

    private static void stopLive(String reason, boolean notify) {
        ControlBridge.stop();
        boolean wasLive = liveBinding != null;
        liveBinding = null;
        if (stream != null) {
            try { stream.stop(reason); }
            catch (IOException failure) {
                LoggerFactory.getLogger("stellar_bridge").warn("EVE stream stop publication failed; native lease will expire", failure);
            }
        }
        clearView();
        if (wasLive) {
            LoggerFactory.getLogger("stellar_bridge").info("{}", stream.mailbox.status(System.nanoTime()));
            MinecraftClient client = MinecraftClient.getInstance();
            if (notify && client.player != null)
                client.player.sendMessage(Text.literal("EVE live diagnostic stopped: " + reason + "; normal camera restored."), false);
        }
    }

    private static void clearView() {
        View previous = active;
        active = null;
        rendering = null;
        presented = null;
        close(previous);
    }

    private static void close(View view) {
        if (view != null) {
            view.color.close();
            GlStateManager._deleteTexture(view.depthTexture);
        }
    }

    private static void composite(WorldRenderContext context) {
        View view = rendering;
        if (view == null) return;
        if (context.advancedTranslucency()) { off(); return; }
        try {
            draw(view, context);
            presented = view;
            ControlBridge.alignPresentedLook();
        } catch (RuntimeException failure) {
            off();
            LoggerFactory.getLogger("stellar_bridge").error("EVE compositor stopped", failure);
            MinecraftClient.getInstance().player.sendMessage(Text.literal("EVE compositor stopped; normal camera restored."), false);
        }
    }

    private static void draw(View view, WorldRenderContext context) {
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE), depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int depthFunction = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), targetRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), targetAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        int vertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        ShaderProgram previousShader = RenderSystem.getShader();
        int[] samplers = {RenderSystem.getShaderTexture(0), RenderSystem.getShaderTexture(1)}, bindings = new int[2];
        for (int i = 0; i < 2; i++) {
            RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
            bindings[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        RenderSystem.activeTexture(activeTexture);
        try {
            var window = MinecraftClient.getInstance().getWindow();
            float extent = (float) CaptureProjection.horizontalExtent(window.getFramebufferWidth(), window.getFramebufferHeight());
            RenderSystem.disableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.depthFunc(GL11.GL_LEQUAL);
            RenderSystem.depthMask(true);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShader(() -> shader);
            RenderSystem.setShaderTexture(0, view.color.getGlId());
            RenderSystem.setShaderTexture(1, view.depthTexture);
            shader.getUniform("MinecraftProjection").set(context.projectionMatrix());
            shader.getUniform("UnitsPerBlock").set((float) view.metadata.link().scale());
            var buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
            // Both native exports use top-down rows. V=0 belongs to the top of the NDC quad.
            buffer.vertex(-extent, 1, 0).texture(0, 0);
            buffer.vertex(-extent, -1, 0).texture(0, 1);
            buffer.vertex(extent, -1, 0).texture(1, 1);
            buffer.vertex(extent, 1, 0).texture(1, 0);
            BufferRenderer.drawWithGlobalProgram(buffer.end());
        } finally {
            shader.unbind();
            GlProgramManager.useProgram(program);
            RenderSystem.setShader(() -> previousShader);
            BufferRenderer.resetCurrentVertexBuffer();
            GlStateManager._glBindVertexArray(vertexArray);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
            for (int i = 0; i < 2; i++) {
                RenderSystem.setShaderTexture(i, samplers[i]);
                RenderSystem.activeTexture(GL13.GL_TEXTURE0 + i);
                RenderSystem.bindTexture(bindings[i]);
            }
            RenderSystem.activeTexture(activeTexture);
            RenderSystem.blendFuncSeparate(sourceRgb, targetRgb, sourceAlpha, targetAlpha);
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            if (cull) RenderSystem.enableCull(); else RenderSystem.disableCull();
            RenderSystem.depthFunc(depthFunction);
            RenderSystem.depthMask(depthWrite);
        }
    }

    private static long now() { return System.currentTimeMillis() / 1000; }
    private record View(CaptureFrame.Metadata metadata, CaptureProjection.CameraPose camera, ClientWorld world, UUID owner,
                        String dimension, CaptureFrame.Position origin, NativeImageBackedTexture color, int depthTexture, boolean live, long receivedNanos) { }
    private record Binding(ClientWorld world, String session, UUID owner, String dimension, CaptureFrame.Position origin) {
        boolean matches(ClientWorld currentWorld, StellarBridge.RenderingContext context) {
            return world == currentWorld && session.equals(context.session()) && owner.equals(context.owner())
                    && dimension.equals(context.dimension()) && origin.equals(context.origin());
        }
    }
}
