package org.stellarbridge;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Ordered controller transitions retained until the native gameplay poll acknowledges them. */
final class ControlState {
    static final int BUTTONS = 0x7f3ff; // XInput's buttons, LT and RT (0x10000, 0x20000) and the walk key (0x40000).
    private static final long MAX = 9007199254740991L, LEASE = 500_000_000L;
    record Axes(int lx, int ly, int rx, int ry) {
        static final Axes ZERO = new Axes(0, 0, 0, 0);
        Axes {
            require(lx >= -32767 && lx <= 32767 && ly >= -32767 && ly <= 32767
                    && rx >= -32767 && rx <= 32767 && ry >= -32767 && ry <= 32767, "Invalid controller axes");
        }
    }
    record Edge(long id, int mask, long time, Axes axes) { }
    record Ack(String run, String session, long epoch, long sequence, long time, long edge, String state) { }
    private final String run, session;
    private final List<Edge> edges = new ArrayList<>();
    private long epoch = 1, sequence, nextEdge, base, lastAckSequence, lastAckEdge, lastAckNanos = -1;
    private int baseMask, mask;
    private Axes axes = Axes.ZERO;
    private boolean eligible, active, ready, stopped;
    private String reason = "waiting for native neutral acknowledgement";

    ControlState(String run, String session) {
        require(identity(run) && identity(session), "Invalid controller binding");
        this.run = run; this.session = session;
    }

    void eligibility(boolean allowed, boolean allPhysicalReleased, long nowMs, long nowNanos) {
        if (stopped) return;
        if (!allowed && eligible) neutral("Minecraft controls suspended");
        eligible = allowed;
        if ((active || ready) && (lastAckNanos < 0 || nowNanos - lastAckNanos > LEASE))
            neutral("native input acknowledgement expired");
        if (!edges.isEmpty() && nowMs - edges.getFirst().time > 500)
            neutral("queued controller input expired");
        if (!active && eligible && ready && allPhysicalReleased) {
            active = true;
            reason = "active";
        }
    }

    void axes(Axes axes) { this.axes = active ? axes : Axes.ZERO; }

    void button(int bit, boolean down, long time) {
        require(Integer.bitCount(bit) == 1 && (bit & BUTTONS) == bit, "Invalid controller button");
        if (!active || stopped) return;
        int next = down ? mask | bit : mask & ~bit;
        if (next == mask) return;
        if (edges.size() == 32) { neutral("controller queue overflow; release physical controls"); return; }
        require(time >= 0 && time <= MAX, "Invalid controller edge time");
        edges.add(new Edge(++nextEdge, next, time, axes));
        mask = next;
    }

    void acknowledge(Ack ack, long nowMs, long nowNanos) {
        if (stopped || !run.equals(ack.run) || !session.equals(ack.session) || epoch != ack.epoch) return;
        if ((active || ready) && (lastAckNanos < 0 || nowNanos - lastAckNanos > LEASE)) {
            neutral("native input acknowledgement expired");
            return;
        }
        if (nowMs - ack.time > 500 || ack.time - nowMs > 100) return;
        if (ack.sequence > sequence || ack.sequence < lastAckSequence || ack.edge > nextEdge || ack.edge < lastAckEdge) {
            neutral("invalid native acknowledgement progression");
            return;
        }
        if (!ack.state.equals("ready")) { neutral("native input " + ack.state); return; }
        if (ack.sequence > lastAckSequence) {
            lastAckSequence = ack.sequence;
            lastAckNanos = nowNanos;
            ready = true;
        }
        // Native can deliver more edges at the same packet sequence. They retire input, but never refresh the lease.
        lastAckEdge = ack.edge;
        while (!edges.isEmpty() && edges.getFirst().id <= ack.edge) {
            Edge delivered = edges.removeFirst();
            base = delivered.id;
            baseMask = delivered.mask;
        }
    }

    void neutral(String why) {
        if (stopped) return;
        epoch = Math.addExact(epoch, 1);
        require(epoch <= MAX, "Controller epoch exhausted");
        sequence = nextEdge = base = lastAckSequence = lastAckEdge = 0;
        lastAckNanos = -1;
        baseMask = mask = 0;
        axes = Axes.ZERO;
        edges.clear();
        active = ready = eligible = false;
        reason = why;
    }

    void unavailable(String why) { if (active || ready) neutral(why); }
    void stop() {
        edges.clear();
        axes = Axes.ZERO;
        active = ready = eligible = false;
        stopped = true;
        reason = "stopped";
    }
    boolean active() { return active && !stopped; }
    String run() { return run; }
    String session() { return session; }
    String reason() { return reason; }
    long epoch() { return epoch; }

