package org.stellarbridge;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import net.minecraft.client.texture.NativeImage;

public final class CaptureBmpTest {
    private static final String ID = "1791110000-1", PREFIX = "eve-frame-" + ID + "-";
    private static final int WIDTH = 1280, HEIGHT = 720;

    public static void main(String[] args) throws Exception {
        byte[] bmp = bmp();
        BufferedImage original = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++) original.setRGB(x, y, rgb(x, y));
        byte[] png = png(original);
        BufferedImage maskImage = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB);
        maskImage.setRGB(0, 0, 0x7f000000);
        maskImage.setRGB(WIDTH - 1, HEIGHT - 1, 0x80000000);
        byte[] mask = png(maskImage), depth = depth();
        String descriptor = descriptor(2, "bmp", bmp.length, mask.length, depth.length);
        var metadata = CaptureFrame.parse(descriptor.getBytes(StandardCharsets.US_ASCII));
        assert metadata.version() == 2;
        var legacy = CaptureFrame.parse(descriptor(1, "png", png.length, mask.length, depth.length).getBytes(StandardCharsets.US_ASCII));
        assert legacy.version() == 1;
        assert metadata.camera().equals(legacy.camera()) && metadata.pawn().equals(legacy.pawn());
        assert CaptureProjection.camera(metadata, new CaptureFrame.Position(0, 0, 0))
                .equals(CaptureProjection.camera(legacy, new CaptureFrame.Position(0, 0, 0)));
        for (String invalid : new String[] {
                descriptor.replace("SBMC_FRAME 2", "SBMC_FRAME 3"),
                descriptor.replace("SBMC_FRAME 2", "SBMC_FRAME 1"),
                descriptor.replace("color.bmp", "color.png"),
                descriptor.replace("mask.png", "mask.bmp"),
                descriptor.replace("MASK " + PREFIX + "mask.png " + mask.length, "DEPTH_RANGE 1 500"),
                descriptor.replace("color.bmp", "../color.bmp"),
                descriptor.replace("color.bmp " + bmp.length, "color.bmp 4194305") })
            rejects(() -> CaptureFrame.parse(invalid.getBytes(StandardCharsets.US_ASCII)));

        CaptureFrame.validateBmp(bmp, WIDTH, HEIGHT);
        for (int[] field : new int[][] {{2, bmp.length - 1}, {6, 1}, {10, 55}, {14, 108}, {18, 16384},
                {22, -HEIGHT}, {30, 1}, {34, 0}, {46, 1}, {50, 1}}) {
            byte[] bad = bmp.clone();
            ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putInt(field[0], field[1]);
            rejects(() -> CaptureFrame.validateBmp(bad, WIDTH, HEIGHT));
        }
        for (int[] field : new int[][] {{0, 0}, {26, 2}, {28, 32}}) {
            byte[] bad = bmp.clone();
            ByteBuffer.wrap(bad).order(ByteOrder.LITTLE_ENDIAN).putShort(field[0], (short) field[1]);
            rejects(() -> CaptureFrame.validateBmp(bad, WIDTH, HEIGHT));
        }
        rejects(() -> CaptureFrame.validateBmp(Arrays.copyOf(bmp, 53), WIDTH, HEIGHT));
        rejects(() -> CaptureFrame.validateBmp(Arrays.copyOf(bmp, bmp.length - 1), WIDTH, HEIGHT));
        rejects(() -> CaptureFrame.validateBmp(Arrays.copyOf(bmp, bmp.length + 1), WIDTH, HEIGHT));
        rejects(() -> CaptureFrame.validateBmp(new byte[4194305], WIDTH, HEIGHT));
        rejects(() -> CaptureFrame.validateBmp(bmp, 320, 180));

        Path directory = Files.createTempDirectory("stellar-bmp-capture-");
        try {
            Files.writeString(directory.resolve("eve-frame.txt"), descriptor);
            Files.write(directory.resolve(PREFIX + "color.bmp"), bmp);
            Files.write(directory.resolve(PREFIX + "mask.png"), mask);
            Files.write(directory.resolve(PREFIX + "depth.hdr"), depth);
            var loaded = CaptureFrame.read(directory, 1791110000);
            assert loaded.depth()[0] == 1 && loaded.depth()[WIDTH * HEIGHT - 1] == 1;
            try (var worker = Executors.newSingleThreadExecutor()) {
                worker.submit(() -> {
                    // Repeat real STB decode/release to exercise native-buffer ownership on a worker.
                    for (int attempt = 0; attempt < 3; attempt++) {
                        try (NativeImage image = CaptureImages.decodeMasked(loaded);
                             NativeImage expected = CaptureImages.decodeMasked(png, mask, WIDTH, HEIGHT)) {
                            assert image.getWidth() == WIDTH && image.getHeight() == HEIGHT;
                            for (int y = 0; y < HEIGHT; y++) for (int x = 0; x < WIDTH; x++)
                                assert image.getColor(x, y) == expected.getColor(x, y) : "BMP pixel differs at " + x + "," + y;
                            assert image.getColor(0, 0) >>> 24 == 128;
                            assert image.getColor(WIDTH - 1, HEIGHT - 1) >>> 24 == 0;
                        }
                    }
                    return null;
                }).get();
            }
            byte[] hugeDimensions = bmp.clone();
            ByteBuffer.wrap(hugeDimensions).order(ByteOrder.LITTLE_ENDIAN).putInt(18, Integer.MAX_VALUE);
            var invalid = new CaptureFrame.Loaded(metadata, loaded.descriptor(), hugeDimensions, mask, loaded.depth());
            rejects(() -> CaptureImages.decodeMasked(invalid));
            Files.delete(directory.resolve(PREFIX + "color.bmp"));
            Files.write(directory.resolve(PREFIX + "color.png"), png);
            rejects(() -> CaptureFrame.read(directory, 1791110000)); // v2 cannot silently fall back to a PNG.
            Files.writeString(directory.resolve("eve-frame.txt"), descriptor(1, "png", png.length, mask.length, depth.length));
            var v1 = CaptureFrame.read(directory, 1791110000);
            try (NativeImage image = CaptureImages.decodeMasked(v1)) {
                assert image.getColor(0, 0) >>> 24 == 128;
            }
            rejects(() -> CaptureFrame.verifyUnchanged(directory, loaded.descriptor()));
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("BMP capture tests passed: strict v2 header, backward-compatible v1, real HD STB pixels and repeated worker release");
    }

    private static int rgb(int x, int y) {
        return 0xff000000 | ((x * 17 + y * 3) & 255) << 16 | ((x * 7 + y * 11) & 255) << 8 | ((x * 13 + y * 5) & 255);
    }
    static byte[] bmp() {
        ByteBuffer output = ByteBuffer.allocate(54 + WIDTH * HEIGHT * 3).order(ByteOrder.LITTLE_ENDIAN);
        output.putShort((short) 0x4d42).putInt(output.capacity()).putInt(0).putInt(54);
        output.putInt(40).putInt(WIDTH).putInt(HEIGHT).putShort((short) 1).putShort((short) 24);
        output.putInt(0).putInt(WIDTH * HEIGHT * 3).putInt(0).putInt(0).putInt(0).putInt(0);
        for (int y = HEIGHT - 1; y >= 0; y--) for (int x = 0; x < WIDTH; x++) {
            int value = rgb(x, y);
            output.put((byte) value).put((byte) (value >>> 8)).put((byte) (value >>> 16));
        }
        return output.array();
    }
    static byte[] png(BufferedImage image) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "PNG", output);
        return output.toByteArray();
    }
    static byte[] depth() throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y " + HEIGHT + " +X " + WIDTH + "\n").getBytes(StandardCharsets.US_ASCII));
        for (int y = 0; y < HEIGHT; y++) {
            output.write(new byte[] {2, 2, (byte) (WIDTH >>> 8), (byte) WIDTH});
            for (int channel = 0; channel < 4; channel++) for (int remaining = WIDTH; remaining > 0;) {
                int count = Math.min(127, remaining);
                output.write(count + 128);
                output.write(channel == 0 ? 128 : channel == 3 ? 129 : 0);
                remaining -= count;
            }
        }
        return output.toByteArray();
    }
    static String descriptor(int version, String suffix, int color, int mask, int depth) {
        return "SBMC_FRAME " + version + " " + ID + " 1791110000\nSIZE " + WIDTH + " " + HEIGHT
                + "\nCAMERA 1 2 3 4 5 0 75\nPAWN 7 8 9 95\nCONTEXT 11 12\n"
                + "LINK 0123456789abcdef0123456789abcdef 1 100 0 0 0\n"
                + "COLOR " + PREFIX + "color." + suffix + " " + color + "\nMASK " + PREFIX + "mask.png " + mask
                + "\nDEPTH " + PREFIX + "depth.hdr " + depth + "\nEND " + ID + "\n";
    }
    private static void rejects(Action action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid BMP capture accepted"); }
        catch (IllegalArgumentException | java.io.IOException expected) { }
    }
    private interface Action { void run() throws Exception; }
}
