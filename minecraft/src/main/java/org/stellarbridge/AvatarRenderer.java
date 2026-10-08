package org.stellarbridge;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
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
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.OverlayTexture;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
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
 * (subsurface color). Hair strands are cut out like other slots, then their soft edges below the cutoff blend over
 * every opaque section without writing depth. A translucent slot ships {@code <slot>.overlay.png} instead: premultiplied
 * color drawn after every opaque section without writing depth. A {@code <slot>.glow.png} slot is such an overlay drawn
 * only while the game raises the slot's streamed Opacity, its emissive map tinted by Emissive_Color times
 * Emissive_Intensity (and the _2 pair).
 * While a shader pack is in use, drawEntity draws the same buffers through Minecraft's entity programs instead.
 * Without a pack, the sun lights her through a shadow map of her own (castShadow) and her shadow darkens the block faces
 * below her (drawCast).
 */
final class AvatarRenderer implements AutoCloseable {
    private static final String[] ATTRIBUTES = {"Position", "Normal", "UV0", "BoneIds", "BoneWeights", "MorphPosition", "MorphNormal"};
    // Skinned vertices captured by transform feedback for drawEntity: camera-relative position, unit normal, tangent.
    private static final String[] SKINNED = {"relative", "unitNormal", "tangent"};
    private static final int SKINNED_BYTES = 40;
    private static final float[] WHITE = {1, 1, 1, 1}, FULL_BRIGHT = {240, 240, 0, 1};
    private final AvatarModel model;
    // Texture units: base 0, normal 1, Minecraft's lightmap 2, surface 3, emissive 4, hair 5, scatter 6.
    private static final int[] UNITS = {0, 1, 3, 4, 5, 6};
    private static final float CUTOFF = 0.5f;
    /** GL textures of one slot in UNITS order (base, normal, surface, emissive, hair, scatter); base 0: not drawn. */
    private record Material(String slot, int[] maps, boolean hair, boolean overlay, boolean glow) {
        int base() { return maps[0]; }
    }
    private final int program, bones, viewProjection, lightUv, fogStart, fogEnd, fogColor, skyColor, hairShading, cutoff, pass,
            opacity, emissiveTint, hurtTint, skinning, skinningBones, sunDirection, sunWeight, shadowMatrix, shadowDepth;
    // EVE's shadow map (depth only, from the sun) and the program darkening the block faces her shadow falls on.
    static final int SHADOW_SIZE = 1024;
    private int shadowFramebuffer, shadowTexture, cast, castArray, castBuffer;
    private final int castViewProjection, castShadowMatrix, castShadowDepth, castDirection, castFogStart, castFogEnd;
    private final Matrix4f light = new Matrix4f();
    private float lightDepth;
    private Map<String, float[]> parameters = Map.of();
    private boolean[] shown; // Per streamed component, whether its part is drawn; null draws every part (replays).
    private boolean hurt;
    private AvatarTrail trail; // The weapon's swing trail, when the pack describes one.
    private AvatarSprites sprites; // The game's effects as sprites, when the pack has their textures.
    private final Vector3d hilt = new Vector3d(), tip = new Vector3d();
    private final int[] arrays, vertexBuffers, indexBuffers, morphBuffers;
    private final int[] skinned, entityArrays; // Per part for drawEntity: transform feedback buffer, vertex array.
    private final float[][] appliedWeights; // Morph weights currently uploaded per part.
    private final FloatBuffer[] morphOffsets;
    // Per part with cloth: the cloth positions currently uploaded as morph deltas (null: bones), and normal scratch.
    private final float[][] appliedCloth, clothNormals;
    private final boolean[] appliedClothed; // The part's deltas hold its cloth.
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
        skinned = new int[count];
        entityArrays = new int[count];
        appliedWeights = new float[count][];
        morphOffsets = new FloatBuffer[count];
        appliedCloth = new float[count][];
        clothNormals = new float[count][];
        appliedClothed = new boolean[count];
        sectionMaterials = new Material[count][];
        palettes = new FloatBuffer[count];
        program = link(shader(GL20.GL_VERTEX_SHADER, "avatar.vsh"), shader(GL20.GL_FRAGMENT_SHADER, "avatar.fsh"), ATTRIBUTES);
        boolean complete = false;
        try {
            bones = GL20.glGetUniformLocation(program, "Bones");
            viewProjection = GL20.glGetUniformLocation(program, "ViewProjection");
            lightUv = GL20.glGetUniformLocation(program, "LightUv");
            fogStart = GL20.glGetUniformLocation(program, "FogStart");
            fogEnd = GL20.glGetUniformLocation(program, "FogEnd");
            fogColor = GL20.glGetUniformLocation(program, "FogColor");
            skyColor = GL20.glGetUniformLocation(program, "SkyColor");
            pass = GL20.glGetUniformLocation(program, "Pass");
            opacity = GL20.glGetUniformLocation(program, "Opacity");
            emissiveTint = GL20.glGetUniformLocation(program, "EmissiveTint");
            hurtTint = GL20.glGetUniformLocation(program, "Hurt");
            hairShading = GL20.glGetUniformLocation(program, "Hair");
            cutoff = GL20.glGetUniformLocation(program, "AlphaCutoff");
            skinning = link(shader(GL20.GL_VERTEX_SHADER, "avatar.vsh"), shader(GL20.GL_FRAGMENT_SHADER, "avatar.fsh"), ATTRIBUTES, SKINNED);
            skinningBones = GL20.glGetUniformLocation(skinning, "Bones");
            sunDirection = GL20.glGetUniformLocation(program, "SunDirection");
            sunWeight = GL20.glGetUniformLocation(program, "SunWeight");
            shadowMatrix = GL20.glGetUniformLocation(program, "ShadowMatrix");
            shadowDepth = GL20.glGetUniformLocation(program, "ShadowDepth");
            GlStateManager._glUseProgram(program);
            for (int unit = 0; unit <= 7; unit++) GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler" + unit), unit);
            cast = link(shader(GL20.GL_VERTEX_SHADER, "cast.vsh"), shader(GL20.GL_FRAGMENT_SHADER, "cast.fsh"), new String[] {"Position", "Normal", "Brightness"});
            castViewProjection = GL20.glGetUniformLocation(cast, "ViewProjection");
            castShadowMatrix = GL20.glGetUniformLocation(cast, "ShadowMatrix");
            castShadowDepth = GL20.glGetUniformLocation(cast, "ShadowDepth");
            castDirection = GL20.glGetUniformLocation(cast, "ShadowDirection");
            castFogStart = GL20.glGetUniformLocation(cast, "FogStart");
            castFogEnd = GL20.glGetUniformLocation(cast, "FogEnd");
            GlStateManager._glUseProgram(cast);
            GL20.glUniform1i(GL20.glGetUniformLocation(cast, "Sampler7"), 7);
            castArray = GL30.glGenVertexArrays();
            GlStateManager._glBindVertexArray(castArray);
            castBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, castBuffer);
            GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, 28, 0);
            GL20.glVertexAttribPointer(1, 3, GL11.GL_FLOAT, false, 28, 12);
            GL20.glVertexAttribPointer(2, 1, GL11.GL_FLOAT, false, 28, 24);
            for (int a = 0; a < 3; a++) GL20.glEnableVertexAttribArray(a);
            int texture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            shadowTexture = GlStateManager._genTexture();
            GlStateManager._bindTexture(shadowTexture);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL14.GL_DEPTH_COMPONENT24, SHADOW_SIZE, SHADOW_SIZE, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._bindTexture(texture0);
            int framebuffer0 = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            shadowFramebuffer = GL30.glGenFramebuffers();
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, shadowFramebuffer);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL11.GL_TEXTURE_2D, shadowTexture, 0);
            GL11.glDrawBuffer(GL11.GL_NONE);
            GL11.glReadBuffer(GL11.GL_NONE);
            boolean framed = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE;
            GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer0);
            if (!framed) throw new IOException("EVE's shadow map framebuffer is incomplete");
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
                // Summed morph deltas per vertex; meshes without morphs read the zero generic attribute instead.
                if (mesh.morphs.length > 0) morphBuffer(p);
                sectionMaterials[p] = new Material[mesh.sections.length];
                for (int s = 0; s < mesh.sections.length; s++) sectionMaterials[p][s] = material(pack, mesh.sections[s].material());
                skinned[p] = GL15.glGenBuffers();
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, skinned[p]);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) SKINNED_BYTES * mesh.vertexCount, GL15.GL_DYNAMIC_COPY);
                entityArrays[p] = GL30.glGenVertexArrays();
                GlStateManager._glBindVertexArray(entityArrays[p]); // Its inputs follow the program drawing it (ShaderPacks.inputs).
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, indexBuffers[p]);
            }
            trail = AvatarTrail.load(model, pack, name -> texture(pack, name, 0), name -> texture(pack, name, 0, AvatarTrail::glow));
            sprites = AvatarSprites.load(name -> texture(pack, name, 0));
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
    void draw(Matrix4f projectionView, int lightmap, int blockLight, int skyLight, float start, float end, float[] fog, float[] sky,
              AvatarShadows.Light key) {
        restoring(() -> {
            GlStateManager._activeTexture(GL13.GL_TEXTURE7);
            GlStateManager._bindTexture(shadowTexture);
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
            GL20.glUniform1f(hurtTint, hurt ? 1 : 0);
            GL20.glUniform3f(sunDirection, key.direction().x, key.direction().y, key.direction().z);
            GL20.glUniform1f(sunWeight, AvatarShadows.KEY * key.strength());
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GL20.glUniformMatrix4fv(shadowMatrix, false, light.get(stack.mallocFloat(16)));
            }
            GL20.glUniform1f(shadowDepth, lightDepth);
            uploadMorphs();
            // Opaque and cut-out sections first; then, blended without writing depth, translucent overlays and the soft
            // edges of hair strands that the cut-out dropped, the hair last so that the eyes' overlays never show through it.
            for (int stage : new int[] {0, 2, 1}) {
                if (stage == 2) {
                    GlStateManager._enableBlend();
                    GlStateManager._blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
                    GlStateManager._depthMask(false);
                }
                GL20.glUniform1i(pass, stage);
                for (int p = 0; p < arrays.length; p++) {
                    if (!drawn(p)) continue;
                    GlStateManager._glBindVertexArray(arrays[p]);
                    GL21.glUniformMatrix3x4fv(bones, false, palettes[p]);
                    AvatarMesh.Section[] sections = model.parts.get(p).mesh().sections;
                    for (int s = 0; s < sections.length; s++) {
                        Material material = sectionMaterials[p][s];
                        if (material.base() == 0 || (stage == 1 ? !material.hair() : material.overlay() != (stage == 2))) continue;
                        float[] tint = {1, 1, 1};
                        float shown = material.glow() ? glow(material.slot(), tint) : 1;
                        if (shown <= 0.001f) continue;
                        GL20.glUniform1f(opacity, Math.min(shown, 1));
                        GL20.glUniform3f(emissiveTint, tint[0], tint[1], tint[2]);
                        for (int k = 0; k < UNITS.length; k++) {
                            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + UNITS[k]);
                            GlStateManager._bindTexture(material.maps()[k]);
                        }
                        GL20.glUniform1i(hairShading, material.hair() ? 1 : 0);
                        GL20.glUniform1f(cutoff, material.overlay() ? 0 : CUTOFF);
                        GL11.glDrawElements(GL11.GL_TRIANGLES, sections[s].indexCount(), GL11.GL_UNSIGNED_INT, 4L * sections[s].firstIndex());
                    }
                }
            }
            if (trail != null) trail.draw(projectionView, start, end, fog);
            if (sprites != null) sprites.draw(projectionView, start, end, fog);
        });
    }

    /**
     * Renders EVE's opaque and cut-out sections into her shadow map from the sun: lightViewProjection maps camera-relative
     * blocks to its clip space, depth blocks deep. After palettes; read by the next draw and drawCast.
     */
    void castShadow(Matrix4f lightViewProjection, float depth) {
        light.set(lightViewProjection);
        lightDepth = depth;
        restoring(() -> {
            int draw0 = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), read0 = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
            int[] viewport0 = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport0);
            boolean offset0 = GL11.glIsEnabled(GL11.GL_POLYGON_OFFSET_FILL);
            float factor0 = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_FACTOR), units0 = GL11.glGetFloat(GL11.GL_POLYGON_OFFSET_UNITS);
            try {
                GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, shadowFramebuffer);
                GlStateManager._viewport(0, 0, SHADOW_SIZE, SHADOW_SIZE);
                GlStateManager._enableDepthTest();
                GlStateManager._depthFunc(GL11.GL_LEQUAL);
                GlStateManager._depthMask(true);
                GlStateManager._disableCull();
                GlStateManager._disableBlend();
                GlStateManager._clearDepth(1);
                GlStateManager._clear(GL11.GL_DEPTH_BUFFER_BIT, false);
                GlStateManager._enablePolygonOffset();
                GlStateManager._polygonOffset(1.5f, 4);
                GlStateManager._glUseProgram(program);
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    GL20.glUniformMatrix4fv(viewProjection, false, lightViewProjection.get(stack.mallocFloat(16)));
                }
                GL20.glUniform1i(pass, 3);
                uploadMorphs();
                GlStateManager._activeTexture(GL13.GL_TEXTURE0);
                // ponytail: the shadow pass skins her meshes a second time; skin() once by transform feedback and draw both
                // passes from skinned[] if a profile shows it.
                for (int p = 0; p < arrays.length; p++) {
                    if (!drawn(p)) continue;
                    GlStateManager._glBindVertexArray(arrays[p]);
                    GL21.glUniformMatrix3x4fv(bones, false, palettes[p]);
                    AvatarMesh.Section[] sections = model.parts.get(p).mesh().sections;
                    for (int s = 0; s < sections.length; s++) {
                        Material material = sectionMaterials[p][s];
                        if (material.base() == 0 || material.overlay()) continue;
                        GlStateManager._bindTexture(material.base());
                        GL20.glUniform1f(cutoff, CUTOFF);
                        GL11.glDrawElements(GL11.GL_TRIANGLES, sections[s].indexCount(), GL11.GL_UNSIGNED_INT, 4L * sections[s].firstIndex());
                    }
                }
            } finally {
                GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw0);
                GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read0);
                GlStateManager._viewport(viewport0[0], viewport0[1], viewport0[2], viewport0[3]);
                GlStateManager._polygonOffset(factor0, units0);
                if (offset0) GlStateManager._enablePolygonOffset(); else GlStateManager._disablePolygonOffset();
            }
        });
    }

    /**
     * Darkens the block faces EVE's shadow falls on (AvatarShadows.faces: camera-relative position, normal, brightness per
     * vertex, two triangles a face), blended over them as Minecraft's entity shadow; toward is the shadow's light. After
     * castShadow, before EVE herself is drawn.
     */
    void drawCast(Matrix4f projectionView, FloatBuffer faces, int vertices, Vector3f toward, float start, float end) {
        if (vertices == 0) return;
        restoring(() -> {
            GlStateManager._activeTexture(GL13.GL_TEXTURE7);
            GlStateManager._bindTexture(shadowTexture);
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._depthMask(false);
            GlStateManager._enableCull();
            GlStateManager._enableBlend();
            GlStateManager._blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GlStateManager._glUseProgram(cast);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                GL20.glUniformMatrix4fv(castViewProjection, false, projectionView.get(stack.mallocFloat(16)));
                GL20.glUniformMatrix4fv(castShadowMatrix, false, light.get(stack.mallocFloat(16)));
            }
            GL20.glUniform1f(castShadowDepth, lightDepth);
            GL20.glUniform3f(castDirection, toward.x, toward.y, toward.z);
            GL20.glUniform1f(castFogStart, start);
            GL20.glUniform1f(castFogEnd, end);
            GlStateManager._glBindVertexArray(castArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, castBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, faces, GL15.GL_STREAM_DRAW);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertices);
        });
    }

    /** After palettes with the same placement: the drawn parts' bone positions' extent, camera-relative blocks. */
    void bounds(AvatarRecording.Pose pose, Matrix4d placement, Vector3d low, Vector3d high) {
        low.set(Double.MAX_VALUE);
        high.set(-Double.MAX_VALUE);
        Vector3d origin = new Vector3d(), at = new Vector3d();
        for (int p = 0; p < arrays.length; p++) {
            if (!drawn(p)) continue;
            AvatarModel.Part part = model.parts.get(p);
            if (part.leaderBones() != null) continue; // A follower's bones are its leader's.
            for (int k = 0; k < part.mesh().bones.length; k++) {
                model.point(pose, placement, p, k, origin, at);
                low.min(at);
                high.max(at);
            }
        }
    }

    /**
     * Draws through Minecraft's entity programs, which a shader pack replaces with its own (see ShaderPacks): the parts
     * skinned on the GPU by transform feedback, opaque and cut-out sections with the cut-out entity program, lit by the
     * lightmap at her feet and tinted by the hurt overlay as entities are; overlays and glows, the trail and the effect
     * sprites additively with the eyes program, as Minecraft draws glowing eyes. Maps beyond the base texture are unused.
     * View is the world's camera rotation and projection its projection; positions stay camera-relative.
     */
    private static boolean missingWarned; // A missing entity program was logged once.
    void drawEntity(Matrix4f view, Matrix4f projection, int lightmap, int overlay, int blockLight, int skyLight,
                    ShaderProgram cutout, ShaderProgram translucent, ShaderProgram eyes) {
        drawEntity(view, projection, lightmap, overlay, blockLight, skyLight, cutout, translucent, eyes, 3);
    }

    /**
     * Into a shader pack's shadow map, during Iris's shadow pass: only the opaque and cut-out sections, through the program
     * Iris maps the cut-out entity program to there. Overlays, glows, the trail and the sprites cast nothing.
     */
    void drawShadow(Matrix4f view, Matrix4f projection, int lightmap, int overlay, ShaderProgram shadow) {
        drawEntity(view, projection, lightmap, overlay, 0, 15, shadow, null, null, 1);
    }

    private void drawEntity(Matrix4f view, Matrix4f projection, int lightmap, int overlay, int blockLight, int skyLight,
                            ShaderProgram cutout, ShaderProgram translucent, ShaderProgram eyes, int passes) {
        restoring(() -> {
            uploadMorphs();
            skin();
            RenderSystem.setShaderTexture(1, overlay);
            RenderSystem.setShaderTexture(2, lightmap);
            GlStateManager._enableDepthTest();
            GlStateManager._depthFunc(GL11.GL_LEQUAL);
            GlStateManager._disableCull();
            float[] hurtUv = {OverlayTexture.getU(0), OverlayTexture.getV(hurt), 0, 1}, light = {16 * blockLight, 16 * skyLight, 0, 1};
            // Passes: opaque and cut-out sections; overlays blended over them as our own path blends them (premultiplied);
            // glows added as Minecraft adds glowing eyes.
            String[] names = {"cut-out entity", "translucent entity", "eyes"};
            for (int pass = 0; pass < passes; pass++) {
                ShaderProgram shader = pass == 0 ? cutout : pass == 1 ? translucent : eyes;
                if (shader == null) {
                    if (!missingWarned) org.slf4j.LoggerFactory.getLogger("stellar_bridge").warn("EVE avatar: Minecraft's {} program is unavailable; part of her is not drawn", names[pass]);
                    missingWarned = true;
                    continue;
                }
                if (pass > 0) {
                    GlStateManager._enableBlend();
                    GlStateManager._blendFuncSeparate(GL11.GL_ONE, pass == 1 ? GL11.GL_ONE_MINUS_SRC_ALPHA : GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
                    GlStateManager._depthMask(false);
                } else {
                    GlStateManager._disableBlend();
                    GlStateManager._depthMask(true);
                }
                for (int p = 0; p < arrays.length; p++) {
                    if (!drawn(p)) continue;
                    GlStateManager._glBindVertexArray(entityArrays[p]);
                    AvatarMesh.Section[] sections = model.parts.get(p).mesh().sections;
                    for (int s = 0; s < sections.length; s++) {
                        Material material = sectionMaterials[p][s];
                        int wanted = !material.overlay() ? 0 : material.glow() ? 2 : 1;
                        if (material.base() == 0 || wanted != pass) continue;
                        float[] tint = {1, 1, 1};
                        float shown = material.glow() ? Math.min(glow(material.slot(), tint), 1) : 1;
                        if (shown <= 0.001f) continue;
                        // ponytail: the entity programs keep texels down to alpha 0.1, not our 0.5 (0.25 for hair), so faint
                        // card fringes show; binary-alpha copies of the cut-out textures would match, at their memory cost.
                        float[] color = material.glow() ? new float[] {tint[0] * shown, tint[1] * shown, tint[2] * shown, 1} : WHITE;
                        ShaderPacks.inputs(shader, feeds(p), Map.of("Color", color, "UV1", hurtUv, "UV2", pass == 2 ? FULL_BRIGHT : light));
                        ShaderPacks.bind(shader, material.base(), view, projection);
                        GL11.glDrawElements(GL11.GL_TRIANGLES, sections[s].indexCount(), GL11.GL_UNSIGNED_INT, 4L * sections[s].firstIndex());
                    }
                }
                shader.unbind();
            }
            if (eyes != null && trail != null) trail.drawEntity(eyes, view, projection);
            if (eyes != null && sprites != null) sprites.drawEntity(eyes, view, projection);
        });
    }

    /** Where drawEntity's programs read a part: skinned positions, normals and tangents, the pack's UVs. */
    private Map<String, ShaderPacks.Feed> feeds(int part) {
        var uv = new ShaderPacks.Feed(vertexBuffers[part], 2, AvatarMesh.VERTEX_BYTES, 24);
        return Map.of("Position", new ShaderPacks.Feed(skinned[part], 3, SKINNED_BYTES, 0), "Normal", new ShaderPacks.Feed(skinned[part], 3, SKINNED_BYTES, 12),
                "at_tangent", new ShaderPacks.Feed(skinned[part], 4, SKINNED_BYTES, 24), "UV0", uv, "mc_midTexCoord", uv);
    }

    /** Skins every part into its transform feedback buffer with the palettes; nothing is rasterized. */
    private void skin() {
        int generic0 = GL11.glGetInteger(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING), indexed0 = GL30.glGetIntegeri(GL30.GL_TRANSFORM_FEEDBACK_BUFFER_BINDING, 0);
        int program0 = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM); // Minecraft's and Iris's program caches stay truthful.
        GlStateManager._glUseProgram(skinning);
        GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
        try {
            for (int p = 0; p < arrays.length; p++) {
                if (!drawn(p)) continue;
                GlStateManager._glBindVertexArray(arrays[p]);
                GL21.glUniformMatrix3x4fv(skinningBones, false, palettes[p]);
                GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, skinned[p]);
                GL30.glBeginTransformFeedback(GL11.GL_POINTS);
                GL11.glDrawArrays(GL11.GL_POINTS, 0, model.parts.get(p).mesh().vertexCount);
                GL30.glEndTransformFeedback();
            }
        } finally {
            GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
            GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, indexed0);
            GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, generic0);
            GlStateManager._glUseProgram(program0);
        }
    }

    /** Runs a draw with the GL and shader-texture state it may change saved first and restored after. */
    private static void restoring(Runnable draw) {
        int program0 = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM), array0 = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int buffer0 = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING), active0 = GlStateManager._getActiveTexture();
        int[] bound0 = new int[8], shaderTextures0 = {RenderSystem.getShaderTexture(0), RenderSystem.getShaderTexture(1), RenderSystem.getShaderTexture(2)};
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
            draw.run();
        } finally {
            GlStateManager._glBindVertexArray(array0);
            GlStateManager._glUseProgram(program0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer0);
            for (int unit = 0; unit < bound0.length; unit++) {
                GlStateManager._activeTexture(GL13.GL_TEXTURE0 + unit);
                GlStateManager._bindTexture(bound0[unit]);
            }
            GlStateManager._activeTexture(active0);
            for (int k = 0; k < shaderTextures0.length; k++) RenderSystem.setShaderTexture(k, shaderTextures0[k]);
            if (depth0) GlStateManager._enableDepthTest(); else GlStateManager._disableDepthTest();
            if (cull0) GlStateManager._enableCull(); else GlStateManager._disableCull();
            if (blend0) GlStateManager._enableBlend(); else GlStateManager._disableBlend();
            GlStateManager._depthFunc(function0);
            GlStateManager._depthMask(mask0);
            GlStateManager._blendFuncSeparate(blendFunction0[0], blendFunction0[1], blendFunction0[2], blendFunction0[3]);
        }
    }

    /** Which streamed components are drawn, by table index, read at each draw; null draws every part. */
    void shown(boolean[] components) { shown = components; }

    /** Whether EVE flashes red as a hurt Minecraft entity does, from the next draw. */
    void hurt(boolean flashing) { hurt = flashing; }

    /** Records the weapon trail's sockets for the displayed pose, after palettes with the same placement. */
    void sampleTrail(AvatarRecording.Pose pose, Matrix4d placement, double cameraX, double cameraY, double cameraZ, double seconds) {
        if (trail != null) trail.sample(model, pose, placement, cameraX, cameraY, cameraZ, seconds);
    }

    /**
     * The game's active effects for this frame, by asset name at absolute Minecraft positions, or null when effects are
     * not streamed: the weapon trail follows the game's Cascade trail, sprites advance to the time in seconds. False
     * when the pack has no effect sprites.
     */
    boolean effects(Map<String, Vector3d> active, Vector3d camera, Quaternionf rotation, double seconds) {
        if (trail != null) {
            if (active == null) trail.followSwings();
            else {
                AvatarTrail.Style playing = null;
                for (String asset : active.keySet()) {
                    AvatarTrail.Style style = AvatarTrail.Style.of(asset);
                    if (style != null) playing = style;
                }
                trail.follow(playing);
            }
        }
        if (sprites == null) return false;
        boolean blade = trail != null && trail.blade(hilt, tip);
        sprites.update(active == null ? Map.of() : active, blade ? hilt : null, blade ? tip : null, camera, rotation, seconds);
        return true;
    }

    /** The weapon trail's newest hilt and tip sockets; false without a trail sample. */
    boolean blade(Vector3d hiltOut, Vector3d tipOut) {
        return trail != null && trail.blade(hiltOut, tipOut);
    }

    /** The weapon trail's newest strength, 0 to 1 (0 without a trail). */
    double trailStrength() {
        return trail == null ? 0 : trail.strength();
    }

    /** The newest streamed material parameters by "slot/name" (empty without a stream). */
    void parameters(Map<String, float[]> values) {
        parameters = values;
    }
    /** A glow slot's streamed Opacity; tint becomes Emissive_Color times Emissive_Intensity plus the _2 pair. */
    private float glow(String slot, float[] tint) {
        for (int k = 0; k < 3; k++)
            tint[k] = parameter(slot, "Emissive_Color", k) * parameter(slot, "Emissive_Intensity", 0)
                    + parameter(slot, "Emissive_Color_2", k) * parameter(slot, "Emissive_Intensity_2", 0);
        return parameter(slot, "Opacity", 0);
    }
    private float parameter(String slot, String name, int channel) {
        float[] value = parameters.get(slot + "/" + name);
        return value == null ? 0 : value[channel];
    }

    /**
     * Current morph deltas for every part; parts without morphs read the zero generic attributes 5 and 6. A part with cloth
     * (AvatarModel.cloth) carries it as deltas instead, with its normals recomputed.
     */
    private void uploadMorphs() {
        GL20.glVertexAttrib3f(5, 0, 0, 0);
        GL20.glVertexAttrib3f(6, 0, 0, 0);
        for (int p = 0; p < arrays.length; p++) if ((morphBuffers[p] != 0 || model.cloth[p] != null) && drawn(p)) uploadMorphs(p);
    }
    /** A part's morph delta buffer on attributes 5 and 6 of its vertex array, bound meanwhile. */
    private void morphBuffer(int part) {
        int array0 = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), buffer0 = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        AvatarMesh mesh = model.parts.get(part).mesh();
        morphOffsets[part] = BufferUtils.createFloatBuffer(6 * mesh.vertexCount);
        appliedWeights[part] = new float[mesh.morphs.length];
        morphBuffers[part] = GL15.glGenBuffers();
        GlStateManager._glBindVertexArray(arrays[part]);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, morphBuffers[part]);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, morphOffsets[part], GL15.GL_DYNAMIC_DRAW);
        GL20.glVertexAttribPointer(5, 3, GL11.GL_FLOAT, false, 24, 0);
        GL20.glVertexAttribPointer(6, 3, GL11.GL_FLOAT, false, 24, 12);
        GL20.glEnableVertexAttribArray(5);
        GL20.glEnableVertexAttribArray(6);
        GlStateManager._glBindVertexArray(array0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer0);
    }
    /** Whether part p is drawn this frame (every part without a shown list, as in replays). */
    private boolean drawn(int p) { return shown == null || shown[model.parts.get(p).component()]; }
    private void uploadMorphs(int part) {
        float[] weights = model.morphWeights[part], cloth = model.cloth[part];
        AvatarMesh mesh = model.parts.get(part).mesh();
        if (cloth != null) { // Each new cloth frame once, however many passes draw it.
            if (morphBuffers[part] == 0) morphBuffer(part);
            if (appliedCloth[part] == null) {
                appliedCloth[part] = new float[cloth.length];
                clothNormals[part] = new float[cloth.length];
            } else if (appliedClothed[part] && java.util.Arrays.equals(cloth, appliedCloth[part])) return;
            mesh.cloth(cloth, clothNormals[part], morphOffsets[part]);
            System.arraycopy(cloth, 0, appliedCloth[part], 0, cloth.length);
            appliedClothed[part] = true;
        } else {
            boolean changed = appliedClothed[part]; // Back to bones: the morphs (zeros without any) replace the cloth once.
            for (int m = 0; m < weights.length && !changed; m++) changed = Math.abs(weights[m] - appliedWeights[part][m]) > 1e-4f;
            if (!changed) return;
            mesh.morph(weights, morphOffsets[part]);
            System.arraycopy(weights, 0, appliedWeights[part], 0, weights.length);
            appliedClothed[part] = false;
        }
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
            if (skinned[i] != 0) GL15.glDeleteBuffers(skinned[i]);
            if (entityArrays[i] != 0) GL30.glDeleteVertexArrays(entityArrays[i]);
            arrays[i] = vertexBuffers[i] = indexBuffers[i] = morphBuffers[i] = skinned[i] = entityArrays[i] = 0;
        }
        if (program != 0) GL20.glDeleteProgram(program);
        if (cast != 0) GL20.glDeleteProgram(cast);
        if (castArray != 0) GL30.glDeleteVertexArrays(castArray);
        if (castBuffer != 0) GL15.glDeleteBuffers(castBuffer);
        if (shadowFramebuffer != 0) GL30.glDeleteFramebuffers(shadowFramebuffer);
        if (shadowTexture != 0) GlStateManager._deleteTexture(shadowTexture);
        cast = castArray = castBuffer = shadowFramebuffer = shadowTexture = 0;
        if (skinning != 0) GL20.glDeleteProgram(skinning);
        if (trail != null) trail.close();
        trail = null;
        if (sprites != null) sprites.close();
        sprites = null;
    }

    /** A slot's textures; a slot without a base texture is not drawn, missing maps fall back to defaults. */
    private Material material(Path pack, String slot) throws IOException {
        Material known = materials.get(slot);
        if (known != null) return known;
        int hair = texture(pack, slot + ".hair.png", 0);
        int base = texture(pack, slot + ".png", CUTOFF);
        boolean overlay = base == 0 && (base = texture(pack, slot + ".overlay.png", 0)) != 0;
        boolean glow = base == 0 && (base = texture(pack, slot + ".glow.png", 0)) != 0;
        int normal = texture(pack, slot + ".normal.png", 0), surface = texture(pack, slot + ".surface.png", 0);
        int emissive = texture(pack, slot + ".emissive.png", 0), scatter = texture(pack, slot + ".scatter.png", 0);
        Material material = new Material(slot, new int[] {base, normal != 0 ? normal : flatNormal, surface != 0 ? surface : overlay ? matte : plainSurface,
                emissive != 0 ? emissive : black, hair != 0 ? hair : black, scatter != 0 ? scatter : black}, hair != 0, overlay || glow, glow);
        materials.put(slot, material);
        return material;
    }

    /** One RGBA texture of the pack, or 0 when absent. With a cutoff, mipmaps keep the cut-out coverage. */
    private int texture(Path pack, String name, float cutoff) throws IOException {
        return texture(pack, name, cutoff, null);
    }
    /** The same, its texels first changed in place by convert when given (a separate texture). */
    private int texture(Path pack, String name, float cutoff, java.util.function.Consumer<ByteBuffer> convert) throws IOException {
        String key = convert == null ? name : name + "#converted";
        Integer known = textures.get(key);
        if (known != null) return known;
        Path file = pack.resolve(name);
        if (!file.normalize().startsWith(pack.normalize()) || !Files.isRegularFile(file)) {
            textures.put(key, 0);
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
                if (convert != null) convert.accept(pixels);
                id = upload(pixels, width.get(0), height.get(0), cutoff);
            } finally {
                STBImage.stbi_image_free(pixels);
            }
        }
        textures.put(key, id);
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

    static int shader(int type, String name) throws IOException {
        String source = source(name);
        if (source.contains("#include shadow.glsl")) source = source.replace("#include shadow.glsl", source("shadow.glsl"));
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

    private static String source(String name) throws IOException {
        try (InputStream in = AvatarRenderer.class.getResourceAsStream("/assets/stellar_bridge/shaders/avatar/" + name)) {
            if (in == null) throw new IOException("Missing avatar shader " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** A program with attributes at their indices; varyings, when given, are captured interleaved by transform feedback. */
    static int link(int vertex, int fragment, String[] attributes, String... varyings) throws IOException {
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertex);
        GL20.glAttachShader(program, fragment);
        for (int i = 0; i < attributes.length; i++) GL20.glBindAttribLocation(program, i, attributes[i]);
        if (varyings.length > 0) GL30.glTransformFeedbackVaryings(program, varyings, GL30.GL_INTERLEAVED_ATTRIBS);
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
