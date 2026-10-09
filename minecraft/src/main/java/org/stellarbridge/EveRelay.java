package org.stellarbridge;

/**
 * The server's view of one link's eve.N stream: the newest packet the owner's client relayed, and that client's clock.
 * Lua stamps TIME with the clock of the machine that runs Stellar Blade, so the server judges it by that clock: the
 * client's wall time at sending minus the server's at arrival, transit included (it only makes a packet look older).
 * Server-thread confined, one per session.
 */
final class EveRelay {
    private final String session;
    private BridgeProtocol.Eve latest;
    private long clock; // Client wall milliseconds minus the server's, at the newest packet's arrival.

    EveRelay(String session) { this.session = session; }

    /** A relayed packet, sent at the client's wall milliseconds and arriving at the server's: kept if it is this session's. */
    boolean receive(String text, long sent, long arrival) {
        BridgeProtocol.Eve packet;
        try {
            packet = BridgeProtocol.parseEve(text);
        } catch (IllegalArgumentException malformed) {
            return false;
        }
        if (!packet.session().equals(session)) return false;
        latest = packet;
        clock = sent - arrival;
        return true;
    }

    BridgeProtocol.Eve latest() { return latest; }

    long clock() { return clock; }

    /** The client's wall clock in whole seconds, Lua's TIME unit, at the server's wall milliseconds now. */
    static long clientSeconds(long now, long clock) { return Math.floorDiv(now + clock, 1000); }
}
