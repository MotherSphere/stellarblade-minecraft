package org.stellarbridge;

import java.io.IOException;
import java.util.List;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client half of one bridge link, which owns the bridge directory Stellar Blade reads: it relays each new eve.N
 * packet to the server, predicts EVE's position with the same PoseGate the server applies (the player never waits a
 * round trip), and writes what the server sends (terrain, mob walls, combat verdicts) and the lock-on target. Every TIME
 * it writes is this machine's clock, never the server's: Lua compares them with its own. Client thread only.
 */
final class BridgeLink {
    private static final Logger LOG = LoggerFactory.getLogger("stellar_bridge");
    /**
     * A session request awaiting Stellar Blade's answer. Lua takes it within 2.5 s of its millisecond TIME by a wall clock
     * in whole seconds, so never later than 3.5 s: past ANSWER it can no longer run, and the next one may go.
     */
    private record Request(long seq, String action, long sentNanos) { }
    private static final long ANSWER = 5_000_000_000L;

    final String session;
    final Vec3d origin;
    final String dimension;
    private final BridgeFiles files;
    private final PoseGate gate;
    private BridgeProtocol.Terrain terrain; // The newest snapshot; its TIME is never written.
    private List<BridgeProtocol.Box> mobs = List.of();
    private boolean mobsListed; // The last eve-mobs.txt listed at least one entity.
    private BridgeProtocol.Eve relayed; // The newest packet sent to the server.
    private Vec3d position; // EVE's feet (world) from the newest accepted packet, null while the link is not live.
    private int ticks;
    private long combatSeq, lockSeq, mobSeq, requestSeq;
    private Request request;

    BridgeLink(BridgeFiles files, String session, Vec3d origin, String dimension) {
        this.files = files;
        this.session = session;
        this.origin = origin;
        this.dimension = dimension;
        this.gate = new PoseGate(session);
    }

    /** The newest terrain revision, 0 before the first. */
    int revision() { return terrain == null ? 0 : terrain.seq(); }

    /** EVE's feet in the world while the link is live, else null. */
    Vec3d position() { return position; }

    /** A newer terrain snapshot of this session: its window joins the gate, and terrain.txt is written now. */
    void terrain(BridgeProtocol.Terrain snapshot, long now) throws IOException {
        if (!snapshot.session().equals(session) || snapshot.seq() <= revision()) return;
        gate.published(snapshot.seq(), snapshot.bounds());
        terrain = snapshot;
        writeTerrain(now);
    }

    /**
     * Each client tick at wall milliseconds now: EVE's position from the newest eve.N, judged as the server's gate judges
     * it, then the heartbeats: terrain every 4 ticks, mob walls while listed every tick. Clients tick at the world's rate,
     * so 4 ticks of a perfect dodge's slowdown outlast Lua's 0.6 s wall lease. Returns that packet's text when the server
     * has not had it yet, else null.
     */
    String tick(long now) throws IOException {
        String text = files.readEve(session);
        BridgeProtocol.Eve packet = text == null ? null : BridgeProtocol.parseEve(text);
        String relay = packet != null && !packet.equals(relayed) ? text : null;
        if (packet != null) relayed = packet;
        BridgeProtocol.Eve eve = gate.accept(packet, revision(), now / 1000);
        position = eve == null ? null : origin.add(eve.x(), eve.y(), eve.z());
        if (++ticks % 4 == 0 && terrain != null) writeTerrain(now);
        if (!mobs.isEmpty()) writeMobs(now);
        return relay;
    }

    private void writeTerrain(long now) throws IOException {
        var t = terrain;
        files.publish(BridgeProtocol.encodeTerrain(new BridgeProtocol.Terrain(t.session(), t.seq(), now / 1000, t.dimension(),
                t.originX(), t.originY(), t.originZ(), t.bounds(), t.boxes())));
    }

    /**
     * The server's mob walls, written now and renewed with the heartbeat while they list any and the link is live; an
     * empty list is written once, then nothing: Lua drops its walls when the heartbeat stops.
     */
    void mobs(List<BridgeProtocol.Box> boxes, long now) throws IOException {
        mobs = boxes;
        writeMobs(now);
    }

    private void writeMobs(long now) throws IOException {
        if (position == null || mobs.isEmpty() && !mobsListed) return;
        files.write("eve-mobs.txt", BridgeProtocol.encodeMobs(session, ++mobSeq, now, mobs));
        mobsListed = !mobs.isEmpty();
    }

    /** A combat verdict for Stellar Blade to answer (an index in BridgeProtocol.COMBAT_EVENTS), with the attacker's yaw or NaN. */
    void verdict(int event, double yaw, long now) throws IOException {
        files.write("eve-combat.txt", BridgeProtocol.encodeCombat(session, ++combatSeq, now, BridgeProtocol.COMBAT_EVENTS.get(event), yaw));
    }

    /** The lock-on target's centre (world) for Stellar Blade, null once released. */
    void lock(Vec3d center, long now) throws IOException {
        double[] relative = center == null ? null : new double[] {center.x - origin.x, center.y - origin.y, center.z - origin.z};
        files.write("eve-lock.txt", BridgeProtocol.encodeLock(session, ++lockSeq, now, relative));
    }

    /**
     * /stellar import, enter and return: Stellar Blade's F5, F8 and F11 through a session request (eve-request.txt), one at
     * a time. Returns why it was not sent, or null.
     */
    String request(String action, long now, long nanos) throws IOException {
        if (request != null) return "Waiting for Stellar Blade's answer to /stellar " + request.action() + ".";
        long seq = requestSeq + 1;
        files.write("eve-request.txt", BridgeProtocol.encodeRequest(session, seq, now, action));
        requestSeq = seq;
        request = new Request(seq, action, nanos);
        return null;
    }

    /** Each tick: Stellar Blade's answer to the pending session request for chat; a request unanswered after ANSWER, once. */
    String answer(long nanos) {
        if (request == null) return null;
        BridgeProtocol.RequestAck ack = null;
        try {
            String text = files.read("eve-request-ack.txt");
            if (text != null) ack = BridgeProtocol.parseRequestAck(text);
        } catch (IOException | IllegalArgumentException unreadable) {
            // Malformed, foreign or being replaced: only a valid answer to this request counts.
        }
        if (ack != null && ack.session().equals(session) && ack.seq() == request.seq() && ack.action().equals(request.action())) {
            LOG.info("Session request {} {}: {}: {}", ack.seq(), ack.action(), ack.result(), ack.message());
            request = null;
            return "Stellar Blade " + ack.action() + " " + ack.result() + ": " + ack.message();
        }
        if (nanos - request.sentNanos() <= ANSWER) return null;
        LOG.info("Session request {} {}: no answer", request.seq(), request.action());
        String action = request.action();
        request = null;
        return "Stellar Blade did not answer /stellar " + action + ". Check that it runs Lua revision 57, or use its key.";
    }

    /** The link ended: Stellar Blade's inputs from it go; it drops the link when the heartbeats stop. */
    void stop() throws IOException { files.stop(); }
}
