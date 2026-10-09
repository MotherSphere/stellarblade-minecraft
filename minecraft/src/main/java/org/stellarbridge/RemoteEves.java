package org.stellarbridge;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EVE poses over the network on this client (protocol/README.md, network pose). Sending: once this player's EVE is
 * anchored to its link, each new generation's names-only table goes to the server (after its mesh fingerprints are read
 * off the render thread), then the stream's newest sample at most RATE times a second, a keyframe every KEYFRAMES poses
 * and deltas between. Receiving, with an avatar pack only (without one the server sends nothing): per other player their
 * table, a decoder and their recent poses in the stream's layout, with arrival times and a clock floor that absorbs
 * transit and clock skew. Render thread only.
 */
final class RemoteEves {
    private static final Logger LOG = LoggerFactory.getLogger("stellar_bridge");
    /** Poses sent a second (-Dstellarbridge.poseRate, 1 to 30) and a keyframe every so many (-Dstellarbridge.poseKeyframes). */
    static final int RATE = Math.max(1, Math.min(30, Integer.getInteger("stellarbridge.poseRate", 20)));
    static final int KEYFRAMES = Math.max(1, Math.min(100, Integer.getInteger("stellarbridge.poseKeyframes", 10)));
    // Recent poses kept per remote: 8 cover 400 ms near and 4 s of the far tier's keyframes (2 a second at 20 Hz).
    static final int RING = 8;
    static final long SILENT_NANOS = 3_000_000_000L; // A remote without a pose for this long is not drawn.
    static final double INTERVAL_CAP = 1000; // Milliseconds: longer gaps (a stall) leave the interval average alone.

    /** One other player's EVE as received: table, decoder, recent poses and the measures of their arrival. */
    static final class Remote {
        final UUID owner;
        NetPose.Table table;
        AvatarStream.Table stream; // The table in the stream's form, for AvatarStream.decode: anchor 0, no parameters, no cloth.
        final NetPose.Decoder decoder = new NetPose.Decoder();
        final AvatarStream.Sample[] ring = new AvatarStream.Sample[RING];
        int newest = -1, count;
        double floor = Double.NaN; // Milliseconds: the least arrival minus publication, rising 1 ms a second.
        double lag, interval = 1000.0 / RATE; // The newest pose's arrival minus publication; publications apart (average).
        long floorNanos, lastNanos, poses, bytes, stalls, malformed, untabled, windowNanos = System.nanoTime(), windowPoses, windowBytes;
        double rate, byteRate;

        Remote(UUID owner) { this.owner = owner; }

        /** A pose of the current table arrived at wall milliseconds arrival and at nanos. */
        void accept(NetPose.Pose pose, int bytes, double arrival, long nanos) {
            AvatarStream.Sample sample = new AvatarStream.Sample(pose.sequence(), pose.publication(), table.generation(), pose.status(),
                    pose.payload(), null, false, Float.NaN, false, 0);
            if (count > 0) {
                double gap = pose.publication() - ring[newest].publication();
                if (gap > 0 && gap < INTERVAL_CAP) interval = interval * 0.9 + gap * 0.1;
                if (nanos - lastNanos > Math.max(250, 3 * interval) * 1e6) stalls++;
            }
            newest = (newest + 1) % RING;
            ring[newest] = sample;
            count = Math.min(count + 1, RING);
            lag = arrival - pose.publication();
            floor = floor(floor, lag, (nanos - floorNanos) / 1e9);
            floorNanos = lastNanos = nanos;
            poses++;
            this.bytes += bytes;
            windowPoses++;
            windowBytes += bytes;
        }
    }

