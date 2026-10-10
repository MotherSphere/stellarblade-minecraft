package org.stellarbridge;

import com.mojang.blaze3d.platform.GlStateManager;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * Pack textures for AvatarRenderer. decode reads and decodes a PNG on any thread, with what the upload needs (the optional
 * texel conversion, a cut-out texture's coverage-keeping mip levels); upload creates the GL texture on the render thread.
 * Uploaded textures stay here across renderers, the least recently used beyond the budget dropped at trim, so a rebuild
 * finds the textures of the items worn before. One cache serves every EVE drawn: another player's EVE draws the textures
 * this player's EVE already holds, and decodes the others at half resolution (a quarter of the memory), so several
 * outfits fit the budget.
 */
final class AvatarTextures implements AutoCloseable {
    // ponytail: one fixed budget for the stream's GL textures (an outfit with its drone, face and sword takes about 650 MB
    // with mipmaps: two fit); a VRAM query should a smaller GPU need less.
    static final long BUDGET = 1536L << 20;

    /**
     * A texture as a renderer asks for it: the pack file, the cut-out threshold (0: none), an optional texel conversion and
     * whether half its resolution will do (another player's EVE).
     */
    record Texture(String name, float cutoff, Consumer<ByteBuffer> convert, boolean half) {
        Texture(String name, float cutoff, Consumer<ByteBuffer> convert) { this(name, cutoff, convert, false); }
        String key() { return (convert == null ? name : name + "#converted") + (half ? "#half" : ""); }
        /** The same texture at full resolution, which a half one takes instead when it is uploaded already. */
        Texture full() { return new Texture(name, cutoff, convert, false); }
    }
    /**
     * Decoded texels: level 0 (from stb unless halved), then every further level of a cut-out texture (one level: the GPU
     * makes them); null: no file.
     */
    record Decoded(int width, int height, ByteBuffer[] levels, boolean stb) {
        Decoded(int width, int height, ByteBuffer[] levels) { this(width, height, levels, true); }
        /** The GL texture's size with its mipmaps. */
        long bytes() { return levels == null ? 0 : 4L * width * height * 4 / 3; }
        void free() {
            if (levels == null) return;
            if (stb) STBImage.stbi_image_free(levels[0]);
            else MemoryUtil.memFree(levels[0]);
            for (int l = 1; l < levels.length; l++) MemoryUtil.memFree(levels[l]);
        }
    }
    private record Entry(int id, long bytes) { }

    private final Map<String, Entry> uploaded = new LinkedHashMap<>(16, 0.75f, true); // Least recently used first.
    private final Set<String> keys = ConcurrentHashMap.newKeySet(); // uploaded's keys, readable off the render thread.
    private final long budget;
    private final IntConsumer delete;
    private long bytes;

    AvatarTextures() { this(BUDGET, GlStateManager::_deleteTexture); }
    AvatarTextures(long budget, IntConsumer delete) {
        this.budget = budget;
        this.delete = delete;
    }

    /** Any thread: whether key is uploaded (it may be dropped by the next trim). */
    boolean holds(String key) { return keys.contains(key); }
    /** The GL texture of key (0: no such file), or null when it is not uploaded. */
    Integer id(String key) {
        Entry entry = uploaded.get(key);
        return entry == null ? null : entry.id;
    }
    /** Uploads decoded under key and frees it; returns its GL texture, 0 without a file. */
    int upload(String key, Decoded decoded) {
        try {
            return keep(key, decoded.levels == null ? 0 : upload(decoded.levels, decoded.width, decoded.height), decoded.bytes());
        } finally {
            decoded.free();
        }
    }
    int keep(String key, int id, long size) {
        Entry previous = uploaded.put(key, new Entry(id, size));
        if (previous != null) { bytes -= previous.bytes; if (previous.id != 0 && previous.id != id) delete.accept(previous.id); }
        keys.add(key);
        bytes += size;
        return id;
    }
    /** Uploads pending textures (freeing them) for at least one and at most about nanos; true once none is left. */
    boolean upload(Map<String, Decoded> pending, long nanos) {
        long start = System.nanoTime();
        for (Iterator<Map.Entry<String, Decoded>> it = pending.entrySet().iterator(); it.hasNext() && System.nanoTime() - start < nanos; ) {
            var entry = it.next();
            it.remove();
            upload(entry.getKey(), entry.getValue());
        }
        return pending.isEmpty();
    }
    /** Deletes the least recently used textures outside used while the budget is exceeded. */
    void trim(Collection<String> used) { trim(used, budget); }
    /** The same against another budget (0: every texture outside used goes). */
    void trim(Collection<String> used, long budget) {
        for (Iterator<Map.Entry<String, Entry>> it = uploaded.entrySet().iterator(); bytes > budget && it.hasNext(); ) {
            var entry = it.next();
            if (used.contains(entry.getKey())) continue;
            if (entry.getValue().id != 0) delete.accept(entry.getValue().id);
            bytes -= entry.getValue().bytes;
            keys.remove(entry.getKey());
            it.remove();
        }
    }
    long bytes() { return bytes; }

    @Override public void close() {
        for (Entry entry : uploaded.values()) if (entry.id != 0) delete.accept(entry.id);
        uploaded.clear();
        keys.clear();
        bytes = 0;
    }

    /**
     * Decodes in parallel, on the common pool, the textures of wanted this cache does not hold, by key; the caller frees what
     * it does not upload. One failure frees the rest and is thrown.
     */
    Map<String, Decoded> decode(Path pack, List<Texture> wanted) throws IOException {
        Map<String, Texture> missing = new LinkedHashMap<>();
        for (Texture texture : wanted)
            if (!holds(texture.key()) && !(texture.half() && holds(texture.full().key()))) missing.putIfAbsent(texture.key(), texture);
        Map<String, Decoded> decoded = new ConcurrentHashMap<>();
        AtomicReference<IOException> failure = new AtomicReference<>();
        missing.values().parallelStream().forEach(texture -> {
            try { decoded.put(texture.key(), decode(pack, texture)); }
            catch (IOException | RuntimeException problem) {
                failure.compareAndSet(null, problem instanceof IOException io ? io : new IOException(texture.name() + ": " + problem.getMessage(), problem));
            }
        });
        if (failure.get() != null) {
            decoded.values().forEach(Decoded::free);
            throw failure.get();
        }
        return decoded;
    }

    /**
     * One pack texture decoded to RGBA with its levels (halved first when half, a cut-out one keeping the share of texels
     * at or above its cutoff, as its mip levels do); null levels when the file is absent.
     */
    static Decoded decode(Path pack, Texture texture) throws IOException {
        Path file = pack.resolve(texture.name());
        if (!file.normalize().startsWith(pack.normalize()) || !Files.isRegularFile(file)) return new Decoded(0, 0, null);
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer encoded = MemoryUtil.memAlloc(bytes.length).put(bytes).flip();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1), height = stack.mallocInt(1), channels = stack.mallocInt(1);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, width, height, channels, 4);
            if (pixels == null) throw new IOException(file.getFileName() + ": " + STBImage.stbi_failure_reason());
            int w = width.get(0), h = height.get(0);
            boolean stb = true;
            try {
                if (texture.convert() != null) texture.convert().accept(pixels);
                if (texture.half() && w >= 2 && h >= 2) {
                    int threshold = Math.round(texture.cutoff() * 255);
                    double coverage = texture.cutoff() > 0 ? AvatarRenderer.coverage(pixels, w * h, 1, threshold) : 1;
                    ByteBuffer halved = AvatarRenderer.halve(pixels, w, h);
                    STBImage.stbi_image_free(pixels);
                    pixels = halved;
                    stb = false;
                    w /= 2;
                    h /= 2;
                    if (coverage > 0 && coverage < 1) AvatarRenderer.preserve(pixels, w * h, threshold, coverage); // Thin strands stay.
                }
                return new Decoded(w, h, levels(pixels, w, h, texture.cutoff()), stb);
            } catch (RuntimeException failure) {
                if (stb) STBImage.stbi_image_free(pixels); else MemoryUtil.memFree(pixels);
                throw failure;
            }
        } finally {
            MemoryUtil.memFree(encoded);
        }
    }

    /**
     * Level 0 alone, or with a cutoff and any cut-out texel every box-filtered level, its alpha scaled so the share of texels
     * at or above the cutoff stays that of level 0: thin strands keep their density at a distance.
     */
    static ByteBuffer[] levels(ByteBuffer pixels, int width, int height, float cutoff) {
        int threshold = Math.round(cutoff * 255);
        double coverage = cutoff > 0 ? AvatarRenderer.coverage(pixels, width * height, 1, threshold) : 1;
        if (coverage >= 1) return new ByteBuffer[] {pixels};
        List<ByteBuffer> levels = new ArrayList<>(List.of(pixels));
        for (ByteBuffer level = pixels; width > 1 || height > 1; levels.add(level)) {
            level = AvatarRenderer.halve(level, width, height);
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
            AvatarRenderer.preserve(level, width * height, threshold, coverage);
        }
        return levels.toArray(ByteBuffer[]::new);
    }

    /** A new texture from its levels (one: mipmaps generated by the GPU); render thread. */
    static int upload(ByteBuffer[] levels, int width, int height) {
        int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT), rowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int skipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS), skipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        int unpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int id = GlStateManager._genTexture();
        try {
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GlStateManager._bindTexture(id);
            for (int l = 0; l < levels.length; l++, width = Math.max(1, width / 2), height = Math.max(1, height / 2))
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, l, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, levels[l]);
            if (levels.length == 1) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
        } finally {
            GlStateManager._bindTexture(previous);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
        }
        return id;
    }
}
