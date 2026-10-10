package org.stellarbridge;

import com.mojang.blaze3d.platform.GlStateManager;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import net.minecraft.client.gl.ShaderProgram;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/**
 * Stellar Blade's effects on EVE redrawn as glowing sprites with the game's own effect textures ({@code fx.<texture>.png}
 * in the pack). Each effect asset has a recipe of emitters, approximating its Niagara emitters: sparks streaking along
 * their velocity, flares and rings facing the camera, lightning along the blade, wind streaks around EVE. Particles are
 * simulated on the CPU and drawn additively after EVE (under a shader pack through Minecraft's eyes program; the effect
 * textures are gray). Render thread only; AvatarRenderer saves and restores GL state.
 */
final class AvatarSprites implements AutoCloseable {
    /** Camera-facing square, streak along the velocity, or wide horizontal flare. */
    enum Shape { FACING, STREAK, HORIZON }
    /** At the effect, anywhere along the blade, or on a ring around the effect. */
    enum Place { POINT, BLADE, AROUND }

    /**
     * One emitter: a texture (with a grid of frames per side, one picked per particle), how it is shaped and placed, a
     * burst when the effect starts and a rate while it plays, life in seconds, size in blocks from start to end, speed
     * in blocks per second, gravity, a tint (above 1 reads brighter), and whether it follows the effect while alive.
     */
    record Emitter(String texture, int grid, Shape shape, Place place, int burst, double rate, double lifeMin, double lifeMax,
                   double sizeStart, double sizeEnd, double speedMin, double speedMax, double gravity, float r, float g, float b,
                   boolean attached) { }

    private static Emitter sparks(int burst, double rate, float r, float g, float b) {
        return new Emitter("T_A_SparkParticle_01", 1, Shape.STREAK, Place.POINT, burst, rate, 0.12, 0.35, 0.025, 0.01, 2, 6, -6, r, g, b, false);
    }
    private static Emitter flare(String texture, Shape shape, double life, double from, double to, float r, float g, float b) {
        return new Emitter(texture, 1, shape, Place.POINT, 1, 0, life, life, from, to, 0, 0, 0, r, g, b, true);
    }