    private static final Map<UUID, Remote> remotes = new HashMap<>();
    // Sending: the encoder, the table the server has (its key), the table being fingerprinted, the last sent publication.
    private static NetPose.Encoder encoder = new NetPose.Encoder(KEYFRAMES);
    private static Key sentKey, pendingKey, refusedKey;
    private static NetPose.Table sent;
    private static CompletableFuture<long[]> pending;
    private static long lastPublication, tables;
    private static final Map<String, Long> fingerprints = new java.util.concurrent.ConcurrentHashMap<>(); // "mesh size mtime" to its fingerprint.
    private static final ExecutorService fingerprinting = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "EVE mesh fingerprints");
        thread.setDaemon(true);
        return thread;
    });

    /** What the server's copy of this player's table depends on: the generation and the link it is placed by. */
    private record Key(long generation, String session, double x, double y, double z, String dimension) { }

    private RemoteEves() { }

    static void initialize() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(RemoteEves::reset));
        ClientTickEvents.END_CLIENT_TICK.register(client -> prune(client, System.nanoTime()));
        // Without a pack nothing could be drawn: no receiver, so the server sends this client no poses and EVE players
        // stay vanilla models (a pack added later counts from the next start).
        if (!Files.isDirectory(AvatarView.packDirectory())) return;
        ClientPlayNetworking.registerGlobalReceiver(BridgeNet.RemoteTable.ID, (payload, context) -> table(payload.owner(), payload.data()));
        ClientPlayNetworking.registerGlobalReceiver(BridgeNet.RemotePose.ID, (payload, context) -> pose(payload.owner(), payload.data(), AvatarView.wallMillis(), System.nanoTime()));
    }

    /**
     * After each local sample decoded while EVE is anchored to link: the table first when it changed, then the sample when
     * RATE allows. Nothing goes to a singleplayer world not open to LAN, where nobody else could see her.
     */
    static void send(AvatarStream.Table table, AvatarStream.Sample sample, StellarBridge.RenderingContext link) {
        var server = MinecraftClient.getInstance().getServer();
        if (table.anchor() == null || server != null && !server.isRemote() || !ClientPlayNetworking.canSend(BridgeNet.Pose.ID)) return;
        var origin = link.origin();
        Key key = new Key(table.generation(), link.session(), origin.x(), origin.y(), origin.z(), link.dimension());
        if (key.equals(refusedKey)) return;
        if (!key.equals(sentKey)) {
            if (!key.equals(pendingKey)) {
                pendingKey = key;
                Path pack = AvatarView.packDirectory();
                List<AvatarRecording.Component> components = table.components();
                pending = CompletableFuture.supplyAsync(() -> components.stream().mapToLong(c -> fingerprint(pack, c.mesh())).toArray(), fingerprinting);
                return;
            }
            if (!pending.isDone()) return;
            try {
                NetPose.Table next = outgoing(table, origin.x(), origin.y(), origin.z(), link.dimension(), pending.join());
                ClientPlayNetworking.send(new BridgeNet.PoseTable(next.encode()));
                sent = next;
                sentKey = key;
                tables++;
                encoder.restart();
            } catch (RuntimeException refused) {
                refusedKey = key; // Logged once per generation; her poses stay local until the next one.
                LOG.warn("EVE's component table of generation {} cannot travel; other players keep seeing a vanilla model: {}",
                        table.generation(), refused.getMessage());
                return;
            } finally {
                pendingKey = null;
                pending = null;
            }
        }
        if (sample.publication() > lastPublication && sample.publication() - lastPublication < 1000 / RATE - 5) return;
        lastPublication = sample.publication();
        int effectsAt = table.effectRecords() ? table.floats() - NetPose.MAX_ACTIVE * NetPose.EFFECT_FLOATS : -1;
        try {
            byte[] data = encoder.encode(sent, table.anchor(), sample.status(), sample.payload(), effectsAt, sample.publication());
            if (data != null) ClientPlayNetworking.send(new BridgeNet.Pose(data));
        } catch (RuntimeException failure) { // Never the local avatar's end: her poses just stay local for this generation.
            refusedKey = key;
            LOG.warn("EVE's poses of generation {} stopped going to other players", table.generation(), failure);
        }
    }

    /**
     * The names-only table of an anchored stream table at the link's Minecraft origin and dimension: names, counts, units
     * per block and effect assets only; none of the stream text's process addresses exist in the parsed table.
     */
    static NetPose.Table outgoing(AvatarStream.Table table, double x, double y, double z, String dimension, long[] fingerprints) {
        return new NetPose.Table(table.generation(), x, y, z, table.anchor()[3], dimension, table.components(), fingerprints,
                table.effects(), table.effectRecords());
    }

    /** A remote table in the stream's form for AvatarStream.decode and effects: anchor 0, no material parameters, no cloth. */
    static AvatarStream.Table stream(NetPose.Table table) {
        return new AvatarStream.Table("0".repeat(32), table.generation(), "none", new double[] {0, 0, 0, table.units()}, false,
                table.components(), List.of(), table.effects(), table.effectRecords(), table.floats(), -1);
    }

    /** The fingerprint of the pack's mesh file, cached by its size and time; 0 without one. Fingerprint worker. */
    private static long fingerprint(Path pack, String mesh) {
        Path file = pack.resolve(mesh + ".sbav");
        if (!file.normalize().startsWith(pack.normalize())) return 0;
        try {
            String key = mesh + " " + Files.size(file) + " " + Files.getLastModifiedTime(file).toMillis();
            return fingerprints.computeIfAbsent(key, k -> NetPose.fingerprint(file));
        } catch (java.io.IOException missing) {
            return 0;
        }
    }

    /** A remote player's table: a new generation drops that player's poses and waits for its keyframe. */
    static void table(UUID owner, byte[] data) {
        Remote remote = remotes.computeIfAbsent(owner, Remote::new);
        try {
            NetPose.Table table = NetPose.Table.decode(data);
            remote.table = table;
            remote.stream = stream(table);
            remote.decoder.restart();
            remote.count = 0;
            remote.newest = -1;
            remote.lastNanos = System.nanoTime(); // The table may come just before its player's entity: not pruned before 3 s.
        } catch (IllegalArgumentException malformed) {
            remote.malformed++;
        }
    }

    /** A remote player's pose, arrived at wall milliseconds arrival and at nanos. */
    static void pose(UUID owner, byte[] data, double arrival, long nanos) {
        Remote remote = remotes.computeIfAbsent(owner, Remote::new);
        try {
            NetPose.Header header = NetPose.header(data);
            if (remote.table == null) { remote.untabled++; return; }
            NetPose.Pose pose = remote.decoder.decode(remote.table, header, data);
            if (pose != null) remote.accept(pose, data.length, arrival, nanos);
        } catch (IllegalArgumentException malformed) {
            remote.malformed++;
        }
    }

    /**
     * The clock floor after a pose whose arrival minus publication is lag, elapsedSeconds after the previous one: it drops
     * to any lower lag at once (transit plus the sender's clock offset, never negative transit) and otherwise rises 1 ms a
     * second, so it follows clock drift both ways.
     */
    static double floor(double floor, double lag, double elapsedSeconds) {
        return Double.isNaN(floor) ? lag : Math.min(lag, floor + elapsedSeconds);
    }

    /**
     * The sender's publication time to draw a remote at, wall milliseconds now: its clock floor back and 1.5 intervals
     * behind, so the pose interpolates between received samples through transit jitter. ponytail: a fixed 1.5 intervals;
     * a jitter average if stalls show.
     */
    static double displayTime(double now, double floor, double interval) { return now - floor - 1.5 * interval; }

    /** Each client tick: rates over 2 s windows; a silent remote's poses go, and the remote with its player entity. */
    private static void prune(MinecraftClient client, long nanos) {
        for (var it = remotes.values().iterator(); it.hasNext(); ) {
            Remote remote = it.next();
            if (nanos - remote.windowNanos >= 2_000_000_000L) {
                double seconds = (nanos - remote.windowNanos) / 1e9;
                remote.rate = remote.windowPoses / seconds;
                remote.byteRate = remote.windowBytes / seconds;
                remote.windowPoses = remote.windowBytes = 0;
                remote.windowNanos = nanos;
            }
            if (nanos - remote.lastNanos <= SILENT_NANOS) continue;
            remote.count = 0;
            remote.newest = -1;
            // The server sends the table again when this player tracks its owner again.
            if (client.world == null || client.world.getPlayerByUuid(remote.owner) == null) it.remove();
        }
    }

    /** A new connection: the next server gets the table anew; nobody's poses carry over. */
    private static void reset() {
        remotes.clear();
        encoder = new NetPose.Encoder(KEYFRAMES);
        sent = null;
        sentKey = pendingKey = refusedKey = null;
        pending = null;
        lastPublication = 0;
    }

    /** Asks the server for its relay counts (they come back as a chat line); false on a server without the bridge. */
    static boolean requestRelayStatus() {
        if (!ClientPlayNetworking.canSend(BridgeNet.LinkRequest.ID)) return false;
        ClientPlayNetworking.send(new BridgeNet.LinkRequest(BridgeNet.LinkRequest.RELAY));
        return true;
    }

    /** The sender's and each remote's counts, for /stellaravatar status. */
    static String status() {
        StringBuilder text = new StringBuilder(String.format(" Network: %s; %d poses sent at up to %d a second (%d keyframes, a keyframe every %d), %d KB, %d over %d bytes;"
                + " %d tables sent.", sent == null ? "nothing sent" : "generation " + sent.generation() + " sent", encoder.keys + encoder.deltas, RATE,
                encoder.keys, KEYFRAMES, encoder.bytes / 1000, encoder.oversized, NetPose.MAX_POSE, tables));
        if (remotes.isEmpty()) return text.append(" No remote EVE.").toString();
        long nanos = System.nanoTime();
        var world = MinecraftClient.getInstance().world;
        for (Remote r : remotes.values()) {
            var player = world == null ? null : world.getPlayerByUuid(r.owner);
            String name = player == null ? r.owner.toString().substring(0, 8) : player.getName().getString();
            text.append(String.format(" Remote %s: %s; %d poses (%.1f/s, %.1f KB/s, %.0f ms apart)", name,
                    r.table == null ? "no table" : "generation " + r.table.generation(), r.poses, r.rate, r.byteRate / 1000, r.interval));
            if (r.poses > 0) text.append(String.format(", clock floor %.0f ms, newest %.0f ms above it, %d stalls, last %.0f ms ago",
                    r.floor, r.lag - r.floor, r.stalls, (nanos - r.lastNanos) / 1e6));
            text.append(String.format("; dropped %d stale, %d deltas without their base, %d of another generation, %d before a table, %d malformed.",
                    r.decoder.stale, r.decoder.broken, r.decoder.foreign, r.untabled, r.malformed));
        }
        return text.toString();
    }
}
