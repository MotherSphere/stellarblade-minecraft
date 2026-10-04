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

/** A manually loaded, depth-tested still. Native EVE movement remains owned by the existing bridge. */
public final class CaptureView {
    private static ShaderProgram shader;
    private static View active;
    private static View rendering; // One immutable choice for camera, FOV and composition of a Minecraft frame.

    static void initialize() {
        CoreShaderRegistrationCallback.EVENT.register(context -> context.register(
                Identifier.of("stellar_bridge", "eve_composite"), VertexFormats.POSITION_TEXTURE, program -> shader = program));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommandManager.literal("stellarview")
                    .then(ClientCommandManager.literal("load").executes(context -> load(context.getSource())))
                    .then(ClientCommandManager.literal("off").executes(context -> {
                        off();
                        context.getSource().sendFeedback(Text.literal("EVE still view closed; normal camera restored."));
                        return 1;
                    }))));
        WorldRenderEvents.END.register(CaptureView::composite);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (active != null && !valid(active)) off();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(CaptureView::off));
    }

    public static void beginFrame() {
        if (active != null && !valid(active)) off();
        rendering = active;
    }
    public static void endFrame() { rendering = null; }
    public static CaptureProjection.CameraPose camera() { return rendering == null ? null : rendering.camera; }

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
                && CaptureProjection.matches(view.metadata, context.session(), context.revision(), now());
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
            Path directory = Path.of(System.getProperty("stellarbridge.directory",
                    Path.of(System.getProperty("user.home"), ".local", "share", "stellar-blade-minecraft", "bridge").toString()));
            var loaded = CaptureFrame.read(directory, now());
            if (!CaptureProjection.matches(loaded.metadata(), context.session(), context.revision(), now()))
                throw new IOException("Capture LINK does not match the active terrain session and revision");
            var camera = CaptureProjection.camera(loaded.metadata(), context.origin());
            color = CaptureImages.decode(loaded.color());
            try (NativeImage mask = CaptureImages.decode(loaded.mask())) {
                if (color.getWidth() != 320 || color.getHeight() != 180 || mask.getWidth() != 320 || mask.getHeight() != 180)
                    throw new IOException("Decoded capture dimensions differ from the descriptor");
                for (int y = 0; y < 180; y++) {
                    for (int x = 0; x < 320; x++) {
                        int alpha = CaptureFrame.previewAlpha(mask.getOpacity(x, y) & 255);
                        color.setColor(x, y, color.getColor(x, y) & 0x00ffffff | alpha << 24);
                    }
                }
            }
            CaptureFrame.verifyUnchanged(directory, loaded.descriptor());
            texture = new NativeImageBackedTexture(color);
            color = null;
            depthTexture = uploadDepth(loaded.depth());
            var next = new View(loaded.metadata(), camera, client.world, context.owner(), context.dimension(), context.origin(), texture, depthTexture);
            if (!valid(next)) throw new IOException("The active bridge changed while loading");
            active = next;
            texture = null;
            depthTexture = 0;
            source.sendFeedback(Text.literal("EVE still view loaded: camera and depth from one capture, expires after 30 seconds. /stellarview off restores the camera."));
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

    private static int uploadDepth(float[] values) {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int texture = GlStateManager._genTexture();
        boolean complete = false;
        try {
            RenderSystem.bindTexture(texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, 320, 180, 0, GL11.GL_RED, GL11.GL_FLOAT, values);
            complete = true;
            return texture;
        } finally {
            RenderSystem.bindTexture(previous);
            if (!complete) GlStateManager._deleteTexture(texture);
        }
    }

    private static void off() {
        View previous = active;
        active = null;
        rendering = null;
        if (previous != null) {
            previous.color.close();
            GlStateManager._deleteTexture(previous.depthTexture);
        }
    }

    private static void composite(WorldRenderContext context) {
        View view = rendering;
        if (view == null) return;
        if (context.advancedTranslucency()) { off(); return; }
        try {
            draw(view, context);
        } catch (RuntimeException failure) {
            off();
            LoggerFactory.getLogger("stellar_bridge").error("EVE still compositor stopped", failure);
            MinecraftClient.getInstance().player.sendMessage(Text.literal("EVE still compositor stopped; normal camera restored."), false);
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
                        String dimension, CaptureFrame.Position origin, NativeImageBackedTexture color, int depthTexture) { }
}