    /** The emitters for an effect asset; none for the Cascade blade trails, which AvatarTrail draws. */
    static List<Emitter> recipe(String asset) {
        if (asset.startsWith("P_A_AnimTrail")) return List.of();
        if (asset.startsWith("NS_A_ParTrail")) return List.of(sparks(6, 160, 1.4f, 1.2f, 0.85f)); // Blade-tip sparks of a swing.
        if (asset.contains("HoldBurst_Signal")) return List.of( // The charged heavy attack's red signal.
                flare("T_A_HorizonFlare_03", Shape.HORIZON, 0.45, 1.8, 1.0, 1.7f, 0.15f, 0.1f),
                flare("T_A_LensFlare_01", Shape.FACING, 0.35, 0.9, 0.4, 1.5f, 0.35f, 0.3f),
                sparks(24, 0, 1.6f, 0.25f, 0.15f),
                new Emitter("T_A_BreakCrystal_01", 4, Shape.FACING, Place.POINT, 6, 0, 0.3, 0.5, 0.12, 0.05, 1, 3, -6, 1.3f, 0.25f, 0.2f, false));
        if (asset.contains("HoldBurst_SwordFX")) return List.of( // Its red-edged shock wave.
                flare("Ring", Shape.FACING, 0.35, 0.2, 1.7, 1.5f, 0.12f, 0.08f),
                sparks(30, 40, 1.5f, 0.6f, 0.25f));
        if (asset.contains("Electric")) return List.of( // Lightning crawling along the blade.
                new Emitter("T_Semicircle_Arcs_2x2", 2, Shape.FACING, Place.BLADE, 6, 70, 0.06, 0.12, 0.4, 0.35, 0, 0, 0, 1.0f, 1.3f, 2.6f, false),
                new Emitter("T_A_SparkParticle_01", 1, Shape.STREAK, Place.BLADE, 0, 30, 0.1, 0.25, 0.02, 0.01, 1, 4, -4, 0.7f, 0.9f, 1.6f, false));
        if (asset.contains("FastDraw") || asset.contains("Fold")) return List.of( // Drawing or folding the sword.
                flare("T_A_ShineFlare_01", Shape.FACING, 0.25, 0.5, 0.2, 1.0f, 1.1f, 1.5f),
                sparks(8, 0, 1.1f, 1.2f, 1.5f));
        if (asset.contains("SpinTrail") || asset.contains("MeshTrail") || asset.contains("ChargeSlash") || asset.contains("Wind"))
            return List.of(new Emitter("T_A_StreakSwirl_01", 1, Shape.FACING, Place.AROUND, 4, 10, 0.3, 0.5, 0.8, 1.4, 0, 0.5, 0,
                    0.55f, 0.6f, 0.7f, false)); // Swirling wind of spins and slashes.
        if (asset.contains("Evade")) return List.of( // A perfect dodge: a cold flash and the air torn around EVE.
                flare("T_A_ShineFlare_01", Shape.FACING, 0.35, 0.6, 2.2, 0.8f, 1.1f, 1.8f),
                new Emitter("T_A_StreakSwirl_01", 1, Shape.FACING, Place.AROUND, 8, 0, 0.3, 0.5, 0.7, 1.6, 0, 0.6, 0, 0.6f, 0.8f, 1.4f, false));
        if (asset.contains("JustParry")) return List.of( // A perfect parry: a white flash, a shock ring and sparks.
                flare("T_A_LensFlare_01", Shape.FACING, 0.25, 1.2, 0.5, 1.4f, 1.4f, 1.6f),
                flare("Ring", Shape.FACING, 0.3, 0.2, 1.4, 1.1f, 1.2f, 1.5f),
                sparks(28, 0, 1.4f, 1.3f, 1.2f));
        if (asset.contains("Kick")) return List.of( // A kick's glow and the streak it leaves.
                new Emitter("T_A_LensFlare_01", 1, Shape.FACING, Place.POINT, 1, 30, 0.12, 0.18, 0.35, 0.1, 0, 0, 0, 0.9f, 1.0f, 1.5f, false),
                new Emitter("T_A_ShineFlare_01", 1, Shape.FACING, Place.POINT, 0, 60, 0.2, 0.3, 0.12, 0.0, 0, 0.2, 0, 0.7f, 0.85f, 1.3f, false));
        return List.of(flare("T_A_ShineFlare_01", Shape.FACING, 0.2, 0.3, 0.1, 1f, 1f, 1f));
    }

    private static final int CAPACITY = 1024, FLOATS = 9, VERTICES = 6;
    private static final String[] ATTRIBUTES = {"Position", "UV0", "Color"};
    private final Random random = new Random();
    private final Map<String, Integer> textureIndex = new HashMap<>();
    private final int[] textures;
    private static final float[] UP = {0, 1, 0, 1}, FULL_BRIGHT = {240, 240, 0, 1};
    private final int program, viewProjection, fogStart, fogEnd, fogColor, array, buffer, entityArray;
    private final FloatBuffer vertices = BufferUtils.createFloatBuffer(CAPACITY * VERTICES * FLOATS);
    // Particles, structure of arrays; positions are absolute Minecraft coordinates.
    private final double[] x = new double[CAPACITY], y = new double[CAPACITY], z = new double[CAPACITY];
    private final double[] vx = new double[CAPACITY], vy = new double[CAPACITY], vz = new double[CAPACITY];
    private final double[] age = new double[CAPACITY], life = new double[CAPACITY];
    private final Emitter[] emitter = new Emitter[CAPACITY];
    private final String[] follow = new String[CAPACITY]; // Effect an attached particle follows.
    private final int[] frame = new int[CAPACITY];
    private int count;
    private final Map<String, Double> owed = new HashMap<>();
    private Set<String> active = new HashSet<>();
    private final Map<String, Vector3d> where = new HashMap<>();
    private final Vector3d camera = new Vector3d(), hilt = new Vector3d(), tip = new Vector3d();
    private final Quaternionf rotation = new Quaternionf();
    private boolean blade;
    private double seconds = Double.NaN;

