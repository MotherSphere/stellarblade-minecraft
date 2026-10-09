package org.stellarbridge;

import java.util.TreeMap;

/**
 * Monotonic packet selection for one explicitly bound session, against the terrain windows it published. Confined to
 * one thread: the server's, or the owner's client, which predicts EVE's position with the same rules.
 */
final class PoseGate {
    /** Seconds without a packet after which Stellar Blade counts as stopped; a pause menu stays well under it. */
    static final long STOPPED = 30;
    private final String session;
    // Each terrain window from the first revision that published it; older ones go once EVE acknowledges a newer one.
    private final TreeMap<Integer, BridgeProtocol.Box> windows = new TreeMap<>();
    private BridgeProtocol.Eve latest;

    PoseGate(String session) {
        this.session = session;
    }

    /** A published terrain revision and its window (relative to the origin). */
    void published(int revision, BridgeProtocol.Box window) {
        var last = windows.lastEntry();
        if (last == null || !last.getValue().equals(window)) windows.put(revision, window);
    }

    /**
     * The packet, or null: EVE's feet must lie inside the window of the revision she acknowledges, whose guards hold her
     * in Stellar Blade.
     */
    BridgeProtocol.Eve accept(BridgeProtocol.Eve packet, int terrainRevision, long now) {
        if (packet == null || !session.equals(packet.session())) return null;
        if (latest != null && (packet.seq() < latest.seq()
                || (packet.seq() == latest.seq() && !packet.equals(latest)))) return null;
        latest = packet;
        if (!BridgeProtocol.fresh(packet.time(), now) || !packet.state().equals("entered")
                || packet.ack() < 1 || packet.ack() > terrainRevision) return null;
        var window = windows.floorEntry(packet.ack());
        if (window == null) return null;
        BridgeProtocol.Box bounds = window.getValue();
        if (packet.x() < bounds.minX() || packet.x() > bounds.maxX()
                || packet.y() < bounds.minY() || packet.y() > bounds.maxY()
                || packet.z() < bounds.minZ() || packet.z() > bounds.maxZ()) return null;
        windows.headMap(window.getKey()).clear();
        return packet;
    }

    /**
     * Whether EVE has left the link: no packet yet, or the newest says she returned (idle) or only imported (ready), or
     * is STOPPED seconds old (Stellar Blade stopped publishing).
     */
    boolean away(long now) {
        return latest == null || !latest.state().equals("entered") || now - latest.time() > STOPPED;
    }
}
