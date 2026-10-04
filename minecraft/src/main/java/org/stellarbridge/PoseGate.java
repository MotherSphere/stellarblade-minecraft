package org.stellarbridge;

/** Monotonic packet selection for one explicitly bound session. Server-thread confined. */
final class PoseGate {
    private final String session;
    private final BridgeProtocol.Box bounds;
    private BridgeProtocol.Eve latest;

    PoseGate(String session, BridgeProtocol.Box bounds) {
        this.session = session;
        this.bounds = bounds;
    }

    BridgeProtocol.Eve accept(BridgeProtocol.Eve packet, int terrainRevision, long now) {
        if (packet == null || !session.equals(packet.session())) return null;
        if (latest != null && (packet.seq() < latest.seq()
                || (packet.seq() == latest.seq() && !packet.equals(latest)))) return null;
        latest = packet;
        if (!BridgeProtocol.fresh(packet.time(), now) || !packet.state().equals("entered")
                || packet.ack() < 1 || packet.ack() > terrainRevision) return null;
        if (packet.x() < bounds.minX() || packet.x() > bounds.maxX()
                || packet.y() < bounds.minY() || packet.y() > bounds.maxY()
                || packet.z() < bounds.minZ() || packet.z() > bounds.maxZ()) return null;
        return packet;
    }
}
