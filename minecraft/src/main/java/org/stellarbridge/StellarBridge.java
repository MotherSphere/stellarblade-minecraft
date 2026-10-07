package org.stellarbridge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.DoubleStream;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
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
import net.minecraft.util.shape.VoxelShapes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StellarBridge implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("stellar_bridge");
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
        AvatarItems.initialize();
        NativeSceneExport.initialize();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            if (environment != CommandManager.RegistrationEnvironment.INTEGRATED) return;
            dispatcher.register(CommandManager.literal("stellar")
                    .then(CommandManager.literal("start").executes(context -> start(context.getSource())))
                    .then(CommandManager.literal("stop").executes(context -> stopCommand(context.getSource())))
                    .then(CommandManager.literal("import").executes(context -> request(context.getSource(), "import")))
                    .then(CommandManager.literal("enter").executes(context -> request(context.getSource(), "enter")))
                    .then(CommandManager.literal("return").executes(context -> request(context.getSource(), "return")))
                    .then(CommandManager.literal("status").executes(context -> status(context.getSource()))));
        });
        ServerTickEvents.END_SERVER_TICK.register(StellarBridge::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (session != null && session.server == server) stop("World closed");
        });
        // Before vanilla saves a leaving player (Save and Quit): EVE's maximum health must never reach the save.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Session current = session;
            if (current != null && current.player == handler.player) AvatarStats.apply(handler.player, AvatarStats.Values.BASE);
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

    /**
     * /stellar import, enter and return: Stellar Blade's F5, F8 and F11 through a session request (eve-request.txt), one at
     * a time; Lua's answer is shown in chat.
     */
    private static int request(ServerCommandSource source, String action) {
        Session current = session;
        if (current == null) return error(source, "No bridge session is active. Use /stellar start first.");
        if (source.getPlayer() != current.player) return error(source, "Only the local bridge owner can send requests to Stellar Blade.");
        if (current.request != null) return error(source, "Waiting for Stellar Blade's answer to /stellar " + current.request.action() + ".");
        try {
            long seq = current.requestSeq + 1;
            current.files.write("eve-request.txt", BridgeProtocol.encodeRequest(current.id, seq, System.currentTimeMillis(), action));
            current.requestSeq = seq;
            current.request = new Request(seq, action, System.nanoTime());
            source.sendFeedback(() -> Text.literal("Sent /stellar " + action + " to Stellar Blade."), false);
            return 1;
        } catch (IOException | IllegalArgumentException failure) {
            return error(source, "Request not delivered to Stellar Blade: " + failure.getMessage());
        }
    }

    private static int status(ServerCommandSource source) {
        Session current = session;
        source.sendFeedback(() -> Text.literal(current == null ? "Stellar Bridge is inactive."
                : "Stellar Bridge: revision " + current.revision + ", " + current.boxes.size()
                    + " boxes, window around block " + Session.middle(current.centre).toShortString() + ", slowest terrain publication " + current.slowestPublish / 1_000_000 + " ms, EVE position "
                    + (control == null ? "waiting" : "active") + "."), false);
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
        AvatarStats.stopHeals();
        NativeSceneExport.stop();
        if (previous == null) return;
        AvatarStats.apply(previous.player, AvatarStats.Values.BASE); // Minecraft's own maximum health again.
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
        current.answer();
        if (!dead) AvatarStats.apply(current.player, AvatarStats.current()); // A respawned player gets them anew.
        AvatarStats.heal(current.player, !dead && overrides(current.player));
        try {
            if (++current.ticks % 4 == 0) current.publish();
            if (dead) { // The terrain heartbeat goes on; EVE drives nobody until the respawn.
                control = null;
                return;
            }
            BridgeProtocol.Eve eve = current.gate.accept(current.files.readEve(current.id), current.revision, now());
            if (eve == null) {
                control = null;
                // Once she has left the link, the window returns to the anchor, where Stellar Blade imports it again.
                if (current.gate.away(now())) current.feet = current.origin;
                return;
            }
            Vec3d position = current.origin.add(eve.x(), eve.y(), eve.z());
            control = new Control(current.server, current.player, current.world.getRegistryKey().getValue().toString(),
                    position, eve.time(), System.nanoTime(), new RenderingContext(current.id, current.revision,
                            new CaptureFrame.Position(current.origin.x, current.origin.y, current.origin.z),
                            current.world.getRegistryKey().getValue().toString(), current.player.getUuid()));
            move(current.player, position);
            current.feet = position; // The terrain window follows her from the next publication.
            // Vanilla recentres chunk loading and sending only from move packets, which the bridge cancels.
            current.world.getChunkManager().updatePosition(current.player);
            AvatarCombat.shove(current.player);
            current.publishMobs();
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

    /**
     * EVE's blade or drone (shot) struck an entity the client saw: Minecraft damages it as the bridged player's attack, on
     * the server thread.
     */
    static void strike(int entityId, float amount, double knockback, boolean charged, boolean first, boolean shot) {
        Control current = control;
        if (current == null) return;
        current.server.execute(() -> {
            if (overrides(current.owner)) AvatarCombat.strike(current.owner, entityId, amount, knockback, charged, first, shot);
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

    /**
     * A session request awaiting Stellar Blade's answer. Lua takes it within 2.5 s of its millisecond TIME by a wall clock
     * in whole seconds, so never later than 3.5 s: past ANSWER it can no longer run, and the next one may go.
     */
    private record Request(long seq, String action, long sentNanos) { }
    private static final long ANSWER = 5_000_000_000L;

    /**
     * The walls Stellar Blade gets for the entities' boxes: the MOBS nearest EVE's feet (by each box's bottom centre),
     * clipped to the patch, relative to the origin, without slivers thinner than 0.01 block.
     */
    static List<BridgeProtocol.Box> mobWalls(Box patch, Vec3d origin, Vec3d feet, List<Box> boxes) {
        return boxes.stream()
                .sorted(java.util.Comparator.comparingDouble(box -> feet.squaredDistanceTo((box.minX + box.maxX) / 2, box.minY, (box.minZ + box.maxZ) / 2)))
                .limit(BridgeProtocol.MOBS)
                .map(box -> box.intersection(patch).offset(origin.negate()))
                .filter(box -> box.getLengthX() >= 0.01 && box.getLengthY() >= 0.01 && box.getLengthZ() >= 0.01)
                .map(box -> new BridgeProtocol.Box(box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ)).toList();
    }

    private static final class Session {
        final IntegratedServer server;
        ServerPlayerEntity player; // Replaced by the respawned player after a death.
        final ServerWorld world;
        final BridgeFiles files;
        final String id = UUID.randomUUID().toString().replace("-", "");
        final Vec3d origin;
        final PoseGate gate = new PoseGate(id);
        int[] centre; // The terrain window's centre cell (world); it follows EVE.
        BridgeProtocol.Box bounds; // The published window, relative to the origin.
        Vec3d feet; // EVE's feet (world) as last applied, or the origin before that.
        final Map<Long, TerrainGeometry.Cell> cells = new HashMap<>(); // Merged cells for reuse across publications.
        long heldWarned; // Wall milliseconds of the last action-bar warning about a held window.
        List<BridgeProtocol.Box> boxes = List.of();
        long slowestPublish; // Nanoseconds of the slowest terrain publication (scan, merge, write) this session.
        int revision;
        int ticks;
        long combatSeq, lockSeq, mobSeq, requestSeq;
        Request request; // The session request awaiting Stellar Blade's answer.
        boolean mobsListed; // The last eve-mobs.txt listed at least one entity.

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
            feet = origin;
            centre = new int[] {TerrainGeometry.cell(origin.x), TerrainGeometry.cell(origin.y), TerrainGeometry.cell(origin.z)};
            bounds = TerrainGeometry.window(centre, origin);
        }

        /** Each tick: Stellar Blade's answer to the pending session request, in chat; a request unanswered after ANSWER, once. */
        void answer() {
            if (request == null) return;
            BridgeProtocol.RequestAck ack = null;
            try {
                String text = files.read("eve-request-ack.txt");
                if (text != null) ack = BridgeProtocol.parseRequestAck(text);
            } catch (IOException | IllegalArgumentException unreadable) {
                // Malformed, foreign or being replaced: only a valid answer to this request counts.
            }
            if (ack != null && ack.session().equals(id) && ack.seq() == request.seq() && ack.action().equals(request.action())) {
                player.sendMessage(Text.literal("Stellar Blade " + ack.action() + " " + ack.result() + ": " + ack.message()), false);
                LOG.info("Session request {} {}: {}: {}", ack.seq(), ack.action(), ack.result(), ack.message());
                request = null;
            } else if (System.nanoTime() - request.sentNanos() > ANSWER) {
                player.sendMessage(Text.literal("Stellar Blade did not answer /stellar " + request.action()
                        + ". Check that it runs Lua revision 49, or use its key."), false);
                LOG.info("Session request {} {}: no answer", request.seq(), request.action());
                request = null;
            }
        }

        void revive(ServerPlayerEntity respawned) {
            player = respawned;
            respawned.teleport(world, origin.x, origin.y, origin.z, respawned.getYaw(), respawned.getPitch());
            // Vanilla recentres chunk loading and sending only from move packets, which the bridge cancels.
            world.getChunkManager().updatePosition(respawned);
            LOG.info("Bridge kept through a death: the respawned player returns to the anchor");
        }

        /**
         * Each tick EVE's position is applied: the living entities in the patch that her body shoves, nearest first and
         * clipped to the patch, for Stellar Blade's walls (eve-mobs.txt). An empty list is written once, then nothing;
         * Lua drops its walls when the heartbeat stops. A failure only costs the walls, never the session.
         */
        void publishMobs() {
            Box patch = new Box(origin.x + bounds.minX(), origin.y + bounds.minY(), origin.z + bounds.minZ(),
                    origin.x + bounds.maxX(), origin.y + bounds.maxY(), origin.z + bounds.maxZ());
            List<BridgeProtocol.Box> mobs = mobWalls(patch, origin, player.getPos(), world.getOtherEntities(player, patch,
                    AvatarCombat.shoved(player)).stream().map(Entity::getBoundingBox).toList());
            if (mobs.isEmpty() && !mobsListed) return;
            try {
                files.write("eve-mobs.txt", BridgeProtocol.encodeMobs(id, ++mobSeq, System.currentTimeMillis(), mobs));
                mobsListed = !mobs.isEmpty();
            } catch (IOException | IllegalArgumentException failure) {
                LOG.warn("Mob walls not delivered to Stellar Blade: {}", failure.toString());
            }
        }

        /**
         * Publishes the terrain window, moved to follow EVE's feet when TerrainGeometry.follow asks. A move that cannot
         * be made (unloaded chunks, too many boxes, too large a packet, the reach limit) holds the current window with an
         * action-bar warning at most every 5 s; EVE then meets its walls. A failure of the current window stops the session.
         */
        void publish() throws IOException {
            long started = System.nanoTime();
            // The window must stay loaded without the player entity: a dead player leaves the world after a second. The
            // ticket lasts 5 ticks and is renewed every 4, so it lapses by itself once the session ends. Radius 3 around
            // the window's centre chunk covers the window, its border and the next window one move away.
            world.getChunkManager().addTicket(ChunkTicketType.POST_TELEPORT, new ChunkPos(middle(centre)), 3, player.getId());
            int[] wanted = TerrainGeometry.follow(centre, feet);
            Snapshot next = null;
            if (!Arrays.equals(wanted, centre)) {
                // The wanted window's chunks load too, even far from the player, as when it returns to the anchor.
                world.getChunkManager().addTicket(ChunkTicketType.POST_TELEPORT, new ChunkPos(middle(wanted)), 3, player.getId());
                try { next = snapshot(wanted); }
                catch (IllegalArgumentException held) { hold(held.getMessage()); }
            }
            if (next == null) next = snapshot(centre);
            files.publish(next.text);
            long elapsed = System.nanoTime() - started;
            slowestPublish = Math.max(slowestPublish, elapsed);
            if (next.revision != revision) {
                gate.published(next.revision, next.bounds);
                LOG.info("Terrain revision={} boxes={} window={} publish={} ms", next.revision, next.boxes.size(),
                        Arrays.toString(next.centre), elapsed / 1_000_000);
            }
            centre = next.centre;
            bounds = next.bounds;
            boxes = next.boxes;
            revision = next.revision;
            // Native visuals read the 9 × 9 columns around the anchor: once the window has left it and its chunks have
            // unloaded, never load them back for that (the last scene lapses within a second).
            int x = MathHelper.floor(origin.x), z = MathHelper.floor(origin.z);
            if (world.isChunkLoaded((x - 4) >> 4, (z - 4) >> 4) && world.isChunkLoaded((x + 4) >> 4, (z - 4) >> 4)
                    && world.isChunkLoaded((x - 4) >> 4, (z + 4) >> 4) && world.isChunkLoaded((x + 4) >> 4, (z + 4) >> 4))
                NativeSceneExport.capture(server, world, player.getUuid(), new NativeScene.Binding(id, revision,
                        world.getRegistryKey().getValue().toString(), new CaptureFrame.Position(origin.x, origin.y, origin.z)));
        }

        /** The block in the middle of window cell c. */
        static BlockPos middle(int[] c) {
            int half = TerrainGeometry.CELL / 2;
            return new BlockPos(c[0] * TerrainGeometry.CELL + half, c[1] * TerrainGeometry.CELL + half, c[2] * TerrainGeometry.CELL + half);
        }

        private record Snapshot(int[] centre, BridgeProtocol.Box bounds, List<BridgeProtocol.Box> boxes, int revision, String text) { }

        /** The encoded window around cell c, without changing the session. */
        private Snapshot snapshot(int[] c) {
            BridgeProtocol.Box window = TerrainGeometry.window(c, origin);
            if (DoubleStream.of(window.minX(), window.minY(), window.minZ(), window.maxX(), window.maxY(), window.maxZ())
                    .anyMatch(v -> Math.abs(v) > BridgeProtocol.MAX_REACH))
                throw new IllegalArgumentException("EVE is near the bridge's reach, " + (int) BridgeProtocol.MAX_REACH + " blocks from the start");
            List<BridgeProtocol.Box> next = TerrainGeometry.merge(scan(window), window, origin.x, origin.y, origin.z, cells);
            int nextRevision = revision == 0 || !next.equals(boxes) || !window.equals(bounds) ? Math.addExact(revision, 1) : revision;
            return new Snapshot(c, window, next, nextRevision, BridgeProtocol.encodeTerrain(new BridgeProtocol.Terrain(id,
                    nextRevision, now(), world.getRegistryKey().getValue().toString(), origin.x, origin.y, origin.z, window, next)));
        }

        /**
         * Collision shapes relative to the origin inside window, plus one block around it for shapes that protrude into it.
         * Rows outside the world's height are empty; Stellar Blade's lab floor and ceiling still bound EVE.
         */
        private List<VoxelShape> scan(BridgeProtocol.Box window) {
            List<VoxelShape> shapes = new ArrayList<>();
            int top = MathHelper.ceil(origin.y + window.maxY());
            ShapeContext context = ShapeContext.of(player);
            for (int x = MathHelper.floor(origin.x + window.minX()) - 1; x <= MathHelper.ceil(origin.x + window.maxX()); x++) {
                for (int z = MathHelper.floor(origin.z + window.minZ()) - 1; z <= MathHelper.ceil(origin.z + window.maxZ()); z++) {
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) throw new IllegalArgumentException("Terrain chunks are not loaded");
                    int bottom = Math.max(MathHelper.floor(origin.y + window.minY()) - 1, world.getBottomY());
                    for (int y = bottom; y <= Math.min(top, world.getTopY() - 1); y++) {
                        BlockPos position = new BlockPos(x, y, z);
                        VoxelShape shape = world.getBlockState(position).getCollisionShape(world, position, context);
                        // Fluids are not simulated: EVE cannot enter them, a wall from the fluid up to the first solid
                        // block above it (ice over a pond, a floor over cave water) or the window's top.
                        if (!world.getFluidState(position).isEmpty() && y - origin.y >= window.minY()) {
                            int column = x, row = z;
                            int end = TerrainGeometry.fluidWallEnd(y, top, above -> {
                                BlockPos at = new BlockPos(column, above, row);
                                return world.getFluidState(at).isEmpty() && !world.getBlockState(at).getCollisionShape(world, at, context).isEmpty();
                            });
                            shape = VoxelShapes.cuboid(0, 0, 0, 1, Math.min(end - y, window.maxY() - (y - origin.y)), 1);
                        }
                        if (!shape.isEmpty()) shapes.add(shape.offset(x - origin.x, y - origin.y, z - origin.z));
                    }
                }
            }
            return shapes;
        }

        private void hold(String reason) {
            long now = System.currentTimeMillis();
            if (now - heldWarned < 5000) return;
            heldWarned = now;
            player.sendMessage(Text.literal("Stellar Bridge: the terrain window cannot follow EVE: " + reason), true);
            LOG.warn("Terrain window held at cell {}: {}", Arrays.toString(centre), reason);
        }
    }
}
