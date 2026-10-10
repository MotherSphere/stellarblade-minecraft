package org.stellarbridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.function.BooleanSupplier;

/** Real file mappings and an independent producer peer; no game, GL context or image codec. */
public final class RawFrameBusTest {
    static final String RUN = "11111111111111111111111111111111", SESSION = "22222222222222222222222222222222";
    static String metadata(int width, int height, long time, long counter) {
        String id = time + "-" + counter;
        int bytes = width * height * 4;
        return "SBMC_FRAME 6 " + id + " " + time + "\nSIZE " + width + " " + height
                + "\nCAMERA 0 0 100 0 90 0 75\nPAWN 0 0 0 90\nCONTEXT 1 2\nLINK " + SESSION
                + " 1 100 0 0 0\nCOLOR raw " + bytes + "\nMASK depth 0\nDEPTH raw " + bytes
                + "\nRUN " + RUN + "\nEND " + id + "\n";
    }
    public static void main(String[] args) throws Exception {
        if (args.length == 2) { interop(Path.of(args[0]), Integer.parseInt(args[1])); return; }
        String valid = metadata(2560, 1440, 1791200000, 1);
        var parsed = CaptureFrame.parse(valid.getBytes(StandardCharsets.US_ASCII));
        assert parsed.version() == 6 && parsed.width() == 2560 && parsed.height() == 1440;
        for (String bad : new String[] {valid.replace("2560 1440", "3841 1440"), valid.replace("2560 1440", "2560 2161"),
                valid.replace("COLOR raw", "COLOR image.bmp"), valid.replace("MASK depth 0", "MASK raw 1"), valid.replace("MASK depth 0", "MASK none 0"),
                valid.replace("DEPTH raw 14745600", "DEPTH raw 4"), valid.replace("SBMC_FRAME 6", "SBMC_FRAME 4"), valid.replace("SBMC_FRAME 6", "SBMC_FRAME 5")})
            rejects(() -> CaptureFrame.parse(bad.getBytes(StandardCharsets.US_ASCII)));
        assert java.util.Arrays.equals(RawFrameBus.dimensions(5120, 2880), new int[] {3840, 2160});
        assert java.util.Arrays.equals(RawFrameBus.dimensions(2560, 1440), new int[] {2560, 1440});
        assert Math.abs(CaptureProjection.horizontalExtent(1080, 1920, 1080, 1920) - 1) < 1e-8;
        if (!com.sun.jna.Platform.isLinux()) {
            rejects(() -> new RawFrameBus(Path.of("unused"), RUN, SESSION, 2, 2, System.currentTimeMillis())); // Refused first.
            System.out.println("SKIP raw frame mappings: capture mode is Linux-only");
            return;
        }
        Path directory = Files.createTempDirectory("stellar-raw-frames-");
        try {
            long now = System.currentTimeMillis();
            try (var bus = new RawFrameBus(directory, RUN, SESSION, 2, 2, now); var peer = new Peer(bus.path)) {
                assert Files.size(bus.path) == 16576;
                assert Files.getPosixFilePermissions(bus.path).equals(PosixFilePermissions.fromString("rw-------"));
                rejects(() -> new RawFrameBus(directory, RUN, SESSION, 2, 2, now));
                assert Files.exists(bus.path) : "Exclusive-create failure must not delete an existing mapping";
                assert bus.poll(now, System.nanoTime()) == null;
                peer.ready(now);
                peer.publish(1, metadata(2, 2, now / 1000, 1), now);
                try (var first = bus.poll(now, System.nanoTime())) {
                    assert first.color.get(3) == (byte) 128 : "Fractional coverage must survive";
                    assert first.depth.getFloat(0) == 1;
                    for (int i = 2; i <= 80; i++) peer.publish(i, metadata(2, 2, now / 1000, i), now);
                    try (var latest = bus.poll(now, System.nanoTime())) {
                        assert latest.metadata.id().endsWith("-80") && latest.color.get(0) == 80;
                        assert first.color.get(0) == 1 : "Retained CPU frame changed after mapped front rotation";
                    }
                }
                bus.stop();
                assert (int) RawFrameBus.INT.getAcquire(peer.map, 256) == 2 && !bus.reap();
                assert Files.exists(bus.path) : "Claimed unresolved mapping must remain intact";
                peer.stopped(); assert bus.reap() && !Files.exists(bus.path);
            }
            try (var absent = new RawFrameBus(directory, RUN, SESSION, 2, 2, now)) {
                rejects(() -> absent.poll(now, System.nanoTime() + 3_100_000_000L));
                assert absent.reap();
            }
            try (var cancelled = new RawFrameBus(directory, RUN, SESSION, 2, 2, now); var peer = new Peer(cancelled.path)) {
                assert cancelled.reap();
                assert (int) RawFrameBus.INT.getAcquire(peer.map, 192) == -1 : "Unclaimed cancellation must reserve the native claim atomically";
                assert !(boolean) RawFrameBus.INT.compareAndSet(peer.map, 192, 0, 123) : "A late native claimant must lose cancellation";
            }
            try (var claimed = new RawFrameBus(directory, RUN, SESSION, 2, 2, now); var peer = new Peer(claimed.path)) {
                assert (boolean) RawFrameBus.INT.compareAndSet(peer.map, 192, 0, 123);
                assert !claimed.reap() && Files.exists(claimed.path) : "Cancellation must retain a native claim it lost";
                peer.stopped(); assert claimed.reap();
            }
            try (var retry = new RawFrameBus(directory, RUN, SESSION, 2, 2, now); var peer = new Peer(retry.path)) {
                peer.ready(now); peer.stopped();
                // Everything is test-owned: a nonempty replacement directory forces deletion to fail after unmapping.
                Files.delete(retry.path); Files.createDirectory(retry.path);
                Path child = retry.path.resolve("owned-test-child"); Files.writeString(child, "retry");
                rejects(retry::reap);
                Files.delete(child);
                assert retry.reap() && !Files.exists(retry.path) : "Authorized deletion must retry after unmapping";
            }
            try (var unresolved = new RawFrameBus(directory, RUN, SESSION, 2, 2, now); var peer = new Peer(unresolved.path)) {
                peer.ready(now);
                unresolved.close(); unresolved.reap();
                assert Files.exists(unresolved.path) : "Ordinary close must never authorize later deletion of a live producer file";
                peer.stopped(); Files.delete(unresolved.path);
            }
            for (String invalid : new String[] {"middle", "metadata", "sequence", "depth", "context", "reserved", "lease"}) {
                try (var bus = new RawFrameBus(directory, RUN, SESSION, 2, 2, now); var peer = new Peer(bus.path)) {
                    peer.ready(now);
                    String packet = metadata(2, 2, now / 1000, 1);
                    if (invalid.equals("metadata")) packet = packet.replace("RUN " + RUN, "RUN " + SESSION);
                    if (invalid.equals("sequence")) packet = packet.replace(now / 1000 + "-1", now / 1000 + "-2");
                    if (invalid.equals("context")) packet = packet.replace("CONTEXT 1 2", "CONTEXT 0 2");
                    int slot = peer.publish(1, packet, now);
                    if (invalid.equals("middle")) RawFrameBus.INT.setRelease(peer.map, 128, 7);
                    if (invalid.equals("depth")) peer.map.putFloat(slot + 4096 + 16, Float.NaN);
                    if (invalid.equals("reserved")) peer.map.put(slot + 24, (byte) 1);
                    if (invalid.equals("lease")) {
                        long monotonic = System.nanoTime();
                        try (var first = bus.poll(now, monotonic)) { assert first != null; }
                        peer.ready(now + 1); // A fresh wall heartbeat cannot revive an already expired monotonic lease.
                        rejects(() -> bus.poll(now + 1, monotonic + 3_000_000_001L));
                    } else rejects(() -> bus.poll(now, System.nanoTime()));
                    assert (int) RawFrameBus.INT.getAcquire(peer.map, 256) == 2 : "Invalid raw data must revoke this run";
                    peer.stopped(); assert bus.reap();
                }
            }
            recycling(directory);
            scheduling(directory);
            stream(directory);
            failedRun(directory);
        } finally {
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Raw frame tests passed: real JNA mapping, triple-slot ownership, bounded v6, fractional coverage, raw worker/request and stop/restart");
    }
    private static void recycling(Path directory) throws Exception {
        long now = System.currentTimeMillis();
        try (var bus = new RawFrameBus(directory, RUN, SESSION, 2, 2, now); var peer = new Peer(bus.path)) {
            peer.ready(now);
            var released = new RawFrameBus.Frame[4];
            var borrowed = new RawFrameBus.Frame[4];
            try {
                for (int i = 0; i < released.length; i++) {
                    peer.publish(i + 1, metadata(2, 2, now / 1000, i + 1), now);
                    released[i] = bus.poll(now, System.nanoTime());
                }
                for (var frame : released) frame.close();
                int reused = 0;
                for (int i = 0; i < borrowed.length; i++) {
                    peer.publish(i + 5, metadata(2, 2, now / 1000, i + 5), now);
                    borrowed[i] = bus.poll(now, System.nanoTime());
                    for (var old : released) {
                        if (old.color == borrowed[i].color) {
                            reused++;
                            assert old != borrowed[i] : "Each publication needs its own immutable metadata and close state";
                            assert old.metadata.id().endsWith("-" + (java.util.Arrays.asList(released).indexOf(old) + 1));
                        }
                        old.close(); // A duplicate release must not return storage borrowed by a newer frame.
                    }
                    for (int j = 0; j <= i; j++)
                        assert borrowed[j].color.get(0) == j + 5 : "An owned frame was overwritten by buffer recycling";
                }
                assert reused == 3 : "Retain exactly three idle buffer pairs; allocate only when every cached pair is owned";
                bus.stop(); peer.stopped(); assert bus.reap();
                for (int i = 0; i < borrowed.length; i++)
                    assert borrowed[i].color.get(0) == i + 5 : "Unmapping must preserve outstanding detached frames";
                String nextRun = "33333333333333333333333333333333";
                try (var resized = new RawFrameBus(directory, nextRun, SESSION, 3, 1, now); var nextPeer = new Peer(resized.path)) {
                    nextPeer.ready(now);
                    nextPeer.publish(1, metadata(3, 1, now / 1000, 1).replace(RUN, nextRun), now);
                    try (var frame = resized.poll(now, System.nanoTime())) {
                        assert frame.color.capacity() == 12 && frame.depth.capacity() == 12;
                        for (var old : borrowed) old.close();
                        assert frame.color.get(0) == 1 : "Late returns from the old run must not touch resized storage";
                    }
                    int slot = nextPeer.publish(2, metadata(3, 1, now / 1000, 2).replace(RUN, nextRun), now);
                    nextPeer.map.putFloat(slot + RawFrameBus.HEADER + 12, Float.NaN);
                    rejects(() -> resized.poll(now, System.nanoTime()));
                    // A failed poll has no returned Frame handle; inspect owned storage to catch leaks on that path.
                    var cacheField = RawFrameBus.class.getDeclaredField("free");
                    cacheField.setAccessible(true);
                    var cache = (java.util.Collection<?>) cacheField.get(resized);
                    assert cache.size() == 1 : "Rejected payload must release its borrowed buffers";
                    nextPeer.stopped(); assert resized.reap();
                    assert cache.isEmpty() : "Unmapping must release every cached buffer pair";
                }
            } finally {
                for (var frame : released) if (frame != null) frame.close();
                for (var frame : borrowed) if (frame != null) { frame.close(); frame.close(); }
            }
        }
    }
    private static void scheduling(Path directory) throws Exception {
        var worker = new ManualWorker();
        try (var stream = new CaptureStream(directory, worker)) {
            assert worker.delayMillis == 0;
            worker.runNext();
            assert worker.delayMillis == 20 : "Inactive reads keep their slow polling cadence";
            stream.startHd(SESSION, 1, new CaptureFrame.Position(0, 64, 0), System.nanoTime(), 2, 2);
            String run = stream.mailbox.request().run();
            try (var peer = new Peer(directory.resolve("eve-frames-" + run + ".bin"))) {
                long now = System.currentTimeMillis(); peer.ready(now);
                peer.publish(1, metadata(2, 2, now / 1000, 1).replace(RUN, run), now);
                worker.runNext();
                assert worker.delayMillis == 1 : "Raw frames must not inherit the encoded reader's 20 ms delay";
                try (var frame = stream.mailbox.take()) { assert frame != null && frame.raw != null; }
                stream.stop("off");
                worker.runNext();
                assert worker.delayMillis == 20 : "Stopping raw capture must restore idle polling";
                peer.stopped(); worker.runNext();
            }
            stream.startHd(SESSION, 1, new CaptureFrame.Position(0, 64, 0), System.nanoTime(), 2, 2);
            worker.runNext();
            assert worker.delayMillis == 1 : "Restart must resume fast raw polling";
            Runnable queued = worker.next;
            stream.close();
            assert worker.isShutdown() && worker.next == null;
            queued.run(); // Model a task already dequeued when shutdown wins the stream lock.
            assert worker.next == null : "A late poll must not schedule after shutdown";
            rejects(() -> stream.startHd(SESSION, 1, new CaptureFrame.Position(0, 64, 0), System.nanoTime(), 2, 2));
        }
    }
    /** Manually advance the real poll at the scheduler boundary, without clock-based assertions. */
    private static final class ManualWorker extends java.util.concurrent.ScheduledThreadPoolExecutor {
        Runnable next;
        long delayMillis;
        ManualWorker() { super(1); }
        @Override public java.util.concurrent.ScheduledFuture<?> schedule(Runnable task, long delay, java.util.concurrent.TimeUnit unit) {
            assert next == null : "Only one reader task may be pending";
            assert !isShutdown() : "CaptureStream scheduled after shutdown";
            next = task; delayMillis = unit.toMillis(delay);
            return null;
        }
        @Override public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initial, long delay,
                                                                                       java.util.concurrent.TimeUnit unit) {
            return schedule(task, initial, unit);
        }
        void runNext() {
            Runnable task = next;
            assert task != null : "Reader did not schedule its next poll";
            next = null;
            task.run();
            assert next != null : "Reader did not schedule its next poll";
        }
        @Override public java.util.List<Runnable> shutdownNow() { next = null; return super.shutdownNow(); }
    }
    private static void failedRun(Path directory) throws Exception {
        for (int nativeState : new int[] {3, 2}) {
            try (var stream = new CaptureStream(directory)) {
                stream.startHd(SESSION, 1, new CaptureFrame.Position(0, 64, 0), System.nanoTime(), 2, 2);
                String run = stream.mailbox.request().run();
                Path path = directory.resolve("eve-frames-" + run + ".bin");
                try (var peer = new Peer(path)) {
                    peer.ready(System.currentTimeMillis());
                    RawFrameBus.INT.setRelease(peer.map, 200, 3);
                    RawFrameBus.INT.setRelease(peer.map, 196, nativeState);
                    await(() -> stream.mailbox.request() == null);
                    assert stream.mailbox.reason().equals("Native raw capture failed (error 3)")
                            : "Native stop must preserve the preceding failure: " + stream.mailbox.reason();
                    assert (int) RawFrameBus.INT.getAcquire(peer.map, 256) == 2;
                    peer.stopped();
                    await(() -> !Files.exists(path));
                }
                stream.startHd(SESSION, 1, new CaptureFrame.Position(0, 64, 0), System.nanoTime(), 2, 2);
                assert !run.equals(stream.mailbox.request().run()) : "A released failed run must allow a fresh mapping";
            }
        }
    }
    private static void interop(Path directory, int finalCounter) throws Exception {
        Files.createDirectories(directory);
        long now = System.currentTimeMillis();
        try (var bus = new RawFrameBus(directory, RUN, SESSION, 32, 18, now)) {
            new CaptureSession(directory).startRaw(RUN, SESSION, 32, 18, now / 1000, System.nanoTime());
            System.out.println("READY " + directory + " " + RUN + " " + SESSION + " 32 18 " + finalCounter);
            long until = System.nanoTime() + 20_000_000_000L, last = 0;
            int received = 0;
            // The external producer is started after READY; start the three-second capability deadline at construction.
            while (last < finalCounter) {
                if (System.nanoTime() >= until) throw new AssertionError("Native raw producer deadline");
                try (var frame = bus.poll(System.currentTimeMillis(), System.nanoTime())) {
                    if (frame == null) { Thread.sleep(1); continue; }
                    long counter = Long.parseLong(frame.metadata.id().split("-")[1]);
                    assert counter > last && counter <= finalCounter;
                    for (int offset = 0; offset < frame.color.capacity(); offset += 4) {
                        assert (frame.color.get(offset) & 255) == (counter & 255);
                        assert frame.color.get(offset + 1) == 2 && frame.color.get(offset + 2) == 3;
                        assert (frame.color.get(offset + 3) & 255) == 128 && frame.depth.getFloat(offset) == 1;
                    }
                    Thread.sleep(3);
                    assert (frame.color.get(0) & 255) == (counter & 255);
                    last = counter; received++;
                }
            }
            bus.stop();
            while (!bus.reap()) {
                if (System.nanoTime() >= until) throw new AssertionError("Native raw stop acknowledgement deadline");
                Thread.sleep(5);
            }
            System.out.println("PASS production RawFrameBus: " + received + " coherent native frames, final=" + last + ", deterministic unmap/stop cleanup");
        }
    }
    private static void stream(Path directory) throws Exception {
        CaptureFrame.stopWatching(); // Raw mode does not require image-close observations.
        try (var stream = new CaptureStream(directory)) {
            stream.startHd(SESSION, 1, new CaptureFrame.Position(0, 64, 0), System.nanoTime(), 2, 2);
            String run = stream.mailbox.request().run();
            String request = Files.readString(directory.resolve("eve-stream.txt"));
            assert request.startsWith("SBMC_CAPTURE_SESSION 2\nRUN " + run) && request.contains("\nSIZE 2 2\nSTATE active\n");
            Path path = directory.resolve("eve-frames-" + run + ".bin");
            try (var peer = new Peer(path)) {
                long now = System.currentTimeMillis(); peer.ready(now);
                peer.publish(1, metadata(2, 2, now / 1000, 1).replace(RUN, run), now);
                try (var frame = take(stream)) {
                    assert frame.raw != null && frame.color == null && frame.raw.color.get(3) == (byte) 128;
                }
                stream.mailbox.revision(2, System.nanoTime());
                now = System.currentTimeMillis(); peer.ready(now);
                peer.publish(2, metadata(2, 2, now / 1000, 2).replace(RUN, run), now);
                await(() -> (long) RawFrameBus.LONG.getAcquire(peer.map, 272) == 2);
                assert stream.mailbox.take() == null : "Old revision must be consumed without presentation";
                now = System.currentTimeMillis(); peer.ready(now);
                peer.publish(3, metadata(2, 2, now / 1000, 3).replace(RUN, run).replace(" 1 100 0 0 0", " 2 100 0 0 0"), now);
                try (var frame = take(stream)) { assert frame.metadata.link().ack() == 2; }
                now = System.currentTimeMillis(); peer.ready(now);
                peer.publish(4, metadata(2, 2, now / 1000, 4).replace(RUN, run).replace(" 1 100 0 0 0", " 2 100 0 0 0")
                        .replace("CONTEXT 1 2", "CONTEXT 3 4"), now);
                await(() -> stream.mailbox.request() == null);
                assert stream.mailbox.reason().equals("native pawn or world changed");
                assert !Files.exists(directory.resolve("eve-frame-ack.txt")) : "Raw frames must not write image ACK files";
                stream.stop("off");
                assert Files.readString(directory.resolve("eve-stream.txt")).contains("\nSIZE 2 2\nSTATE stopped\n");
                rejects(() -> stream.startHd(SESSION, 2, new CaptureFrame.Position(0, 64, 0), System.nanoTime(), 2, 2));
                peer.stopped(); await(() -> !Files.exists(path));
            }
            stream.startHd(SESSION, 2, new CaptureFrame.Position(0, 64, 0), System.nanoTime(), 2, 2);
            assert !run.equals(stream.mailbox.request().run());
        }
    }
    private static CaptureStream.Prepared take(CaptureStream stream) throws Exception {
        var owned = new java.util.concurrent.atomic.AtomicReference<CaptureStream.Prepared>();
        await(() -> { owned.set(stream.mailbox.take()); return owned.get() != null; });
        return owned.get();
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + 2_000_000_000L;
        while (!condition.getAsBoolean()) { if (System.nanoTime() >= until) throw new AssertionError("Raw worker deadline"); Thread.sleep(5); }
    }
    private static void rejects(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid raw operation accepted"); }
        catch (java.io.IOException | IllegalArgumentException expected) { }
    }
    private interface Action { void run() throws Exception; }
    private static final class Peer implements AutoCloseable {
        final FileChannel file;
        final ByteBuffer map;
        int back = 1;
        Peer(Path path) throws Exception {
            file = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE);
            map = file.map(FileChannel.MapMode.READ_WRITE, 0, file.size()).order(ByteOrder.LITTLE_ENDIAN);
        }
        void ready(long time) { RawFrameBus.INT.setRelease(map, 192, 123); RawFrameBus.LONG.setRelease(map, 208, time); RawFrameBus.INT.setRelease(map, 196, 1); }
        void stopped() { RawFrameBus.INT.setRelease(map, 196, 2); }
        int publish(long counter, String packet, long time) {
            int at = 4096 + back * map.getInt(24), plane = map.getInt(16) * map.getInt(20) * 4;
            for (int i = 0; i < 4096; i++) map.put(at + i, (byte) 0);
            byte[] bytes = packet.getBytes(StandardCharsets.US_ASCII);
            map.putLong(at, counter).putLong(at + 8, time).putInt(at + 16, bytes.length).putInt(at + 20, 1).put(at + 64, bytes);
            for (int i = 0; i < plane; i += 4) {
                map.putInt(at + 4096 + i, 0x80030200 | (int) counter & 255);
                map.putFloat(at + 4096 + plane + i, 1);
            }
            back = (int) RawFrameBus.INT.getAndSet(map, 128, back | 4) & 3;
            RawFrameBus.LONG.setRelease(map, 216, counter);
            return at;
        }
        @Override public void close() throws Exception { file.close(); }
    }
}