    String packet(long time) {
        require(time >= 0 && time <= MAX, "Invalid controller packet time");
        if (!edges.isEmpty() && edges.getLast().time > time) neutral("wall clock moved backwards");
        sequence = Math.addExact(sequence, 1);
        require(sequence <= MAX, "Controller sequence exhausted");
        boolean send = active();
        Axes output = send ? axes : Axes.ZERO;
        StringBuilder text = new StringBuilder("SBMC_INPUT 1\nRUN ").append(run).append("\nSESSION ").append(session)
                .append("\nEPOCH ").append(epoch).append("\nSEQ ").append(sequence).append("\nTIME ").append(time)
                .append("\nSTATE ").append(stopped ? "stopped" : send ? "active" : "neutral")
                .append("\nMOVE ").append(output.lx).append(' ').append(output.ly)
                .append("\nLOOK ").append(output.rx).append(' ').append(output.ry)
                .append("\nBASE ").append(send ? base : 0).append(' ').append(send ? baseMask : 0)
                .append("\nEDGES ").append(send ? edges.size() : 0).append('\n');
        if (send) for (Edge edge : edges) text.append("EDGE ").append(edge.id).append(' ').append(edge.mask).append(' ')
                .append(edge.time).append(' ').append(edge.axes.lx).append(' ').append(edge.axes.ly).append(' ')
                .append(edge.axes.rx).append(' ').append(edge.axes.ry).append('\n');
        text.append("END ").append(sequence).append('\n');
        require(text.length() <= 4096, "Controller packet exceeds limits");
        return text.toString();
    }

    static Ack parseAck(byte[] bytes) {
        require(bytes.length > 0 && bytes.length <= 512, "Controller ACK exceeds limits");
        for (byte value : bytes) require(value == '\n' || value >= 32 && value <= 126, "Controller ACK must be ASCII/LF");
        String[] lines = new String(bytes, StandardCharsets.US_ASCII).split("\n", -1);
        require(lines.length == 10 && lines[9].isEmpty() && lines[0].equals("SBMC_INPUT_ACK 1"), "Invalid controller ACK framing");
        String run = field(lines[1], "RUN"), session = field(lines[2], "SESSION");
        require(identity(run) && identity(session), "Invalid controller ACK binding");
        long epoch = integer(field(lines[3], "EPOCH"), 1), seq = integer(field(lines[4], "SEQ"), 1);
        long time = integer(field(lines[5], "TIME"), 0), edge = integer(field(lines[6], "EDGE"), 0);
        String state = field(lines[7], "STATE");
        require(state.equals("ready") || state.equals("blocked") || state.equals("released"), "Invalid controller ACK state");
        require(integer(field(lines[8], "END"), 1) == seq, "Controller ACK footer mismatch");
        return new Ack(run, session, epoch, seq, time, edge, state);
    }
    /**
     * The adapter's slot-0 XInputGetState callers (eve-input-calls.txt): totals since it loaded and calls per second over
     * its last interval, from the recognized gameplay poll and from any other caller. Another caller at the game's poll
     * rate with no gameplay calls is an overlay hook (Steam Input) calling on the game's behalf: input is never forwarded.
     * Otherwise, no gameplay call at all since the adapter loaded most likely means an overlay answered Unreal's poll
     * itself, as disconnected, so Unreal stopped polling: neither the pose stream nor input runs.
     */
    record Calls(long time, long gameplay, long gameplayRate, long other, long otherRate) {
        /** One status sentence while the report is fresh (an adapter running now), else empty. */
        String status(long nowMs) {
            if (nowMs - time > 3000 || time - nowMs > 1000) return "";
            return String.format(" XInput slot 0: %d calls/s from the gameplay poll, %d/s from other callers%s.", gameplayRate, otherRate,
                    gameplayRate == 0 && otherRate >= 10 ? " (the gameplay poll is not recognized: disable Steam Input for Stellar Blade)"
                            : gameplay == 0 ? " (Stellar Blade's gameplay poll never reached it: an overlay most likely answers for the"
                            + " adapter; disable Steam Input or the Steam overlay for Stellar Blade)" : "");
        }
    }
    static Calls parseCalls(byte[] bytes) {
        require(bytes.length > 0 && bytes.length <= 512, "Callers report exceeds limits");
        for (byte value : bytes) require(value == '\n' || value >= 32 && value <= 126, "Callers report must be ASCII/LF");
        String[] lines = new String(bytes, StandardCharsets.US_ASCII).split("\n", -1);
        require(lines.length == 6 && lines[5].isEmpty() && lines[0].equals("SBMC_XINPUT_CALLS 1") && lines[4].equals("END"),
                "Invalid callers report framing");
        long time = integer(field(lines[1], "TIME"), 1);
        long[] gameplay = counts(lines[2], "GAMEPLAY"), other = counts(lines[3], "OTHER");
        return new Calls(time, gameplay[0], gameplay[1], other[0], other[1]);
    }
    private static long[] counts(String line, String tag) {
        String[] fields = line.split(" ", -1);
        require(fields.length == 3 && fields[0].equals(tag), "Invalid callers report field");
        long count = integer(fields[1], 0), rate = integer(fields[2], 0);
        require(count <= 0xffffffffL && rate <= 0xffffffffL, "Callers report count exceeds 32 bits");
        return new long[] {count, rate};
    }
    private static String field(String line, String tag) {
        String[] fields = line.split(" ", -1);
        require(fields.length == 2 && fields[0].equals(tag) && !fields[1].isEmpty(), "Invalid controller ACK field");
        return fields[1];
    }
    private static long integer(String value, long minimum) {
        require(value.matches("[0-9]{1,16}"), "Invalid controller ACK integer");
        long parsed = Long.parseLong(value);
        require(parsed >= minimum && parsed <= MAX, "Controller ACK integer exceeds limits");
        return parsed;
    }
    private static boolean identity(String value) { return value != null && value.matches("[0-9a-f]{32}"); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalArgumentException(message); }
}
