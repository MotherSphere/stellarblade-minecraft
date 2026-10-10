package org.stellarbridge;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executors;
import java.util.zip.CRC32;

/** Real Linux close events and real native image decode; no game process or window. */
public final class CaptureAsyncTest {
    private static final String ID = "1791110000-1", PREFIX = "eve-frame-" + ID + "-";
    private static final long NOW = 1791110000;

    public static void main(String[] args) throws Exception {
        byte[] bmp = CaptureBmpTest.bmp(), depth = CaptureBmpTest.depth();
        BufferedImage maskImage = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB);
        maskImage.setRGB(0, 0, 0x7f000000);
        maskImage.setRGB(1279, 719, 0x80000000);
        byte[] mask = CaptureBmpTest.png(maskImage);
        String descriptor = CaptureBmpTest.descriptor(3, "bmp", 0, 0, depth.length);
        assert CaptureFrame.parse(descriptor.getBytes()).version() == 3;
        for (String invalid : new String[] {
                descriptor.replace("SBMC_FRAME 3", "SBMC_FRAME 4"),
                descriptor.replace("SBMC_FRAME 3", "SBMC_FRAME 2"),
                descriptor.replace("SBMC_FRAME 3", "SBMC_FRAME 1"),
                descriptor.replace("1280 720", "320 180"),
                descriptor.replace("color.bmp 0", "color.bmp 1"),
                descriptor.replace("mask.png 0", "mask.png 1"),
                descriptor.replace("depth.hdr " + depth.length, "depth.hdr 0"),
                descriptor.replace("color.bmp", "color.png") }) rejects(() -> CaptureFrame.parse(invalid.getBytes()));
        validatePng(mask);
        if (!com.sun.jna.Platform.isLinux()) { System.out.println("SKIP asynchronous capture: its close watch is Linux-only"); return; }
        Path directory = Files.createTempDirectory("stellar-async-capture-");
        try {
            // Pose/terrain traffic exists before the first capture. A bounded backlog is not lost evidence.
            try (var watch = new CaptureCloseWatch(directory)) {
                synchronized (watch) {
                    for (int i = 0; i < 2000; i++) {
                        Path temporary = directory.resolve("eve-pose.tmp");
                        Files.writeString(temporary, "pose " + i);
                        Files.move(temporary, directory.resolve("eve-pose.txt"), StandardCopyOption.REPLACE_EXISTING);
                    }
                    try { watch.available(directory); } catch (IOException pending) {
                        assert pending.getMessage().contains("catching up") : "Backlog must remain retryable: " + pending;
                    }
                    watch.available(directory); // A second bounded batch must finish without a poisoned epoch.
                    Files.write(directory.resolve(PREFIX + "color.bmp"), bmp);
                    Files.write(directory.resolve(PREFIX + "mask.png"), mask);
                    Files.write(directory.resolve(PREFIX + "depth.hdr"), depth);
                    watch.requireReady(directory, ID);
                }
            }
            clear(directory);
            try (var watch = new CaptureCloseWatch(directory)) {
                // The descriptor may be published before its asynchronous images exist.
                Files.writeString(directory.resolve("eve-frame.txt"), descriptor);
                rejects(() -> CaptureFrame.readIfNew(directory, NOW, null, watch));
                Files.write(directory.resolve(PREFIX + "mask.png"), mask);
                Files.write(directory.resolve(PREFIX + "depth.hdr"), depth);
                try (var output = Files.newOutputStream(directory.resolve(PREFIX + "color.bmp"))) {
                    output.write(bmp);
                    output.flush();
                    rejects(() -> CaptureFrame.readIfNew(directory, NOW, null, watch)); // Valid full bytes are not a close.
                }
                var loaded = CaptureFrame.readIfNew(directory, NOW, null, watch);
                assert loaded.metadata().colorBytes() == bmp.length && loaded.metadata().maskBytes() == mask.length;
                assert loaded.metadata().version() == 3 && loaded.depth()[0] == 1;
                try (var worker = Executors.newFixedThreadPool(2)) {
                    var decode = worker.submit(() -> {
                        try (var image = CaptureImages.decodeMasked(loaded)) {
                            assert image.getWidth() == 1280 && image.getHeight() == 720;
                            assert image.getColor(0, 0) >>> 24 == 128;
                            assert image.getColor(1279, 719) >>> 24 == 0;
                        }
                        return null;
                    });
                    worker.submit(() -> { watch.requireReady(directory, ID); return null; }).get();
                    decode.get();
                }
                // Descriptor replacement is rejected by the same shared Loaded decode path.
                Files.writeString(directory.resolve("eve-frame.txt"), descriptor.replace(" 0 75\n", " 0 76\n"));
                rejects(() -> CaptureImages.decodeMasked(loaded));
                Files.writeString(directory.resolve("eve-frame.txt"), descriptor);
                // A writable reopen, even without new bytes, violates this generation's one-close contract.
                try (var ignored = Files.newOutputStream(directory.resolve(PREFIX + "color.bmp"), StandardOpenOption.APPEND)) { }
                rejects(() -> CaptureFrame.readIfNew(directory, NOW, null, watch));
                rejects(() -> CaptureImages.decodeMasked(loaded));
            }
            // Starting after files exist cannot manufacture close evidence by reopening them.
            try (var late = new CaptureCloseWatch(directory)) {
                Files.write(directory.resolve(PREFIX + "color.bmp"), bmp);
                Files.write(directory.resolve(PREFIX + "mask.png"), mask);
                Files.write(directory.resolve(PREFIX + "depth.hdr"), depth);
                rejects(() -> CaptureFrame.readIfNew(directory, NOW, null, late));
            }
            clear(directory);
            try (var watch = new CaptureCloseWatch(directory)) {
                // Images may also close before publication of the descriptor.
                Files.write(directory.resolve(PREFIX + "color.bmp"), bmp);
                Files.write(directory.resolve(PREFIX + "mask.png"), mask);
                Files.write(directory.resolve(PREFIX + "depth.hdr"), depth);
                watch.drain();
                Files.writeString(directory.resolve("eve-frame.txt"), descriptor);
                var loaded = CaptureFrame.readIfNew(directory, NOW, null, watch);
                // Post-close modification poisons a name even while the new writer is still open.
                try (var output = Files.newOutputStream(directory.resolve(PREFIX + "mask.png"), StandardOpenOption.APPEND)) {
                    output.write(0);
                    output.flush();
                    rejects(() -> CaptureFrame.readIfNew(directory, NOW, null, watch));
                    rejects(() -> CaptureImages.decodeMasked(loaded));
                }
                Files.write(directory.resolve(PREFIX + "mask.png"), mask);
                rejects(() -> CaptureFrame.readIfNew(directory, NOW, null, watch)); // Permanently poisoned.
            }
            clear(directory);
            try (var watch = new CaptureCloseWatch(directory)) {
                Files.write(directory.resolve(PREFIX + "color.bmp"), bmp);
                Files.write(directory.resolve(PREFIX + "mask.png"), mask);
                Files.write(directory.resolve(PREFIX + "depth.hdr"), depth);
                Files.writeString(directory.resolve("eve-frame.txt"), descriptor);
                var loaded = CaptureFrame.readIfNew(directory, NOW, null, watch);
                Path replacement = directory.resolve("replacement.tmp");
                Files.write(replacement, mask);
                Files.move(replacement, directory.resolve(PREFIX + "mask.png"), StandardCopyOption.REPLACE_EXISTING);
                rejects(() -> CaptureImages.decodeMasked(loaded));
            }
            clear(directory);
            try (var watch = new CaptureCloseWatch(directory)) {
                Files.write(directory.resolve(PREFIX + "color.bmp"), bmp);
                Files.write(directory.resolve(PREFIX + "mask.png"), mask);
                Files.write(directory.resolve(PREFIX + "depth.hdr"), depth);
                Files.writeString(directory.resolve("eve-frame.txt"), descriptor);
                var loaded = CaptureFrame.readIfNew(directory, NOW, null, watch);
                watch.close();
                rejects(() -> CaptureImages.decodeMasked(loaded));
            }
            clear(directory);
            for (int failure : new int[] {CaptureCloseWatch.OVERFLOW, CaptureCloseWatch.IGNORED,
                    CaptureCloseWatch.DELETE_SELF, CaptureCloseWatch.MOVE_SELF, CaptureCloseWatch.UNMOUNT}) {
                try (var watch = new CaptureCloseWatch(directory)) {
                    watch.event(failure, "");
                    rejects(watch::drain);
                    rejects(watch::drain); // Real loss never becomes a retryable backlog.
                }
            }
            try (var watch = new CaptureCloseWatch(directory)) {
                for (int i = 1; i <= 129; i++) watch.event(CaptureCloseWatch.CREATE, "eve-frame-1791110000-" + i + "-color.bmp");
                rejects(watch::drain);
            }
            // V1/V2 do not require a functioning close-observation epoch.
            Files.write(directory.resolve(PREFIX + "color.bmp"), bmp);
            Files.write(directory.resolve(PREFIX + "mask.png"), mask);
            Files.write(directory.resolve(PREFIX + "depth.hdr"), depth);
            Files.writeString(directory.resolve("eve-frame.txt"), CaptureBmpTest.descriptor(2, "bmp", bmp.length, mask.length, depth.length));
            assert CaptureFrame.readIfNew(directory, NOW, null, null).metadata().version() == 2;
            Files.write(directory.resolve(PREFIX + "color.png"), mask);
            Files.writeString(directory.resolve("eve-frame.txt"), CaptureBmpTest.descriptor(1, "png", mask.length, mask.length, depth.length));
            assert CaptureFrame.readIfNew(directory, NOW, null, null).metadata().version() == 1;
            Files.writeString(directory.resolve("eve-frame.txt"), descriptor);
            rejects(() -> CaptureFrame.readIfNew(directory, NOW, null, null));
        } finally {
            clear(directory);
            Files.delete(directory);
        }
        System.out.println("Async capture tests passed: real close-write lifecycle, immutable generations, strict v3, shared decode and legacy fallback");
    }

    private static void validatePng(byte[] png) throws Exception {
        CaptureFrame.validatePng(png, 1280, 720);
        byte[] corrupt = png.clone(); corrupt[40] ^= 1;
        rejects(() -> CaptureFrame.validatePng(corrupt, 1280, 720));
        for (int index : new int[] {26, 27, 28}) {
            byte[] wrong = png.clone(); wrong[index] = 1;
            CRC32 crc = new CRC32(); crc.update(wrong, 12, 17);
            ByteBuffer.wrap(wrong).putInt(29, (int) crc.getValue());
            rejects(() -> CaptureFrame.validatePng(wrong, 1280, 720));
        }
        byte[] duplicate = new byte[png.length + 25];
        System.arraycopy(png, 0, duplicate, 0, 33);
        System.arraycopy(png, 8, duplicate, 33, 25);
        System.arraycopy(png, 33, duplicate, 58, png.length - 33);
        rejects(() -> CaptureFrame.validatePng(duplicate, 1280, 720));
        rejects(() -> CaptureFrame.validatePng(Arrays.copyOf(png, png.length - 1), 1280, 720));
    }
    private static void clear(Path directory) throws IOException {
        try (var files = Files.list(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
    private static void rejects(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid asynchronous capture accepted"); }
        catch (IOException | IllegalArgumentException expected) { }
    }
    private interface Action { void run() throws Exception; }
}
