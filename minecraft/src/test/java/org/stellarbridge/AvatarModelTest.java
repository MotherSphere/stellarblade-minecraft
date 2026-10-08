package org.stellarbridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;

/** Avatar pack parsing, pose recording interpolation and skinning matrices, and the sun and shadow math; no GL context or game files. */
public final class AvatarModelTest {
    static final float[] IDENTITY = {0, 0, 0, 1, 0, 0, 0, 1, 1, 1};

    /** Two-bone chain: Root, then Tip 100 cm above it; one vertex on each bone; one section; morph Smile moves the tip vertex. */
    static ByteBuffer mesh(String tipName, Consumer<ByteBuffer> corrupt) {
        ByteBuffer b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        b.put("SBAVMESH".getBytes(StandardCharsets.US_ASCII)).putInt(2).putInt(2);
        name(b, "Root"); b.putInt(-1); for (float v : IDENTITY) b.putFloat(v);
        name(b, tipName); b.putInt(0); for (float v : new float[] {0, 0, 0, 1, 0, 0, 100, 1, 1, 1}) b.putFloat(v);
        b.putInt(2);
        vertex(b, 0, 0, 0, 0);
        vertex(b, 0, 0, 100, 1);
        b.putInt(3).putInt(0).putInt(1).putInt(1);
        b.putInt(1); name(b, "Skin"); b.putInt(0).putInt(1);
        b.putInt(1); name(b, "Smile"); b.putInt(1).putInt(1).putFloat(0).putFloat(10).putFloat(0).putFloat(0).putFloat(0).putFloat(0.5f);
        corrupt.accept(b);
        return b.flip();
    }
    static void vertex(ByteBuffer b, float x, float y, float z, int bone) {
        b.putFloat(x).putFloat(y).putFloat(z).putFloat(0).putFloat(0).putFloat(1).putFloat(0.5f).putFloat(0.5f);
        b.putShort((short) bone).putShort((short) 0).putShort((short) 0).putShort((short) 0);
        b.putFloat(1).putFloat(0).putFloat(0).putFloat(0);
    }
    static void name(ByteBuffer b, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        b.putShort((short) bytes.length).put(bytes);
    }

