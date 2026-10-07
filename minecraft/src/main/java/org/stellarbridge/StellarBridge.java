package org.stellarbridge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StellarBridge implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("stellar_bridge");
    static final BridgeProtocol.Box BOUNDS = new BridgeProtocol.Box(-4.5, -2, -4.5, 4.5, 4, 4.5);
    private static Session session; // Integrated server thread only.
    private static volatile Control control; // Immutable publication to client and packet threads.
    private static volatile PauseBinding pauseBinding; // Independent of EVE pose freshness; never exposes Session.
    private static volatile boolean pauseOverridden; // A pause menu is open while the bridge keeps the world running.

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
        NativeSceneExport.initialize();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            if (environment != CommandManager.RegistrationEnvironment.INTEGRATED) return;
            dispatcher.register(CommandManager.literal("stellar")
                    .then(CommandManager.literal("start").executes(context -> start(context.getSource())))
                    .then(CommandManager.literal("stop").executes(context -> stopCommand(context.getSource())))
                    .then(CommandManager.literal("status").executes(context -> status(context.getSource()))));
        });
        ServerTickEvents.END_SERVER_TICK.register(StellarBridge::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (session != null && session.server == server) stop("World closed");
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> pauseBinding = null);
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            Control current = control;
            if (client.player != null && permits(client.player, current)) move(client.player, current.position);
        });
        LOG.info("Stellar Bridge loaded; use /stellar start in a private test world");
    }

    private static int start(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        if (!(source.getServer() instanceof IntegratedServer server) || !eligible(server, player) || !player.isAlive())
            return error(source, "Use a private, non-LAN singleplayer world in creative or survival as its local owner.");
        if (session != null) return error(source, "A bridge session is already active. Use /stellar stop first.");
        if (!player.isOnGround() || Math.abs(player.getY() - Math.floor(player.getY())) > 0.05)
            return error(source, "Stand on a full block floor before starting the bridge.");
        try {
            Path directory = Path.of(System.getProperty("stellarbridge.directory",
                    Path.of(System.getProperty("user.home"), ".local", "share", "stellar-blade-minecraft", "bridge").toString()));
            Session next = new Session(server, player, new BridgeFiles(directory));
            next.publish();
            session = next;
            pauseBinding = new PauseBinding(server, player.getUuid(), next.world.getRegistryKey().getValue().toString());
            source.sendFeedback(() -> Text.literal("Stellar Bridge ready: " + next.boxes.size()
                    + " collision boxes. Enter the imported patch in Stellar Blade to control this player's position."), false);
            LOG.info("Bridge started: session={} origin={} revision={} boxes={}", next.id, next.origin, next.revision, next.boxes.size());
            return 1;
        } catch (IOException | IllegalArgumentException exception) {
            return error(source, "Bridge start failed: " + exception.getMessage());
        }
    }

    private static boolean eligible(IntegratedServer server, ServerPlayerEntity player) {
        return player != null && MinecraftClient.getInstance().getServer() == server && !server.isRemote()
                && server.getPlayerManager().getCurrentPlayerCount() == 1 && server.isHost(player.getGameProfile())
                && !player.isSpectator() && !player.hasVehicle();
    }

    private static int stopCommand(ServerCommandSource source) {
        if (session == null) return error(source, "No bridge session is active.");
        if (source.getPlayer() != session.player) return error(source, "Only the local bridge owner can stop this session.");
        stop("Stopped by the local owner");
        return 1;
    }

    private static int status(ServerCommandSource source) {
        Session current = session;
        source.sendFeedback(() -> Text.literal(current == null ? "Stellar Bridge is inactive."
                : "Stellar Bridge: revision " + current.revision + ", " + current.boxes.size()
                    + " boxes, EVE position " + (control == null ? "waiting" : "active") + "."), false);
        return 1;
    }

    private static int error(ServerCommandSource source, String message) {
        source.sendError(Text.literal(message));
        return 0;
    }

    private static void stop(String reason) {
        Session previous = session;
        session = null;
        control = null;
        pauseBinding = null;
        AvatarCombat.restoreTime(true);
        NativeSceneExport.stop();
        if (previous == null) return;
        try { previous.files.stop(); }
        catch (IOException exception) { LOG.warn("Could not delete terrain snapshot; its heartbeat will expire", exception); }
        previous.player.sendMessage(Text.literal("Stellar Bridge stopped: " + reason), false);
        LOG.info("Bridge stopped: {}", reason);
    }

    private static void tick(MinecraftServer server) {
        AvatarCombat.restoreTime(false);
        Session current = session;
        if (current == null || current.server != server) return;
        ServerPlayerEntity live = current.server.getPlayerManager().getPlayer(current.player.getUuid());
        // A death in Minecraft keeps the link and Stellar Blade's lab: the player respawned in the session's world returns
        // to the anchor. Any other replacement (another dimension, hardcore spectator, the End's exit) ends the session.
        if (live != null && live != current.player && live.isAlive() && current.player.isDead()
                && live.getServerWorld() == current.world && eligible(current.server, live)) current.revive(live);
        boolean dead = !current.player.isAlive();
        if (live != current.player || !eligible(current.server, current.player) || !dead && current.player.getServerWorld() != current.world) {
            stop("The original private session is no longer available");
            return;
        }
        try {
            if (++current.ticks % 4 == 0) current.publish();
            if (dead) { // The terrain heartbeat goes on; EVE drives nobody until the respawn.
                control = null;
                return;
            }
            BridgeProtocol.Eve eve = current.gate.accept(current.files.readEve(current.id), current.revision, now());
            if (eve == null) {
                control = null;
                return;
            }
            Vec3d position = current.origin.add(eve.x(), eve.y(), eve.z());
            control = new Control(current.server, current.player, current.world.getRegistryKey().getValue().toString(),
                    position, eve.time(), System.nanoTime(), new RenderingContext(current.id, current.revision,
                            new CaptureFrame.Position(current.origin.x, current.origin.y, current.origin.z),
                            current.world.getRegistryKey().getValue().toString(), current.player.getUuid()));
            move(current.player, position);
        } catch (IOException | IllegalArgumentException exception) {
            stop(exception.getMessage());
        }
    }

    private static void move(Entity entity, Vec3d position) {
        entity.setPosition(position);
        entity.setVelocity(Vec3d.ZERO);
        entity.fallDistance = 0;
    }

    public static boolean overrides(ClientPlayerEntity player) { return permits(player, control); }

    /** Keep the real private server ticking while its bridge is active, including pause screens from focus loss. */
    public static boolean keepsWorldRunning(MinecraftClient client) {
        PauseBinding current = pauseBinding;
        IntegratedServer server = client.getServer();
        return current != null && server == current.server && !server.isRemote()
                && client.world != null && client.player != null && client.player.getUuid().equals(current.owner)
                && client.world.getRegistryKey().getValue().toString().equals(current.dimension)
                && !client.player.isSpectator() && client.player.isAlive() && !client.player.hasVehicle();
    }

    public static void pauseOverridden(boolean overridden) { pauseOverridden = overridden; }

    /**
     * Server thread: the bridge session's player while EVE cannot answer, behind a pause menu the world keeps running
     * through, without a live link (EVE outside the patch, stale packets), or while Stellar Blade withholds her input
     * (its own pause or a cutscene keeps the link live).
     */
    static boolean defenseless(ServerPlayerEntity player) {
        PauseBinding current = pauseBinding;
        return current != null && player.getUuid().equals(current.owner) && (pauseOverridden || !overrides(player) || AvatarView.halted());
    }

    private record PauseBinding(IntegratedServer server, UUID owner, String dimension) { }

    /** Immutable server publication; no live Session or entity is exposed to the renderer. */
    static RenderingContext renderingContext() {
        Control current = control;
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && permits(client.player, current) && !client.player.isSpectator()
                && client.player.isAlive() && !client.player.hasVehicle()
                && current.server.getPlayerManager().getCurrentPlayerCount() == 1 ? current.rendering : null;
    }

    private static boolean permits(ClientPlayerEntity player, Control current) {
        MinecraftClient client = MinecraftClient.getInstance();
        return live(current) && client.player == player && client.getServer() == current.server
                && client.world != null && player.getUuid().equals(current.owner.getUuid())
                && client.world.getRegistryKey().getValue().toString().equals(current.dimension)
                && !current.server.isRemote();
    }

    public static boolean overrides(ServerPlayerEntity player) {
        Control current = control;
        return live(current) && current.owner == player && !current.server.isRemote();
    }

    /**
     * Server thread: tells Stellar Blade the defense at an impact (eve-combat.txt), with the direction of the attack's
     * origin when known, so EVE's own game can answer it.
     */
    static void combatEvent(String event, Vec3d from) {
        Session current = session;
        if (current == null) return;
        double yaw = from == null ? Double.NaN // Unreal X, Y are Minecraft X, Z; yaw turns from +X toward +Y.
                : Math.toDegrees(Math.atan2(from.z - current.player.getZ(), from.x - current.player.getX()));
        try {
            current.files.write("eve-combat.txt", BridgeProtocol.encodeCombat(current.id, ++current.combatSeq, System.currentTimeMillis(), event, yaw));
        } catch (IOException | IllegalArgumentException failure) {
            LOG.warn("Combat event not delivered to Stellar Blade: {}", failure.toString());
        }
    }

    /**
     * Render thread: the lock-on target's centre for Stellar Blade (eve-lock.txt), null once released; written on the
     * server thread, which owns the session.
     */
    static void lockTarget(Vec3d center) {
        var server = MinecraftClient.getInstance().getServer();
        if (server == null) return;
        long time = System.currentTimeMillis();
        server.execute(() -> {
            Session current = session;
            if (current == null) return;
            double[] relative = center == null ? null : new double[] {center.x - current.origin.x, center.y - current.origin.y, center.z - current.origin.z};
            try {
                current.files.write("eve-lock.txt", BridgeProtocol.encodeLock(current.id, ++current.lockSeq, time, relative));
            } catch (IOException | IllegalArgumentException failure) {
                LOG.warn("Lock-on not delivered to Stellar Blade: {}", failure.toString());
            }
        });
    }

    /** EVE's blade struck an entity the client saw: Minecraft damages it as the bridged player's attack, on the server thread. */
    static void strike(int entityId, float amount, double knockback, boolean charged) {
        Control current = control;
        if (current == null) return;
        current.server.execute(() -> {
            if (overrides(current.owner)) AvatarCombat.strike(current.owner, entityId, amount, knockback, charged);
        });
    }

    public static void keepLocalLook(ServerPlayerEntity player, PlayerMoveC2SPacket packet) {
        Control current = control;
        if (current == null || !packet.changesLook()) return;
        float yaw = packet.getYaw(0), pitch = packet.getPitch(0);
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        // The packet callback can run on the connection thread; entity changes belong to the server.
        current.server.execute(() -> {
            if (overrides(player)) {
                player.setYaw(MathHelper.wrapDegrees(yaw));
                player.setPitch(MathHelper.clamp(pitch, -90, 90));
            }
        });
    }

    private static boolean live(Control current) {
        return current != null && BridgeProtocol.fresh(current.packetTime, now())
                && System.nanoTime() - current.updatedNanos < 1_000_000_000L;
    }

    private static long now() { return System.currentTimeMillis() / 1000; }

    private record Control(IntegratedServer server, ServerPlayerEntity owner, String dimension,
                           Vec3d position, long packetTime, long updatedNanos, RenderingContext rendering) { }

    record RenderingContext(String session, int revision, CaptureFrame.Position origin, String dimension, UUID owner) { }

    private static final class Session {
        final IntegratedServer server;
        ServerPlayerEntity player; // Replaced by the respawned player after a death.
        final ServerWorld world;
        final BridgeFiles files;
        final String id = UUID.randomUUID().toString().replace("-", "");
        final Vec3d origin;
        final PoseGate gate = new PoseGate(id, BOUNDS);
        List<BridgeProtocol.Box> boxes = List.of();
        int revision;
        int ticks;
        long combatSeq, lockSeq;

        Session(IntegratedServer server, ServerPlayerEntity player, BridgeFiles files) {
            this.server = server;
            this.player = player;
            this.world = player.getServerWorld();
            this.files = files;
            this.origin = new Vec3d(Math.floor(player.getX()) + 0.5, Math.floor(player.getY()), Math.floor(player.getZ()) + 0.5);
            Box entry = player.getBoundingBox().offset(origin.subtract(player.getPos()));
            if (!world.isSpaceEmpty(player, entry)) throw new IllegalArgumentException("The center of the starting block must be clear");
            BlockPos floor = BlockPos.ofFloored(origin.x, origin.y - 0.01, origin.z);
            VoxelShape support = world.getBlockState(floor).getCollisionShape(world, floor, ShapeContext.of(player));
            if (support.isEmpty() || Math.abs(support.getMax(Direction.Axis.Y) - 1) > 0.00001)
                throw new IllegalArgumentException("The starting block must have a full-height collision floor");
        }

        void revive(ServerPlayerEntity respawned) {
            player = respawned;
            respawned.teleport(world, origin.x, origin.y, origin.z, respawned.getYaw(), respawned.getPitch());
            // Vanilla recentres chunk loading and sending only from move packets, which the bridge cancels.
            world.getChunkManager().updatePosition(respawned);
            LOG.info("Bridge kept through a death: the respawned player returns to the anchor");
        }

        void publish() throws IOException {
            // The patch must stay loaded without the player entity: a dead player leaves the world after a second. The
            // ticket lasts 5 ticks and is renewed every 4, so it lapses by itself once the session ends.
            world.getChunkManager().addTicket(ChunkTicketType.POST_TELEPORT, new ChunkPos(BlockPos.ofFloored(origin)), 1, player.getId());
            List<VoxelShape> shapes = new ArrayList<>();
            int x0 = MathHelper.floor(origin.x), y0 = MathHelper.floor(origin.y), z0 = MathHelper.floor(origin.z);
            // Include one neighboring block for collision shapes that protrude across the fixed patch boundary.
            for (int x = x0 - 5; x <= x0 + 5; x++) {
                for (int z = z0 - 5; z <= z0 + 5; z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) throw new IllegalArgumentException("All test-area chunks must remain loaded");
                    for (int y = y0 - 3; y <= y0 + 4; y++) {
                        BlockPos position = new BlockPos(x, y, z);
                        if (world.isOutOfHeightLimit(position)) throw new IllegalArgumentException("The test area exceeds the world height limit");
                        if (!world.getFluidState(position).isEmpty()) throw new IllegalArgumentException("Use a dry test area without fluids");
                        VoxelShape shape = world.getBlockState(position).getCollisionShape(world, position, ShapeContext.of(player));
                        if (!shape.isEmpty()) shapes.add(shape.offset(x - origin.x, y - origin.y, z - origin.z));
                    }
                }
            }
            List<BridgeProtocol.Box> next = TerrainGeometry.merge(shapes, BOUNDS);
            int nextRevision = revision == 0 || !next.equals(boxes) ? Math.addExact(revision, 1) : revision;
            files.publish(BridgeProtocol.encodeTerrain(new BridgeProtocol.Terrain(id, nextRevision, now(),
                    world.getRegistryKey().getValue().toString(), origin.x, origin.y, origin.z, BOUNDS, next)));
            if (nextRevision != revision) LOG.info("Terrain revision={} boxes={}", nextRevision, next.size());
            boxes = next;
            revision = nextRevision;
            NativeSceneExport.capture(server, world, player.getUuid(), new NativeScene.Binding(id, revision,
                    world.getRegistryKey().getValue().toString(), new CaptureFrame.Position(origin.x, origin.y, origin.z)));
        }
    }
}
