package org.stellarbridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Consumer;
import org.joml.Matrix4d;

/** Avatar pack parsing, pose recording interpolation and skinning matrices; no GL context or game files. */
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
        System.out.println("PASS avatar model: pack validation, recording interpolation, leader-pose mapping, Minecraft-space skinning and cut-out mipmaps");
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
