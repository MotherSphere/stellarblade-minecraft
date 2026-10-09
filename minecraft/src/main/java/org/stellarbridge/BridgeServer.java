package org.stellarbridge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.DoubleStream;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
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

/**
 * The bridge's server half, loaded on dedicated servers too: one link session per player, the position authority,
 * combat and item effects. A session reads the eve.N packets its player's client relays and sends that client the
 * terrain, mob walls and verdicts it writes for Stellar Blade (BridgeLink). It must never reference client classes
 * (ServerSafetyTest); StellarBridge is the client half.
 */
public final class BridgeServer implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("stellar_bridge");
    private static final Map<UUID, Session> sessions = new HashMap<>(); // Server thread only.
    // Immutable publications, per player, to the packet threads (move packets) and this JVM's client.
    private static final Map<UUID, Control> controls = new ConcurrentHashMap<>();
    static volatile PauseBinding pauseBinding; // Singleplayer host only; independent of EVE pose freshness.
    // Written by this JVM's client, read by its integrated server's damage hook; a dedicated server never sets it.
    static volatile boolean pauseOverridden; // A pause menu is open while the bridge keeps the world running.

    @Override
    public void onInitialize() {
        BridgeNet.register();
        AvatarItems.initialize();
        ServerPlayNetworking.registerGlobalReceiver(BridgeNet.LinkRequest.ID, (request, context) -> {
            ServerPlayerEntity player = context.player();
            // A custom payload skips vanilla's command spam limit, and each start scans the whole terrain window.
            AvatarCombat.Fighter fighter = AvatarCombat.fighter(player);
            if (!fighter.links.take(System.nanoTime())) {
                fighter.rejected++;
                return;
            }
            switch (request.action()) {
                case BridgeNet.LinkRequest.START -> start(player);
                case BridgeNet.LinkRequest.STOP -> {
                    Session current = sessions.get(player.getUuid());
                    if (current == null) error(player, "No bridge session is active.");
                    else end(current, "Stopped by its player");
                }
                default -> status(player);
            }
        });
        ServerPlayNetworking.registerGlobalReceiver(BridgeNet.Eve.ID, (eve, context) -> {
            Session current = sessions.get(context.player().getUuid());
            if (current != null) current.relay.receive(eve.text(), eve.sent(), System.currentTimeMillis());
        });
        // Combat from each player's client, judged against that player's own Fighter.
        ServerPlayNetworking.registerGlobalReceiver(BridgeNet.Strike.ID, (strike, context) -> AvatarCombat.strike(context.player(), strike));
        ServerPlayNetworking.registerGlobalReceiver(BridgeNet.Defense.ID, (defense, context) -> AvatarCombat.press(context.player(), defense));
        ServerPlayNetworking.registerGlobalReceiver(BridgeNet.ItemUse.ID, (use, context) -> AvatarCombat.use(context.player(), use.kind()));
        ServerPlayNetworking.registerGlobalReceiver(BridgeNet.Status.ID, (status, context) -> AvatarCombat.fighter(context.player()).status(status));
        ServerTickEvents.START_SERVER_TICK.register(AvatarCombat::judgeHeld);
        ServerTickEvents.END_SERVER_TICK.register(BridgeServer::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Session current : List.copyOf(sessions.values())) if (current.server == server) end(current, "World closed");
        });
        // Before vanilla saves a leaving player (Save and Quit): EVE's maximum health must never reach the save. A player
        // respawned this tick is not adopted yet, so the session ends on the entity that leaves and is saved.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            Session current = sessions.get(handler.player.getUuid());
            if (current != null) {
                current.player = handler.player;
                end(current, null);
            }
            AvatarCombat.forget(handler.player.getUuid());
        });
    }

    private static void start(ServerPlayerEntity player) {
        if (!eligible(player) || !player.isAlive())
            error(player, "Start the bridge alive, in creative or survival, and not riding anything.");
        else if (sessions.containsKey(player.getUuid())) error(player, "A bridge session is already active. Use /stellar stop first.");
        else if (!player.isOnGround() || Math.abs(player.getY() - Math.floor(player.getY())) > 0.05)
            error(player, "Stand on a full block floor before starting the bridge.");
        else {
            Session next;
            try {
                next = new Session(player.getServer(), player);
            } catch (IllegalArgumentException refused) {
                error(player, "Bridge start failed: " + refused.getMessage());
                return;
            }
            sessions.put(player.getUuid(), next);
            send(player, new BridgeNet.Link(next.id, next.origin, next.world.getRegistryKey().getValue().toString(), ""));
            try {
                next.publish();
            } catch (IllegalArgumentException failure) {
                end(next, "Bridge start failed: " + failure.getMessage());
                return;
            }
            // The pause menu keeps a singleplayer world running only for its host (never on a LAN or dedicated server).
            if (!next.server.isRemote() && next.server.isHost(player.getGameProfile()))
                pauseBinding = new PauseBinding(next.server, player.getUuid(), next.world.getRegistryKey().getValue().toString());
            player.sendMessage(Text.literal("Stellar Bridge ready: " + next.boxes.size()
                    + " collision boxes. Enter the imported patch in Stellar Blade to control this player's position."), false);
            LOG.info("Bridge started: player={} session={} origin={} revision={} boxes={}", player.getGameProfile().getName(),
                    next.id, next.origin, next.revision, next.boxes.size());
        }
    }

    /** Any player whose client completed the handshake, on foot and not a spectator. */
    private static boolean eligible(ServerPlayerEntity player) {
        return player != null && BridgeNet.greeted(player) && !player.isSpectator() && !player.hasVehicle();
    }

    private static void status(ServerPlayerEntity player) {
        Session current = sessions.get(player.getUuid());
        player.sendMessage(Text.literal(current == null ? "Stellar Bridge is inactive."
                : "Stellar Bridge: revision " + current.revision + ", " + current.boxes.size()
                    + " boxes, window around block " + Session.middle(current.centre).toShortString() + ", slowest terrain publication " + current.slowestPublish / 1_000_000 + " ms, EVE position "
                    + (controls.containsKey(player.getUuid()) ? "active" : "waiting") + "." + AvatarCombat.status(AvatarCombat.fighter(player))), false);
    }

    private static void error(ServerPlayerEntity player, String message) {
        player.sendMessage(Text.literal(message).formatted(Formatting.RED), false);
    }

    /** A payload for the player's client, if it listens on that channel (a client without the mod never does). */
    static void send(ServerPlayerEntity player, CustomPayload payload) {
        if (ServerPlayNetworking.canSend(player, payload.getId())) ServerPlayNetworking.send(player, payload);
    }

    /** Ends a session; the reason goes to its player's chat and client, none when the player leaves. */
    private static void end(Session previous, String reason) {
        UUID owner = previous.player.getUuid();
        sessions.remove(owner);
        controls.remove(owner);
        PauseBinding binding = pauseBinding;
        if (binding != null && binding.owner().equals(owner)) pauseBinding = null;
        NativeSceneCapture.Snapshot scene = NativeSceneCapture.snapshot();
        if (scene != null && scene.owner().equals(owner)) NativeSceneCapture.stop();
        if (sessions.isEmpty()) AvatarCombat.restoreTime(true);
        AvatarStats.stopHeals(AvatarCombat.fighter(previous.player).heals);
        AvatarStats.apply(previous.player, AvatarStats.Values.BASE); // Minecraft's own maximum health again.
        LOG.info("Bridge stopped: player={} {}", previous.player.getGameProfile().getName(), reason == null ? "left" : reason);
        if (reason == null) return;
        send(previous.player, new BridgeNet.Link("", Vec3d.ZERO, "", reason));
        previous.player.sendMessage(Text.literal("Stellar Bridge stopped: " + reason), false);
    }

    private static void tick(MinecraftServer server) {
        AvatarCombat.restoreTime(false);
        for (Session current : List.copyOf(sessions.values())) if (current.server == server) tick(current);
    }

    private static void tick(Session current) {
        UUID owner = current.player.getUuid();
        ServerPlayerEntity live = current.server.getPlayerManager().getPlayer(owner);
        // A death in Minecraft keeps the link and Stellar Blade's lab: the player respawned in the session's world returns
        // to the anchor. Any other replacement (another dimension, hardcore spectator, the End's exit) ends the session.
        if (live != null && live != current.player && live.isAlive() && current.player.isDead()
                && live.getServerWorld() == current.world && eligible(live)) current.revive(live);
        boolean dead = !current.player.isAlive();
        String ended = live == null ? "The player left"
                : live != current.player || !dead && current.player.getServerWorld() != current.world
                    ? "The player respawned elsewhere or changed dimension. Use /stellar start again where you stand."
                : !eligible(current.player) ? "The player became a spectator or mounted something. Use /stellar start again on foot."
                : !dead && teleported(current.seen, current.player.getPos(), current.patch()) ? "The player was teleported out of the terrain window. Use /stellar start again where you stand."
                : null;
        if (ended != null) {
            end(current, ended);
            return;
        }
        // EVE's stats and heals from the player's client; health share, heal count and gauge points back when they change.
        AvatarCombat.Fighter fighter = AvatarCombat.fighter(current.player);
        if (!dead) AvatarStats.apply(current.player, fighter.stats); // A respawned player gets them anew.
        AvatarStats.heal(current.player, fighter.heals, fighter.stats, !dead && overrides(current.player));
        var feedback = new BridgeNet.Feedback(fighter.heals.mirror.share(), fighter.heals.mirror.acknowledged(), fighter.beta, fighter.burst);
        if (!feedback.equals(fighter.sent)) {
            send(current.player, feedback);
            fighter.sent = feedback;
        }
        defenseless(current.player); // The multiplayer grace starts when EVE falls silent, hit or not.
        try {
            if (++current.ticks % 4 == 0) current.publish();
            if (dead) { // The terrain heartbeat goes on; EVE drives nobody until the respawn.
                controls.remove(owner);
                return;
            }
            long now = System.currentTimeMillis();
            BridgeProtocol.Eve eve = current.gate.accept(current.relay.latest(), current.revision,
                    EveRelay.clientSeconds(now, current.relay.clock()));
            if (eve == null) {
                controls.remove(owner);
                // Once she has left the link, the window returns to the anchor, where Stellar Blade imports it again.
                if (current.gate.away(EveRelay.clientSeconds(now, current.relay.clock()))) current.feet = current.origin;
                return;
            }
            Vec3d position = current.origin.add(eve.x(), eve.y(), eve.z());
            controls.put(owner, new Control(current.player, eve.time(), System.nanoTime(), current.relay.clock()));
            move(current.player, position);
            current.feet = position; // The terrain window follows her from the next publication.
            // Vanilla recentres chunk loading and sending only from move packets, which the bridge cancels.
            current.world.getChunkManager().updatePosition(current.player);
            AvatarCombat.shove(current.player);
            current.publishMobs();
        } catch (IllegalArgumentException exception) {
            end(current, exception.getMessage());
        } finally {
            current.seen = current.player.getPos();
        }
    }

    /**
     * Whether something moved the player more than a cell since the last tick (seen, null before the first) and out of
     * the terrain window (a /tp, a pearl): walking out, or the bridge's own moves, never count.
     */
    static boolean teleported(Vec3d seen, Vec3d now, Box window) {
        return seen != null && now.squaredDistanceTo(seen) > TerrainGeometry.CELL * TerrainGeometry.CELL && !window.contains(now);
    }

    static void move(Entity entity, Vec3d position) {
        entity.setPosition(position);
        entity.setVelocity(Vec3d.ZERO);
        entity.fallDistance = 0;
    }

    /**
     * Server thread: a bridged player whose EVE cannot answer a hit, behind a pause menu the world keeps running through,
     * without a live link (EVE outside the patch, stale packets), or while Stellar Blade withholds her input (its own
     * pause or a cutscene keeps the link live). The singleplayer host is spared for as long as that lasts; in multiplayer
     * a player is spared for AvatarCombat.GRACE of it, as vanilla never protects an idle player for long.
     */
    static boolean defenseless(ServerPlayerEntity player) {
        if (!sessions.containsKey(player.getUuid())) return false;
        AvatarCombat.Fighter fighter = AvatarCombat.fighter(player);
        PauseBinding current = pauseBinding;
        boolean host = current != null && player.getUuid().equals(current.owner) && !current.server.isRemote();
        return defenseless(host, pauseOverridden, !overrides(player) || fighter.halted, fighter, System.nanoTime());
    }

    /** The decision above at now: the singleplayer host while paused or silent, anyone else for GRACE of a silence. */
    static boolean defenseless(boolean host, boolean paused, boolean silent, AvatarCombat.Fighter fighter, long now) {
        return host ? paused || silent : fighter.graced(silent, now);
    }

    record PauseBinding(MinecraftServer server, UUID owner, String dimension) { }

    /** Server thread: whether that player has a bridge session. */
    static boolean bridged(UUID player) { return sessions.containsKey(player); }

    public static boolean overrides(ServerPlayerEntity player) {
        Control current = controls.get(player.getUuid());
        return live(current) && current.owner == player;
    }

    /**
     * Server thread: tells the player's Stellar Blade the defense at an impact (a verdict its client writes to
     * eve-combat.txt), with the direction of the attack's origin when known, so EVE's own game can answer it.
     */
    static void combatEvent(ServerPlayerEntity player, String event, Vec3d from) {
        if (!sessions.containsKey(player.getUuid())) return;
        double yaw = from == null ? Double.NaN // Unreal X, Y are Minecraft X, Z; yaw turns from +X toward +Y.
                : Math.toDegrees(Math.atan2(from.z - player.getZ(), from.x - player.getX()));
        send(player, new BridgeNet.Verdict((byte) BridgeProtocol.COMBAT_EVENTS.indexOf(event), (float) yaw));
    }

    public static void keepLocalLook(ServerPlayerEntity player, PlayerMoveC2SPacket packet) {
        if (!controls.containsKey(player.getUuid()) || !packet.changesLook()) return;
        float yaw = packet.getYaw(0), pitch = packet.getPitch(0);
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) return;
        // The packet callback can run on the connection thread; entity changes belong to the server.
        player.getServer().execute(() -> {
            if (overrides(player)) {
                player.setYaw(MathHelper.wrapDegrees(yaw));
                player.setPitch(MathHelper.clamp(pitch, -90, 90));
            }
        });
    }

    /** A fresh publication: TIME by its client's clock, applied within the last second. */
    static boolean live(Control current) {
        return current != null && BridgeProtocol.fresh(current.packetTime, EveRelay.clientSeconds(System.currentTimeMillis(), current.clock))
                && System.nanoTime() - current.updatedNanos < 1_000_000_000L;
    }

    private static long now() { return System.currentTimeMillis() / 1000; }

    /** Immutable server publication of one bridged player; no live Session is exposed. clock as EveRelay's. */
    record Control(ServerPlayerEntity owner, long packetTime, long updatedNanos, long clock) { }

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
        final MinecraftServer server;
        ServerPlayerEntity player; // Replaced by the respawned player after a death.
        final ServerWorld world;
        final EveRelay relay;
        final String id = UUID.randomUUID().toString().replace("-", "");
        final Vec3d origin;
        final PoseGate gate = new PoseGate(id);
        Vec3d seen; // The player's position at the end of the last tick, null before the first.
        int[] centre; // The terrain window's centre cell (world); it follows EVE.
        BridgeProtocol.Box bounds; // The published window, relative to the origin.
        Vec3d feet; // EVE's feet (world) as last applied, or the origin before that.
        final Map<Long, TerrainGeometry.Cell> cells = new HashMap<>(); // Merged cells for reuse across publications.
        long heldWarned; // Wall milliseconds of the last action-bar warning about a held window.
        List<BridgeProtocol.Box> boxes = List.of();
        long slowestPublish; // Nanoseconds of the slowest terrain publication (scan, merge, write) this session.
        int revision;
        int ticks;
        List<BridgeProtocol.Box> mobsSent = List.of(); // The mob walls last sent to the client.

        Session(MinecraftServer server, ServerPlayerEntity player) {
            this.server = server;
            this.player = player;
            this.world = player.getServerWorld();
            this.relay = new EveRelay(id);
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

        void revive(ServerPlayerEntity respawned) {
            player = respawned;
            respawned.teleport(world, origin.x, origin.y, origin.z, respawned.getYaw(), respawned.getPitch());
            // Vanilla recentres chunk loading and sending only from move packets, which the bridge cancels.
            world.getChunkManager().updatePosition(respawned);
            seen = origin; // The bridge's own move, never a teleport.
            LOG.info("Bridge kept through a death: the respawned player returns to the anchor");
        }

        /** The terrain window in the world. */
        Box patch() {
            return new Box(origin.x + bounds.minX(), origin.y + bounds.minY(), origin.z + bounds.minZ(),
                    origin.x + bounds.maxX(), origin.y + bounds.maxY(), origin.z + bounds.maxZ());
        }

        /**
         * Each tick EVE's position is applied: the living entities in the patch that her body shoves, nearest first and
         * clipped to the patch, for Stellar Blade's walls, sent to the client when they change (it renews eve-mobs.txt).
         */
        void publishMobs() {
            Box patch = patch();
            List<BridgeProtocol.Box> mobs = mobWalls(patch, origin, player.getPos(), world.getOtherEntities(player, patch,
                    AvatarCombat.shoved(player)).stream().map(Entity::getBoundingBox).toList());
            if (mobs.equals(mobsSent)) return;
            send(player, new BridgeNet.Mobs(mobs));
            mobsSent = mobs;
        }

        /**
         * Publishes the terrain window, moved to follow EVE's feet when TerrainGeometry.follow asks. A move that cannot
         * be made (unloaded chunks, too many boxes, too large a packet, the reach limit) holds the current window with an
         * action-bar warning at most every 5 s; EVE then meets its walls. A failure of the current window stops the session.
         * The client gets each new revision and renews terrain.txt with its own clock.
         */
        void publish() {
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
            long elapsed = System.nanoTime() - started;
            slowestPublish = Math.max(slowestPublish, elapsed);
            if (next.revision != revision) {
                gate.published(next.revision, next.bounds);
                send(player, new BridgeNet.Terrain(next.terrain));
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
            if (server.isHost(player.getGameProfile()) && world.isChunkLoaded((x - 4) >> 4, (z - 4) >> 4) && world.isChunkLoaded((x + 4) >> 4, (z - 4) >> 4)
                    && world.isChunkLoaded((x - 4) >> 4, (z + 4) >> 4) && world.isChunkLoaded((x + 4) >> 4, (z + 4) >> 4))
                NativeSceneCapture.capture(server, world, player.getUuid(), new NativeScene.Binding(id, revision,
                        world.getRegistryKey().getValue().toString(), new CaptureFrame.Position(origin.x, origin.y, origin.z)));
        }

        /** The block in the middle of window cell c. */
        static BlockPos middle(int[] c) {
            int half = TerrainGeometry.CELL / 2;
            return new BlockPos(c[0] * TerrainGeometry.CELL + half, c[1] * TerrainGeometry.CELL + half, c[2] * TerrainGeometry.CELL + half);
        }

        private record Snapshot(int[] centre, BridgeProtocol.Box bounds, List<BridgeProtocol.Box> boxes, int revision, BridgeProtocol.Terrain terrain) { }

        /** The encoded window around cell c, without changing the session. */
        private Snapshot snapshot(int[] c) {
            BridgeProtocol.Box window = TerrainGeometry.window(c, origin);
            if (DoubleStream.of(window.minX(), window.minY(), window.minZ(), window.maxX(), window.maxY(), window.maxZ())
                    .anyMatch(v -> Math.abs(v) > BridgeProtocol.MAX_REACH))
                throw new IllegalArgumentException("EVE is near the bridge's reach, " + (int) BridgeProtocol.MAX_REACH + " blocks from the start");
            List<BridgeProtocol.Box> next = TerrainGeometry.merge(scan(window), window, origin.x, origin.y, origin.z, cells);
            int nextRevision = revision == 0 || !next.equals(boxes) || !window.equals(bounds) ? Math.addExact(revision, 1) : revision;
            var terrain = new BridgeProtocol.Terrain(id, nextRevision, now(), world.getRegistryKey().getValue().toString(),
                    origin.x, origin.y, origin.z, window, next);
            BridgeProtocol.encodeTerrain(terrain); // Refuses what the client could not write (a packet over 64 KiB).
            return new Snapshot(c, window, next, nextRevision, terrain);
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
