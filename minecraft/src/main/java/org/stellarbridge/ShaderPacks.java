package org.stellarbridge;

import java.lang.reflect.Method;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.VertexFormat;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.slf4j.LoggerFactory;

/**
 * Iris shader packs, without a compile dependency on Iris. While a pack renders the world, Iris resolves the frame from
 * its own buffers, so our own GL programs' output never reaches the screen; Iris does replace Minecraft's entity programs
 * with the pack's. The avatar then draws its buffers through those programs (they are also correct without a pack).
 * Render thread only.
 */
final class ShaderPacks {
    private static final float[] DEFAULT = {0, 0, 0, 1};
    // The overlay texture's neutral row (OverlayTexture.DEFAULT_UV): no hurt tint, for draws that are never hurt.
    static final float[] NO_OVERLAY = {0, 10, 0, 1};
    private static final Set<String> logged = new HashSet<>(); // Programs (name:id) whose inputs were logged since the last switch.
    private static Object api;
    private static Method inUse;
    private static boolean failed, last;
    /** /stellaravatar shading entity: Minecraft's entity programs even without a pack. */
    static boolean forced;

    /** Whether EVE is drawn through Minecraft's entity programs this frame: a pack is in use or forced. */
    static boolean active() {
        boolean active = decide(forced, FabricLoader.getInstance().isModLoaded("iris"), ShaderPacks::inUse);
        if (active != last) {
            last = active;
            logged.clear(); // A pack switch recreates its programs, often under the same ids.
            LoggerFactory.getLogger("stellar_bridge").info("EVE avatar: drawing {}.", active
                    ? "through Minecraft's entity programs (" + (forced ? "forced" : "shader pack in use") + ")" : "with her own shaders");
        }
        return active;
    }

    /**
     * The path: forced, or Iris loaded and its pack in use. Iris's answer failing counts as in use: the entity programs
     * are correct either way, while our own programs would be invisible under a pack.
     */
    static boolean decide(boolean forced, boolean irisLoaded, java.util.function.BooleanSupplier inUse) {
        if (forced) return true;
        if (!irisLoaded) return false;
        try {
            return inUse.getAsBoolean();
        } catch (RuntimeException failure) {
            return true;
        }
    }

    /** Iris's public API; if it cannot be read, entity programs, which are correct either way. */
    private static boolean inUse() {
        if (failed) return true;
        try {
            if (inUse == null) {
                Class<?> type = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                api = type.getMethod("getInstance").invoke(null);
                inUse = type.getMethod("isShaderPackInUse");
            }
            return (Boolean) inUse.invoke(api);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            failed = true;
            LoggerFactory.getLogger("stellar_bridge").warn("Iris API unreadable; EVE uses Minecraft's entity programs", failure);
            return true;
        }
    }

    /** A float array feeding a vertex input: buffer, components, stride and offset in bytes. */
    record Feed(int buffer, int size, int stride, long offset) { }

    /**
     * Points the bound vertex array at a program's active inputs: named feeds as float arrays, every other input as the
     * constant of that name or (0, 0, 0, 1). Names are those of Minecraft's vertex formats (Position, Color, UV0, UV1, UV2,
     * Normal) and Iris's extra inputs (mc_midTexCoord, at_tangent); Iris's iris_ prefix is ignored. Changes GL_ARRAY_BUFFER.
     * Logs each program's name and inputs once, which tells whether a pack replaced it.
     */
    static void inputs(ShaderProgram shader, Map<String, Feed> feeds, Map<String, float[]> constants) {
        for (int a = 0; a < 16; a++) GL20.glDisableVertexAttribArray(a);
        int program = shader.getGlRef(), count = GL20.glGetProgrami(program, GL20.GL_ACTIVE_ATTRIBUTES);
        StringBuilder seen = logged.add(shader.getName() + ":" + program) ? new StringBuilder() : null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer size = stack.mallocInt(1), type = stack.mallocInt(1);
            for (int i = 0; i < count; i++) {
                String name = GL20.glGetActiveAttrib(program, i, 64, size, type);
                int location = GL20.glGetAttribLocation(program, name);
                if (location < 0) continue; // Built-ins such as gl_VertexID.
                String key = input(name);
                Feed feed = feeds.get(key);
                if (seen != null) seen.append(' ').append(name).append(feed != null ? "=array" : "=constant");
                if (feed != null) {
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, feed.buffer());
                    GL20.glVertexAttribPointer(location, feed.size(), GL11.GL_FLOAT, false, feed.stride(), feed.offset());
                    GL20.glEnableVertexAttribArray(location);
                    continue;
                }
                float[] v = constants.getOrDefault(key, DEFAULT);
                switch (kind(type.get(0))) {
                    case 1 -> GL30.glVertexAttribI4i(location, (int) v[0], (int) v[1], (int) v[2], (int) v[3]);
                    case 2 -> GL30.glVertexAttribI4ui(location, (int) v[0], (int) v[1], (int) v[2], (int) v[3]);
                    default -> GL20.glVertexAttrib4f(location, v[0], v[1], v[2], v[3]);
                }
            }
        }
        if (seen != null) LoggerFactory.getLogger("stellar_bridge").info("EVE avatar: Minecraft program {} ({}) reads{}.", shader.getName(), program, seen);
    }

    /** An input's name as Minecraft's vertex formats call it. */
    static String input(String name) {
        return name.startsWith("iris_") ? name.substring(5) : name;
    }

    /** 1 for signed integer inputs, 2 for unsigned ones, 0 for float inputs, by GL type. */
    static int kind(int type) {
        return switch (type) {
            case GL11.GL_INT, GL20.GL_INT_VEC2, GL20.GL_INT_VEC3, GL20.GL_INT_VEC4 -> 1;
            case GL11.GL_UNSIGNED_INT, GL30.GL_UNSIGNED_INT_VEC2, GL30.GL_UNSIGNED_INT_VEC3, GL30.GL_UNSIGNED_INT_VEC4 -> 2;
            default -> 0;
        };
    }

    /**
     * Binds a Minecraft program as Minecraft's own draws do (Iris binds its buffers and uniforms here): texture on unit 0,
     * the world's camera rotation and projection. The caller draws, then unbinds the program.
     */
    static void bind(ShaderProgram program, int texture, Matrix4f view, Matrix4f projection) {
        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, texture);
        var client = MinecraftClient.getInstance(); // None in AvatarRenderCheck: entity programs have no ScreenSize to read.
        program.initializeUniforms(VertexFormat.DrawMode.TRIANGLES, view, projection, client == null ? null : client.getWindow());
        program.bind();
    }
}
