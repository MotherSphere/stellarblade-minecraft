package org.stellarbridge;

import com.mojang.blaze3d.platform.GlStateManager;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.minecraft.client.gl.ShaderProgram;
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
 * second. While the streamed effects say which Cascade trail plays, the ribbon follows it in that trail's style;
 * without them a trail shows while the held blade turns fast relative to EVE's chest (a flip turns both together).
 * The ribbon covers the style's lifetime, from its head color to its faded one, drawn additively with the game's
 * trail texture. The pack describes it beside the weapon mesh in {@code <mesh>.trail.txt} with {@code <mesh>.trail.png}.
 * Under a shader pack the ribbon goes through Minecraft's eyes program with the texture's glow baked in gray.
 * Render thread only; AvatarRenderer saves and restores the GL state around draw.
 */
final class AvatarTrail implements AutoCloseable {
    static final double LIFETIME = 0.12; // Seconds: the game's main trail emitter lives 0.1 s, its others up to 0.2 s.
    /**
     * The game's Cascade trails, from their emitters: head and faded color and lifetime. While Minecraft knows which one
     * plays, the trail follows the game instead of the swing.
     */
    enum Style {
        LIGHT(1, 1, 1, 0, 0.1f, 0.3f, 0.12), STRONG(1, 1, 1.05f, 0.1f, 0.12f, 0.18f, 0.25), THRUST(0.85f, 0.95f, 1.2f, 0, 0.1f, 0.5f, 0.3);

        final float r, g, b, fadedR, fadedG, fadedB;
        final double lifetime;
        Style(float r, float g, float b, float fadedR, float fadedG, float fadedB, double lifetime) {
            this.r = r; this.g = g; this.b = b; this.fadedR = fadedR; this.fadedG = fadedG; this.fadedB = fadedB; this.lifetime = lifetime;
        }
        /** The style of a Cascade trail asset, or null for another asset. */
        static Style of(String asset) {
            if (!asset.startsWith("P_A_AnimTrail")) return null;
            return asset.contains("Strong") ? STRONG : asset.contains("Thrust") ? THRUST : LIGHT;
        }
    }
    static final double SWING_START = 9, SWING_FULL = 18; // Blade turn rate in the chest's frame, radians per second.
    static final float BRIGHTNESS = 0.9f;
    private static final int CAPACITY = 128, FLOATS = 9; // Samples kept; per vertex position, UV and RGBA.
    private static final String[] ATTRIBUTES = {"Position", "UV0", "Color"};
    private static final float[] UP = {0, 1, 0, 1}, FULL_BRIGHT = {240, 240, 0, 1};

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
    private final int program, viewProjection, fogStart, fogEnd, fogColor, array, buffer, texture, glowTexture, entityArray;
    private final double[] times = new double[CAPACITY], strengths = new double[CAPACITY];
    private final Vector3d[] begins = new Vector3d[CAPACITY], ends = new Vector3d[CAPACITY];
    private final FloatBuffer vertices = BufferUtils.createFloatBuffer(2 * CAPACITY * FLOATS);
    private final Vector3d begin = new Vector3d(), end = new Vector3d(), root = new Vector3d(), hand = new Vector3d(), camera = new Vector3d();
    private final Vector3d chest = new Vector3d(), chestX = new Vector3d(), chestY = new Vector3d(), blade = new Vector3d(), previousBlade = new Vector3d();
    private boolean bladeKnown;
    private Style style = Style.LIGHT;
    private boolean followsGame, gameTrail; // Whether the game's trail state is known, and whether one plays.
    private int newest = -1, count;

