package org.stellarbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.ToLongFunction;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EVE poses over the network on this client (protocol/README.md, network pose). Sending: once this player's EVE is
 * anchored to its link, each new generation's names-only table goes to the server (after its mesh fingerprints are read
 * off the render thread), then the stream's newest sample at most RATE times a second, a keyframe every KEYFRAMES poses
 * and deltas between. Receiving, with an avatar pack only (without one the server sends nothing): per other player their
 * table, a decoder and the EVE AvatarView draws from their poses, with a clock floor that absorbs transit and clock skew.
 * Development tools, registered with -Dstellarbridge.netTools=true only: an echo of this player's own stream as a fake
 * remote, and a recording of it (netrecord) played back by another client as its own (netplay). Render thread only.
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
    // A window whose least arrival minus publication stays STEP ms above the clock floor moves the floor there at once.
    static final long FLOOR_WINDOW_NANOS = 5_000_000_000L;
    static final double STEP = 250;
    static final boolean TOOLS = Boolean.getBoolean("stellarbridge.netTools");
    /** The echo: this player's own stream as a remote ECHO_NANOS late, ECHO_OFFSET blocks along +X. */
    static final UUID ECHO = new UUID(0x53424d4345484f00L, 1);
    static final long ECHO_NANOS = 1_000_000_000L;
    static final double ECHO_OFFSET = 3;
    static final long RECORD_MILLIS = 60_000;
    /** A development offset of this client's network clock (-Dstellarbridge.clockOffsetMs), to test a skewed clock on one PC. */
    static final long CLOCK_OFFSET = Long.getLong("stellarbridge.clockOffsetMs", 0);

    /** One other player's EVE as received: table, decoder, the EVE drawn from its poses and the measures of their arrival. */
    static final class Remote {
        final UUID owner;
        volatile NetPose.Table table; // Volatile: the build worker reads its fingerprints.
        AvatarStream.Table stream; // The table in the stream's form, for AvatarStream.decode: anchor 0, no parameters, no cloth.
        final NetPose.Decoder decoder = new NetPose.Decoder();
        AvatarView.Eve eve; // Built from this pack (AvatarView.frameRemotes); null before, or after its build failed.
        boolean changed; // A table the EVE does not want yet.
        long failed = -1; // The generation whose model this pack cannot draw: that player keeps a vanilla model.
        String failure;
        boolean drawn, near, culled; // In the last frame.
        double floor = Double.NaN; // Milliseconds: the least arrival minus publication, rising 1 ms a second.
        double lag, interval = 1000.0 / RATE; // The newest pose's arrival minus publication; publications apart (average).
        double newestPublication = Double.NaN;
        double lowest = Double.POSITIVE_INFINITY; // The least lag in the window since lowestNanos.
        long lowestNanos;
        long floorNanos, lastNanos, poses, bytes, stalls, malformed, untabled, windowNanos = System.nanoTime(), windowPoses, windowBytes;
        double rate, byteRate;

        Remote(UUID owner) { this.owner = owner; }

        boolean echo() { return owner.equals(ECHO); }
        /** The Minecraft position of the sender's link anchor (the echo's moved aside). */
        Vec3d origin() {
            NetPose.Table t = table;
            return new Vec3d(t.originX() + (echo() ? ECHO_OFFSET : 0), t.originY(), t.originZ());
        }

        /** A pose of the current table arrived at wall milliseconds arrival and at nanos. */
        void accept(NetPose.Pose pose, int bytes, double arrival, long nanos) {
            if (!Double.isNaN(newestPublication)) {
                double gap = pose.publication() - newestPublication;
                if (gap > 0 && gap < INTERVAL_CAP) interval = interval * 0.9 + gap * 0.1;
                if (nanos - lastNanos > Math.max(250, 3 * interval) * 1e6) stalls++;
            }
            newestPublication = pose.publication();
            lag = arrival - pose.publication();
            floor = floor(floor, lag, (nanos - floorNanos) / 1e9);
            // A clock step (the sender's back, or this one's forward) raises every lag at once, which the floor's 1 ms a
            // second would take hours to absorb, drawing her ahead of her newest pose meanwhile. A stall cannot move it:
            // the window still holds the poses before the stall.
            lowest = Math.min(lowest, lag);
            if (nanos - lowestNanos >= FLOOR_WINDOW_NANOS) {
                if (lowest > floor + STEP) floor = lowest;
                lowest = Double.POSITIVE_INFINITY;
                lowestNanos = nanos;
            }
            floorNanos = lastNanos = nanos;
            poses++;
            this.bytes += bytes;
            windowPoses++;
            windowBytes += bytes;
            if (eve != null) eve.accept(new AvatarStream.Sample(pose.sequence(), pose.publication(), table.generation(), pose.status(),
                    pose.payload(), null, false, Float.NaN, false, 0), nanos);
        }

        /** Its EVE is not drawn from this pack until the next generation; the reason shows in /stellaravatar status. */
        void drop(String reason) {
            if (eve != null) eve.close();
            eve = null;
            failed = table.generation();
            failure = reason;
            LOG.warn("Another player's EVE ({}) stays a vanilla model for generation {}: {}", owner, failed, reason);
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
    // Development tools: the echo's delayed payloads, the recording being made and the one being played.
    private static boolean echo;
    private static final ArrayDeque<Delayed> echoes = new ArrayDeque<>();
    private static List<Entry> recording;
    private static long recordStart;
    private static Playback playback;

    /** What the server's copy of this player's table depends on: the generation and the link it is placed by. */
    private record Key(long generation, String session, double x, double y, double z, String dimension) { }
    private record Delayed(long due, byte kind, byte[] data) { }
    /** One payload of a network recording: TABLE or POSE, sent millis after the recording started. */
    record Entry(byte kind, int millis, byte[] data) { }
    static final byte TABLE = 'T', POSE = 'P';

    private RemoteEves() { }

    static void initialize() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(RemoteEves::reset));
        ClientTickEvents.END_CLIENT_TICK.register(client -> prune(client, System.nanoTime()));
        // Without a pack nothing could be drawn: no receiver, so the server sends this client no poses and EVE players
        // stay vanilla models (a pack added later counts from the next start).
        if (!Files.isDirectory(AvatarView.packDirectory())) return;
        ClientPlayNetworking.registerGlobalReceiver(BridgeNet.RemoteTable.ID, (payload, context) -> table(payload.owner(), payload.data()));
        ClientPlayNetworking.registerGlobalReceiver(BridgeNet.RemotePose.ID, (payload, context) -> pose(payload.owner(), payload.data(), clock(), System.nanoTime()));
    }

    static Collection<Remote> remotes() { return remotes.values(); }
    static Remote get(UUID owner) { return remotes.get(owner); }

    /**
     * After each local sample decoded while EVE is anchored to link: the table first when it changed, then the sample when
     * RATE allows. Nothing goes to a singleplayer world not open to LAN, where nobody else could see her; the echo and a
     * recording still get it.
     */
    static void send(AvatarStream.Table table, AvatarStream.Sample sample, StellarBridge.RenderingContext link) {
        var server = MinecraftClient.getInstance().getServer();
        boolean network = !(server != null && !server.isRemote()) && ClientPlayNetworking.canSend(BridgeNet.Pose.ID);
        if (table.anchor() == null || !network && !echo && recording == null) return;
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
                byte[] bytes = next.encode();
                if (network) ClientPlayNetworking.send(new BridgeNet.PoseTable(bytes));
                loop(TABLE, bytes);
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
            if (data == null) return;
            if (network) ClientPlayNetworking.send(new BridgeNet.Pose(data));
            loop(POSE, data);
        } catch (RuntimeException failure) { // Never the local avatar's end: her poses just stay local for this generation.
            refusedKey = key;
            LOG.warn("EVE's poses of generation {} stopped going to other players", table.generation(), failure);
        }
    }

    /** A payload sent: to the echo (ECHO_NANOS later) and to the recording being made. */
    private static void loop(byte kind, byte[] data) {
        long nanos = System.nanoTime();
        if (echo) echoes.add(new Delayed(nanos + ECHO_NANOS, kind, data));
        if (recording == null) return;
        recording.add(new Entry(kind, (int) ((nanos - recordStart) / 1_000_000), data));
        if (nanos - recordStart >= RECORD_MILLIS * 1_000_000) stopRecording();
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

    /** The fingerprint of the pack's mesh file, cached by its size and time; 0 without one. Off the render thread. */
    static long fingerprint(Path pack, String mesh) {
        Path file = pack.resolve(mesh + ".sbav");
        if (!file.normalize().startsWith(pack.normalize())) return 0;
        try {
            String key = mesh + " " + Files.size(file) + " " + Files.getLastModifiedTime(file).toMillis();
            return fingerprints.computeIfAbsent(key, k -> NetPose.fingerprint(file));
        } catch (IOException missing) {
            return 0;
        }
    }

    /** Stand-ins by role for another player's meshes: the core meshes every pack holds. Accessories and others have none. */
    private static final Map<String, String> ROLES = Map.of("CharacterMesh0", "CH_P_EVE_52_TypeB", "SBHair", "EVE_HR_01",
            "SBPonytail", "CH_P_EVE_Hair_PonyTail_2", "Mesh_Face", "CH_P_EVE_Face_Jali3");
    static final String DRONE = "CH_NPC_Drone_Bear";
    static String role(AvatarRecording.Component c) {
        return c.name().startsWith(AvatarStream.OPTIONAL) ? DRONE : ROLES.get(c.name());
    }

    /**
     * Another player's components as this pack draws them: a mesh this pack holds with the sender's fingerprint (or an
     * unknown one) as is; one it lacks or holds in another version gets its role's stand-in, driven by bone name
     * (AvatarModel lenient), or none. local gives this pack's fingerprint of a mesh (0: missing); notes collects each change.
     */
    static List<AvatarRecording.Component> substitute(NetPose.Table table, ToLongFunction<String> local, List<String> notes) {
        List<AvatarRecording.Component> out = new ArrayList<>();
        for (int i = 0; i < table.components().size(); i++) {
            var c = table.components().get(i);
            String mesh = c.mesh();
            if (!mesh.equals("none")) {
                long mine = local.applyAsLong(mesh), theirs = table.fingerprints()[i];
                if (mine == 0 || theirs != 0 && mine != theirs) {
                    String role = role(c);
                    mesh = role != null && local.applyAsLong(role) != 0 ? role : "none";
                    notes.add(c.mesh() + (mine == 0 ? " missing" : " differs") + (mesh.equals("none") ? ", left out" : ", drawn as " + mesh));
                }
            }
            out.add(new AvatarRecording.Component(c.name(), mesh, c.leader(), c.bones(), c.morphs(), c.weights()));
        }
        return out;
    }

    /** A remote player's table: a new generation drops that player's poses and waits for its keyframe. */
    static void table(UUID owner, byte[] data) {
        Remote remote = remotes.computeIfAbsent(owner, Remote::new);
        try {
            NetPose.Table table = NetPose.Table.decode(data);
            remote.table = table;
            remote.stream = stream(table);
            remote.changed = true;
            remote.decoder.restart();
            remote.newestPublication = Double.NaN;
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
     * second, so it follows clock drift both ways (Remote.accept also follows a clock step up within FLOOR_WINDOW_NANOS).
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

    /** Each frame before the remotes are posed: the echo's due payloads, and the playback's. */
    static void frame(long nanos) {
        while (!echoes.isEmpty() && echoes.peek().due() <= nanos) {
            Delayed due = echoes.poll();
            if (due.kind() == TABLE) table(ECHO, due.data());
            else pose(ECHO, due.data(), clock(), nanos);
        }
        if (playback == null) return;
        try {
            playback.play(nanos);
        } catch (RuntimeException failure) {
            playback = null;
            AvatarView.report("EVE network playback stopped: " + failure.getMessage(), failure);
        }
    }

    /** Each client tick: rates over 2 s windows; a silent remote goes with its player entity (the echo with the echo). */
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
            if (nanos - remote.lastNanos <= SILENT_NANOS || remote.echo() && echo) continue;
            // The server sends the table again when this player tracks its owner again.
            if (remote.echo() || client.world == null || client.world.getPlayerByUuid(remote.owner) == null) {
                if (remote.eve != null) remote.eve.close();
                it.remove();
            }
        }
    }

    /** A new connection: the next server gets the table anew; nobody's poses carry over, nor the tools'. */
    private static void reset() {
        for (Remote remote : remotes.values()) if (remote.eve != null) remote.eve.close();
        remotes.clear();
        AvatarView.releaseTextures();
        encoder = new NetPose.Encoder(KEYFRAMES);
        sent = null;
        sentKey = pendingKey = refusedKey = null;
        pending = null;
        lastPublication = 0;
        echo = false;
        echoes.clear();
        if (recording != null) stopRecording();
        playback = null;
    }

    /** The next sample sends the table again and a keyframe (for the echo or a recording that just started). */
    private static void resend() {
        sentKey = null;
        encoder.restart();
    }

    /** Toggles the echo; returns its new state. */
    static boolean toggleEcho() {
        echo = !echo;
        echoes.clear();
        if (echo) resend();
        else {
            Remote remote = remotes.remove(ECHO);
            if (remote != null && remote.eve != null) remote.eve.close();
        }
        return echo;
    }

    /** Starts recording this player's network poses for RECORD_MILLIS, or stops the recording made; the message to show. */
    static String toggleRecording() {
        if (recording != null) return stopRecording();
        recording = new ArrayList<>();
        recordStart = System.nanoTime();
        resend();
        return "Recording EVE's network poses for " + RECORD_MILLIS / 1000 + " s to " + recordingFile() + " (once she is anchored);"
                + " /stellaravatar netrecord again stops it early.";
    }
    private static String stopRecording() {
        List<Entry> entries = recording;
        recording = null;
        String message;
        try {
            Files.write(recordingFile(), recording(entries));
            message = String.format("EVE network recording written: %d payloads over %.1f s to %s.", entries.size(),
                    entries.isEmpty() ? 0 : entries.get(entries.size() - 1).millis() / 1000.0, recordingFile());
        } catch (IOException | RuntimeException failure) {
            message = "EVE network recording failed: " + failure.getMessage();
        }
        AvatarView.report(message, null);
        return message;
    }
    static Path recordingFile() { return AvatarView.bridgeDirectory().resolve("eve-netrecord.bin"); }

    private static final byte[] MAGIC = "SBMCNET1".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    static final int MAX_ENTRIES = 4096; // 60 s at up to 35 poses a second, plus tables.

    /** A recording's bytes: MAGIC, then per entry its kind, millis, length and payload (big-endian). */
    static byte[] recording(List<Entry> entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(MAGIC);
        for (Entry e : entries) {
            out.writeBytes(ByteBuffer.allocate(9).put(e.kind()).putInt(e.millis()).putInt(e.data().length).array());
            out.writeBytes(e.data());
        }
        return out.toByteArray();
    }
    /** The entries of a recording; refuses another file, a count, kind, time or length beyond the bounds, or no table first. */
    static List<Entry> recording(byte[] data) {
        require(data.length >= MAGIC.length && java.util.Arrays.equals(data, 0, MAGIC.length, MAGIC, 0, MAGIC.length), "not an EVE network recording");
        ByteBuffer in = ByteBuffer.wrap(data).position(MAGIC.length);
        List<Entry> entries = new ArrayList<>();
        int previous = 0;
        while (in.hasRemaining()) {
            require(in.remaining() >= 9 && entries.size() < MAX_ENTRIES, "truncated or too long");
            byte kind = in.get();
            int millis = in.getInt(), length = in.getInt();
            require(kind == TABLE || kind == POSE, "entry kind");
            require(millis >= previous && millis <= 2 * RECORD_MILLIS, "entry time");
            require(length > 0 && length <= (kind == TABLE ? NetPose.MAX_TABLE : NetPose.MAX_POSE) && length <= in.remaining(), "entry length");
            require(!entries.isEmpty() || kind == TABLE, "a table first");
            byte[] payload = new byte[length];
            in.get(payload);
            entries.add(new Entry(kind, millis, payload));
            previous = millis;
        }
        require(entries.size() > 1, "no pose");
        return entries;
    }

    /** Plays a recording as this player's own EVE, looping, placed at this player; or stops the one playing. */
    static String togglePlayback(Path file) throws IOException {
        if (playback != null) {
            playback = null;
            return "EVE network playback stopped.";
        }
        var client = MinecraftClient.getInstance();
        if (client.player == null || client.world == null) return "Enter a world first.";
        if (!ClientPlayNetworking.canSend(BridgeNet.Pose.ID)) return "This server does not relay EVE poses.";
        if (sent != null) return "This client streams its own EVE already.";
        playback = new Playback(recording(Files.readAllBytes(file)), client.player.getPos(), client.world.getRegistryKey().getValue().toString());
        return String.format("Playing %d recorded payloads as your EVE, in a loop, from where you stand; /stellaravatar netplay again stops it.",
                playback.entries.size());
    }

    /**
     * A recording sent as this player's own stream: its tables moved so her first pose stands at this player, its poses
     * decoded and encoded again with this clock's times (keeping the recorded spacing) and one sequence across loops.
     */
    static final class Playback {
        final List<Entry> entries;
        final Vec3d at;
        final String dimension;
        final NetPose.Encoder out = new NetPose.Encoder(KEYFRAMES);
        Vec3d shift; // Recorded origin to the played one.
        NetPose.Table recorded, played;
        NetPose.Decoder decoder;
        int next;
        long start = System.nanoTime();
        long loopFirst = -1, stamped; // The loop's first recorded publication (-1: none yet), the last time sent.
        double loopBase; // This clock's time for loopFirst.

        Playback(List<Entry> entries, Vec3d at, String dimension) { this.entries = entries; this.at = at; this.dimension = dimension; }

        void play(long nanos) {
            while (next < entries.size() && entries.get(next).millis() * 1_000_000L <= nanos - start) {
                Entry e = entries.get(next++);
                if (e.kind() == TABLE) {
                    recorded = NetPose.Table.decode(e.data());
                    decoder = new NetPose.Decoder();
                    played = null; // Sent with the first pose it decodes, once that pose places it.
                    continue;
                }
                if (recorded == null) continue;
                NetPose.Pose pose = decoder.decode(recorded, NetPose.header(e.data()), e.data());
                if (pose == null) continue;
                if (played == null) {
                    if (shift == null) shift = at.subtract(position(recorded, pose));
                    played = new NetPose.Table(recorded.generation(), recorded.originX() + shift.x, recorded.originY() + shift.y,
                            recorded.originZ() + shift.z, recorded.units(), dimension, recorded.components(), recorded.fingerprints(),
                            recorded.effects(), recorded.effectRecords());
                    ClientPlayNetworking.send(new BridgeNet.PoseTable(played.encode()));
                    out.restart();
                }
                int effectsAt = played.effectRecords() ? played.floats() - NetPose.MAX_ACTIVE * NetPose.EFFECT_FLOATS : -1;
                byte[] data = out.encode(played, new double[] {0, 0, 0, played.units()}, pose.status(), pose.payload(), effectsAt,
                        stamp(pose.publication(), clock()));
                if (data != null) ClientPlayNetworking.send(new BridgeNet.Pose(data));
            }
            if (next == entries.size()) { // Again from the start, a moment later.
                next = 0;
                start = nanos + 100_000_000L;
                loopFirst = -1;
            }
        }

        /**
         * This clock's publication time for a recorded one, played at now: the loop's first pose at now (at least an
         * interval after the last one sent), the others as far from it as they were recorded, so the frame a pose falls
         * due on adds no judder to the viewer's interpolation.
         */
        long stamp(long recorded, double now) {
            if (loopFirst < 0) {
                loopFirst = recorded;
                loopBase = Math.max(now, stamped + 1000.0 / RATE);
            }
            stamped = Math.round(loopBase + (recorded - loopFirst));
            return stamped;
        }
    }

    /** The Minecraft position of a decoded pose's first component (its root) under table. */
    static Vec3d position(NetPose.Table table, NetPose.Pose pose) {
        float[] p = pose.payload();
        return new Vec3d(table.originX() + p[4] / table.units(), table.originY() + p[6] / table.units(), table.originZ() + p[5] / table.units());
    }

    /** Wall milliseconds of this client's network clock: arrivals, the display time and a playback's publications. */
    static double clock() { return AvatarView.wallMillis() + CLOCK_OFFSET; }

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
        if (echo) text.append(" Echo on.");
        if (recording != null) text.append(String.format(" Recording: %d payloads.", recording.size()));
        if (playback != null) text.append(String.format(" Playing a recording: %d poses sent.", playback.out.keys + playback.out.deltas));
        if (remotes.isEmpty()) return text.append(" No remote EVE.").toString();
        long nanos = System.nanoTime();
        double now = clock();
        var world = MinecraftClient.getInstance().world;
        for (Remote r : remotes.values()) {
            var player = world == null ? null : world.getPlayerByUuid(r.owner);
            String name = r.echo() ? "echo" : player == null ? r.owner.toString().substring(0, 8) : player.getName().getString();
            text.append(String.format(" Remote %s: %s; %d poses (%.1f/s, %.1f KB/s, %.0f ms apart)", name,
                    r.table == null ? "no table" : "generation " + r.table.generation(), r.poses, r.rate, r.byteRate / 1000, r.interval));
            if (r.poses > 0) text.append(String.format(", clock floor %.0f ms, newest %.0f ms above it%s, %d stalls, last %.0f ms ago",
                    r.floor, r.lag - r.floor, Double.isNaN(r.newestPublication) ? ""
                            : String.format(", drawn %.0f ms behind the newest pose", r.newestPublication - displayTime(now, r.floor, r.interval)),
                    r.stalls, (nanos - r.lastNanos) / 1e6));
            text.append(String.format("; dropped %d stale, %d deltas without their base, %d of another generation, %d before a table, %d malformed",
                    r.decoder.stale, r.decoder.broken, r.decoder.foreign, r.untabled, r.malformed));
            var eve = r.eve;
            if (eve == null) text.append(r.failure == null ? "; vanilla model." : "; vanilla model: " + r.failure + ".");
            else if (eve.model == null) text.append("; vanilla model while her outfit builds.");
            else text.append(String.format("; %s, %d meshes%s, %d poses drawn, %d incomplete, %d frames past the newest pose.",
                    !r.drawn ? r.culled ? "out of view" : "not drawn" : r.near ? "drawn in full" : "drawn far (no face, shadow or effects)",
                    eve.model.parts.size(), eve.note.isEmpty() ? "" : " (" + eve.note + ")", eve.poses, eve.incomplete, eve.extrapolated));
        }
        return text.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
