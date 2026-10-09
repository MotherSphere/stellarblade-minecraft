package org.stellarbridge;

import com.mojang.brigadier.context.CommandContext;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StellarBridge implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("stellar_bridge");
    private static BridgeLink link; // Client thread only: this player's link, from the server's link payload.

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
        // The server checks start, stop and status for this player; import, enter and return only talk to this PC's Stellar Blade.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(ClientCommandManager.literal("stellar")
                .then(ClientCommandManager.literal("start").executes(context -> link(context, BridgeNet.LinkRequest.START)))
                .then(ClientCommandManager.literal("stop").executes(context -> link(context, BridgeNet.LinkRequest.STOP)))
                .then(ClientCommandManager.literal("status").executes(context -> link(context, BridgeNet.LinkRequest.STATUS)))
                .then(ClientCommandManager.literal("import").executes(context -> request(context.getSource(), "import")))
                .then(ClientCommandManager.literal("enter").executes(context -> request(context.getSource(), "enter")))
                .then(ClientCommandManager.literal("return").executes(context -> request(context.getSource(), "return")))));
        ClientPlayNetworking.registerGlobalReceiver(BridgeNet.Link.ID, (payload, context) -> linked(payload));
        ClientPlayNetworking.registerGlobalReceiver(BridgeNet.Terrain.ID, (payload, context) -> {
            BridgeLink current = link;
            if (current == null) return;
            try { current.terrain(payload.snapshot(), System.currentTimeMillis()); }
            catch (IOException | IllegalArgumentException failure) { fail(failure); }
        });
        ClientPlayNetworking.registerGlobalReceiver(BridgeNet.Mobs.ID, (payload, context) -> {
            BridgeLink current = link;
            if (current == null) return;
            try { current.mobs(payload.boxes(), System.currentTimeMillis()); }
            catch (IOException | IllegalArgumentException failure) { LOG.warn("Mob walls not delivered to Stellar Blade: {}", failure.toString()); }
        });
        ClientPlayNetworking.registerGlobalReceiver(BridgeNet.Verdict.ID, (payload, context) -> {
            BridgeLink current = link;
            if (current == null) return;
            try { current.verdict(payload.event(), payload.yaw(), System.currentTimeMillis()); }
            catch (IOException | IllegalArgumentException failure) { LOG.warn("Combat event not delivered to Stellar Blade: {}", failure.toString()); }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            BridgeServer.pauseBinding = null;
            end();
        });
        ClientTickEvents.START_CLIENT_TICK.register(StellarBridge::tick);
        LOG.info("Stellar Bridge loaded; use /stellar start in a world or on a server with the mod");
    }

    /** Each client tick: Stellar Blade's answer to a request, the relay of a new eve.N packet, then the predicted position. */
    private static void tick(MinecraftClient client) {
        BridgeLink current = link;
        if (current == null) return;
        String answer = current.answer(System.nanoTime());
        if (answer != null && client.player != null) client.player.sendMessage(Text.literal(answer), false);
        try {
            String relay = current.tick(System.currentTimeMillis());
            if (relay != null && ClientPlayNetworking.canSend(BridgeNet.Eve.ID))
                ClientPlayNetworking.send(new BridgeNet.Eve(relay, System.currentTimeMillis()));
        } catch (IOException | IllegalArgumentException failure) {
            fail(failure);
            return;
        }
        if (client.player != null && overrides(client.player)) BridgeServer.move(client.player, current.position());
    }

    private static int link(CommandContext<FabricClientCommandSource> context, byte action) {
        if (!ClientPlayNetworking.canSend(BridgeNet.LinkRequest.ID)) {
            context.getSource().sendError(Text.literal("This server does not run Stellar Bridge; the link needs the mod on the server too."));
            return 0;
        }
        ClientPlayNetworking.send(new BridgeNet.LinkRequest(action));
        return 1;
    }

    private static int request(FabricClientCommandSource source, String action) {
        BridgeLink current = link;
        if (current == null) {
            source.sendError(Text.literal("No bridge session is active. Use /stellar start first."));
            return 0;
        }
        try {
            String refused = current.request(action, System.currentTimeMillis(), System.nanoTime());
            if (refused != null) {
                source.sendError(Text.literal(refused));
                return 0;
            }
            source.sendFeedback(Text.literal("Sent /stellar " + action + " to Stellar Blade."));
            return 1;
        } catch (IOException | IllegalArgumentException failure) {
            source.sendError(Text.literal("Request not delivered to Stellar Blade: " + failure.getMessage()));
            return 0;
        }
    }

    /** The server's link payload: a new session replaces any link, an empty one ends it. */
    private static void linked(BridgeNet.Link payload) {
        end();
        if (payload.session().isEmpty()) {
            LOG.info("Bridge link ended: {}", payload.message());
            return;
        }
        try {
            link = new BridgeLink(new BridgeFiles(AvatarView.bridgeDirectory()), payload.session(), payload.origin(), payload.dimension());
            LOG.info("Bridge linked: session={} origin={} dimension={}", payload.session(), payload.origin(), payload.dimension());
        } catch (IOException failure) {
            fail(failure);
        }
    }

    /** This PC's bridge directory failed the link: it ends here, and the server is asked to end it too. */
    private static void fail(Exception failure) {
        LOG.warn("Bridge link failed", failure);
        end();
        var player = MinecraftClient.getInstance().player;
        if (player != null) player.sendMessage(Text.literal("Stellar Bridge stopped: " + failure.getMessage()), false);
        if (ClientPlayNetworking.canSend(BridgeNet.LinkRequest.ID)) ClientPlayNetworking.send(new BridgeNet.LinkRequest(BridgeNet.LinkRequest.STOP));
    }

    private static void end() {
        BridgeLink previous = link;
        link = null;
        if (previous == null) return;
        try { previous.stop(); }
        catch (IOException exception) { LOG.warn("Could not delete terrain snapshot; its heartbeat will expire", exception); }
    }

    /** Whether the bridge drives this client's player: a live link in the player's dimension. */
    public static boolean overrides(ClientPlayerEntity player) {
        BridgeLink current = link;
        MinecraftClient client = MinecraftClient.getInstance();
        return current != null && current.position() != null && client.player == player && player.isAlive() && client.world != null
                && client.world.getRegistryKey().getValue().toString().equals(current.dimension);
    }

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

    /** The live link for the renderer; no BridgeLink or entity is exposed. */
    static RenderingContext renderingContext() {
        BridgeLink current = link;
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && overrides(client.player) && !client.player.isSpectator() && !client.player.hasVehicle()
                ? new RenderingContext(current.session, current.revision(), new CaptureFrame.Position(current.origin.x,
                        current.origin.y, current.origin.z), current.dimension, client.player.getUuid()) : null;
    }

    /** Render thread: the lock-on target's centre for Stellar Blade (eve-lock.txt), null once released. */
    static void lockTarget(Vec3d center) {
        BridgeLink current = link;
        if (current == null) return;
        try { current.lock(center, System.currentTimeMillis()); }
        catch (IOException | IllegalArgumentException failure) { LOG.warn("Lock-on not delivered to Stellar Blade: {}", failure.toString()); }
    }

    /**
     * EVE's blade or drone (shot) struck an entity the client saw: Minecraft damages it as the bridged player's attack, on
     * this JVM's integrated server thread (singleplayer and a LAN host, until strikes travel as payloads).
     */
    static void strike(int entityId, float amount, double knockback, boolean charged, boolean first, boolean shot) {
        MinecraftClient client = MinecraftClient.getInstance();
        IntegratedServer server = client.getServer();
        if (server == null || client.player == null) return;
        UUID owner = client.player.getUuid();
        server.execute(() -> {
            var player = server.getPlayerManager().getPlayer(owner);
            if (player != null && BridgeServer.overrides(player)) AvatarCombat.strike(player, entityId, amount, knockback, charged, first, shot);
        });
    }

    record RenderingContext(String session, int revision, CaptureFrame.Position origin, String dimension, UUID owner) { }
}
