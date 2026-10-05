package org.stellarbridge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.UUID;

/** Small request/ack snapshots; image closure never certifies an Unreal task's lifetime. */
final class CaptureSession {
    private final Path directory;
    private BridgeFiles files;
    private String run, session;
    private boolean active, stopPending;
    private long publishedNanos;
    private int width, height;

    CaptureSession(Path directory) { this.directory = directory; }

    synchronized String start(String session, long now, long nowNanos) throws IOException {
        if (!session.matches("[0-9a-f]{32}")) throw new IllegalArgumentException("Invalid capture session");
        String next = UUID.randomUUID().toString().replace("-", "");
        publish("eve-stream.txt", packet(next, session, now, true));
        this.run = next;
        this.session = session;
        width = height = 0;
        active = true;
        stopPending = false;
        publishedNanos = nowNanos;
        return next;
    }

    synchronized void startRaw(String run, String session, int width, int height, long now, long nowNanos) throws IOException {
        if (!run.matches("[0-9a-f]{32}") || !session.matches("[0-9a-f]{32}")
                || width < 1 || width > 3840 || height < 1 || height > 2160)
            throw new IllegalArgumentException("Invalid raw capture request");
        publish("eve-stream.txt", packet(run, session, now, true, width, height));
        this.run = run; this.session = session; this.width = width; this.height = height;
        active = true; stopPending = false; publishedNanos = nowNanos;
    }

    synchronized void heartbeat(long now, long nowNanos) throws IOException {
        if (run == null || !stopPending && (!active || nowNanos - publishedNanos < 1_000_000_000L)) return;
        publish("eve-stream.txt", packet(run, session, now, active, width, height));
        publishedNanos = nowNanos;
        stopPending = false;
    }

    synchronized void stop(long now) throws IOException {
        if (run == null || !active && !stopPending) return;
        active = false;
        stopPending = true;
        publish("eve-stream.txt", packet(run, session, now, false, width, height));
        stopPending = false;
    }

    void acknowledge(CaptureFrame.Metadata metadata) throws IOException {
        if (metadata.version() != 4 || metadata.run() == null || !metadata.run().matches("[0-9a-f]{32}")
                || !metadata.link().session().matches("[0-9a-f]{32}"))
            throw new IllegalArgumentException("Invalid continuous capture acknowledgement");
        publish("eve-frame-ack.txt", "SBMC_CAPTURE_ACK 1\nRUN " + metadata.run()
                + "\nSESSION " + metadata.link().session() + "\nID " + metadata.id() + "\nEND\n");
    }

    private static String packet(String run, String session, long now, boolean active) {
        return packet(run, session, now, active, 0, 0);
    }
    private static String packet(String run, String session, long now, boolean active, int width, int height) {
        if (now < 0 || now > 9007199254740991L) throw new IllegalArgumentException("Invalid capture session time");
        return "SBMC_CAPTURE_SESSION " + (width == 0 ? 1 : 2) + "\nRUN " + run + "\nSESSION " + session + "\nTIME " + now
                + (width == 0 ? "" : "\nSIZE " + width + " " + height)
                + "\nSTATE " + (active ? "active" : "stopped") + "\nEND\n";
    }

    private synchronized void publish(String name, String packet) throws IOException {
        if (files == null) files = new BridgeFiles(directory);
        Path target = files.checked(name);
        Path temporary = Files.createTempFile(target.getParent(), "capture-session-", ".tmp",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        try {
            Files.writeString(temporary, packet, StandardCharsets.US_ASCII);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
