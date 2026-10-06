package org.stellarbridge;

import com.mojang.blaze3d.platform.GlStateManager;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * GPU skinning for an avatar pack on the render thread. Cached GL state goes through GlStateManager and
 * everything changed by a draw is restored, so Minecraft's own rendering continues unchanged.
 * A material slot draws with its base texture {@code <slot>.png} and optional maps beside it:
 * {@code .normal} (Unreal tangent space), {@code .surface} (occlusion, roughness, metallic, specular),
 * {@code .emissive}, {@code .hair} (root to tip, strand id, depth; selects hair shading) and {@code .scatter}
 * (subsurface color). A translucent slot ships {@code <slot>.overlay.png} instead: premultiplied color drawn after
 * every opaque section without writing depth.
 */
final class AvatarRenderer implements AutoCloseable {
    private static final String[] ATTRIBUTES = {"Position", "Normal", "UV0", "BoneIds", "BoneWeights", "MorphPosition", "MorphNormal"};
    private final AvatarModel model;
    // Texture units: base 0, normal 1, Minecraft's lightmap 2, surface 3, emissive 4, hair 5, scatter 6.
    private static final int[] UNITS = {0, 1, 3, 4, 5, 6};
    private static final float CUTOFF = 0.5f, HAIR_CUTOFF = 0.25f;
    /** GL textures of one slot in UNITS order (base, normal, surface, emissive, hair, scatter); base 0: not drawn. */
    private record Material(int[] maps, boolean hair, boolean overlay) {
        int base() { return maps[0]; }
    }
    private final int program, bones, viewProjection, lightUv, fogStart, fogEnd, fogColor, skyColor, hairShading, cutoff, overlayPass;
    private final int[] arrays, vertexBuffers, indexBuffers, morphBuffers;
    private final float[][] appliedWeights; // Morph weights currently uploaded per part.
    private final FloatBuffer[] morphOffsets;
    private final Material[][] sectionMaterials;
    private final Map<String, Material> materials = new HashMap<>();
    private final Map<String, Integer> textures = new HashMap<>();
    private int flatNormal, plainSurface, matte, black;
    final FloatBuffer[] palettes;

