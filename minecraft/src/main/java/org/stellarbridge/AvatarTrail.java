package org.stellarbridge;

import com.mojang.blaze3d.platform.GlStateManager;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

/**
 * Stellar Blade's sword trails. Its attack shows stretch an anim trail between two weapon sockets for about a quarter
 * second; those show keys are not streamed, but the swings are, so a trail shows while the held blade turns fast
 * relative to EVE's chest (a flip turns both together): a ribbon over the last LIFETIME seconds, white at its head and
 * fading to blue, drawn additively with the game's trail texture. The pack describes it beside the weapon mesh in {@code <mesh>.trail.txt} with {@code <mesh>.trail.png}.
 * Render thread only; AvatarRenderer saves and restores the GL state around draw.
 */
final class AvatarTrail implements AutoCloseable {
    static final double LIFETIME = 0.12; // Seconds: the game's main trail emitter lives 0.1 s, its others up to 0.2 s.
    static final double SWING_START = 9, SWING_FULL = 18; // Blade turn rate in the chest's frame, radians per second.
    static final float BRIGHTNESS = 0.9f;
    private static final int CAPACITY = 128, FLOATS = 9; // Samples kept; per vertex position, UV and RGBA.
    private static final String[] ATTRIBUTES = {"Position", "UV0", "Color"};

    /**
     * {@code SBAVTRAIL 1}, {@code BEGIN bone x y z} and {@code END bone x y z} (sockets in Unreal units in that bone's
     * space), {@code HELD mesh handBone centimeters chestBone}: a trail only while the weapon's root bone is that close to
     * the holder's hand, measuring the blade's turn in the holder's chest frame.
     */
    record Spec(String beginBone, Vector3d begin, String endBone, Vector3d end, String holderMesh, String holderBone, double reach,
                String chestBone) {
        static Spec parse(String text) {
            String[] lines = text.split("\n");
            if (lines.length != 4 || !lines[0].equals("SBAVTRAIL 1")) throw new IllegalArgumentException("Not a trail description");
            String[] b = fields(lines[1], "BEGIN", 5), e = fields(lines[2], "END", 5), h = fields(lines[3], "HELD", 5);
            double reach = Double.parseDouble(h[3]);
            if (!(reach > 0 && reach < 1000)) throw new IllegalArgumentException("Trail reach out of range");
            return new Spec(b[1], point(b), e[1], point(e), h[1], h[2], reach, h[4]);
        }
        private static String[] fields(String line, String key, int count) {
            String[] f = line.split(" ");
            if (f.length != count || !f[0].equals(key)) throw new IllegalArgumentException("Expected " + key);
            return f;
        }
        private static Vector3d point(String[] f) {
            Vector3d v = new Vector3d(Double.parseDouble(f[2]), Double.parseDouble(f[3]), Double.parseDouble(f[4]));
            if (!v.isFinite() || v.length() > 10000) throw new IllegalArgumentException("Trail socket out of range");
            return v;
        }
    }

