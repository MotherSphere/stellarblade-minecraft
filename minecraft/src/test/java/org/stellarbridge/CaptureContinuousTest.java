package org.stellarbridge;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.BooleanSupplier;

/** End-to-end local producer simulation: real inotify, native decoder, worker and atomic acknowledgements. */
public final class CaptureContinuousTest {
    private static final String SESSION = "0123456789abcdef0123456789abcdef";
    private static final CaptureFrame.Position ORIGIN = new CaptureFrame.Position(0, 64, 0);
    private static byte[] bmp, mask, depth;
    private static int sequence;

    public static void main(String[] args) throws Exception {
        bmp = CaptureBmpTest.bmp();
        mask = CaptureBmpTest.png(new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB));
        depth = CaptureBmpTest.depth();
        Path directory = Files.createTempDirectory("stellar-continuous-capture-");
        try {
            try (var unavailable = new CaptureStream(directory)) {
                rejects(() -> unavailable.startEncoded(SESSION, 1, ORIGIN, System.nanoTime()));
                assert !Files.exists(directory.resolve("eve-stream.txt")) : "Missing watcher must not request native work";
            }
            CaptureFrame.startWatching(directory);
            Files.delete(directory);
            Files.createDirectory(directory);
            try (var invalidated = new CaptureStream(directory)) {
                rejects(() -> invalidated.startEncoded(SESSION, 1, ORIGIN, System.nanoTime()));
                assert !Files.exists(directory.resolve("eve-stream.txt")) : "Invalidated watcher must not request native work";
            }
            CaptureFrame.stopWatching();
            CaptureFrame.startWatching(directory);
            try (var stream = new CaptureStream(directory)) {
                // Ordinary bridge traffic must drain before the first live command or capture descriptor.
                for (int wave = 0; wave < 3; wave++) {
                    for (int i = 0; i < 2000; i++) {
                        Path temporary = directory.resolve("eve-pose.tmp");
                        Files.writeString(temporary, "pose " + i);
                        Files.move(temporary, directory.resolve("eve-pose.txt"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    Thread.sleep(50);
                }
                assert !Files.exists(directory.resolve("eve-frame.txt")) && !Files.exists(directory.resolve("eve-stream.txt"));
                stream.startEncoded(SESSION, 1, ORIGIN, System.nanoTime());
                String firstRun = stream.mailbox.request().run();
                assert firstRun.matches("[0-9a-f]{32}");
                String request = Files.readString(directory.resolve("eve-stream.txt"));
                assert request.matches("SBMC_CAPTURE_SESSION 1\nRUN " + firstRun + "\nSESSION " + SESSION
                        + "\nTIME [0-9]+\nSTATE active\nEND\n");
                String id = id();
                try (var color = begin(directory, id, firstRun, 1)) {
                    Thread.sleep(120);
                    assert !Files.exists(directory.resolve("eve-frame-ack.txt")) : "Full valid bytes without close must not ACK";
                }
                awaitAck(directory, firstRun, id);
                var first = awaitFrame(stream);
                assert first.metadata.version() == 4 && first.metadata.run().equals(firstRun);
                first.close();
                retireFiles(directory, id);

                // More than 128 unique names must work without cache eviction or unbounded growth.
                for (int i = 0; i < 45; i++) {
                    id = id();
                    try (var color = begin(directory, id, firstRun, 1)) { }
                    awaitAck(directory, firstRun, id);
                    var frame = awaitFrame(stream);
                    assert frame.metadata.id().equals(id);
                    frame.close();
                    retireFiles(directory, id);
                    stream.heartbeat();
                }

                // ACK failure after retirement owns its decoded receipt and retries without reading deleted images.
                Files.delete(directory.resolve("eve-frame-ack.txt"));
                Files.createDirectory(directory.resolve("eve-frame-ack.txt"));
                id = id();
                try (var color = begin(directory, id, firstRun, 1)) { }
                await(() -> stream.mailbox.status(System.nanoTime()).contains("regular file"));
                retireFiles(directory, id);
                Files.delete(directory.resolve("eve-frame-ack.txt"));
                awaitAck(directory, firstRun, id);
                var retried = awaitFrame(stream);
                assert retried.metadata.id().equals(id);
                retried.close();

                // A terrain revision change discards the old view but still completes the producer ACK.
                id = id();
                try (var color = begin(directory, id, firstRun, 1)) { stream.mailbox.revision(2); }
                awaitAck(directory, firstRun, id);
                assert stream.mailbox.take() == null;
                retireFiles(directory, id);

                // Re-entering creates a fresh RUN; the previous run can finish later without adoption.
                id = id();
                String secondRun;
                try (var color = begin(directory, id, firstRun, 2)) {
                    stream.startEncoded(SESSION, 2, ORIGIN, System.nanoTime());
                    secondRun = stream.mailbox.request().run();
                    assert !firstRun.equals(secondRun);
                }
                awaitAck(directory, firstRun, id);
                assert stream.mailbox.take() == null;
                retireFiles(directory, id);
                id = id();
                try (var color = begin(directory, id, secondRun, 2)) { }
                awaitAck(directory, secondRun, id);
                var current = awaitFrame(stream);
                assert current.metadata.run().equals(secondRun);
                current.close();
                retireFiles(directory, id);

                // Valid pixels with an unsupported camera projection are consumed/discarded, never a producer stall.
                id = id();
                try (var color = begin(directory, id, secondRun, 2)) {
                    Files.writeString(directory.resolve("eve-frame.txt"), descriptor(id, secondRun, 2).replace("4 5 0 75", "4 5 20 75"));
                }
                awaitAck(directory, secondRun, id);
                assert stream.mailbox.take() == null;
                retireFiles(directory, id);

                // Off must retain the receiver until an already submitted writer closes.
                id = id();
                try (var color = begin(directory, id, secondRun, 2)) { stream.stop("off"); }
                assert Files.readString(directory.resolve("eve-stream.txt")).contains("STATE stopped\n");
                awaitAck(directory, secondRun, id);
                assert stream.mailbox.request() == null && stream.mailbox.take() == null;
                retireFiles(directory, id);
            }
            CaptureFrame.stopWatching();
            clear(directory);

            // A stale descriptor still settles; age is an adoption rule, not permission to strand a writer.
            CaptureFrame.startWatching(directory);
            try (var stream = new CaptureStream(directory)) {
                stream.startEncoded(SESSION, 1, ORIGIN, System.nanoTime());
                String run = stream.mailbox.request().run(), old = (now() - 60) + "-1";
                try (var color = begin(directory, old, run, 1)) { }
                awaitAck(directory, run, old);
                assert stream.mailbox.take() == null;
                retireFiles(directory, old);
            }
            CaptureFrame.stopWatching();
            clear(directory);

            // Truncated/invalid payloads never ACK even after close.
            CaptureFrame.startWatching(directory);
            try (var stream = new CaptureStream(directory)) {
                stream.startEncoded(SESSION, 1, ORIGIN, System.nanoTime());
                String run = stream.mailbox.request().run(), invalid = id();
                try (var color = begin(directory, invalid, run, 1)) { color.write(0); }
                Thread.sleep(160);
                assert !Files.exists(directory.resolve("eve-frame-ack.txt"));
                String valid = descriptor(id(), run, 1);
                for (String malformed : new String[] {
                        valid.replace("RUN " + run, "RUN uppercase"), valid.replace("RUN " + run + "\n", ""),
                        valid.replace("SBMC_FRAME 4", "SBMC_FRAME 3"), valid + "EXTRA\n",
                        valid.replace("LINK " + SESSION + " 1", "LINK none 0"),
                        valid.replace("color.bmp 0", "color.bmp 1") })
                    rejects(() -> CaptureFrame.parse(malformed.getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
            }
        } finally {
            CaptureFrame.stopWatching();
            clear(directory);
            Files.delete(directory);
        }
        System.out.println("Continuous capture tests passed: 45-frame bounded retirement, ACK retry, old run/revision/off races, stale drain and no partial ACK");
    }

    private static OutputStream begin(Path directory, String id, String run, int revision) throws IOException {
        String prefix = "eve-frame-" + id + "-";
        OutputStream color = Files.newOutputStream(directory.resolve(prefix + "color.bmp"));
        try {
            color.write(bmp);
            color.flush();
            Files.write(directory.resolve(prefix + "mask.png"), mask);
            Files.write(directory.resolve(prefix + "depth.hdr"), depth);
            Files.writeString(directory.resolve("eve-frame.txt"), descriptor(id, run, revision));
            return color;
        } catch (IOException failure) { color.close(); throw failure; }
    }
    private static String descriptor(String id, String run, int revision) {
        String prefix = "eve-frame-" + id + "-";
        return "SBMC_FRAME 4 " + id + " " + id.split("-")[0] + "\nSIZE 1280 720\nCAMERA 1 2 3 4 5 0 75\n"
                + "PAWN 7 8 9 95\nCONTEXT 11 12\nLINK " + SESSION + " " + revision + " 100 0 0 0\n"
                + "COLOR " + prefix + "color.bmp 0\nMASK " + prefix + "mask.png 0\nDEPTH " + prefix + "depth.hdr " + depth.length
                + "\nRUN " + run + "\nEND " + id + "\n";
    }
    private static void retireFiles(Path directory, String id) throws IOException {
        for (String plane : new String[] {"color.bmp", "mask.png", "depth.hdr"})
            Files.delete(directory.resolve("eve-frame-" + id + "-" + plane));
    }
    private static void awaitAck(Path directory, String run, String id) throws Exception {
        String expected = "SBMC_CAPTURE_ACK 1\nRUN " + run + "\nSESSION " + SESSION + "\nID " + id + "\nEND\n";
        await(() -> {
            try { return Files.readString(directory.resolve("eve-frame-ack.txt")).equals(expected); }
            catch (IOException failure) { return false; }
        });
    }
    private static CaptureStream.Prepared awaitFrame(CaptureStream stream) throws Exception {
        CaptureStream.Prepared[] received = new CaptureStream.Prepared[1];
        await(() -> (received[0] = stream.mailbox.take()) != null);
        return received[0];
    }
    private static void await(BooleanSupplier predicate) throws Exception {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!predicate.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Timed out waiting for capture ownership transition");
            Thread.sleep(5);
        }
    }
    private static void clear(Path directory) throws IOException {
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) if (!file.equals(directory)) Files.delete(file);
        }
    }
    private static void rejects(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid continuous descriptor accepted"); }
        catch (IllegalArgumentException | IOException expected) { }
    }
    private static String id() { return now() + "-" + (++sequence); }
    private static long now() { return System.currentTimeMillis() / 1000; }
    private interface Action { void run() throws Exception; }
}