    public static void main(String[] args) {
        AvatarMesh body = AvatarMesh.parse("Body", mesh("Tip", b -> { }));
        assert body.bones.length == 2 && body.sections[0].indexCount() == 3 && body.vertices.capacity() == 112;
        rejects(b -> b.putShort(8 + 4 + 4 + (2 + 4 + 4 + 40) + (2 + 3 + 4 + 40) + 4 + 56 + 32, (short) 2)); // Bone index outside the skeleton.
        rejects(b -> b.putInt(8 + 4 + 4 + (2 + 4 + 4 + 40) + 2 + 3, 1)); // Parent after child.
        rejects(b -> b.putInt(8 + 4 + 4 + (2 + 4 + 4 + 40) + (2 + 3 + 4 + 40) + 4 + 112 + 4 + 8, 2)); // Index past the vertices.
        rejects(b -> b.putFloat(8 + 4 + 4 + 2 + 4 + 4 + 4, Float.NaN));
        rejects(b -> b.putInt(280, 2)); // Morph delta on a missing vertex.

        // Body is recorded; Face follows Body by bone name and lacks "Jaw" in the leader.
        String summary = "SBMC_POSE_PROBE 1\nREASON limit\nFRAMES 2\n"
                + "COMPONENT 1 CharacterMesh0 mesh=Body bones=2 morphs=0 weights=1 leader=- parent=- socket=- recorded=true method=snapshot\n"
                + "BONES 1 Root Tip\nMORPHS 1 Smile\n"
                + "COMPONENT 2 Mesh_Face mesh=Face bones=2 morphs=0 weights=0 leader=CharacterMesh0 parent=CharacterMesh0 socket=None recorded=false method=-\n"
                + "COMPONENT 3 Slot mesh=none bones=0 morphs=0 weights=0 leader=- parent=CharacterMesh0 socket=SC_Wing recorded=true method=sockets\n"
                + "BONES 3 \nTIMING frame_total count=2 mean_ms=1 max_ms=1\nEND\n";
        ByteBuffer data = ByteBuffer.allocate(2 * (12 + 40 + 80 + 4 + 40 + 40)).order(ByteOrder.LITTLE_ENDIAN);
        float root90 = (float) Math.sqrt(0.5);
        for (int frame = 0; frame < 2; frame++) {
            data.putInt(frame + 1).putDouble(frame * 0.5);
            for (float v : new float[] {0, 0, 0, 1, 100, 200 + frame * 100, 300, 1, 1, 1}) data.putFloat(v); // Body world.
            float[] rootBone = frame == 0 ? IDENTITY : new float[] {root90, 0, 0, root90, 0, 0, 0, 1, 1, 1}; // 90 degrees about X.
            for (float v : rootBone) data.putFloat(v);
            for (float v : new float[] {0, 0, 0, 1, 0, 0, 100, 1, 1, 1}) data.putFloat(v); // Tip local.
            data.putFloat(0.25f + frame * 0.5f); // Smile weight.
            for (float v : new float[] {0, 0, 0, 1, 100, 200 + frame * 100, 300, 1, 1, 1}) data.putFloat(v); // Face world.
            for (float v : IDENTITY) data.putFloat(v); // Slot world.
        }
        AvatarRecording recording = AvatarRecording.parse(summary, data.flip());
        assert recording.frames.length == 2 && recording.duration() == 0.5 && recording.components.get(2).bones().length == 0;
        assert recording.components.get(1).leader().equals("CharacterMesh0") && !recording.components.get(1).recorded();

        AvatarRecording.Pose pose = recording.sample(0.25, recording.emptyPose());
        float[] root = pose.local()[0];
        assert Math.abs(root[0] - Math.sin(Math.PI / 8)) < 1e-6 && Math.abs(root[3] - Math.cos(Math.PI / 8)) < 1e-6 : "nlerp halfway is 45 degrees";
        assert Math.abs(pose.world()[0][5] - 250) < 1e-4;
        assert Math.abs(recording.sample(0.75, recording.emptyPose()).world()[0][5] - 250) < 1e-4 : "Sampling loops over the duration";
        float[] flipped = new float[10];
        AvatarRecording.blend(IDENTITY, new float[] {0, 0, 0, -1, 0, 0, 0, 1, 1, 1}, 0.5f, flipped);
        assert flipped[3] == 1 : "q and -q are the same rotation";

        AvatarMesh face = AvatarMesh.parse("Face", mesh("Jaw", b -> { }));
        AvatarModel model = new AvatarModel(recording.components, Map.of("Body", body, "Face", face));
        assert model.parts.size() == 2 && model.skipped.isEmpty() && model.parts.get(1).leaderBones()[0] == 0 && model.parts.get(1).leaderBones()[1] == -1;
        FloatBuffer[] palettes = {FloatBuffer.allocate(24), FloatBuffer.allocate(24)};
        Matrix4d placement = new Matrix4d(AvatarModel.UNREAL_TO_MINECRAFT);
        model.palettes(recording.sample(0, recording.emptyPose()), placement, palettes, false);
        // Reference pose: the tip vertex (0, 0, 100) lands at body world (100, 200, 400) cm, i.e. Minecraft (1, 4, 2).
        assertPoint(palettes[0], 1, 0, 0, 100, 1, 4, 2);
        assert model.morphWeights[0][0] == 0.25f && model.morphWeights[1][0] == 0 : "Followers without recorded weights stay neutral";
        FloatBuffer offsets = FloatBuffer.allocate(12);
        body.morph(model.morphWeights[0], offsets);
        assert offsets.get(7) == 2.5f && offsets.get(11) == 0.125f && offsets.get(1) == 0 && offsets.remaining() == 12;
        assert recording.sample(0.25, recording.emptyPose()).weights()[0][0] == 0.5f : "Weights interpolate linearly";
        model.palettes(recording.frames[1], placement, palettes, false);
        // Root turned 90 degrees about X keeps the body vertex (100, 0, 0), plus world (100, 300, 300);
        // the tip, child of the turned root, swings from (0, 0, 100) to (0, -100, 0).
        assertPoint(palettes[0], 0, 100, 0, 0, 2, 3, 3);
        assertPoint(palettes[0], 1, 0, 0, 100, 1, 3, 2);
        // Face Root follows the leader; Face Jaw is missing in the leader and keeps its reference pose (no root turn).
        assertPoint(palettes[1], 0, 100, 0, 0, 2, 3, 3);
        assertPoint(palettes[1], 1, 100, 0, 100, 2, 4, 3);

        try {
            new AvatarModel(recording.components, Map.of("Body", face));
            throw new AssertionError("Recorded bone names must match the mesh");
        } catch (IllegalArgumentException expected) { }
        AvatarModel bodyOnly = new AvatarModel(recording.components, Map.of("Body", body));
        assert bodyOnly.parts.size() == 1 && bodyOnly.skipped.equals(java.util.List.of("Mesh_Face (Face not in pack)"));
        // Cut-out mipmaps: a one-texel strand on every other column averages to half alpha and would vanish at the cutoff;
        // the level's alpha scales back so the same share of texels passes.
        ByteBuffer strands = org.lwjgl.system.MemoryUtil.memAlloc(4 * 4 * 4);
        for (int i = 0; i < 16; i++) strands.putInt(4 * i, 0).put(4 * i, (byte) 200).put(4 * i + 3, (byte) (i % 4 < 2 && i % 2 == 0 ? 255 : 0));
        ByteBuffer half = AvatarRenderer.halve(strands, 4, 4);
        assert (half.get(0) & 0xff) == 200 && (half.get(3) & 0xff) == 128 && (half.get(4 * 1 + 3) & 0xff) == 0 : "Box filter";
        double before = AvatarRenderer.coverage(strands, 16, 1, 192);
        assert before == 0.25 && AvatarRenderer.coverage(half, 4, 1, 192) == 0 : "Plain mipmaps lose the strands";
        AvatarRenderer.preserve(half, 4, 192, before);
        assert AvatarRenderer.coverage(half, 4, 1, 192) == 0.5 && (half.get(4 * 1 + 3) & 0xff) == 0 : "Coverage kept, gaps stay cut";
        org.lwjgl.system.MemoryUtil.memFree(half);
        org.lwjgl.system.MemoryUtil.memFree(strands);
        // Weapon trail: the pack's socket description, and strength from the blade's turn rate in the chest frame.
        String trail = "SBAVTRAIL 1\nBEGIN Weapon_Root 22.4 0 2.3\nEND AB_WP02_Top 0 -33.9 0\nHELD CH_P_EVE_52_TypeB Bip001-R-Hand 30 Bip001-Spine2\n";
        var spec = AvatarTrail.Spec.parse(trail);
        assert spec.endBone().equals("AB_WP02_Top") && spec.end().y == -33.9 && spec.reach() == 30 && spec.chestBone().equals("Bip001-Spine2");
        for (String bad : new String[] {trail.replace("SBAVTRAIL 1", "SBAVTRAIL 2"), trail.replace(" 30 ", " 0 "),
                trail.replace(" Bip001-Spine2", ""), trail.replace("22.4", "NaN")}) {
            try {
                AvatarTrail.Spec.parse(bad);
                throw new AssertionError("Accepted " + bad);
            } catch (IllegalArgumentException expected) { }
        }
        assert AvatarTrail.strength(AvatarTrail.SWING_START) == 0 && AvatarTrail.strength(AvatarTrail.SWING_FULL) == 1
                && AvatarTrail.strength(1) == 0 && AvatarTrail.strength(100) == 1;
        double middle = AvatarTrail.strength((AvatarTrail.SWING_START + AvatarTrail.SWING_FULL) / 2);
        assert Math.abs(middle - 0.5) < 1e-9 : "Smooth ramp between the thresholds";
        // Shader packs: Minecraft's names for Iris's inputs, generic value kinds by GL type, the trail's glow baked in gray.
        assert ShaderPacks.input("iris_UV2").equals("UV2") && ShaderPacks.input("at_tangent").equals("at_tangent");
        // The path never goes to our own programs under a pack: forced, in use, or Iris's answer failing all draw through
        // Minecraft's entity programs; without Iris, or with it and no pack, our own shaders.
        assert ShaderPacks.decide(true, false, () -> false) && ShaderPacks.decide(false, true, () -> true);
        assert ShaderPacks.decide(false, true, () -> { throw new IllegalStateException("Iris API"); }) : "A failing answer is a pack";
        assert !ShaderPacks.decide(false, false, () -> true) && !ShaderPacks.decide(false, true, () -> false);
        assert ShaderPacks.kind(org.lwjgl.opengl.GL20.GL_INT_VEC2) == 1 && ShaderPacks.kind(org.lwjgl.opengl.GL30.GL_UNSIGNED_INT_VEC3) == 2
                && ShaderPacks.kind(org.lwjgl.opengl.GL20.GL_FLOAT_VEC4) == 0;
        ByteBuffer glow = ByteBuffer.allocate(12).put(new byte[] {(byte) 255, (byte) 255, 9, 0, 30, (byte) 255, 9, 0, (byte) 166, 0, 9, 7});
        AvatarTrail.glow(glow);
        assert glow.get(0) == (byte) 255 && glow.get(2) == (byte) 255 && glow.get(3) == (byte) 255 : "Band and noise saturate";
        assert glow.get(4) == 0 && glow.get(6) == 0 && glow.get(7) == (byte) 255 : "Below the band: dark and opaque";
        assert (glow.get(8) & 0xff) == 155 && glow.get(9) == glow.get(8) && glow.get(10) == glow.get(8) : "smoothstep(0.15, 0.8, r) * 0.7";
        // The sun where Minecraft's sky draws it: up at noon, east in the morning, west in the evening, gone at midnight,
        // in rain and under a roof; sky light 13 in half rain gives half of half.
        AvatarShadows.Light noon = AvatarShadows.sun(0, 0, 15);
        assert noon.direction().distance(0, 1, 0) < 1e-6 && noon.strength() == 1;
        Vector3f morning = AvatarShadows.sun(0.8f, 0, 15).direction();
        assert morning.x > 0.9f && morning.y > 0 && morning.z == 0 : "Morning sun in the east";
        assert AvatarShadows.sun(0.2f, 0, 15).direction().x < -0.9f : "Evening sun in the west";
        assert AvatarShadows.sun(0.5f, 0, 15).strength() == 0 && AvatarShadows.sun(0, 1, 15).strength() == 0 && AvatarShadows.sun(0, 0, 11).strength() == 0;
        assert Math.abs(AvatarShadows.sun(0, 0.5f, 13).strength() - 0.25f) < 1e-6;
        // The shadow's light: straight up without a sun, never below 15 degrees.
        assert AvatarShadows.toward(AvatarShadows.NONE).distance(0, 1, 0) < 1e-6;
        assert AvatarShadows.toward(AvatarShadows.sun(0.2f, 1, 15)).distance(0, 1, 0) < 1e-6 : "A sun at strength 0 casts straight down";
        Vector3f low = AvatarShadows.toward(new AvatarShadows.Light(new Vector3f(1, 0.05f, 0).normalize(), 1));
        assert Math.abs(low.y - Math.sin(Math.toRadians(15))) < 1e-5 && Math.abs(low.length() - 1) < 1e-5 && low.z == 0 : "15 degree clamp";
        // The fitted light view holds the padded box, centred, and its depth spans the box along the light.
        Vector3d boxLow = new Vector3d(-0.3, -1.6, 2), boxHigh = new Vector3d(0.3, 0.2, 2.5);
        Vector3f slanted = new Vector3f(1, 1, 0.5f).normalize();
        Matrix4f light = new Matrix4f();
        float depth = AvatarShadows.fit(boxLow, boxHigh, slanted, light);
        for (int k = 0; k < 8; k++) {
            Vector3f corner = light.transformProject((float) ((k & 1) == 0 ? boxLow.x - AvatarShadows.PAD : boxHigh.x + AvatarShadows.PAD),
                    (float) ((k & 2) == 0 ? boxLow.y - AvatarShadows.PAD : boxHigh.y + AvatarShadows.PAD),
                    (float) ((k & 4) == 0 ? boxLow.z - AvatarShadows.PAD : boxHigh.z + AvatarShadows.PAD), new Vector3f());
            assert Math.abs(corner.x) <= 1 + 1e-4 && Math.abs(corner.y) <= 1 + 1e-4 && Math.abs(corner.z) <= 1 + 1e-4 : "Corner " + k + " at " + corner;
        }
        Vector3f center = light.transformProject(0, -0.7f, 2.25f, new Vector3f());
        assert Math.abs(center.x) < 1e-5 && Math.abs(center.y) < 1e-5 : "Box centred";
        // The shaders clear depth to 1 and keep the nearest to the light.
        assert light.transformProject(slanted.x / 2, -0.7f + slanted.y / 2, 2.25f + slanted.z / 2, new Vector3f()).z < center.z : "Nearer the light, nearer in depth";
        double span = 0;
        for (int i = 0; i < 3; i++) span += Math.abs(slanted.get(i)) * (boxHigh.get(i) - boxLow.get(i) + 2 * AvatarShadows.PAD);
        assert Math.abs(depth - span) < 1e-4 : "Depth " + depth + " against " + span;
        // Faces under her: a floor, and a wall at x = 2 three blocks high; light level 0 inside full cubes, as Minecraft's.
        AvatarShadows.Cells cells = new AvatarShadows.Cells() {
            @Override public float top(int x, int y, int z) { return y < 0 || (x == 2 && y < 3) ? 1 : 0; }
            @Override public float brightness(int x, int y, int z) { return top(x, y, z) >= 1 ? 0 : 1; }
        };
        Vector3d feet = new Vector3d(-0.3, 0, -0.3), head = new Vector3d(0.3, 1.7, 0.3);
        FloatBuffer faces = FloatBuffer.allocate(7 * 6 * 2048);
        Vector3f up = AvatarShadows.toward(AvatarShadows.NONE);
        AvatarShadows.fit(feet, head, up, light);
        int vertices = AvatarShadows.faces(cells, feet, head, up, light, 0, 0, 0, faces);
        assert vertices > 0 && vertices % 6 == 0;
        for (int v = 0; v < vertices; v++) assert faces.get(7 * v + 3) == 0 && faces.get(7 * v + 4) == 1 && faces.get(7 * v + 5) == 0 : "Tops only";
        Vector3f west = new Vector3f(-1, 0.5f, 0).normalize(); // The sun in the west: her shadow climbs the wall's west face.
        AvatarShadows.fit(feet, head, west, light);
        vertices = AvatarShadows.faces(cells, feet, head, west, light, 0, 0, 0, faces);
        boolean climbs = false;
        for (int v = 0; v < vertices; v++) {
            float nx = faces.get(7 * v + 3), nz = faces.get(7 * v + 5);
            climbs |= nx == -1 && faces.get(7 * v) == 2;
            assert nx != 1 && nz == 0 : "Only sides turned to the light";
        }
        assert climbs : "The wall's west face";
        AvatarShadows.Cells dark = new AvatarShadows.Cells() {
            @Override public float top(int x, int y, int z) { return cells.top(x, y, z); }
            @Override public float brightness(int x, int y, int z) { return 0; }
        };
        assert AvatarShadows.faces(dark, feet, head, west, light, 0, 0, 0, faces) == 0 : "No shadow in the dark";
        // The sun in the east, walls at x = -5 and -2 two blocks high, a step at x = 1: her shadow reaches the near wall and
        // climbs its east face, and stops there, as the wall blocks the light before it reaches her; the step past her blocks
        // nothing.
        AvatarShadows.Cells walled = new AvatarShadows.Cells() {
            @Override public float top(int x, int y, int z) { return y < 0 || ((x == -5 || x == -2) && y < 2) || (x == 1 && y == 0) ? 1 : 0; }
            @Override public float brightness(int x, int y, int z) { return top(x, y, z) >= 1 ? 0 : 1; }
        };
        Vector3f east = new Vector3f(1, 0.36f, 0).normalize();
        AvatarShadows.fit(feet, head, east, light);
        vertices = AvatarShadows.faces(walled, feet, head, east, light, 0, 0, 0, faces);
        climbs = false;
        boolean reaches = false;
        for (int v = 0; v < vertices; v++) {
            float x = faces.get(7 * v), y = faces.get(7 * v + 1), nx = faces.get(7 * v + 3), ny = faces.get(7 * v + 4);
            climbs |= nx == 1 && x == -1;
            reaches |= ny == 1 && y == 0 && x == -1;
            assert ny != 1 || y != 0 || x > -3 || x < -5 : "Ground past the wall at x " + x;
            assert nx != 1 || x != -4 || y != 0 : "The far wall's east face";
        }
        assert climbs && reaches : "The near wall's east face and the ground before it";
        System.out.println("PASS avatar model: pack validation, recording interpolation, leader-pose mapping, Minecraft-space skinning, cut-out mipmaps, weapon trails, shader-pack inputs, and sun and shadow math");
    }

    /** Applies bone row matrices (GLSL mat3x4 layout) to an Unreal reference-space point. */
    static void assertPoint(FloatBuffer palette, int bone, double x, double y, double z, double ex, double ey, double ez) {
        double[] result = new double[3];
        for (int row = 0; row < 3; row++) {
            int at = bone * 12 + row * 4;
            result[row] = palette.get(at) * x + palette.get(at + 1) * y + palette.get(at + 2) * z + palette.get(at + 3);
        }
        assert Math.abs(result[0] - ex) < 1e-5 && Math.abs(result[1] - ey) < 1e-5 && Math.abs(result[2] - ez) < 1e-5
                : "bone " + bone + " gave " + java.util.Arrays.toString(result);
    }

    static void rejects(Consumer<ByteBuffer> corrupt) {
        try {
            AvatarMesh.parse("Bad", mesh("Tip", corrupt));
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException expected) {
            return;
        }
        throw new AssertionError("Corrupt avatar mesh accepted");
    }
}
