package org.stellarbridge;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * The server's relay of EVE poses (protocol/README.md, network pose): each player's names-only table and poses go, as
 * the opaque bytes the codecs checked, to the players tracking that player whose clients listen (a client without the
 * mod or without an avatar pack does not). Interest: every pose within NEAR blocks, keyframes only beyond; a delta goes to
 * a viewer only on top of the pose it was made from, so a viewer that missed that pose (far, over its budget, newly
 * tracking) gets nothing until the next keyframe. Each sender is capped at RATE poses, SENT bytes and TABLES tables a
 * second, each viewer at BUDGET bytes a second, so one client sending large or junk poses or a flood of tables cannot use
 * up its viewers' budgets or keep their build workers busy. The latest table of each sender is cached for players who
 * start tracking it. Server thread only.
 */
final class PoseRelay {
    static final double NEAR = 64, RATE = 35, TABLES = 2;
    /** Bytes a second per viewer, -Dstellarbridge.relayBudget in KiB (512 by default: 7 remote EVEs take about 350). */
    static final double BUDGET = Math.max(16, Integer.getInteger("stellarbridge.relayBudget", 512)) * 1024.0;
    static final int OVERHEAD = 20; // Bytes a relayed payload adds: its channel, owner UUID and length.
    /** Pose bytes a second from one sender: a quarter of a viewer's budget, 2.5 times a 20 Hz stream's 50 KB/s. */
    static final double SENT = BUDGET / 4;

    /**
     * One sending player: the cached table, its generation and whether its viewers still wait for it, the caps, each
     * viewer's last pose and the counts.
     */
    static final class Sender {
        byte[] table;
        long generation;
        boolean unsent;
        final AvatarCombat.Bucket rate = new AvatarCombat.Bucket(RATE), volume = new AvatarCombat.Bucket(SENT), tableRate = new AvatarCombat.Bucket(TABLES);
        final Map<UUID, Long> forwarded = new HashMap<>(); // Viewer to the sequence of the last pose it got.
        long poses, bytes, refused, tables;
    }

    /** One viewing player: its byte budget and what it got. */
    static final class Viewer {
        final AvatarCombat.Bucket budget = new AvatarCombat.Bucket(BUDGET);
        long poses, bytes, dropped, withheld;
    }

    private static final Map<UUID, Sender> senders = new HashMap<>();
    private static final Map<UUID, Viewer> viewers = new HashMap<>();
    private static long relayed, relayedBytes; // Since the server started.

    private PoseRelay() { }

    /** A sender's new table (checked by its codec): cached, then sent to every player tracking the sender. */
    static void table(ServerPlayerEntity sender, byte[] data) {
        if (!BridgeNet.greeted(sender)) return; // Only a client of this protocol streams; poses without a table go nowhere.
        Sender s = senders.computeIfAbsent(sender.getUuid(), owner -> new Sender());
        cache(s, data);
        release(sender, s, System.nanoTime());
    }

    /** Caches a sender's new table, which its viewers still have to get; the new generation starts with a keyframe for everyone. */
    static void cache(Sender s, byte[] data) {
        s.table = data;
        s.generation = ByteBuffer.wrap(data).getLong(); // A table starts with its generation (NetPose.Table.encode).
        s.tables++;
        s.unsent = true;
        s.forwarded.clear();
    }

    /**
     * Whether a sender's cached table goes to its viewers at nanos: once, within TABLES a second. One over the cap waits
     * for the sender's next pose (only its latest table goes), so a client cannot flood its viewers with outfit rebuilds.
     */
    static boolean release(Sender s, long nanos) {
        if (!s.unsent || !s.tableRate.take(nanos)) return false;
        s.unsent = false;
        return true;
    }

    private static void release(ServerPlayerEntity sender, Sender s, long nanos) {
        if (!release(s, nanos)) return;
        var out = new BridgeNet.RemoteTable(sender.getUuid(), s.table);
        for (ServerPlayerEntity viewer : PlayerLookup.tracking(sender)) BridgeServer.send(viewer, out);
    }

