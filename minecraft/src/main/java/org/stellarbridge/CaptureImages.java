package org.stellarbridge;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import net.minecraft.client.texture.NativeImage;

/** Shared decoding for the already bounded capture PNGs. */
final class CaptureImages {
    static NativeImage decode(byte[] bytes) throws IOException {
        // The byte[] overload copies the whole compressed PNG onto LWJGL's small thread stack.
        // Minecraft's stream overload uses a temporary native-heap buffer and frees it in finally.
        return NativeImage.read(new ByteArrayInputStream(bytes));
    }

    static NativeImage decodeMasked(byte[] colorBytes, byte[] maskBytes) throws IOException {
        NativeImage color = decode(colorBytes);
        boolean complete = false;
        try (NativeImage mask = decode(maskBytes)) {
            if (color.getWidth() != 320 || color.getHeight() != 180 || mask.getWidth() != 320 || mask.getHeight() != 180)
                throw new IOException("Decoded capture dimensions differ from the descriptor");
            for (int y = 0; y < 180; y++) {
                for (int x = 0; x < 320; x++) {
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
}