    /** Sprites for a pack's fx textures, or null when the pack has none. */
    static AvatarSprites load(AvatarTrail.TextureLoader loader) throws IOException {
        Map<String, Integer> loaded = new HashMap<>();
        for (String name : textures()) {
            int id = loader.load("fx." + name + ".png");
            if (id != 0) loaded.put(name, id);
        }
        return loaded.isEmpty() ? null : new AvatarSprites(loaded);
    }
    /** The pack files load asks for. */
    static List<String> textureNames() {
        return textures().stream().map(name -> "fx." + name + ".png").toList();
    }
    private static Set<String> textures() {
        Set<String> names = new HashSet<>();
        for (String asset : new String[] {"NS_A_ParTrail", "HoldBurst_Signal", "HoldBurst_SwordFX", "Electric", "FastDraw", "SpinTrail", "Evade",
                "JustParry", "Kick", ""})
            for (Emitter e : recipe(asset)) names.add(e.texture());
        return names;
    }

    private AvatarSprites(Map<String, Integer> loaded) throws IOException {
        textures = new int[loaded.size()];
        int i = 0;
        for (var entry : loaded.entrySet()) {
            textureIndex.put(entry.getKey(), i);
            textures[i++] = entry.getValue();
        }
        program = AvatarRenderer.link(AvatarRenderer.shader(GL20.GL_VERTEX_SHADER, "trail.vsh"),
                AvatarRenderer.shader(GL20.GL_FRAGMENT_SHADER, "sprite.fsh"), ATTRIBUTES);
        viewProjection = GL20.glGetUniformLocation(program, "ViewProjection");
        fogStart = GL20.glGetUniformLocation(program, "FogStart");
        fogEnd = GL20.glGetUniformLocation(program, "FogEnd");
        fogColor = GL20.glGetUniformLocation(program, "FogColor");
        int previousArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        array = GL30.glGenVertexArrays();
        buffer = GL15.glGenBuffers();
        GlStateManager._glBindVertexArray(array);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) vertices.capacity() * 4, GL15.GL_DYNAMIC_DRAW);
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, FLOATS * 4, 0);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, FLOATS * 4, 12);
        GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, FLOATS * 4, 20);
        for (int a = 0; a < 3; a++) GL20.glEnableVertexAttribArray(a);
        entityArray = GL30.glGenVertexArrays();
        GlStateManager._glBindVertexArray(previousArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
    }

    /**
     * Advances to a time in seconds with the active effects at absolute Minecraft positions (keyed by asset name; one
     * position per asset), the blade between hilt and tip when known, the camera position and rotation (as Minecraft
     * rotates billboards).
     */
    void update(Map<String, Vector3d> effects, Vector3d hiltAt, Vector3d tipAt, Vector3d cameraAt, Quaternionf cameraRotation, double now) {
        double step = Double.isNaN(seconds) ? 0 : Math.clamp(now - seconds, 0, 0.1);
        seconds = now;
        camera.set(cameraAt);
        rotation.set(cameraRotation);
        blade = hiltAt != null && tipAt != null;
        if (blade) {
            hilt.set(hiltAt);
            tip.set(tipAt);
        }
        for (var entry : effects.entrySet()) where.computeIfAbsent(entry.getKey(), k -> new Vector3d()).set(entry.getValue());
        for (var entry : effects.entrySet()) {
            String asset = entry.getKey();
            boolean started = !active.contains(asset);
            for (Emitter e : recipe(asset)) {
                if (!textureIndex.containsKey(e.texture())) continue;
                if (started) for (int k = 0; k < e.burst(); k++) emit(e, asset, entry.getValue());
                String key = asset + "/" + e.texture() + "/" + e.shape();
                double due = owed.getOrDefault(key, 0.0) + e.rate() * step;
                for (; due >= 1; due--) emit(e, asset, entry.getValue());
                owed.put(key, due);
            }
        }
        active = new HashSet<>(effects.keySet());
        for (int i = 0; i < count; ) {
            age[i] += step;
            if (age[i] >= life[i]) { remove(i); continue; }
            if (emitter[i].attached() && follow[i] != null && active.contains(follow[i])) {
                Vector3d at = where.get(follow[i]);
                x[i] = at.x; y[i] = at.y; z[i] = at.z;
            } else {
                vy[i] += emitter[i].gravity() * step;
                x[i] += vx[i] * step; y[i] += vy[i] * step; z[i] += vz[i] * step;
            }
            i++;
        }
    }

    private void emit(Emitter e, String asset, Vector3d at) {
        if (count == CAPACITY) return;
        int i = count++;
        double px = at.x, py = at.y, pz = at.z;
        if (e.place() == Place.BLADE && blade) {
            double t = random.nextDouble();
            px = hilt.x + (tip.x - hilt.x) * t; py = hilt.y + (tip.y - hilt.y) * t; pz = hilt.z + (tip.z - hilt.z) * t;
        } else if (e.place() == Place.AROUND) {
            double angle = random.nextDouble() * 2 * Math.PI;
            px += Math.cos(angle) * 0.6; pz += Math.sin(angle) * 0.6; py += (random.nextDouble() - 0.5) * 0.4;
        }
        x[i] = px; y[i] = py; z[i] = pz;
        // A uniformly random direction at a random speed.
        double cos = random.nextDouble() * 2 - 1, sin = Math.sqrt(1 - cos * cos), phi = random.nextDouble() * 2 * Math.PI;
        double speed = e.speedMin() + random.nextDouble() * (e.speedMax() - e.speedMin());
        vx[i] = sin * Math.cos(phi) * speed; vy[i] = cos * speed; vz[i] = sin * Math.sin(phi) * speed;
        age[i] = 0;
        life[i] = e.lifeMin() + random.nextDouble() * (e.lifeMax() - e.lifeMin());
        emitter[i] = e;
        follow[i] = asset;
        frame[i] = random.nextInt(e.grid() * e.grid());
    }

    private void remove(int i) {
        int last = --count;
        x[i] = x[last]; y[i] = y[last]; z[i] = z[last];
        vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last];
        age[i] = age[last]; life[i] = life[last]; emitter[i] = emitter[last]; follow[i] = follow[last]; frame[i] = frame[last];
    }

    int count() { return count; }

    /** Draws the particles additively, depth tested without depth writes, grouped by texture. */
    void draw(Matrix4f projectionView, float start, float stop, float[] fog) {
        int[] first = new int[textures.length], size = new int[textures.length];
        if (!upload(first, size)) return;
        GlStateManager._glUseProgram(program);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20.glUniformMatrix4fv(viewProjection, false, projectionView.get(stack.mallocFloat(16)));
        }
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler0"), 0);
        GL20.glUniform1f(fogStart, start);
        GL20.glUniform1f(fogEnd, stop);
        GL20.glUniform4f(fogColor, fog[0], fog[1], fog[2], fog[3]);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        additive();
        GlStateManager._glBindVertexArray(array);
        for (int t = 0; t < textures.length; t++) {
            if (size[t] == 0) continue;
            GlStateManager._bindTexture(textures[t]);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, first[t], size[t]);
        }
    }

    /** The same particles through Minecraft's eyes program (a shader pack's while one is in use). */
    void drawEntity(ShaderProgram eyes, Matrix4f view, Matrix4f projection) {
        int[] first = new int[textures.length], size = new int[textures.length];
        if (!upload(first, size)) return;
        GlStateManager._glBindVertexArray(entityArray);
        ShaderPacks.inputs(eyes, Map.of("Position", new ShaderPacks.Feed(buffer, 3, FLOATS * 4, 0),
                "UV0", new ShaderPacks.Feed(buffer, 2, FLOATS * 4, 12), "Color", new ShaderPacks.Feed(buffer, 4, FLOATS * 4, 20)),
                Map.of("Normal", UP, "UV1", ShaderPacks.NO_OVERLAY, "UV2", FULL_BRIGHT));
        additive();
        for (int t = 0; t < textures.length; t++) {
            if (size[t] == 0) continue;
            ShaderPacks.bind(eyes, textures[t], view, projection);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, first[t], size[t]);
        }
        eyes.unbind();
    }

    private static void additive() {
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
        GlStateManager._depthMask(false);
    }

    /** Uploads every particle's quad grouped by texture into first and size (in vertices); false without particles. */
    private boolean upload(int[] first, int[] size) {
        if (count == 0) return false;
        vertices.clear();
        Vector3f right = rotation.transform(new Vector3f(1, 0, 0)), up = rotation.transform(new Vector3f(0, 1, 0));
        int written = 0;
        for (int t = 0; t < textures.length; t++) {
            first[t] = written;
            for (int i = 0; i < count; i++) {
                if (textureIndex.get(emitter[i].texture()) != t) continue;
                quad(i, right, up);
                written += VERTICES;
            }
            size[t] = written - first[t];
        }
        vertices.flip();
        int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, vertices);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
        return true;
    }

    private void quad(int i, Vector3f right, Vector3f up) {
        Emitter e = emitter[i];
        double t = age[i] / life[i];
        float intensity = (float) (Math.min(1, t / 0.1) * (1 - t)); // Quick rise, linear fade.
        double size = e.sizeStart() + (e.sizeEnd() - e.sizeStart()) * t;
        double cx = x[i] - camera.x, cy = y[i] - camera.y, cz = z[i] - camera.z;
        double ax, ay, az, bx, by, bz; // Half extents of the quad's two axes.
        if (e.shape() == Shape.STREAK) {
            // Along the velocity, as long as the distance covered in 40 ms, as wide as size, turned toward the camera.
            Vector3d along = new Vector3d(vx[i], vy[i], vz[i]);
            double length = Math.max(along.length() * 0.04, size);
            if (along.lengthSquared() < 1e-9) along.set(up.x, up.y, up.z);
            along.normalize();
            Vector3d side = new Vector3d(along).cross(cx, cy, cz);
            if (side.lengthSquared() < 1e-12) side.set(right.x, right.y, right.z);
            side.normalize(size / 2);
            ax = along.x * length / 2; ay = along.y * length / 2; az = along.z * length / 2;
            bx = side.x; by = side.y; bz = side.z;
        } else {
            double width = e.shape() == Shape.HORIZON ? size : size / 2, height = e.shape() == Shape.HORIZON ? size / 8 : size / 2;
            ax = right.x * width; ay = right.y * width; az = right.z * width;
            bx = up.x * height; by = up.y * height; bz = up.z * height;
        }
        int grid = e.grid(), column = frame[i] % grid, row = frame[i] / grid;
        float u0 = (float) column / grid, u1 = (float) (column + 1) / grid, v0 = (float) row / grid, v1 = (float) (row + 1) / grid;
        float r = e.r() * intensity, g = e.g() * intensity, b = e.b() * intensity;
        put(cx - ax - bx, cy - ay - by, cz - az - bz, u0, v1, r, g, b);
        put(cx + ax - bx, cy + ay - by, cz + az - bz, u1, v1, r, g, b);
        put(cx + ax + bx, cy + ay + by, cz + az + bz, u1, v0, r, g, b);
        put(cx - ax - bx, cy - ay - by, cz - az - bz, u0, v1, r, g, b);
        put(cx + ax + bx, cy + ay + by, cz + az + bz, u1, v0, r, g, b);
        put(cx - ax + bx, cy - ay + by, cz - az + bz, u0, v0, r, g, b);
    }

    private void put(double px, double py, double pz, float u, float v, float r, float g, float b) {
        vertices.put((float) px).put((float) py).put((float) pz).put(u).put(v).put(r).put(g).put(b).put(1);
    }

    @Override
    public void close() {
        if (array != 0) GL30.glDeleteVertexArrays(array);
        if (entityArray != 0) GL30.glDeleteVertexArrays(entityArray);
        if (buffer != 0) GL15.glDeleteBuffers(buffer);
        if (program != 0) GL20.glDeleteProgram(program);
    }
}
