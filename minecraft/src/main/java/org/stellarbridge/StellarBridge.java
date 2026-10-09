package org.stellarbridge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StellarBridge implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("stellar_bridge");

    @Override
    public void onInitializeClient() {
        try {
            Path directory = Path.of(System.getProperty("stellarbridge.directory",
                    Path.of(System.getProperty("user.home"), ".local", "share", "stellar-blade-minecraft", "bridge").toString()));
            new BridgeFiles(directory);
            CaptureFrame.startWatching(directory);
            LOG.info("Asynchronous capture close notifications armed");
        } catch (IOException | RuntimeException | LinkageError failure) {
            LOG.warn("Asynchronous capture unavailable; synchronous captures remain supported: {}", failure.toString());
        }
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> CaptureFrame.stopWatching());
        CapturePreview.initialize();
        CaptureView.initialize();
        AvatarView.initialize();
        AvatarHudFile.initialize();
        NativeSceneExport.initialize();
        // The handshake: a modded server's hello gets this client's protocol and version back; the server judges them.
        ClientConfigurationNetworking.registerGlobalReceiver(BridgeNet.Hello.ID, (hello, context) ->
                context.responseSender().sendPacket(new BridgeNet.Hello(BridgeNet.PROTOCOL, BridgeNet.version())));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> BridgeServer.pauseBinding = null);
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            BridgeServer.Control current = BridgeServer.control;
            if (client.player != null && permits(client.player, current)) BridgeServer.move(client.player, current.position());
        });
        LOG.info("Stellar Bridge loaded; use /stellar start in a private test world");
    }

    public static boolean overrides(ClientPlayerEntity player) { return permits(player, BridgeServer.control); }

    /** Keep the real private server ticking while its bridge is active, including pause screens from focus loss. */
    public static boolean keepsWorldRunning(MinecraftClient client) {
        BridgeServer.PauseBinding current = BridgeServer.pauseBinding;
        IntegratedServer server = client.getServer();
        return current != null && server == current.server() && !server.isRemote()
                && client.world != null && client.player != null && client.player.getUuid().equals(current.owner())
                && client.world.getRegistryKey().getValue().toString().equals(current.dimension())
                && !client.player.isSpectator() && client.player.isAlive() && !client.player.hasVehicle();
    }

    public static void pauseOverridden(boolean overridden) { BridgeServer.pauseOverridden = overridden; }

    /** Immutable server publication; no live Session or entity is exposed to the renderer. */
    static RenderingContext renderingContext() {
        BridgeServer.Control current = BridgeServer.control;
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && permits(client.player, current) && !client.player.isSpectator()
                && client.player.isAlive() && !client.player.hasVehicle()
                && current.server().getPlayerManager().getCurrentPlayerCount() == 1
                ? new RenderingContext(current.session(), current.revision(), new CaptureFrame.Position(current.origin().x,
                        current.origin().y, current.origin().z), current.dimension(), current.owner().getUuid()) : null;
    }

    private static boolean permits(ClientPlayerEntity player, BridgeServer.Control current) {
        MinecraftClient client = MinecraftClient.getInstance();
        return BridgeServer.live(current) && client.player == player && client.getServer() == current.server()
                && client.world != null && player.getUuid().equals(current.owner().getUuid())
                && client.world.getRegistryKey().getValue().toString().equals(current.dimension())
                && !current.server().isRemote();
    }

    /**
     * Render thread: the lock-on target's centre for Stellar Blade (eve-lock.txt), null once released; written on the
     * server thread, which owns the session.
     */
    static void lockTarget(Vec3d center) {
        var server = MinecraftClient.getInstance().getServer();
        if (server == null) return;
        long time = System.currentTimeMillis();
        server.execute(() -> BridgeServer.lockTarget(center, time));
    }

    /**
     * EVE's blade or drone (shot) struck an entity the client saw: Minecraft damages it as the bridged player's attack, on
     * the server thread.
     */
    static void strike(int entityId, float amount, double knockback, boolean charged, boolean first, boolean shot) {
        BridgeServer.Control current = BridgeServer.control;
        if (current == null) return;
        current.server().execute(() -> {
            if (BridgeServer.overrides(current.owner())) AvatarCombat.strike(current.owner(), entityId, amount, knockback, charged, first, shot);
        });
    }

    record RenderingContext(String session, int revision, CaptureFrame.Position origin, String dimension, UUID owner) { }
}
