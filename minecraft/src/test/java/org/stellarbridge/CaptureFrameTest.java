package org.stellarbridge;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import javax.imageio.ImageIO;

public final class CaptureFrameTest {
    private static final String ID = "1791110000-1";
    private static final String FRAME = "SBMC_FRAME 1 " + ID + " 1791110000\n"
            + "SIZE 320 180\nCAMERA 1 2 3 4 5 0 75\nPAWN 7 8 9 95\nCONTEXT 11 12\n"
            + "LINK none 0 100 0 0 0\nCOLOR eve-frame-" + ID + "-color.png 1000\n"
            + "MASK eve-frame-" + ID + "-mask.png 1000\nDEPTH eve-frame-" + ID + "-depth.hdr 1000\nEND " + ID + "\n";

    public static void main(String[] args) throws Exception {
        CaptureFrame.Metadata frame = CaptureFrame.parse(FRAME.getBytes(StandardCharsets.US_ASCII));
        assert frame != null : "A complete native frame must parse";
        assert frame.id().equals(ID) && frame.time() == 1791110000 && frame.fov() == 75;
        assert frame.camera().position().x() == 1 && frame.camera().pitch() == 4 && frame.pawn().halfHeight() == 95;
        assert frame.context().pawn() == 11 && frame.link().session().equals("none") && frame.link().scale() == 100;
        assert CaptureFrame.fresh(frame.time(), 1791110030) && !CaptureFrame.fresh(frame.time(), 1791110031);
        assert CaptureFrame.fresh(frame.time(), 1791109998) && !CaptureFrame.fresh(frame.time(), 1791109997);
        CaptureFrame.parse(FRAME.replace("LINK none 0", "LINK 0123456789abcdef0123456789abcdef 4").getBytes(StandardCharsets.US_ASCII));
        for (String invalid : new String[] {
                FRAME.replace("SBMC_FRAME 1", "SBMC_FRAME 2"), FRAME.replace("SIZE 320", "SIZE 640"),
                FRAME.replace(" 1791110000\n", " 1791110001\n"), FRAME.replace("CAMERA 1", "CAMERA NaN"),
                FRAME.replace(" 0 75\n", " 0 180\n"), FRAME.replace(" 9 95\n", " 9 0\n"),
                FRAME.replace("CONTEXT 11 12", "CONTEXT 0 12"), FRAME.replace("LINK none 0", "LINK none 1"),
                FRAME.replace("LINK none 0 100 0", "LINK none 0 100 1"), FRAME.replace("LINK none 0 100", "LINK none 0 0"),
                FRAME.replace("eve-frame-" + ID + "-color.png", "../elsewhere.png"),
                FRAME.replace("-color.png 1000", "-color.png 4194305"), FRAME.replace("-mask.png 1000", "-mask.png 0"),
                FRAME.replace("END " + ID, "END 1791110000-2"), FRAME.replace("SIZE ", "SIZE  "),
                FRAME.replace("\n", "\r\n"), FRAME + "EXTRA\n", FRAME.stripTrailing(), "x".repeat(2049) }) {
            rejects(() -> CaptureFrame.parse(invalid.getBytes(StandardCharsets.US_ASCII)));
        }
        byte[] nonAscii = FRAME.getBytes(StandardCharsets.US_ASCII); nonAscii[20] = (byte) 0xff;
        rejects(() -> CaptureFrame.parse(nonAscii));
        assert CaptureFrame.previewAlpha(0) == 255;
        assert CaptureFrame.previewAlpha(127) == 128;
        assert CaptureFrame.previewAlpha(128) == 0;
        assert CaptureFrame.previewAlpha(255) == 0;

        Path directory = Files.createTempDirectory("stellar-capture-test-");
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB), "PNG", png);
            byte[] color = png.toByteArray(), depth = depthFixture();
            float[] values = CaptureFrame.decodeDepth(depth);
            assert values.length == 57600 && values[0] == 0;
            byte[] one = depth.clone();
            int firstScanline = "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 180 +X 320\n".length();
            one[firstScanline + 5] = (byte) 128; // R = 128 for the first 127 pixels.
            one[firstScanline + 23] = (byte) 129; // E = 129, so R * 2^(129-136) = 1.
            values = CaptureFrame.decodeDepth(one);
            assert values[0] == 1 && values[126] == 1 && values[127] == 0;
            one[firstScanline + 23] = (byte) 255;
            rejects(() -> CaptureFrame.decodeDepth(one));
            String metadata = FRAME.replace("-color.png 1000", "-color.png " + color.length)
                    .replace("-mask.png 1000", "-mask.png " + color.length)
                    .replace("-depth.hdr 1000", "-depth.hdr " + depth.length);
            Files.writeString(directory.resolve("eve-frame.txt"), metadata);
            Path colorPath = directory.resolve("eve-frame-" + ID + "-color.png");
            Files.write(colorPath, color);
            Files.write(colorPath, new byte[4194305]);
            rejects(() -> CaptureFrame.read(directory, 1791110000));
            Files.write(colorPath, color);
            Files.write(directory.resolve("eve-frame-" + ID + "-mask.png"), color);
            Files.write(directory.resolve("eve-frame-" + ID + "-depth.hdr"), depth);
            CaptureFrame.Loaded loaded = CaptureFrame.read(directory, 1791110000);
            assert Arrays.equals(loaded.color(), color) && Arrays.equals(loaded.mask(), color);
            Files.delete(colorPath);
            assert CaptureFrame.readIfNew(directory, 1791110000, ID) == null
                    : "An unchanged generation must not read retired image files";
            assert CaptureFrame.readIfNew(directory, 1791110000, "1791110000-2") == null
                    : "A regressed generation must not refresh live capture";
            Files.write(colorPath, color);
            assert CaptureFrame.newer("1791110000-10", "1791110000-9") : "Sequence ordering must be numeric";
            assert CaptureFrame.newer("1791110001-1", "1791110000-10");
            rejects(() -> CaptureFrame.read(directory, 1791110031));
            Files.write(colorPath, Arrays.copyOf(color, color.length - 1));
            rejects(() -> CaptureFrame.read(directory, 1791110000));
            Files.write(colorPath, color);
            byte[] wrongSize = color.clone(); wrongSize[19] = 65;
            Files.write(colorPath, wrongSize);
            rejects(() -> CaptureFrame.read(directory, 1791110000));
            Files.delete(colorPath);
            if (BridgeRuntimeTest.symlink(colorPath, directory.resolve("eve-frame-" + ID + "-mask.png"))) {
                rejects(() -> CaptureFrame.read(directory, 1791110000));
                Files.delete(colorPath);
            }
            Files.write(colorPath, color);
            byte[] badDepth = depth.clone(); badDepth[depth.length - 1] = 0;
            // Corrupt a scanline run length, preserving declared byte count.
            badDepth[badDepth.length - 2] = 0;
            Files.write(directory.resolve("eve-frame-" + ID + "-depth.hdr"), badDepth);
            rejects(() -> CaptureFrame.read(directory, 1791110000));
            Files.writeString(directory.resolve("eve-frame.txt"), metadata.replace(" 0 75\n", " 0 76\n"));
            rejects(() -> CaptureFrame.verifyUnchanged(directory, loaded.descriptor()));
            Path alias = directory.resolve("alias");
            if (BridgeRuntimeTest.symlink(alias, directory)) rejects(() -> CaptureFrame.read(alias, 1791110000));
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
        System.out.println("Capture frame tests passed");
    }

    private static byte[] depthFixture() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n-Y 180 +X 320\n".getBytes(StandardCharsets.US_ASCII));
        for (int y = 0; y < 180; y++) {
            bytes.write(new byte[] {2, 2, 1, 64});
            for (int channel = 0; channel < 4; channel++)
                bytes.write(new byte[] {(byte) 255, 0, (byte) 255, 0, (byte) 194, 0});
        }
        return bytes.toByteArray();
    }

    private static void rejects(CheckedRunnable action) throws Exception {
        try { action.run(); throw new AssertionError("Invalid capture accepted"); }
        catch (IllegalArgumentException | java.io.IOException expected) { }
    }
    private interface CheckedRunnable { void run() throws Exception; }
}
