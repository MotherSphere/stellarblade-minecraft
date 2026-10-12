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
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientConfigurationNetworking;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StellarBridge implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("stellar_bridge");
    private static BridgeLink link; // Client thread only: this player's link, from the server's link payload.
    // Client thread (Minecraft renders on it): EVE's stats and heal count as last read, whether Stellar Blade withholds her
    // input, the status last sent, the server's feedback, the tint's end after a perfect dodge, and the presses last sent.
    private static AvatarStats.Values stats;
    private static long heals = -1;
    private static boolean halted;
    private static BridgeNet.Status sentStatus;
    private static final BridgeNet.Feedback NO_FEEDBACK = new BridgeNet.Feedback(0, 0, 0, 0);
    private static BridgeNet.Feedback feedback = NO_FEEDBACK;
    private static long tintUntil = System.nanoTime();
    private static long dodgeSent = Long.MIN_VALUE, guardSent = Long.MIN_VALUE;
    private static boolean guardHeld;
    private static KeyBinding playKey;
    private static final PlaySequence play = new PlaySequence(new PlaySequence.Client() {
        public boolean server() { return ClientPlayNetworking.canSend(BridgeNet.LinkRequest.ID); }
        public String start() {
            // BridgeServer.start's checks, so its refusal stops the sequence instead of a terrain timeout 10 s later.
            var player = MinecraftClient.getInstance().player;
            if (player == null || !player.isAlive() || player.isSpectator() || player.hasVehicle())
                return "Start the bridge alive, in creative or survival, and not riding anything.";
            if (!player.isOnGround() || Math.abs(player.getY() - Math.floor(player.getY())) > 0.05)
                return "Stand on a full block floor before starting the bridge.";
            ClientPlayNetworking.send(new BridgeNet.LinkRequest(BridgeNet.LinkRequest.START));
            return null;
        }
        public int revision() { return link == null ? -1 : link.revision(); }
        public String state() { return link == null ? "idle" : link.state(System.currentTimeMillis()); }
        public String request(String action) {
            if (link == null) return "The link ended.";
            try { return link.request(action, System.currentTimeMillis(), System.nanoTime()); }
            catch (IOException | IllegalArgumentException failure) { return "Request not delivered to Stellar Blade: " + failure.getMessage(); }
        }
        public boolean placed() { return renderingContext() != null; }
        public boolean playing() { return AvatarView.playing(); }
        public void play() {
            var handler = MinecraftClient.getInstance().getNetworkHandler();
            if (handler != null) AvatarView.play((FabricClientCommandSource) handler.getCommandSource()); // Fabric's mixin implements it.
        }
        public void say(String line) {
            var player = MinecraftClient.getInstance().player;
            if (player != null) player.sendMessage(Text.literal(line), false);
        }
    });

    @Override
    public void onInitializeClient() {
        try {
            Path directory = Path.of(System.getProperty("stellarbridge.directory",
                    Path.of(System.getProperty("user.home"), ".local", "share", "stellar-blade-minecraft", "bridge").toString()));
            new BridgeFiles(directory);
            if (com.sun.jna.Platform.isLinux()) {
                CaptureFrame.startWatching(directory);
                LOG.info("Asynchronous capture close notifications armed");
            } else LOG.info("Capture mode is Linux-only; EVE plays in avatar mode (/stellaravatar play)");
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
        playKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.stellar_bridge.play", GLFW.GLFW_KEY_G, "key.categories.stellar_bridge"));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(ClientCommandManager.literal("stellar")
                .then(ClientCommandManager.literal("play").executes(context -> {
                    if (play.begin(System.nanoTime())) return 1;
                    context.getSource().sendError(Text.literal("Play as EVE is already starting."));
                    return 0;
                }))
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
        ClientPlayNetworking.registerGlobalReceiver(BridgeNet.Feedback.ID, (payload, context) -> feedback = payload);
        ClientPlayNetworking.registerGlobalReceiver(BridgeNet.Verdict.ID, (payload, context) -> {
            // A perfect dodge's cold tint, as long as the slow motion it brings when the player is alone on the server.
            if (payload.event() == BridgeProtocol.COMBAT_EVENTS.indexOf("perfect_dodge")) tintUntil = System.nanoTime() + AvatarCombat.SLOW_NANOS;
            BridgeLink current = link;
            if (current == null) return;
            try { current.verdict(payload.event(), payload.yaw(), System.currentTimeMillis()); }
            catch (IOException | IllegalArgumentException failure) { LOG.warn("Combat event not delivered to Stellar Blade: {}", failure.toString()); }
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            BridgeServer.pauseBinding = null;
            sentStatus = null; // The next server gets the status anew, and counts gauge points from zero.
            feedback = NO_FEEDBACK;
            play.cancel();
            end();
        });
        ClientTickEvents.START_CLIENT_TICK.register(StellarBridge::tick);
        LOG.info("Stellar Bridge loaded; use /stellar start in a world or on a server with the mod");
    }

    /**
     * Each client tick: EVE's status when it changed, the Play as EVE key, Stellar Blade's answer to a request (to /stellar
     * play while it runs), the relay of a new eve.N packet, then the predicted position.
     */
    private static void tick(MinecraftClient client) {
        var status = new BridgeNet.Status(stats, heals, halted);
        if (!status.equals(sentStatus) && ClientPlayNetworking.canSend(BridgeNet.Status.ID)) {
            ClientPlayNetworking.send(status);
            sentStatus = status;
        }
        while (playKey.wasPressed()) play.begin(System.nanoTime()); // Ignored while it runs.
        BridgeLink current = link;
        BridgeLink.Answer answer = current == null ? null : current.answer(System.nanoTime());
        if (play.running()) play.tick(answer, System.nanoTime());
        else if (answer != null && client.player != null) client.player.sendMessage(Text.literal(answer.chat()), false);
        if (current == null) return;
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
     * Render thread: EVE's blade (in a trail style, null for the swing heuristic) or her drone (shot: the point it hit)
     * struck an entity the client saw; the server validates it and computes the damage.
     */
    static void strike(int entityId, AvatarTrail.Style style, boolean charged, boolean first, Vec3d shot) {
        if (!ClientPlayNetworking.canSend(BridgeNet.Strike.ID)) return;
        int flags = (charged ? BridgeNet.Strike.CHARGED : 0) | (first ? BridgeNet.Strike.FIRST : 0)
                | (AvatarCombat.quiet(System.nanoTime()) ? BridgeNet.Strike.QUIET : 0);
        ClientPlayNetworking.send(new BridgeNet.Strike(entityId, shot == null ? BridgeNet.Strike.style(style) : BridgeNet.Strike.SHOT, (byte) flags, shot));
    }

    /**
     * Render thread, while EVE's keys drive Stellar Blade (MIN_VALUE presses and a released guard otherwise): the dodge and
     * guard keys' latest presses (System.nanoTime) and whether the guard is held. Each new press and the guard's release
     * go to the server as defense edges, stamped when they happened, so it can place them before their arrival.
     */
    static void inputs(long dodgePressed, long guardPressed, boolean guardDown) {
        if (dodgePressed != Long.MIN_VALUE && dodgePressed != dodgeSent) {
            dodgeSent = dodgePressed;
            defense(BridgeNet.Defense.DODGE, dodgePressed);
        }
        if (guardPressed != Long.MIN_VALUE && guardPressed != guardSent) {
            guardSent = guardPressed;
            guardHeld = true;
            defense(BridgeNet.Defense.GUARD_DOWN, guardPressed);
        }
        if (guardHeld && !guardDown) {
            guardHeld = false;
            defense(BridgeNet.Defense.GUARD_UP, System.nanoTime());
        }
    }

    private static void defense(byte edge, long at) {
        if (ClientPlayNetworking.canSend(BridgeNet.Defense.ID)) ClientPlayNetworking.send(new BridgeNet.Defense(edge, at, System.nanoTime()));
    }

    /** Render thread: EVE's stats while she is the bridged player (null otherwise), sent with the next status. */
    static void stats(float[] values) { stats = AvatarStats.Values.of(values); }
    /** Render thread: the adapter's heal count (-1 once the stream stops), sent with the next status. */
    static void heals(long counter) { heals = counter; }
    /** Render thread: whether Stellar Blade withholds EVE's input (paused, a cutscene), sent with the next status. */
    static void halted(boolean withheld) { halted = withheld; }
    /** Render thread: EVE's stats as last read, BASE without them. */
    static AvatarStats.Values stats() { return stats == null ? AvatarStats.Values.BASE : stats; }
    /** Render thread: the server's latest health share, heal acknowledgement and gauge points for the adapter. */
    static BridgeNet.Feedback feedback() { return feedback; }
    /** Render thread: whether a perfect dodge's tint shows. */
    static boolean slowMotion() { return System.nanoTime() - tintUntil < 0; }

    record RenderingContext(String session, int revision, CaptureFrame.Position origin, String dimension, UUID owner) { }
}
