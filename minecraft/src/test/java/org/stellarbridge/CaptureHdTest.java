package org.stellarbridge;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import net.minecraft.client.texture.NativeImage;

public final class CaptureHdTest {
    private static final String ID = "1791110000-1";
    private static final String PREFIX = "eve-frame-" + ID + "-";

    public static void main(String[] args) throws Exception {
        BufferedImage source = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(0x7205bL);
        for (int y = 0; y < 720; y++) for (int x = 0; x < 1280; x++) source.setRGB(x, y, random.nextInt());
        source.setRGB(0, 0, 0xff112233);
        source.setRGB(1278, 719, 0xffabcdef);
        byte[] color = png(source);
        assert color.length > 3 * 1024 * 1024 && color.length <= 4 * 1024 * 1024
                : "The real HD fixture must exercise a large bounded compressed PNG";
        BufferedImage maskImage = new BufferedImage(1280, 720, BufferedImage.TYPE_INT_ARGB);
        maskImage.setRGB(1279, 719, 0x80000000);
        byte[] mask = png(maskImage), depth = depth(1280, 720);
        String descriptor = descriptor(1280, 720, color.length, mask.length, depth.length);
        var metadata = CaptureFrame.parse(descriptor.getBytes(StandardCharsets.US_ASCII));
        assert metadata.width() == 1280 && metadata.height() == 720;
        var low = CaptureFrame.parse(descriptor(320, 180, color.length, mask.length, depth.length).getBytes(StandardCharsets.US_ASCII));
        assert CaptureProjection.camera(metadata, new CaptureFrame.Position(0, 0, 0))
                .equals(CaptureProjection.camera(low, new CaptureFrame.Position(0, 0, 0)))
                : "Resolution must not change the same 16:9 camera/depth projection";
        for (int[] size : new int[][] {{640,360}, {1280,180}, {320,720}, {1920,1080}, {0,720}, {Integer.MAX_VALUE,720}})
            rejects(() -> CaptureFrame.parse(descriptor(size[0], size[1], color.length, mask.length, depth.length).getBytes(StandardCharsets.US_ASCII)));

        Path directory = Files.createTempDirectory("stellar-hd-capture-");
        try {
            Files.writeString(directory.resolve("eve-frame.txt"), descriptor);
            Files.write(directory.resolve(PREFIX + "color.png"), color);
            Files.write(directory.resolve(PREFIX + "mask.png"), mask);
            Files.write(directory.resolve(PREFIX + "depth.hdr"), depth);
            var loaded = CaptureFrame.read(directory, 1791110000);
            assert loaded.depth().length == 1280 * 720;
            assert loaded.depth()[0] == 1 && loaded.depth()[1279] == 1;
            assert loaded.depth()[1280] == 0.5f && loaded.depth()[1280 * 720 - 1] == 0.5f;
            try (var worker = Executors.newSingleThreadExecutor()) {
                worker.submit(() -> {
                    try (NativeImage image = CaptureImages.decodeMasked(loaded.color(), loaded.mask(), 1280, 720)) {
                        assert image.getWidth() == 1280 && image.getHeight() == 720;
                        assert image.getColor(0, 0) == 0xff332211;
                        assert image.getColor(1278, 719) == 0xffefcdab;
                        assert image.getColor(1279, 719) >>> 24 == 0;
                    }
                    return null;
                }).get();
            }
            byte[] lowMask = png(new BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB));
            rejects(() -> CaptureImages.decodeMasked(color, lowMask, 1280, 720));
            rejects(() -> CaptureImages.decodeMasked(color, mask, 320, 180));
            rejects(() -> CaptureImages.decodeMasked(new byte[4 * 1024 * 1024 + 1], mask, 1280, 720));
            byte[] oversizedHeader = color.clone();
            java.nio.ByteBuffer.wrap(oversizedHeader).putInt(16, 16384).putInt(20, 16384);
            rejects(() -> CaptureImages.decodeMasked(oversizedHeader, mask, 1280, 720));
            Files.write(directory.resolve(PREFIX + "mask.png"), lowMask);
            Files.writeString(directory.resolve("eve-frame.txt"), descriptor(1280, 720, color.length, lowMask.length, depth.length));
            rejects(() -> CaptureFrame.read(directory, 1791110000));
            Files.write(directory.resolve(PREFIX + "mask.png"), mask);
            byte[] lowDepth = depth(320, 180);
            Files.write(directory.resolve(PREFIX + "depth.hdr"), lowDepth);
            Files.writeString(directory.resolve("eve-frame.txt"), descriptor(1280, 720, color.length, mask.length, lowDepth.length));
            rejects(() -> CaptureFrame.read(directory, 1791110000));
            byte[] wrongScanline = depth.clone();
            int header = "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 720 +X 1280\n".length();
            wrongScanline[header + 3] = 1;
            rejects(() -> CaptureFrame.decodeDepth(wrongScanline, 1280, 720));
            rejects(() -> CaptureFrame.decodeDepth(new byte[4 * 1024 * 1024 + 1], 1280, 720));
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("HD capture tests passed: real " + color.length + "-byte PNG, mask and 921600 depth values");
    }

    private static String descriptor(int width, int height, int color, int mask, int depth) {
        return "SBMC_FRAME 1 " + ID + " 1791110000\nSIZE " + width + " " + height
                + "\nCAMERA 1 2 3 4 5 0 75\nPAWN 7 8 9 95\nCONTEXT 11 12\n"
                + "LINK 0123456789abcdef0123456789abcdef 1 100 0 0 0\n"
                + "COLOR " + PREFIX + "color.png " + color + "\nMASK " + PREFIX + "mask.png " + mask
                + "\nDEPTH " + PREFIX + "depth.hdr " + depth + "\nEND " + ID + "\n";
    }
    private static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", output);
        return output.toByteArray();
    }
    private static byte[] depth(int width, int height) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y " + height + " +X " + width + "\n").getBytes(StandardCharsets.US_ASCII));
        for (int y = 0; y < height; y++) {
            output.write(new byte[] {2, 2, (byte) (width >>> 8), (byte) width});
            for (int channel = 0; channel < 4; channel++) {
                int remaining = width;
                while (remaining > 0) {
                    int run = Math.min(127, remaining);
                    output.write(128 + run);
                    output.write(channel == 0 ? (y % 2 == 0 ? 128 : 64) : channel == 3 ? 129 : 0);
                    remaining -= run;
                }
            }
        }
        return output.toByteArray();
    }
    private static void rejects(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid HD capture accepted"); }
        catch (IllegalArgumentException | java.io.IOException expected) { }
    }
    private interface Action { void run() throws Exception; }
}
