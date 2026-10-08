package org.stellarbridge;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class ControlStateTest {
    private static final String RUN = "11111111111111111111111111111111", SESSION = "22222222222222222222222222222222";
    private static final long TIME = 1791200000000L;

    public static void main(String[] args) throws Exception {
        var state = new ControlState(RUN, SESSION);
        String neutral = state.packet(TIME);
        assert neutral.contains("EPOCH 1\nSEQ 1\n") && neutral.contains("STATE neutral\nMOVE 0 0\nLOOK 0 0\nBASE 0 0\nEDGES 0\nEND 1\n");
        state.eligibility(true, true, TIME, 0);
        assert !state.active() : "A frame alone cannot arm native controls";
        state.acknowledge(ack(1, 1, 0, "ready", TIME), TIME, 1);
        state.eligibility(true, false, TIME, 2);
        assert !state.active() : "Held physical controls must first release";
        state.eligibility(true, true, TIME, 3);
        assert state.active();
        state.axes(new ControlState.Axes(0, 32767, 100, -200));
        state.button(0x4000, true, TIME + 1);
        state.axes(new ControlState.Axes(32767, 0, 300, -400));
        state.button(0x4000, false, TIME + 2);
        state.button(0x2000, true, TIME + 3);
        String active = state.packet(TIME + 4);
        assert active.contains("EDGES 3\nEDGE 1 16384 " + (TIME + 1) + " 0 32767 100 -200\n"
                + "EDGE 2 0 " + (TIME + 2) + " 32767 0 300 -400\nEDGE 3 8192 " + (TIME + 3) + " 32767 0 300 -400\n");
        state.acknowledge(ack(1, 2, 1, "ready", TIME + 5), TIME + 5, 10);
        String overlap = state.packet(TIME + 6);
        assert overlap.contains("BASE 1 16384\nEDGES 2\nEDGE 2 0 ");
        state.acknowledge(ack(1, 2, 2, "ready", TIME + 7), TIME + 7, 499_000_000);
        assert state.packet(TIME + 8).contains("BASE 2 0\nEDGES 1\nEDGE 3 8192 ");
        state.eligibility(true, false, TIME + 9, 500_000_011L);
        assert !state.active() : "Same-SEQ progress must retire edges without renewing the adapter lease";
        assert state.epoch() == 2;
        String expired = state.packet(TIME + 10);
        assert expired.contains("STATE neutral\nMOVE 0 0\nLOOK 0 0\nBASE 0 0\nEDGES 0\n");

        var stalled = ready();
        stalled.button(0x4000, true, TIME);
        stalled.packet(TIME);
        stalled.acknowledge(ack(1, 2, 1, "ready", TIME + 501), TIME + 501, 500_000_001L);
        assert !stalled.active() && stalled.epoch() == 2 : "A fresh higher-SEQ ACK cannot revive an already expired epoch";
        stalled.packet(TIME + 501);
        stalled.acknowledge(ack(2, 1, 0, "ready", TIME + 502), TIME + 502, 501_000_000L);
        stalled.eligibility(true, false, TIME + 502, 501_000_001L);
        assert !stalled.active() : "Rearming after a stall still requires physical release";
        stalled.eligibility(true, true, TIME + 503, 502_000_000L);
        assert stalled.active();

        // Each ownership change requires a new neutral epoch, native ready and fresh physical release.
        for (String boundary : new String[] {"menu", "focus", "build", "stale frame"}) {
            var next = ready();
            long epoch = next.epoch();
            next.neutral(boundary);
            next.packet(TIME);
            next.acknowledge(ack(epoch, 1, 0, "ready", TIME), TIME, 0);
            next.eligibility(true, true, TIME, 1);
            assert !next.active() : "An old epoch ACK cannot rearm " + boundary;
            next.acknowledge(ack(epoch + 1, 1, 0, "ready", TIME), TIME, 2);
            next.eligibility(true, false, TIME, 3);
            assert !next.active();
            next.eligibility(true, true, TIME, 4);
            assert next.active();
        }
        var blocked = ready();
        blocked.acknowledge(ack(1, 1, 0, "blocked", TIME), TIME, 1);
        assert !blocked.active() && blocked.epoch() == 2;
        var released = ready();
        released.acknowledge(ack(1, 1, 0, "released", TIME), TIME, 1);
        assert !released.active() && released.epoch() == 2;
        for (ControlState.Ack invalid : new ControlState.Ack[] {ack(1, 2, 0, "ready", TIME), ack(1, 1, 1, "ready", TIME)}) {
            var next = ready(); next.acknowledge(invalid, TIME, 1); assert !next.active();
        }
        for (long ackTime : new long[] {TIME - 501, TIME + 101}) {
            var next = new ControlState(RUN, SESSION); next.packet(TIME);
            next.acknowledge(ack(1, 1, 0, "ready", ackTime), TIME, 1);
            next.eligibility(true, true, TIME, 2); assert !next.active();
        }
        var queued = ready();
        for (int i = 0; i < 32; i++) queued.button(0x1000, i % 2 == 0, TIME);
        assert queued.packet(TIME).contains("EDGES 32\n");
        queued.button(0x1000, true, TIME);
        assert !queued.active() && queued.packet(TIME).contains("BASE 0 0\nEDGES 0\n");
        var walk = ready(); walk.button(0x40000, true, TIME);
        assert walk.packet(TIME).contains("EDGE 1 262144 ") : "The walk key is an ordered edge";
        rejects(() -> walk.button(0x80000, true, TIME));
        var oldEdge = ready(); oldEdge.button(0x1000, true, TIME);
        oldEdge.eligibility(true, false, TIME + 501, 1);
        assert !oldEdge.active();
        var stoppedState = ready(); stoppedState.button(0x1000, true, TIME);
        stoppedState.stop();
        String stopped = stoppedState.packet(TIME + 1);
        assert stopped.contains("EPOCH 1\nSEQ 2\n") && stopped.contains("STATE stopped\nMOVE 0 0\nLOOK 0 0\nBASE 0 0\nEDGES 0\n");
        stoppedState.acknowledge(ack(1, 2, 0, "ready", TIME + 1), TIME + 1, 1);
        stoppedState.eligibility(true, true, TIME + 1, 2); assert !stoppedState.active();
        assert CaptureView.controlFrameFresh(10, 500_000_010L) && !CaptureView.controlFrameFresh(10, 500_000_011L);
        assert !CaptureView.controlFrameFresh(10, 9);

        String goodAck = ackText(1, 1, 0, "ready", TIME);
        for (String bad : new String[] {goodAck + "EXTRA\n", goodAck.replace("\n", "\r\n"), goodAck.replace("END 1", "END 2"),
                goodAck.replace("STATE ready", "STATE active"), goodAck.replace("EPOCH 1", "EPOCH 0"),
                goodAck.replace("SEQ 1", "SEQ -1"), goodAck.replace("EDGE 0", "EDGE 9007199254740992"),
                goodAck.replace("RUN " + RUN, "RUN ABCD"), "x".repeat(513)}) rejects(() -> ControlState.parseAck(bad.getBytes(StandardCharsets.US_ASCII)));

        Path directory = Files.createTempDirectory("stellar-control-packets-");
        try {
            var files = new ControlFiles(directory);
            files.publish(neutral);
            assert Files.readString(directory.resolve("eve-input.txt")).equals(neutral);
            Files.writeString(directory.resolve("eve-input-ack.txt"), goodAck);
            assert files.readAck().equals(ack(1, 1, 0, "ready", TIME));
            Files.delete(directory.resolve("eve-input-ack.txt"));
            Files.createSymbolicLink(directory.resolve("eve-input-ack.txt"), directory.resolve("eve-input.txt"));
            rejects(files::readAck);
            rejects(() -> files.publish("x".repeat(4097)));
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        if (args.length == 1) {
            Path output = Path.of(args[0]); Files.createDirectories(output);
            var fixture = ready();
            fixture.axes(new ControlState.Axes(0, 32767, 100, -200));
            fixture.button(0x4000, true, TIME + 1);
            fixture.axes(new ControlState.Axes(32767, 0, 300, -400));
            fixture.button(0x4000, false, TIME + 2);
            fixture.button(0x2000, true, TIME + 3);
            assert fixture.packet(TIME + 4).equals(active);
            fixture.acknowledge(ack(1, 2, 1, "ready", TIME + 5), TIME + 5, 10);
            assert fixture.packet(TIME + 6).equals(overlap);
            fixture.stop();
            Files.writeString(output.resolve("java-neutral.txt"), neutral);
            Files.writeString(output.resolve("java-active.txt"), active);
            Files.writeString(output.resolve("java-overlap.txt"), overlap);
            Files.writeString(output.resolve("java-stopped.txt"), fixture.packet(TIME + 7));
            Files.writeString(output.resolve("java-ack-fixture.txt"), goodAck);
            var waiting = new ControlState(RUN, SESSION);
            Files.writeString(output.resolve("java-wait-1.txt"), waiting.packet(TIME));
            waiting.acknowledge(ack(1, 1, 0, "ready", TIME + 1), TIME + 1, 1_000_000);
            waiting.eligibility(true, false, TIME + 50, 50_000_000);
            Files.writeString(output.resolve("java-wait-2.txt"), waiting.packet(TIME + 50));
            waiting.acknowledge(ack(1, 2, 0, "ready", TIME + 51), TIME + 51, 51_000_000);
            waiting.eligibility(true, false, TIME + 100, 100_000_000);
            Files.writeString(output.resolve("java-wait-3.txt"), waiting.packet(TIME + 100));
            waiting.acknowledge(ack(1, 3, 0, "ready", TIME + 101), TIME + 101, 101_000_000);
            waiting.eligibility(true, true, TIME + 102, 102_000_000);
            Files.writeString(output.resolve("java-wait-active.txt"), waiting.packet(TIME + 102));
        }
        System.out.println("Controller tests passed: exact ordered snapshots, native-neutral handshake, ACK progression/lease, ownership boundaries and bounded files");
    }
    private static ControlState ready() {
        var state = new ControlState(RUN, SESSION); state.packet(TIME);
        state.acknowledge(ack(1, 1, 0, "ready", TIME), TIME, 0);
        state.eligibility(true, true, TIME, 0);
        assert state.active(); return state;
    }
    private static ControlState.Ack ack(long epoch, long sequence, long edge, String state, long time) {
        return ControlState.parseAck(ackText(epoch, sequence, edge, state, time).getBytes(StandardCharsets.US_ASCII));
    }
    private static String ackText(long epoch, long sequence, long edge, String state, long time) {
        return "SBMC_INPUT_ACK 1\nRUN " + RUN + "\nSESSION " + SESSION + "\nEPOCH " + epoch + "\nSEQ " + sequence
                + "\nTIME " + time + "\nEDGE " + edge + "\nSTATE " + state + "\nEND " + sequence + "\n";
    }
    private static void rejects(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid controller data accepted"); }
        catch (IllegalArgumentException | java.io.IOException expected) { }
    }
    private interface Action { void run() throws Exception; }
}
