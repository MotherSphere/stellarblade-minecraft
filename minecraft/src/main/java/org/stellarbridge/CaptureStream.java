package org.stellarbridge;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.texture.NativeImage;

/** One CPU worker and one owned pending frame. No Minecraft world access or GL calls. */
final class CaptureStream implements AutoCloseable {
    final Mailbox mailbox = new Mailbox();
    private final Path directory;
    private final ScheduledExecutorService worker;
    private final CaptureSession session;
    private String previousReadId;
    private Receipt settling;
    private boolean closed;

    CaptureStream(Path directory) {
        this.directory = directory;
        session = new CaptureSession(directory);
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "stellar-capture-reader");
            thread.setDaemon(true);
            return thread;
        });
        worker.scheduleWithFixedDelay(this::poll, 0, 20, TimeUnit.MILLISECONDS);
    }

    synchronized void startHd(String terrainSession, int revision, CaptureFrame.Position origin, long nowNanos) throws IOException {
        if (closed) throw new IOException("Capture reader is closed");
        stop("restarted");
        CaptureFrame.requireWatching(directory); // Never request a generation whose close evidence cannot be observed.
        String run = session.start(terrainSession, now(), nowNanos);
        mailbox.start(terrainSession, revision, origin, nowNanos, run);
    }

    void heartbeat() throws IOException { session.heartbeat(now(), System.nanoTime()); }

    synchronized void stop(String reason) throws IOException {
        mailbox.stop(reason);
        session.stop(now());
    }

    private synchronized void poll() {
        if (closed) return;
        Request request = mailbox.request();
        NativeImage color = null;
        try {
            CaptureFrame.pollWatching(); // Drain unrelated bridge writes even without a descriptor or during ACK retries.
            if (settling != null) { acknowledge(); return; }
            long started = System.nanoTime();
            var frame = CaptureFrame.readForStream(directory, now(), previousReadId, request == null || request.run() != null);
            if (frame == null) return;
            if (frame.metadata().version() == 4) {
                // Always settle a complete continuous payload, including old runs, expired views and old revisions.
                color = CaptureImages.decodeMasked(frame);
                frame.verifyObservation();
                Prepared prepared = null;
                if (request != null && frame.metadata().run().equals(request.run())
                        && fresh(frame.metadata().time(), now())
                        && CaptureProjection.matches(frame.metadata(), request.session(), request.revision(), now())) {
                    try {
                        prepared = new Prepared(frame.metadata(), CaptureProjection.camera(frame.metadata(), request.origin()),
                                color, frame.depth(), System.nanoTime() - started);
                    } catch (IllegalArgumentException unsupportedView) {
                        mailbox.failure(request, unsupportedView.getMessage()); // Presentation rejection must not strand the writer.
                    }
                }
                frame.retire(); // Before ACK: late filesystem events must not recreate retired cache entries.
                settling = new Receipt(frame.metadata(), request, prepared);
                if (prepared != null) color = null;
                acknowledge();
                return;
            }
            previousReadId = frame.metadata().id();
            if (request == null) return;
            if (frame.metadata().width() != 320 || frame.metadata().height() != 180) {
                mailbox.failure(request, "HD capture is still-only; use /stellarview load");
                return;
            }
            if (!fresh(frame.metadata().time(), now())
                    || !CaptureProjection.matches(frame.metadata(), request.session(), request.revision(), now())) return;
            var camera = CaptureProjection.camera(frame.metadata(), request.origin());
            color = CaptureImages.decodeMasked(frame);
            CaptureFrame.verifyUnchanged(directory, frame.descriptor());
            var prepared = new Prepared(frame.metadata(), camera, color, frame.depth(), System.nanoTime() - started);
            color = null;
            mailbox.publish(request, prepared, now(), System.nanoTime());
        } catch (IOException | RuntimeException failure) {
            // A producer may replace or retire a generation while it is being read. The deadline still applies.
            if (request != null) mailbox.failure(request, failure.getMessage());
        } finally {
            if (color != null) color.close();
        }
    }

    private void acknowledge() throws IOException {
        Receipt receipt = settling;
        session.acknowledge(receipt.metadata);
        previousReadId = receipt.metadata.id();
        settling = null;
        if (receipt.frame != null)
            mailbox.publish(receipt.request, receipt.frame, now(), System.nanoTime());
    }

    private record Receipt(CaptureFrame.Metadata metadata, Request request, Prepared frame) { }

    @Override public synchronized void close() {
        closed = true;
        try { stop("client shutdown"); }
        catch (IOException failure) {
            org.slf4j.LoggerFactory.getLogger("stellar_bridge").warn("Could not publish capture stop; native heartbeat will expire", failure);
        } finally {
            worker.shutdownNow();
            if (settling != null && settling.frame != null) settling.frame.close();
            settling = null;
        }
    }

    static boolean fresh(long timestamp, long now) {
        return CaptureFrame.fresh(timestamp, now) && now - timestamp <= 2;
    }
    private static long now() { return System.currentTimeMillis() / 1000; }

    record Request(long epoch, String session, int revision, CaptureFrame.Position origin, String previousId, String run) { }

    static final class Prepared implements AutoCloseable {
        final CaptureFrame.Metadata metadata;
        final CaptureProjection.CameraPose camera;
        final float[] depth;
        final long loadNanos;
        final long receivedNanos = System.nanoTime();
        NativeImage color;
        long epoch;

        Prepared(CaptureFrame.Metadata metadata, CaptureProjection.CameraPose camera, NativeImage color,
                 float[] depth, long loadNanos) {
            this.metadata = metadata;
            this.camera = camera;
            this.color = color;
            this.depth = depth;
            this.loadNanos = loadNanos;
        }
        @Override public void close() {
            if (color != null) { color.close(); color = null; }
        }
    }

    /** Synchronization makes cancellation and worker publication one atomic ownership decision. */
    static final class Mailbox {
        private long epoch;
        private Request active;
        private Prepared pending;
        private CaptureFrame.Context nativeContext;
        private String previousId;
        private String reason = "inactive";
        private String lastFailure = "";
        private long startedNanos, lastNewNanos;
        private boolean received;
        private int accepted;
        private long loadNanos;
        private long stoppedNanos;

        synchronized void start(String session, int revision, CaptureFrame.Position origin, long nowNanos) {
            start(session, revision, origin, nowNanos, null);
        }

        synchronized void start(String session, int revision, CaptureFrame.Position origin, long nowNanos, String run) {
            stop("restarted");
            active = new Request(++epoch, session, revision, origin, null, run);
            nativeContext = null;
            previousId = null;
            startedNanos = nowNanos;
            received = false;
            accepted = 0;
            loadNanos = 0;
            stoppedNanos = 0;
            reason = "waiting for a fresh frame";
            lastFailure = "";
        }

        synchronized Request request() {
            return active == null ? null : new Request(active.epoch, active.session, active.revision, active.origin, previousId, active.run);
        }

        synchronized void revision(int revision) {
            if (active == null || active.revision == revision) return;
            active = new Request(++epoch, active.session, revision, active.origin, previousId, active.run);
            discardPending();
            reason = "waiting for terrain revision " + revision;
        }

        synchronized void publish(Request request, Prepared frame, long now, long nowNanos) {
            if (active != null && request.epoch == active.epoch && expired(nowNanos))
                stop("no complete fresh generation before the deadline");
            if (active == null || request.epoch != active.epoch || !fresh(frame.metadata.time(), now)
                    || (active.run == null ? frame.metadata.version() == 4 : !active.run.equals(frame.metadata.run()))
                    || !CaptureProjection.matches(frame.metadata, active.session, active.revision, now)
                    || !CaptureFrame.newer(frame.metadata.id(), previousId)) {
                frame.close();
                return;
            }
            if (nativeContext != null && !nativeContext.equals(frame.metadata.context())) {
                frame.close();
                stop("native pawn or world changed");
                return;
            }
            nativeContext = frame.metadata.context();
            previousId = frame.metadata.id();
            received = true;
            lastNewNanos = nowNanos;
            frame.epoch = active.epoch;
            discardPending();
            pending = frame;
            reason = "live";
            lastFailure = "";
        }

        synchronized Prepared take() {
            Prepared result = pending;
            pending = null;
            return result;
        }

        synchronized boolean current(Prepared frame) { return active != null && active.epoch == frame.epoch; }

        synchronized void accepted(Prepared frame) {
            if (!current(frame)) return;
            accepted++;
            loadNanos = frame.loadNanos;
        }

        synchronized boolean expired(long nowNanos) {
            return active != null && nowNanos - (received ? lastNewNanos : startedNanos)
                    >= (received ? 2_000_000_000L : 5_000_000_000L);
        }

        synchronized void stop(String message) {
            if (active != null) stoppedNanos = System.nanoTime();
            active = null;
            ++epoch;
            discardPending();
            reason = message;
        }

        synchronized String reason() { return reason; }

        synchronized String status(long nowNanos) {
            double seconds = Math.max(0, ((active == null ? stoppedNanos : nowNanos) - startedNanos) / 1_000_000_000.0);
            return String.format(java.util.Locale.ROOT,
                    "EVE live diagnostic: %s; %d frames accepted in %.1f s (%.2f frames/s); last worker read/decode %.1f ms%s",
                    reason, accepted, seconds, seconds > 0 ? accepted / seconds : 0, loadNanos / 1_000_000.0,
                    lastFailure.isEmpty() ? "" : "; waiting: " + lastFailure);
        }

        synchronized void failure(Request request, String message) {
            if (active != null && active.epoch == request.epoch) lastFailure = message == null ? "capture unavailable" : message;
        }

        private void discardPending() {
            if (pending != null) { pending.close(); pending = null; }
        }
    }
}
