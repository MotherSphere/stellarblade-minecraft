package org.stellarbridge;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.texture.NativeImage;

public final class CaptureStreamTest {
    private static final String SESSION = "0123456789abcdef0123456789abcdef";
    private static final CaptureFrame.Position ORIGIN = new CaptureFrame.Position(0, 64, 0);

    public static void main(String[] args) throws Exception {
        var mailbox = new CaptureStream.Mailbox();
        mailbox.start(SESSION, 5, ORIGIN, 0);
        var firstRequest = mailbox.request();
        var first = prepared("100-1", 5, 11);
        NativeImage firstImage = first.color;
        mailbox.publish(firstRequest, first, 100, 1_000_000_000L);
        var second = prepared("100-2", 5, 11);
        mailbox.publish(firstRequest, second, 100, 1_200_000_000L);
        assert first.color == null : "Only the latest pending NativeImage may remain owned";
        closed(firstImage);
        assert mailbox.take() == second;
        second.close();
        var duplicate = prepared("100-2", 5, 11);
        mailbox.publish(firstRequest, duplicate, 100, 3_100_000_000L);
        assert duplicate.color == null && mailbox.take() == null;
        assert !mailbox.expired(3_199_999_999L);
        assert mailbox.expired(3_200_000_000L) : "Duplicate IDs must not renew the two-second timeout";
        var tooLate = prepared("100-3", 5, 11);
        mailbox.publish(firstRequest, tooLate, 100, 3_200_000_000L);
        assert mailbox.request() == null && tooLate.color == null : "A worker completion cannot revive an expired stream";

        mailbox.start(SESSION, 5, ORIGIN, 10_000_000_000L);
        assert !mailbox.expired(14_999_999_999L) && mailbox.expired(15_000_000_000L);
        var stale = prepared("100-3", 5, 11);
        mailbox.publish(mailbox.request(), stale, 103, 11_000_000_000L);
        assert stale.color == null && mailbox.take() == null : "Live must reject a three-second-old capture";
        assert CaptureStream.fresh(100, 102) && !CaptureStream.fresh(100, 103);

        // Completion after off, reactivation or a revision change must never resurrect a view.
        for (int invalidation = 0; invalidation < 3; invalidation++) {
            mailbox.start(SESSION, 5, ORIGIN, 0);
            var request = mailbox.request();
            var delayed = prepared("100-4", 5, 11);
            NativeImage delayedImage = delayed.color;
            CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
            Thread worker = new Thread(() -> {
                started.countDown();
                try { release.await(); }
                catch (InterruptedException exception) { throw new AssertionError(exception); }
                mailbox.publish(request, delayed, 100, 1_000_000_000L);
            });
            worker.start();
            assert started.await(2, TimeUnit.SECONDS);
            if (invalidation == 0) mailbox.stop("off");
            if (invalidation == 1) mailbox.start(SESSION, 5, ORIGIN, 0);
            if (invalidation == 2) mailbox.revision(6);
            release.countDown();
            worker.join(2000);
            assert !worker.isAlive() && delayed.color == null && mailbox.take() == null;
            closed(delayedImage);
        }
        var revision = prepared("100-5", 6, 11);
        mailbox.publish(mailbox.request(), revision, 100, 1_000_000_000L);
        assert mailbox.take() == revision : "A new terrain revision must recover without reactivation";
        revision.close();
        var changedPawn = prepared("100-6", 6, 12);
        mailbox.publish(mailbox.request(), changedPawn, 100, 1_500_000_000L);
        assert changedPawn.color == null && mailbox.request() == null;
        assert mailbox.reason().contains("native") : "Native context replacement must stop live";

        mailbox.start(SESSION, 5, ORIGIN, 0);
        var pending = prepared("100-7", 5, 11);
        NativeImage pendingImage = pending.color;
        mailbox.publish(mailbox.request(), pending, 100, 1_000_000_000L);
        mailbox.stop("shutdown");
        assert pending.color == null && mailbox.take() == null;
        closed(pendingImage);
        System.out.println("Capture stream lifecycle tests passed");
    }

    private static void closed(NativeImage image) {
        try { image.getColor(0, 0); throw new AssertionError("Discarded native pixels were not freed"); }
        catch (IllegalStateException expected) { }
    }

    private static CaptureStream.Prepared prepared(String id, int revision, long pawn) {
        var point = new CaptureFrame.Position(0, 0, 0);
        var metadata = new CaptureFrame.Metadata(id, 100, 75, 1, 1, 1,
                new CaptureFrame.Camera(point, 0, 0, 0), new CaptureFrame.Pawn(point, 95),
                new CaptureFrame.Context(pawn, 12), new CaptureFrame.Link(SESSION, revision, 100, point), 320, 180);
        return new CaptureStream.Prepared(metadata, CaptureProjection.camera(metadata, ORIGIN),
                new NativeImage(1, 1, false), new float[1], 100);
    }
}