    /** Trail strength from the blade's turn rate in radians per second: 0 below SWING_START, 1 from SWING_FULL. */
    static double strength(double turnRate) {
        double t = Math.clamp((turnRate - SWING_START) / (SWING_FULL - SWING_START), 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static final Vector3d ORIGIN = new Vector3d(), UNIT_X = new Vector3d(1, 0, 0), UNIT_Y = new Vector3d(0, 1, 0);
    private final int part, beginBone, endBone, holderPart, holderBone, chestBone;
    private final Spec spec;
    private final int program, viewProjection, fogStart, fogEnd, fogColor, array, buffer, texture;
    private final double[] times = new double[CAPACITY], strengths = new double[CAPACITY];
    private final Vector3d[] begins = new Vector3d[CAPACITY], ends = new Vector3d[CAPACITY];
    private final FloatBuffer vertices = BufferUtils.createFloatBuffer(2 * CAPACITY * FLOATS);
    private final Vector3d begin = new Vector3d(), end = new Vector3d(), root = new Vector3d(), hand = new Vector3d(), camera = new Vector3d();
    private final Vector3d chest = new Vector3d(), chestX = new Vector3d(), chestY = new Vector3d(), blade = new Vector3d(), previousBlade = new Vector3d();
    private boolean bladeKnown;
    private int newest = -1, count;

    /** The trail of the first pack mesh with a description, or null. The texture belongs to the caller. */
    static AvatarTrail load(AvatarModel model, Path pack, TextureLoader textures) throws IOException {
        for (int p = 0; p < model.parts.size(); p++) {
            AvatarMesh mesh = model.parts.get(p).mesh();
            Path file = pack.resolve(mesh.name + ".trail.txt");
            if (!file.normalize().startsWith(pack.normalize()) || !Files.isRegularFile(file)) continue;
            Spec spec = Spec.parse(Files.readString(file));
            int holder = -1;
            for (int q = 0; q < model.parts.size(); q++) if (model.parts.get(q).mesh().name.equals(spec.holderMesh)) holder = q;
            if (holder < 0) return null; // Without the holder's mesh no trail can tell a held blade.
            int texture = textures.load(mesh.name + ".trail.png");
            if (texture == 0) return null;
            AvatarMesh body = model.parts.get(holder).mesh();
            return new AvatarTrail(spec, p, bone(mesh, spec.beginBone), bone(mesh, spec.endBone), holder,
                    bone(body, spec.holderBone), bone(body, spec.chestBone), texture);
        }
        return null;
    }
    interface TextureLoader { int load(String name) throws IOException; }
    private static int bone(AvatarMesh mesh, String name) {
        for (int k = 0; k < mesh.bones.length; k++) if (mesh.bones[k].name().equals(name)) return k;
        throw new IllegalArgumentException(mesh.name + " has no bone " + name);
    }

    private AvatarTrail(Spec spec, int part, int beginBone, int endBone, int holderPart, int holderBone, int chestBone, int texture)
            throws IOException {
        this.spec = spec;
        this.part = part;
        this.beginBone = beginBone;
        this.endBone = endBone;
        this.holderPart = holderPart;
        this.holderBone = holderBone;
        this.chestBone = chestBone;
        this.texture = texture;
        for (int i = 0; i < CAPACITY; i++) {
            begins[i] = new Vector3d();
            ends[i] = new Vector3d();
        }
        program = AvatarRenderer.link(AvatarRenderer.shader(GL20.GL_VERTEX_SHADER, "trail.vsh"),
                AvatarRenderer.shader(GL20.GL_FRAGMENT_SHADER, "trail.fsh"), ATTRIBUTES);
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
        GlStateManager._glBindVertexArray(previousArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
    }

    /**
     * Records the sockets of the displayed pose (after AvatarModel.palettes with the same placement) at a time in seconds;
     * camera is the camera's position, to which the placement is relative.
     */
    void sample(AvatarModel model, AvatarRecording.Pose pose, Matrix4d placement, double cameraX, double cameraY, double cameraZ,
                double seconds) {
        camera.set(cameraX, cameraY, cameraZ);
        model.point(pose, placement, part, beginBone, spec.begin, begin).add(camera);
        model.point(pose, placement, part, endBone, spec.end, end).add(camera);
        model.point(pose, placement, part, 0, ORIGIN, root); // The weapon's root bone sits in the gripping hand.
        model.point(pose, placement, holderPart, holderBone, ORIGIN, hand);
        // The blade's direction in the chest's frame (two of its axes, orthonormalized): swings turn it, flips do not.
        model.point(pose, placement, holderPart, chestBone, ORIGIN, chest);
        model.point(pose, placement, holderPart, chestBone, UNIT_X, chestX).sub(chest).normalize();
        model.point(pose, placement, holderPart, chestBone, UNIT_Y, chestY).sub(chest);
        chestY.sub(new Vector3d(chestX).mul(chestY.dot(chestX))).normalize();
        Vector3d direction = new Vector3d(end).sub(begin), chestZ = new Vector3d(chestX).cross(chestY);
        blade.set(direction.dot(chestX), direction.dot(chestY), direction.dot(chestZ));
        if (count > 0 && seconds == times[newest]) return; // Same displayed time: nothing new.
        if (count > 0 && seconds < times[newest]) { // Time went back (a new run or a looped replay): start over.
            count = 0;
            bladeKnown = false;
        }
        double strength = 0;
        if (count > 0 && bladeKnown && root.distance(hand) * 100 <= spec.reach) {
            double turn = previousBlade.angle(blade) / (seconds - times[newest]);
            if (Double.isFinite(turn)) strength = strength(turn);
        }
        previousBlade.set(blade);
        bladeKnown = blade.isFinite() && blade.lengthSquared() > 0;
        newest = (newest + 1) % CAPACITY;
        count = Math.min(count + 1, CAPACITY);
        times[newest] = seconds;
        strengths[newest] = strength;
        begins[newest].set(begin);
        ends[newest].set(end);
    }

    /** Strength of the newest sample, 0 to 1. */
    double strength() {
        return count == 0 ? 0 : strengths[newest];
    }

    /**
     * Draws the ribbon as of the newest sample with additive blending, depth test without depth writes; false when
     * nothing is visible.
     */
    boolean draw(Matrix4f projectionView, float start, float stop, float[] fog) {
        if (count == 0) return false;
        double now = times[newest];
        vertices.clear();
        int drawn = 0;
        boolean visible = false;
        for (int i = 0; i < count; i++) {
            int at = Math.floorMod(newest - i, CAPACITY);
            double age = now - times[at];
            if (age > LIFETIME) break;
            float life = (float) Math.max(age / LIFETIME, 0), intensity = (float) (strengths[at] * (1 - life));
            visible |= intensity > 0.01f;
            // White at the head, then the game's blue (0, 0.1, 0.3) as it fades.
            float r = BRIGHTNESS * (1 - life), g = BRIGHTNESS * (1 - 0.9f * life), b = BRIGHTNESS * (1 - 0.7f * life);
            put(begins[at], life, 0, r, g, b, intensity);
            put(ends[at], life, 1, r, g, b, intensity);
            drawn += 2;
        }
        if (!visible || drawn < 4) return false;
        vertices.flip();
        int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, vertices);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
        GlStateManager._glUseProgram(program);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            GL20.glUniformMatrix4fv(viewProjection, false, projectionView.get(stack.mallocFloat(16)));
        }
        GL20.glUniform1i(GL20.glGetUniformLocation(program, "Sampler0"), 0);
        GL20.glUniform1f(fogStart, start);
        GL20.glUniform1f(fogEnd, stop);
        GL20.glUniform4f(fogColor, fog[0], fog[1], fog[2], fog[3]);
        GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        GlStateManager._bindTexture(texture);
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
        GlStateManager._depthMask(false);
        GlStateManager._glBindVertexArray(array);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, drawn);
        return true;
    }

    private void put(Vector3d at, float u, float v, float r, float g, float b, float a) {
        vertices.put((float) (at.x - camera.x)).put((float) (at.y - camera.y)).put((float) (at.z - camera.z))
                .put(Math.min(u, 0.98f)).put(v).put(r).put(g).put(b).put(a);
    }

    @Override
    public void close() {
        if (array != 0) GL30.glDeleteVertexArrays(array);
        if (buffer != 0) GL15.glDeleteBuffers(buffer);
        if (program != 0) GL20.glDeleteProgram(program);
    }
}
