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
}
