package org.stellarbridge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.texture.NativeImage;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Shared decoding for the bounded capture color and PNG mask. */
final class CaptureImages {
    static NativeImage decode(byte[] bytes) throws IOException {
        // The byte[] overload copies the whole compressed PNG onto LWJGL's small thread stack.
        // Minecraft's stream overload uses a temporary native-heap buffer and frees it in finally.
        return NativeImage.read(new ByteArrayInputStream(bytes));
    }

    static NativeImage decodeMasked(byte[] colorBytes, byte[] maskBytes, int width, int height) throws IOException {
        return decodeMasked(colorBytes, maskBytes, width, height, false);
    }

    static NativeImage decodeMasked(CaptureFrame.Loaded frame) throws IOException {
        var metadata = frame.metadata();
        return decodeMasked(frame.color(), frame.mask(), metadata.width(), metadata.height(), metadata.version() == 2);
    }

    private static NativeImage decodeMasked(byte[] colorBytes, byte[] maskBytes, int width, int height, boolean bmp) throws IOException {
        // Validate both planes before asking STB to allocate any native pixels.
        if (bmp) CaptureFrame.validateBmp(colorBytes, width, height);
        else CaptureFrame.validatePng(colorBytes, width, height);
        CaptureFrame.validatePng(maskBytes, width, height);
        NativeImage color = bmp ? decodeBmp(colorBytes, width, height) : decode(colorBytes);
        boolean complete = false;
        try (NativeImage mask = decode(maskBytes)) {
            if (color.getWidth() != width || color.getHeight() != height || mask.getWidth() != width || mask.getHeight() != height)
                throw new IOException("Decoded capture dimensions differ from the descriptor");
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int alpha = CaptureFrame.previewAlpha(mask.getOpacity(x, y) & 255);
                    color.setColor(x, y, color.getColor(x, y) & 0x00ffffff | alpha << 24);
                }
            }
            complete = true;
            return color;
        } finally {
            if (!complete) color.close();
        }
    }

    private static NativeImage decodeBmp(byte[] bytes, int width, int height) throws IOException {
        // NativeImage.read validates PNG signatures. Use the installed STB decoder for the validated BMP.
        ByteBuffer input = MemoryUtil.memAlloc(bytes.length), pixels = null;
        NativeImage image = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            input.put(bytes).flip();
            var decodedWidth = stack.mallocInt(1);
            var decodedHeight = stack.mallocInt(1);
            var channels = stack.mallocInt(1);
            pixels = STBImage.stbi_load_from_memory(input, decodedWidth, decodedHeight, channels, 4);
            if (pixels == null) throw new IOException("Native BMP decode failed: " + STBImage.stbi_failure_reason());
            if (decodedWidth.get(0) != width || decodedHeight.get(0) != height)
                throw new IOException("Decoded BMP dimensions differ from the descriptor");
            pixels.order(ByteOrder.LITTLE_ENDIAN); // RGBA bytes become NativeImage's packed ABGR integer.
            image = new NativeImage(NativeImage.Format.RGBA, width, height, false);
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++)
                // Absolute reads retain the allocation base required by stbi_image_free(ByteBuffer).
                image.setColor(x, y, pixels.getInt((y * width + x) * 4));
            NativeImage complete = image;
            image = null;
            return complete;
        } finally {
            if (image != null) image.close();
            if (pixels != null) STBImage.stbi_image_free(pixels);
            MemoryUtil.memFree(input);
        }
    }
}
