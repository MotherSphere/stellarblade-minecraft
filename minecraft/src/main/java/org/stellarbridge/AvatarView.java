package org.stellarbridge;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.render.LightmapTextureManager;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.slf4j.LoggerFactory;

/** EVE drawn by Minecraft from her bone poses. The first source is a looping pose probe recording; the camera stays Minecraft's. */
public final class AvatarView {
    private static Replay active; // Render thread only.

    private record Replay(ClientWorld world, AvatarRecording recording, AvatarModel model, AvatarRenderer renderer,
                          AvatarRecording.Pose pose, Vec3d anchor, Vector3d origin, long started) { }

    static void initialize() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(
                ClientCommandManager.literal("stellaravatar")
                        .then(ClientCommandManager.literal("replay").executes(context -> replay(context.getSource())))
                        .then(ClientCommandManager.literal("off").executes(context -> {
                            off();
                            context.getSource().sendFeedback(Text.literal("EVE avatar removed."));
                            return 1;
                        }))));
        WorldRenderEvents.AFTER_ENTITIES.register(AvatarView::render);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(AvatarView::off));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> off());
    }

    private static int replay(FabricClientCommandSource source) {
        off();
        var player = source.getClient().player;
        if (player == null || source.getClient().world == null) return error(source, "Enter a world first.");
        Path bridge = Path.of(System.getProperty("stellarbridge.directory",
                Path.of(System.getProperty("user.home"), ".local", "share", "stellar-blade-minecraft", "bridge").toString()));
        Path pack = Path.of(System.getProperty("stellarbridge.avatar",
                Path.of(System.getProperty("user.home"), ".local", "share", "stellar-blade-minecraft", "avatar").toString()));
        AvatarRenderer renderer = null;
        try {
            long loading = System.nanoTime();
            AvatarRecording recording = AvatarRecording.read(bridge);
            AvatarModel model = AvatarModel.load(pack, recording);
            if (model.parts.isEmpty()) return error(source, "No recorded EVE mesh is in the avatar pack " + pack + ".");
            renderer = new AvatarRenderer(model, pack);
            float[] root = recording.frames[0].world()[model.parts.get(0).component()];
            Vec3d anchor = player.getPos().add(Vec3d.fromPolar(0, player.getYaw()).multiply(2));
            active = new Replay(source.getClient().world, recording, model, renderer, recording.emptyPose(), anchor,
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

    private static void render(WorldRenderContext context) {
        Replay replay = active;
        if (replay == null) return;
        if (context.world() != replay.world) { off(); return; }
        try {
            replay.recording.sample((System.nanoTime() - replay.started) / 1e9, replay.pose);
            Vec3d camera = context.camera().getPos();
            Matrix4d placement = new Matrix4d().translation(replay.anchor.x - camera.x, replay.anchor.y - camera.y, replay.anchor.z - camera.z)
                    .mul(AvatarModel.UNREAL_TO_MINECRAFT).translate(-replay.origin.x, -replay.origin.y, -replay.origin.z);
            replay.model.palettes(replay.pose, placement, replay.renderer.palettes);
            float[] root = replay.pose.world()[replay.model.parts.get(0).component()];
            Vector3d feet = placement.transformPosition(new Vector3d(root[4], root[5], root[6])).add(camera.x, camera.y, camera.z);
            int light = WorldRenderer.getLightmapCoordinates(replay.world, BlockPos.ofFloored(feet.x, feet.y + 1, feet.z));
            Matrix4f projectionView = new Matrix4f(context.projectionMatrix()).mul(context.positionMatrix());
            replay.renderer.draw(projectionView, lightmap(context.lightmapTextureManager()),
                    LightmapTextureManager.getBlockLightCoordinates(light), LightmapTextureManager.getSkyLightCoordinates(light),
                    RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(), RenderSystem.getShaderFogColor());
        } catch (RuntimeException failure) {
            off();
            LoggerFactory.getLogger("stellar_bridge").error("EVE avatar renderer stopped", failure);
            var player = net.minecraft.client.MinecraftClient.getInstance().player;
            if (player != null) player.sendMessage(Text.literal("EVE avatar renderer stopped: " + failure.getMessage()), false);
        }
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

    private static void off() {
        Replay previous = active;
        active = null;
        if (previous != null) previous.renderer.close();
    }

    private static int error(FabricClientCommandSource source, String message) {
        source.sendError(Text.literal(message));
        return 0;
    }
}
