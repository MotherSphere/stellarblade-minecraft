package org.stellarbridge;

import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

/** Opt-in still-image inspection. This never owns the game camera or player movement. */
final class CapturePreview {
    private static final Identifier TEXTURE = Identifier.of("stellar_bridge", "eve_preview");
    private static CaptureFrame.Metadata frame;
    private static ClientWorld world;

    static void initialize() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommandManager.literal("stellarpreview")
                    .then(ClientCommandManager.literal("load").executes(context -> load(context.getSource())))
                    .then(ClientCommandManager.literal("off").executes(context -> {
                        off();
                        context.getSource().sendFeedback(Text.literal("EVE capture preview closed."));
                        return 1;
                    }))));
        HudRenderCallback.EVENT.register((context, ticks) -> render(context));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (frame != null && !valid(client)) off();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(CapturePreview::off));
    }

    private static boolean eligible(MinecraftClient client) {
        var server = client.getServer();
        return client.world != null && client.player != null && server != null && !server.isRemote()
                && server.getPlayerManager().getCurrentPlayerCount() == 1 && server.isHost(client.player.getGameProfile());
    }

    private static boolean valid(MinecraftClient client) {
        return eligible(client) && world == client.world && CaptureFrame.fresh(frame.time(), now());
    }

    private static int load(FabricClientCommandSource source) {
        off();
        MinecraftClient client = source.getClient();
        if (!eligible(client)) {
            source.sendError(Text.literal("Use EVE capture preview in private, non-LAN singleplayer."));
            return 0;
        }
        NativeImage color = null, mask = null;
        NativeImageBackedTexture texture = null;
        try {
            Path directory = Path.of(System.getProperty("stellarbridge.directory",
                    Path.of(System.getProperty("user.home"), ".local", "share", "stellar-blade-minecraft", "bridge").toString()));
            CaptureFrame.Loaded loaded = CaptureFrame.read(directory, now());
            color = CaptureImages.decode(loaded.color());
            mask = CaptureImages.decode(loaded.mask());
            if (color.getWidth() != 320 || color.getHeight() != 180 || mask.getWidth() != 320 || mask.getHeight() != 180)
                throw new IOException("Decoded capture dimensions differ from the descriptor");
            for (int y = 0; y < 180; y++) {
                for (int x = 0; x < 320; x++) {
                    int alpha = CaptureFrame.previewAlpha(mask.getOpacity(x, y) & 255);
                    color.setColor(x, y, color.getColor(x, y) & 0x00ffffff | alpha << 24);
                }
            }
            CaptureFrame.verifyUnchanged(directory, loaded.descriptor());
            if (!eligible(client) || !CaptureFrame.fresh(loaded.metadata().time(), now()))
                throw new IOException("Capture or private world expired while loading");
            texture = new NativeImageBackedTexture(color);
            color = null; // The texture now owns the NativeImage.
            client.getTextureManager().registerTexture(TEXTURE, texture);
            texture = null; // The texture manager now owns the texture.
            frame = loaded.metadata();
            world = client.world;
            source.sendFeedback(Text.literal("EVE capture loaded: one still, expiring 30 seconds after capture. "
                    + "Cutout preview; partial hair and transparency are unsupported."));
            return 1;
        } catch (IOException | RuntimeException failure) {
            source.sendError(Text.literal("EVE capture preview failed: " + failure.getMessage()));
            return 0;
        } finally {
            if (color != null) color.close();
            if (mask != null) mask.close();
            if (texture != null) texture.close();
        }
    }

    private static void off() {
        if (frame != null) MinecraftClient.getInstance().getTextureManager().destroyTexture(TEXTURE);
        frame = null;
        world = null;
    }

    private static void render(DrawContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (frame == null) return;
        if (!valid(client)) { off(); return; }
        int width = Math.min(320, context.getScaledWindowWidth() - 24);
        if (width < 128) return;
        int height = width * 9 / 16, x = context.getScaledWindowWidth() - width - 12, y = 24;
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND), depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean depthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int sourceRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), targetRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int sourceAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), targetAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        ShaderProgram shader = RenderSystem.getShader();
        int previousTexture = RenderSystem.getShaderTexture(0);
        float[] tint = RenderSystem.getShaderColor().clone();
        try {
            context.draw();
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.setShaderColor(1, 1, 1, 1);
            context.fill(x - 3, y - 15, x + width + 3, y + height + 25, 0xee101010);
            for (int row = 0; row < height; row += 10) {
                for (int column = 0; column < width; column += 10) {
                    int shade = (row / 10 + column / 10) % 2 == 0 ? 0xff707070 : 0xff484848;
                    context.fill(x + column, y + row, x + Math.min(column + 10, width), y + Math.min(row + 10, height), shade);
                }
            }
            context.draw();
            RenderSystem.disableDepthTest();
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            context.drawTexture(TEXTURE, x, y, width, height, 0f, 0f, 320, 180, 320, 180);
            context.drawTextWithShadow(client.textRenderer, "EVE capture — diagnostic", x, y - 12, 0xffffffff);
            context.drawTextWithShadow(client.textRenderer, String.format(Locale.ROOT, "Still: %ds old | FOV %.1f°",
                    Math.max(0, now() - frame.time()), frame.fov()), x, y + height + 3, 0xffffffff);
            context.drawTextWithShadow(client.textRenderer, "Cutout; hair/transparency incomplete", x, y + height + 14, 0xffffcc80);
            context.draw();
        } finally {
            RenderSystem.blendFuncSeparate(sourceRgb, targetRgb, sourceAlpha, targetAlpha);
            if (blend) RenderSystem.enableBlend(); else RenderSystem.disableBlend();
            if (depth) RenderSystem.enableDepthTest(); else RenderSystem.disableDepthTest();
            RenderSystem.depthMask(depthWrite);
            RenderSystem.setShaderColor(tint[0], tint[1], tint[2], tint[3]);
            RenderSystem.setShaderTexture(0, previousTexture);
            RenderSystem.setShader(() -> shader);
        }
    }

    private static long now() { return System.currentTimeMillis() / 1000; }
}