    /**
     * Whether a sender's pose of bytes is relayed at nanos: of its cached table's generation, within RATE poses and SENT
     * bytes a second; counted on the sender.
     */
    static boolean admit(Sender s, NetPose.Header pose, int bytes, long nanos) {
        if (pose.generation() != s.generation || !s.rate.take(nanos) || !s.volume.take(nanos, bytes)) {
            s.refused++;
            return false;
        }
        s.poses++;
        s.bytes += bytes;
        return true;
    }

    /** A sender's pose (its header checked by the codec): to each tracking viewer that should get it. */
    static void pose(ServerPlayerEntity sender, byte[] data) {
        NetPose.Header header = NetPose.header(data);
        Sender s = senders.get(sender.getUuid());
        long now = System.nanoTime();
        if (s == null) return; // No table yet: no viewer could decode it.
        release(sender, s, now); // A table held over the cap goes first.
        if (!admit(s, header, data.length, now)) return;
        BridgeNet.RemotePose out = null;
        for (ServerPlayerEntity viewer : PlayerLookup.tracking(sender)) {
            if (!ServerPlayNetworking.canSend(viewer, BridgeNet.RemotePose.ID)) continue;
            Viewer v = viewers.computeIfAbsent(viewer.getUuid(), owner -> new Viewer());
            if (!forward(header, s.forwarded.get(viewer.getUuid()), viewer.squaredDistanceTo(sender) <= NEAR * NEAR, v, data.length + OVERHEAD, now))
                continue;
            s.forwarded.put(viewer.getUuid(), header.sequence());
            if (out == null) out = new BridgeNet.RemotePose(sender.getUuid(), data);
            ServerPlayNetworking.send(viewer, out);
        }
    }

    /**
     * Whether a pose of bytes goes to one viewer at nanos, given the sequence of the last pose that viewer got from its
     * sender (null: none yet) and whether it is near; counted on the viewer. ponytail: first come, first served within
     * the budget; nearest senders first if a full server shows budget drops.
     */
    static boolean forward(NetPose.Header pose, Long last, boolean near, Viewer viewer, int bytes, long nanos) {
        if (!pose.keyframe() && (!near || last == null || last != pose.base())) {
            viewer.withheld++;
            return false;
        }
        if (!viewer.budget.take(nanos, bytes)) {
            viewer.dropped++;
            return false;
        }
        viewer.poses++;
        viewer.bytes += bytes;
        relayed++;
        relayedBytes += bytes;
        return true;
    }

    /** A player starts tracking an entity: the table of the EVE player it is, and that player's next keyframe. */
    static void tracking(Entity entity, ServerPlayerEntity viewer) {
        Sender s = entity instanceof ServerPlayerEntity player ? senders.get(player.getUuid()) : null;
        if (s == null || s.table == null) return;
        s.forwarded.remove(viewer.getUuid());
        BridgeServer.send(viewer, new BridgeNet.RemoteTable(entity.getUuid(), s.table));
    }

    /** A player left: as a sender and as a viewer. */
    static void forget(UUID player) {
        senders.remove(player);
        viewers.remove(player);
        for (Sender s : senders.values()) s.forwarded.remove(player);
    }

    /** The server stopped (an integrated one may start again in this JVM). */
    static void clear() {
        senders.clear();
        viewers.clear();
        relayed = relayedBytes = 0;
    }

    /** The relay's counts for one player, for /stellaravatar status. */
    static String status(ServerPlayerEntity player) {
        Sender s = senders.getOrDefault(player.getUuid(), new Sender());
        Viewer v = viewers.getOrDefault(player.getUuid(), new Viewer());
        return String.format("Pose relay: from you %d poses (%d KB) and %d tables, %d refused (another generation, or over %.0f a second or %.0f KiB a second);"
                + " to you %d poses (%d KB), %d dropped over your budget of %.0f KiB/s, %d deltas withheld (beyond %.0f blocks or after a"
                + " missed pose); %d poses (%d KB) relayed in all.", s.poses, s.bytes / 1000, s.tables, s.refused, RATE, SENT / 1024, v.poses,
                v.bytes / 1000, v.dropped, BUDGET / 1024, v.withheld, NEAR, relayed, relayedBytes / 1000);
    }
}