    /** The trail of the first pack mesh with a description, or null; glows loads its texture through glow(). Textures belong to the caller. */
    static AvatarTrail load(AvatarModel model, Path pack, TextureLoader textures, TextureLoader glows) throws IOException {
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
                    bone(body, spec.holderBone), bone(body, spec.chestBone), texture, glows.load(mesh.name + ".trail.png"));
        }
        return null;
    }
    interface TextureLoader { int load(String name) throws IOException; }

    /** Bakes trail.fsh's glow of each RGBA texel (gradient in red, streak noise in green) into opaque gray. */
    static void glow(ByteBuffer rgba) {
        for (int i = 0; i + 3 < rgba.capacity(); i += 4) {
            double r = (rgba.get(i) & 0xff) / 255.0, g = (rgba.get(i + 1) & 0xff) / 255.0, t = Math.clamp((r - 0.15) / 0.65, 0, 1);
            byte value = (byte) Math.round(Math.min(1, t * t * (3 - 2 * t) * (0.7 + 0.6 * g)) * 255);
            rgba.put(i, value).put(i + 1, value).put(i + 2, value).put(i + 3, (byte) 255);
        }
    }
    private static int bone(AvatarMesh mesh, String name) {
        for (int k = 0; k < mesh.bones.length; k++) if (mesh.bones[k].name().equals(name)) return k;
        throw new IllegalArgumentException(mesh.name + " has no bone " + name);
    }

    private AvatarTrail(Spec spec, int part, int beginBone, int endBone, int holderPart, int holderBone, int chestBone, int texture,
                        int glowTexture) throws IOException {
        this.spec = spec;
        this.part = part;
        this.beginBone = beginBone;
        this.endBone = endBone;
        this.holderPart = holderPart;
        this.holderBone = holderBone;
        this.chestBone = chestBone;
        this.texture = texture;
        this.glowTexture = glowTexture;
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
        entityArray = GL30.glGenVertexArrays();
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
        if (followsGame) strength = gameTrail ? 1 : 0;
        else if (count > 0 && bladeKnown && root.distance(hand) * 100 <= spec.reach) {
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

    /** The game's trail for the coming samples: a style while one plays, null while none does. */
    void follow(Style playing) {
        followsGame = true;
        gameTrail = playing != null;
        if (playing != null) style = playing;
    }

    /** Without the game's trail state, swings decide again. */
    void followSwings() {
        followsGame = false;
        style = Style.LIGHT;
    }

    /** The newest blade sockets (absolute Minecraft coordinates); false before any sample. */
    boolean blade(Vector3d hiltOut, Vector3d tipOut) {
        if (count == 0) return false;
        hiltOut.set(begins[newest]);
        tipOut.set(ends[newest]);
        return true;
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
        int drawn = upload();
        if (drawn == 0) return false;
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
        additive();
        GlStateManager._glBindVertexArray(array);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, drawn);
        return true;
    }

    /** The same ribbon through Minecraft's eyes program (a shader pack's while one is in use), its glow baked in gray. */
    void drawEntity(ShaderProgram eyes, Matrix4f view, Matrix4f projection) {
        int drawn = upload();
        if (drawn == 0) return;
        GlStateManager._glBindVertexArray(entityArray);
        ShaderPacks.inputs(eyes, Map.of("Position", new ShaderPacks.Feed(buffer, 3, FLOATS * 4, 0),
                "UV0", new ShaderPacks.Feed(buffer, 2, FLOATS * 4, 12), "Color", new ShaderPacks.Feed(buffer, 4, FLOATS * 4, 20)),
                Map.of("Normal", UP, "UV1", ShaderPacks.NO_OVERLAY, "UV2", FULL_BRIGHT));
        additive();
        ShaderPacks.bind(eyes, glowTexture, view, projection);
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, drawn);
        eyes.unbind();
    }

    private static void additive() {
        GlStateManager._enableBlend();
        GlStateManager._blendFuncSeparate(GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ZERO, GL11.GL_ONE);
        GlStateManager._depthMask(false);
    }

    /** Uploads the ribbon as of the newest sample; the vertex count, 0 when nothing is visible. */
    private int upload() {
        if (count == 0) return 0;
        double now = times[newest];
        vertices.clear();
        int drawn = 0;
        boolean visible = false;
        for (int i = 0; i < count; i++) {
            int at = Math.floorMod(newest - i, CAPACITY);
            double age = now - times[at];
            if (age > style.lifetime) break;
            float life = (float) Math.max(age / style.lifetime, 0), intensity = (float) (strengths[at] * (1 - life));
            visible |= intensity > 0.01f;
            // The style's head color, fading to its faded color.
            float r = BRIGHTNESS * (style.r + (style.fadedR - style.r) * life), g = BRIGHTNESS * (style.g + (style.fadedG - style.g) * life),
                    b = BRIGHTNESS * (style.b + (style.fadedB - style.b) * life);
            put(begins[at], life, 0, r, g, b, intensity);
            put(ends[at], life, 1, r, g, b, intensity);
            drawn += 2;
        }
        if (!visible || drawn < 4) return 0;
        vertices.flip();
        int previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, vertices);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer);
        return drawn;
    }

    /** One ribbon vertex; the color is premultiplied by its intensity, as additive programs read it. */
    private void put(Vector3d at, float u, float v, float r, float g, float b, float a) {
        vertices.put((float) (at.x - camera.x)).put((float) (at.y - camera.y)).put((float) (at.z - camera.z))
                .put(Math.min(u, 0.98f)).put(v).put(r * a).put(g * a).put(b * a).put(1);
    }

    @Override
    public void close() {
        if (array != 0) GL30.glDeleteVertexArrays(array);
        if (entityArray != 0) GL30.glDeleteVertexArrays(entityArray);
        if (buffer != 0) GL15.glDeleteBuffers(buffer);
        if (program != 0) GL20.glDeleteProgram(program);
    }
}
