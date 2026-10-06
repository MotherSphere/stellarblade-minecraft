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
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

/**
 * GPU skinning for an avatar pack on the render thread. Cached GL state goes through GlStateManager and
 * everything changed by a draw is restored, so Minecraft's own rendering continues unchanged.
 */
final class AvatarRenderer implements AutoCloseable {
    private static final String[] ATTRIBUTES = {"Position", "Normal", "UV0", "BoneIds", "BoneWeights", "MorphPosition", "MorphNormal"};
    private final AvatarModel model;
    private final int program, bones, viewProjection, lightUv, fogStart, fogEnd, fogColor;
    private final int[] arrays, vertexBuffers, indexBuffers, morphBuffers;
    private final float[][] appliedWeights; // Morph weights currently uploaded per part.
    private final FloatBuffer[] morphOffsets;
    private final int[][] sectionTextures;
    private final Map<String, Integer> textures = new HashMap<>();
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
        sectionTextures = new int[count][];
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
            GlStateManager._glUseProgram(program);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler0"), 0);
            GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler2"), 2);
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
                sectionTextures[p] = new int[mesh.sections.length];
                for (int s = 0; s < mesh.sections.length; s++) sectionTextures[p][s] = texture(pack, mesh.sections[s].material());
            }
            complete = true;
        } finally {
            GlStateManager._glBindVertexArray(previousArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
            GlStateManager._glUseProgram(previousProgram);
            if (!complete) close();
        }
    }

    /** Draws every part with the palettes filled by AvatarModel.palettes; positions are camera-relative blocks. */
    void draw(Matrix4f projectionView, int lightmap, int blockLight, int skyLight, float start, float end, float[] fog) {
        int program0 = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), array0 = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int active0 = GlStateManager._getActiveTexture();
        boolean depth0 = GL11.glIsEnabled(GL11.GL_DEPTH_TEST), cull0 = GL11.glIsEnabled(GL11.GL_CULL_FACE), blend0 = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean mask0 = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int function0 = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        int texture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GlStateManager._activeTexture(GL13.GL_TEXTURE2);
        int texture2 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GlStateManager._bindTexture(lightmap);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
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
            GL20.glVertexAttrib3f(5, 0, 0, 0);
            GL20.glVertexAttrib3f(6, 0, 0, 0);
            for (int p = 0; p < arrays.length; p++) {
                if (morphBuffers[p] != 0) uploadMorphs(p);
                GlStateManager._glBindVertexArray(arrays[p]);
                GL21.glUniformMatrix3x4fv(bones, false, palettes[p]);
                AvatarMesh.Section[] sections = model.parts.get(p).mesh().sections;
                for (int s = 0; s < sections.length; s++) {
                    if (sectionTextures[p][s] == 0) continue;
                    GlStateManager._bindTexture(sectionTextures[p][s]);
                    GL11.glDrawElements(GL11.GL_TRIANGLES, sections[s].indexCount(), GL11.GL_UNSIGNED_INT, 4L * sections[s].firstIndex());
                }
            }
        } finally {
            GlStateManager._glBindVertexArray(array0);
            GlStateManager._glUseProgram(program0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
            GlStateManager._bindTexture(texture0);
            GlStateManager._activeTexture(GL13.GL_TEXTURE2);
            GlStateManager._bindTexture(texture2);
            GlStateManager._activeTexture(active0);
            if (depth0) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
            if (cull0) GlStateManager._enableCull(); else GlStateManager._disableCull();
            if (blend0) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
            GlStateManager._depthFunc(function0);
            GlStateManager._depthMask(mask0);
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
        for (int[] list : sectionTextures) for (int texture : list) { total++; if (texture != 0) drawn++; }
        return new int[] {drawn, total};
    }

    @Override
    public void close() {
        for (int texture : textures.values()) if (texture != 0) GlStateManager._deleteTexture(texture);
        textures.clear();
        for (int i = 0; i < arrays.length; i++) {
            if (arrays[i] != 0) GL30.glDeleteVertexArrays(arrays[i]);
            if (vertexBuffers[i] != 0) GL15.glDeleteBuffers(vertexBuffers[i]);
            if (indexBuffers[i] != 0) GL15.glDeleteBuffers(indexBuffers[i]);
            if (morphBuffers[i] != 0) GL15.glDeleteBuffers(morphBuffers[i]);
            arrays[i] = vertexBuffers[i] = indexBuffers[i] = morphBuffers[i] = 0;
        }
        if (program != 0) GL20.glDeleteProgram(program);
    }

    /** A baked RGBA base texture named after the material slot; a slot without one is not drawn. */
    private int texture(Path pack, String material) throws IOException {
        Integer known = textures.get(material);
        if (known != null) return known;
        Path file = pack.resolve(material + ".png");
        if (!file.normalize().startsWith(pack.normalize()) || !Files.isRegularFile(file)) {
            textures.put(material, 0);
            return 0;
        }
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer encoded = BufferUtils.createByteBuffer(bytes.length).put(bytes).flip();
        int id;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1), height = stack.mallocInt(1), channels = stack.mallocInt(1);
            ByteBuffer pixels = STBImage.stbi_load_from_memory(encoded, width, height, channels, 4);
            if (pixels == null) throw new IOException(file.getFileName() + ": " + STBImage.stbi_failure_reason());
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            int alignment = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT), rowLength = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
            int skipRows = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS), skipPixels = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
            int unpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
            id = GlStateManager._genTexture();
            try {
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
                GlStateManager._bindTexture(id);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width.get(0), height.get(0), 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                GL30.glGenerateMipmap(GL11.GL_TEXTURE_2D);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR_MIPMAP_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL11.GL_REPEAT);
            } finally {
                STBImage.stbi_image_free(pixels);
                GlStateManager._bindTexture(previous);
                GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, alignment);
                GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, rowLength);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, skipRows);
                GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, skipPixels);
                GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, unpack);
            }
        }
        textures.put(material, id);
        return id;
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