    AvatarRenderer(AvatarModel model, Path pack) throws IOException {
        this.model = model;
        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), previousArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int count = model.parts.size();
        arrays = new int[count];
        vertexBuffers = new int[count];
        indexBuffers = new int[count];
        morphBuffers = new int[count];
        appliedWeights = new float[count][];
        morphOffsets = new FloatBuffer[count];
        sectionMaterials = new Material[count][];
        palettes = new FloatBuffer[count];
        program = link(shader(GL20.GL_VERTEX_SHADER, "avatar.vsh"), shader(GL20.GL_FRAGMENT_SHADER, "avatar.fsh"));
        boolean complete = false;
        try {
            bones = GL20.glGetUniformLocation(program, "Bones");
            viewProjection = GL20.glGetUniformLocation(program, "ViewProjection");
            lightUv = GL20.glGetUniformLocation(program, "LightUv");
            fogStart = GL20.glGetUniformLocation(program, "FogStart");
            fogEnd = GL20.glGetUniformLocation(program, "FogEnd");
            fogColor = GL20.glGetUniformLocation(program, "FogColor");
            skyColor = GL20.glGetUniformLocation(program, "SkyColor");
            overlayPass = GL20.glGetUniformLocation(program, "Overlay");
            hairShading = GL20.glGetUniformLocation(program, "Hair");
            cutoff = GL20.glGetUniformLocation(program, "AlphaCutoff");
            GlStateManager._glUseProgram(program);
            for (int unit = 0; unit <= 6; unit++) GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler" + unit), unit);
            // Defaults for missing maps: flat normal; full occlusion, roughness 0.8, no metal, 4 % reflectance; black.
            flatNormal = solid(128, 128, 255, 255);
            plainSurface = solid(255, 204, 0, 128);
            matte = solid(255, 255, 0, 0); // Overlays without a surface map: no highlight, no reflection.
            black = solid(0, 0, 0, 255);
            for (int p = 0; p < count; p++) {
                AvatarMesh mesh = model.parts.get(p).mesh();
                palettes[p] = BufferUtils.createFloatBuffer(12 * mesh.bones.length);
                arrays[p] = GL30.glGenVertexArrays();
                GlStateManager._glBindVertexArray(arrays[p]);
                vertexBuffers[p] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffers[p]);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, mesh.vertices.duplicate(), GL15.GL_STATIC_DRAW);
                indexBuffers[p] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffers[p]);
                GL15.glBufferData(GL15.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.duplicate(), GL15.GL_STATIC_DRAW);
                int stride = AvatarMesh.VERTEX_BYTES;
                GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0);
                GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, stride, 12);
                GL20.glVertexAttribPointer(2, 2, GL11.GL_FLOAT, false, stride, 24);
                GL30.glVertexAttribIPointer(3, 4, GL11.GL_UNSIGNED_SHORT, stride, 32);
                GL20.glVertexAttribPointer(4, 4, GL11.GL_FLOAT, false, stride, 40);
                for (int a = 0; a < 5; a++) GL20.glEnableVertexAttribArray(a);
                if (mesh.morphs.length > 0) {
                    // Summed morph deltas per vertex; meshes without morphs read the zero generic attribute instead.
                    morphOffsets[p] = BufferUtils.createFloatBuffer(6 * mesh.vertexCount);
                    appliedWeights[p] = new float[mesh.morphs.length];
                    morphBuffers[p] = GL15.glGenBuffers();
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, morphBuffers[p]);
                    GL15.glBufferData(GL15.GL_ARRAY_BUFFER, morphOffsets[p], GL15.GL_DYNAMIC_DRAW);
                    GL20.glVertexAttribPointer(5, 3, GL11.GL_FLOAT, false, 24, 0);
                    GL20.glVertexAttribPointer(6, 3, GL11.GL_FLOAT, false, 24, 12);
                    GL20.glEnableVertexAttribArray(5);
                    GL20.glEnableVertexAttribArray(6);
                }
                sectionMaterials[p] = new Material[mesh.sections.length];
                for (int s = 0; s < mesh.sections.length; s++) sectionMaterials[p][s] = material(pack, mesh.sections[s].material());
            }
            complete = true;
        } finally {
            GlStateManager._glBindVertexArray(previousArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
            GlStateManager._glUseProgram(previousProgram);
            if (!complete) close();
        }
    }

    /**
     * Draws every part with the palettes filled by AvatarModel.palettes; positions are camera-relative blocks. Sky is
     * Minecraft's sky color, reflected by glossy surfaces in proportion to the sky light.
     */
    void draw(Matrix4f projectionView, int lightmap, int blockLight, int skyLight, float start, float end, float[] fog, float[] sky) {
        int program0 = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), array0 = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int active0 = GlStateManager._getActiveTexture();
        int[] bound0 = new int[7];
        int[] blendFunction0 = {GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB), GL11.glGetInteger(GL14.GL_BLEND_DST_RGB),
                GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA), GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA)};
        boolean depth0 = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull0 = GL11.glIsEnabled(GL11.GL_CULL_FACE), blend0 = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean mask0 = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int function0 = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        for (int unit = 0; unit < bound0.length; unit++) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
            bound0[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        try {
            GlStateManager._activeTexture(GL13.GL_TEXTURE2);
            GlStateManager._bindTexture(lightmap);
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._depthMask(true);
            GlStateManager._disableCull();
            GlStateManager._disableBlend();
            GlStateManager._glUseProgram(program);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GL20.glUniformMatrix4fv(viewProjection, false, projectionView.get(stack.mallocFloat(16)));
            }
            GL20.glUniform2i(lightUv, blockLight, skyLight);
            GL20.glUniform1f(fogStart, start);
            GL20.glUniform1f(fogEnd, end);
            GL20.glUniform4f(fogColor, fog[0], fog[1], fog[2], fog[3]);
            GL20.glUniform3f(skyColor, sky[0], sky[1], sky[2]);
            GL20.glVertexAttrib3f(5, 0, 0, 0);
            GL20.glVertexAttrib3f(6, 0, 0, 0);
            for (int p = 0; p < arrays.length; p++) if (morphBuffers[p] != 0) uploadMorphs(p);
            // Opaque and cut-out sections first; translucent overlays then blend over them without writing depth.
            for (boolean overlays : new boolean[] {false, true}) {
                if (overlays) {
                    GlStateManager._enableBlend();
                    GlStateManager._blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
                    GlStateManager._depthMask(false);
                }
                GL20.glUniform1i(overlayPass, overlays ? 1 : 0);
                for (int p = 0; p < arrays.length; p++) {
                    GlStateManager._glBindVertexArray(arrays[p]);
                    GL21.glUniformMatrix3x4fv(bones, false, palettes[p]);
                    AvatarMesh.Section[] sections = model.parts.get(p).mesh().sections;
                    for (int s = 0; s < sections.length; s++) {
                        Material material = sectionMaterials[p][s];
                        if (material.base() == 0 || material.overlay() != overlays) continue;
                        for (int k = 0; k < UNITS.length; k++) {
                            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNITS[k]);
                            GlStateManager._bindTexture(material.maps()[k]);
                        }
                        GL20.glUniform1i(hairShading, material.hair() ? 1 : 0);
                        GL20.glUniform1f(cutoff, material.hair() ? HAIR_CUTOFF : overlays ? 0 : CUTOFF);
                        GL11.glDrawElements(GL11.GL_TRIANGLES, sections[s].indexCount(), GL11.GL_UNSIGNED_INT, 4L * sections[s].firstIndex());
                    }
                }
            }
        } finally {
            GlStateManager._glBindVertexArray(array0);
            GlStateManager._glUseProgram(program0);
            for (int unit = 0; unit < bound0.length; unit++) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
                GlStateManager._bindTexture(bound0[unit]);
            }
            GlStateManager._activeTexture(active0);
            if (depth0) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
            if (cull0) GlStateManager._enableCull(); else GlStateManager._disableCull();
            if (blend0) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
            GlStateManager._depthFunc(function0);
            GlStateManager._depthMask(mask0);
            GlStateManager._blendFuncSeparate(blendFunction0[0], blendFunction0[1], blendFunction0[2], blendFunction0[3]);
        }
    }

    private void uploadMorphs(int part) {
        float[] weights = model.morphWeights[part];
        boolean changed = false;
        for (int m = 0; m < weights.length && !changed; m++) changed = Math.abs(weights[m] - appliedWeights[part][m]) > 1e-4f;
        if (!changed) return;
        model.parts.get(part).mesh().morph(weights, morphOffsets[part]);
        System.arraycopy(weights, 0, appliedWeights[part], 0, weights.length);
        int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, morphBuffers[part]);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, morphOffsets[part]);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
    }

    /** Drawn sections versus all sections, for status messages. */
    int[] sectionCounts() {
        int drawn = 0, total = 0;
        for (Material[] list : sectionMaterials) for (Material material : list) { total++; if (material.base() != 0) drawn++; }
        return new int[] {drawn, total};
    }

    @Override
    public void close() {
        for (int texture : textures.values()) if (texture != 0) GlStateManager._deleteTexture(texture);
        textures.clear();
        for (int texture : new int[] {flatNormal, plainSurface, matte, black}) if (texture != 0) GlStateManager._deleteTexture(texture);
        flatNormal = plainSurface = matte = black = 0;
        for (int i = 0; i < arrays.length; i++) {
            if (arrays[i] != 0) GL30.glDeleteVertexArrays(arrays[i]);
            if (vertexBuffers[i] != 0) GL15.glDeleteBuffers(vertexBuffers[i]);
            if (indexBuffers[i] != 0) GL15.glDeleteBuffers(indexBuffers[i]);
            if (morphBuffers[i] != 0) GL15.glDeleteBuffers(morphBuffers[i]);
            arrays[i] = vertexBuffers[i] = indexBuffers[i] = morphBuffers[i] = 0;
        }
        if (program != 0) GL20.glDeleteProgram(program);
    }

    /** A slot's textures; a slot without a base texture is not drawn, missing maps fall back to defaults. */
    private Material material(Path pack, String slot) throws IOException {
        Material known = materials.get(slot);
        if (known != null) return known;
        int hair = texture(pack, slot + ".hair.png", 0);
        int base = texture(pack, slot + ".png", hair != 0 ? HAIR_CUTOFF : CUTOFF);
        boolean overlay = base == 0 && (base = texture(pack, slot + ".overlay.png", 0)) != 0;
        int normal = texture(pack, slot + ".normal.png", 0), surface = texture(pack, slot + ".surface.png", 0);
        int emissive = texture(pack, slot + ".emissive.png", 0), scatter = texture(pack, slot + ".scatter.png", 0);
        Material material = new Material(new int[] {base, normal != 0 ? normal : flatNormal, surface != 0 ? surface : overlay ? matte : plainSurface,
                emissive != 0 ? emissive : black, hair != 0 ? hair : black, scatter != 0 ? scatter : black}, hair != 0, overlay);
        materials.put(slot, material);
        return material;
    }

    /** One RGBA texture of the pack, or 0 when absent. With a cutoff, mipmaps keep the cut-out coverage. */
    private int texture(Path pack, String name, float cutoff) throws IOException {
        Integer known = textures.get(name);
        if (known != null) return known;
        Path file = pack.resolve(name);
        if (!file.normalize().startsWith(pack.normalize()) || !Files.isRegularFile(file)) {
            textures.put(name, 0);
            return 0;
        }
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer encoded = BufferUtils.createByteBuffer(bytes.length).put(bytes).flip();
        int id;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1), height = stack.mallocInt(1), channels = stack.mallocInt(1);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, width, height, channels, 4);
            if (pixels == null) throw new IOException(file.getFileName() + ": " + STBImage.stbi_failure_reason());
            try {
                id = upload(pixels, width.get(0), height.get(0), cutoff);
            } finally {
                STBImage.stbi_image_free(pixels);
            }
        }
        textures.put(name, id);
        return id;
    }

    private static int solid(int r, int g, int b, int a) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return upload(stack.bytes((byte) r, (byte) g, (byte) b, (byte) a), 1, 1, 0);
        }
    }

    /**
     * A new mipmapped texture. With a cutoff and any cut-out texel, each box-filtered level scales its alpha so the share
     * of texels at or above the cutoff stays that of level 0: thin strands keep their density at a distance.
     */
    private static int upload(ByteBuffer pixels, int width, int height, float cutoff) {
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
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            int threshold = Math.round(cutoff * 255);
            double coverage = cutoff > 0 ? coverage(pixels, width * height, 1, threshold) : 1;
            if (coverage >= 1) GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
            else {
                ByteBuffer level = pixels;
                for (int l = 1; width > 1 || height > 1; l++) {
                    ByteBuffer next = halve(level, width, height);
                    if (level != pixels) MemoryUtil.memFree(level);
                    width = Math.max(1, width / 2);
                    height = Math.max(1, height / 2);
                    preserve(next, width * height, threshold, coverage);
                    GL11.glTexImage2D(GL11.GL_TEXTURE_2D, l, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, next);
                    level = next;
                }
                if (level != pixels) MemoryUtil.memFree(level);
            }
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

    /** Share of texels whose alpha times scale reaches threshold. */
    static double coverage(ByteBuffer rgba, int texels, double scale, int threshold) {
        int passing = 0;
        for (int i = 0; i < texels; i++) if ((rgba.get(4 * i + 3) & 0xff) * scale >= threshold) passing++;
        return (double) passing / texels;
    }

    /** Scales alpha so the share of texels reaching threshold is as close as possible to target. */
    static void preserve(ByteBuffer rgba, int texels, int threshold, double target) {
        double low = 0, high = 16;
        for (int k = 0; k < 24; k++) {
            double mid = (low + high) / 2;
            if (coverage(rgba, texels, mid, threshold) < target) low = mid; else high = mid;
        }
        for (int i = 0; i < texels; i++) {
            int alpha = rgba.get(4 * i + 3) & 0xff;
            rgba.put(4 * i + 3, (byte) Math.min(255, (int) Math.round(alpha * high)));
        }
    }

    /** The next box-filtered mip level (edge texels repeat for odd sizes); the caller frees it. */
    static ByteBuffer halve(ByteBuffer rgba, int width, int height) {
        int w = Math.max(1, width / 2), h = Math.max(1, height / 2);
        ByteBuffer out = MemoryUtil.memAlloc(w * h * 4);
        for (int y = 0; y < h; y++) {
            int y0 = Math.min(2 * y, height - 1), y1 = Math.min(2 * y + 1, height - 1);
            for (int x = 0; x < w; x++) {
                int x0 = Math.min(2 * x, width - 1), x1 = Math.min(2 * x + 1, width - 1);
                for (int c = 0; c < 4; c++) {
                    int sum = (rgba.get(4 * (y0 * width + x0) + c) & 0xff) + (rgba.get(4 * (y0 * width + x1) + c) & 0xff)
                            + (rgba.get(4 * (y1 * width + x0) + c) & 0xff) + (rgba.get(4 * (y1 * width + x1) + c) & 0xff);
                    out.put(4 * (y * w + x) + c, (byte) ((sum + 2) / 4));
                }
            }
        }
        return out;
    }

    private static int shader(int type, String name) throws IOException {
        String source;
        try (InputStream in = AvatarRenderer.class.getResourceAsStream("/assets/stellar_bridge/shaders/avatar/" + name)) {
            if (in == null) throw new IOException("Missing avatar shader " + name);
            source = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) != GL11.GL_TRUE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IOException("Avatar shader " + name + " failed: " + log);
        }
        return shader;
    }

    private static int link(int vertex, int fragment) throws IOException {
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        for (int i = 0; i < ATTRIBUTES.length; i++) GL20.glBindAttribLocation(program, i, ATTRIBUTES[i]);
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vertex);
        GL20.glDeleteShader(fragment);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) != GL11.GL_TRUE) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            throw new IOException("Avatar shader link failed: " + log);
        }
        return program;
    }
}
