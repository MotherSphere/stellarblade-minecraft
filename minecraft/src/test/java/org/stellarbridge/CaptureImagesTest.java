package org.stellarbridge;

import com.mojang.blaze3d.systems.RenderSystem;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import net.minecraft.client.texture.NativeImage;

public final class CaptureImagesTest {
    public static void main(String[] args) throws Exception {
        RenderSystem.initRenderThread(); // Pixel access checks thread ownership; no window or GL context is needed.
        BufferedImage source = new BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB);
        Random random = new Random(0x5b1adeL);
        for (int y = 0; y < 180; y++) {
            for (int x = 0; x < 320; x++) source.setRGB(x, y, random.nextInt());
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(source, "PNG", output);
        byte[] png = output.toByteArray();
        assert png.length > 65536 : "Fixture must exceed the native thread stack, as the crashing capture did";
        int expected = source.getRGB(137, 89);
        for (int attempt = 0; attempt < 3; attempt++) {
            try (NativeImage decoded = CaptureImages.decode(png)) {
                assert decoded.getWidth() == 320 && decoded.getHeight() == 180;
                assert (decoded.getRed(137, 89) & 255) == (expected >>> 16 & 255);
                assert (decoded.getGreen(137, 89) & 255) == (expected >>> 8 & 255);
                assert (decoded.getBlue(137, 89) & 255) == (expected & 255);
                assert (decoded.getOpacity(137, 89) & 255) == (expected >>> 24);
            }
        }
        try (NativeImage ignored = CaptureImages.decode(new byte[] {1, 2, 3})) {
            throw new AssertionError("Invalid PNG must fail decoding");
        } catch (IOException expectedFailure) { }
        BufferedImage mask = new BufferedImage(320, 180, BufferedImage.TYPE_INT_ARGB);
        mask.setRGB(137, 89, 0x7f000000);
        mask.setRGB(138, 89, 0x80000000);
        ByteArrayOutputStream maskOutput = new ByteArrayOutputStream();
        ImageIO.write(mask, "PNG", maskOutput);
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                assert !RenderSystem.isOnRenderThread();
                try (NativeImage decoded = CaptureImages.decodeMasked(png, maskOutput.toByteArray())) {
                    assert (decoded.getColor(137, 89) >>> 24) == 128;
                    assert (decoded.getColor(138, 89) >>> 24) == 0;
                    int pixel = decoded.getColor(137, 89); // ABGR; component helpers require the render thread.
                    assert (pixel & 255) == (expected >>> 16 & 255);
                    assert (pixel >>> 8 & 255) == (expected >>> 8 & 255);
                    assert (pixel >>> 16 & 255) == (expected & 255);
                }
                return null;
            }).get();
        }
        System.out.println("Capture native PNG tests passed: " + png.length + " bytes decoded through real STB");
    }
}
