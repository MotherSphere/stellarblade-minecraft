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

    public static void main(String[] args) throws java.io.IOException {
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
        clothSkinning();
        wideSkeleton();
        caches();
        System.out.println("PASS avatar model: pack validation, recording interpolation, leader-pose mapping, Minecraft-space skinning, cloth"
                + " deltas through the rigid palette, 512-bone palettes, cut-out mipmaps, weapon trails, shader-pack inputs, sun and shadow math,"
                + " and the mesh and texture caches");
    }

    /**
     * The rebuild caches: meshes the model does not draw go least recently used first beyond the budget; textures already
     * uploaded are not decoded again (a hit), a missing one is (a miss), and a trim keeps the used ones and the budget.
     */
    static void caches() throws java.io.IOException {
        Map<String, AvatarMesh> meshes = new java.util.LinkedHashMap<>(16, 0.75f, true);
        for (String name : new String[] {"A", "B", "C"}) meshes.put(name, AvatarMesh.parse(name, mesh("Tip", b -> { })));
        meshes.get("A"); // B is now the least recently used.
        long size = meshes.get("C").bytes();
        AvatarModel wearing = new AvatarModel(java.util.List.of(new AvatarRecording.Component("C", "C", null, new String[] {"Root", "Tip"}, new String[0], 0)), meshes);
        AvatarModel.trim(meshes, wearing, 2 * size);
        assert meshes.keySet().equals(java.util.Set.of("A", "C")) : meshes.keySet();
        AvatarModel.trim(meshes, wearing, 0);
        assert meshes.keySet().equals(java.util.Set.of("C")) : "The drawn mesh stays, whatever the budget";

        java.nio.file.Path pack = java.nio.file.Files.createTempDirectory("stellar-pack-");
        try {
            ByteBuffer rgba = org.lwjgl.BufferUtils.createByteBuffer(64);
            for (int i = 0; i < 16; i++) rgba.put((byte) 200).put((byte) 100).put((byte) 50).put((byte) (i % 4 == 0 ? 255 : 0));
            assert org.lwjgl.stb.STBImageWrite.stbi_write_png(pack.resolve("Skin.png").toString(), 4, 4, 4, rgba.flip(), 16);
            java.nio.file.Files.copy(pack.resolve("Skin.png"), pack.resolve("Skin.normal.png"));
            long texture = 4 * 16 * 4 / 3; // A 4x4 texture with its mipmaps.
            java.util.List<Integer> deleted = new java.util.ArrayList<>();
            AvatarTextures textures = new AvatarTextures(2 * texture, deleted::add);
            var wanted = java.util.List.of(new AvatarTextures.Texture("Skin.png", 0.5f, null), new AvatarTextures.Texture("Skin.normal.png", 0, null),
                    new AvatarTextures.Texture("Skin.hair.png", 0, null), new AvatarTextures.Texture("Skin.png", 0.5f, null));
            var decoded = textures.decode(pack, wanted);
            assert decoded.size() == 3 && decoded.get("Skin.png").levels().length == 3 && decoded.get("Skin.normal.png").levels().length == 1
                    && decoded.get("Skin.hair.png").levels() == null : "Cut-out levels, GPU mipmaps, an absent file; one decode per key";
            int id = 1;
            for (var entry : decoded.entrySet()) { // As upload keeps them, without GL.
                textures.keep(entry.getKey(), entry.getValue().levels() == null ? 0 : id++, entry.getValue().bytes());
                entry.getValue().free();
            }
            assert textures.decode(pack, wanted).isEmpty() && textures.id("Skin.hair.png") == 0 && textures.id("Other.png") == null : "Hits";
            assert textures.decode(pack, java.util.List.of(new AvatarTextures.Texture("Skin.png", 0, AvatarTrail::glow))).size() == 1
                    : "A converted copy is another texture";
            int normal = textures.id("Skin.normal.png"), skin = textures.id("Skin.png"); // Skin.png is now the most recently used.
            textures.keep("Third.png", 9, texture);
            textures.trim(java.util.Set.of("Third.png"));
            assert deleted.equals(java.util.List.of(normal)) && !textures.holds("Skin.normal.png") && textures.holds("Skin.png")
                    && textures.bytes() == 2 * texture : "The least recently used unused texture goes: " + deleted;
            textures.trim(java.util.Set.of());
            assert deleted.size() == 1 : "Within the budget nothing goes";
            textures.close();
            assert deleted.containsAll(java.util.List.of(skin, 9)) && deleted.size() == 3 && textures.bytes() == 0 && !textures.holds("Skin.png");
        } finally {
            try (var files = java.nio.file.Files.walk(pack)) { files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete()); }
        }
    }

    /**
     * Stellar Blade's cloth through the vertex shader: a CPU copy of avatar.vsh ((P + d) * sum of w * M) with the cloth part's
     * rigid palette and its deltas must place each vertex at placement * world * cloth and turn its normal as the cloth
     * turned the triangle, whatever its bone weights.
     */
    static void clothSkinning() {
        // One triangle on the Root/Tip chain, its pack normal +Z opposite to (p1 - p0) x (p2 - p0), as the ponytail's are; the
        // last vertex split between both bones.
        ByteBuffer b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        b.put("SBAVMESH".getBytes(StandardCharsets.US_ASCII)).putInt(2).putInt(2);
        name(b, "Root"); b.putInt(-1); for (float v : IDENTITY) b.putFloat(v);
        name(b, "Tip"); b.putInt(0); for (float v : new float[] {0, 0, 0, 1, 0, 0, 100, 1, 1, 1}) b.putFloat(v);
        b.putInt(3);
        vertex(b, 0, 0, 0, 0);
        vertex(b, 0, 100, 0, 1);
        b.putFloat(100).putFloat(0).putFloat(0).putFloat(0).putFloat(0).putFloat(1).putFloat(0).putFloat(0);
        b.putShort((short) 0).putShort((short) 1).putShort((short) 0).putShort((short) 0).putFloat(0.6f).putFloat(0.4f).putFloat(0).putFloat(0);
        b.putInt(3).putInt(0).putInt(1).putInt(2);
        b.putInt(1); name(b, "Hair"); b.putInt(0).putInt(1);
        b.putInt(0);
        AvatarMesh tail = AvatarMesh.parse("Tail", b.flip()), body = AvatarMesh.parse("Body", mesh("Tip", x -> { }));
        var components = java.util.List.of(new AvatarRecording.Component("CharacterMesh0", "Body", null, new String[] {"Root", "Tip"}, new String[0], 0),
                new AvatarRecording.Component("SBPonytail", "Tail", "CharacterMesh0", null, new String[0], 0));
        AvatarModel model = new AvatarModel(components, Map.of("Body", body, "Tail", tail));
        AvatarRecording.Pose pose = new AvatarRecording.Pose(new float[2][10], new float[][] {new float[20], null}, new float[2][0], new float[2][]);
        float root90 = (float) Math.sqrt(0.5);
        float[] world = {0, 0, root90, root90, 100, 200, 300, 1, 1, 1}; // 90 degrees about Z, then moved.
        System.arraycopy(world, 0, pose.world()[0], 0, 10);
        System.arraycopy(world, 0, pose.world()[1], 0, 10);
        System.arraycopy(new float[] {root90, 0, 0, root90, 0, 0, 0, 1, 1, 1}, 0, pose.local()[0], 0, 10); // Bones turned: unused by cloth.
        System.arraycopy(new float[] {0, 0, 0, 1, 0, 0, 100, 1, 1, 1}, 0, pose.local()[0], 10, 10);
        // The cloth: the reference triangle turned 90 degrees about X (y to z) and moved by (5, 6, 7).
        float[] cloth = {5, 6, 7, 5, 6, 107, 105, 6, 7};
        pose.cloth()[1] = cloth;
        Matrix4d placement = new Matrix4d().translation(-1, -2, -3).mul(AvatarModel.UNREAL_TO_MINECRAFT);
        FloatBuffer[] palettes = {FloatBuffer.allocate(24), FloatBuffer.allocate(24)};
        model.palettes(pose, placement, palettes, false);
        assert model.cloth[1] == cloth && model.cloth[0] == null;
        FloatBuffer deltas = FloatBuffer.allocate(18);
        tail.cloth(cloth, new float[9], deltas);
        assert deltas.limit() == 18 && deltas.get(0) == 5 && deltas.get(7) == -94 && deltas.get(8) == 107 : "Position deltas from the reference";
        Matrix4d expected = new Matrix4d(placement).mul(AvatarMesh.transform(world, 0, new Matrix4d()));
        Vector3d turnedNormal = expected.transformDirection(new Vector3d(0, -1, 0)).normalize(); // +Z turned about X.
        for (int v = 0; v < 3; v++) {
            int at = v * AvatarMesh.VERTEX_BYTES;
            double[] position = new double[3], normal = new double[3];
            for (int row = 0; row < 3; row++)
                for (int k = 0; k < 4; k++) {
                    int bone = Short.toUnsignedInt(tail.vertices.getShort(at + 32 + 2 * k)), m = bone * 12 + row * 4;
                    float weight = tail.vertices.getFloat(at + 40 + 4 * k);
                    for (int c = 0; c < 3; c++) {
                        position[row] += weight * palettes[1].get(m + c) * (tail.vertices.getFloat(at + 4 * c) + deltas.get(6 * v + c));
                        normal[row] += weight * palettes[1].get(m + c) * (tail.vertices.getFloat(at + 12 + 4 * c) + deltas.get(6 * v + 3 + c));
                    }
                    position[row] += weight * palettes[1].get(m + 3);
                }
            Vector3d want = expected.transformPosition(new Vector3d(cloth[3 * v], cloth[3 * v + 1], cloth[3 * v + 2]));
            assert want.distance(position[0], position[1], position[2]) < 1e-5 : "Vertex " + v + " at " + java.util.Arrays.toString(position) + ", not " + want;
            Vector3d drawn = new Vector3d(normal[0], normal[1], normal[2]).normalize();
            assert drawn.distance(turnedNormal) < 1e-5 : "Normal " + v + " " + drawn + ", not " + turnedNormal;
        }
        // Without cloth the part is skinned to its leader's bones again (the turned root).
        float[] rigid = palettes[1].array().clone();
        pose.cloth()[1] = null;
        model.palettes(pose, placement, palettes, false);
        assert model.cloth[1] == null && !java.util.Arrays.equals(rigid, palettes[1].array()) : "Bones again";
        // Two cloth triangles at right angles share vertex 0, one twice the other's size: its normal is their area-weighted sum.
        ByteBuffer pair = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN);
        pair.put("SBAVMESH".getBytes(StandardCharsets.US_ASCII)).putInt(2).putInt(1);
        name(pair, "Root"); pair.putInt(-1); for (float v : IDENTITY) pair.putFloat(v);
        pair.putInt(4);
        for (int v = 0; v < 4; v++) vertex(pair, 0, 0, 0, 0);
        pair.putInt(6).putInt(0).putInt(1).putInt(2).putInt(0).putInt(3).putInt(1);
        pair.putInt(1); name(pair, "Hair"); pair.putInt(0).putInt(2);
        pair.putInt(0);
        FloatBuffer paired = FloatBuffer.allocate(24);
        AvatarMesh.parse("Pair", pair.flip()).cloth(new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 0, 2}, new float[12], paired);
        Vector3d shared = new Vector3d(paired.get(3), paired.get(4), paired.get(5) + 1); // Plus the reference normal +Z.
        assert shared.distance(new Vector3d(0, -2, -1).normalize()) < 1e-6 : "Area-weighted normal " + shared;
    }

    /**
     * Suits carry up to 364 bones: a 512-bone chain (bone k one centimeter above bone k - 1) loads, its palette fills the
     * Palette block exactly (bone k at byte 48 k) and a CPU copy of avatar.vsh places the vertices of bones past 255 (u16
     * indices) where their bones went; a 513th bone is refused, as is a driver whose uniform blocks cannot hold the palette.
     */
    static void wideSkeleton() {
        int[] skinned = {0, 1, 255, 256, 300, 511};
        AvatarMesh wide = AvatarMesh.parse("Wide", chain(AvatarMesh.MAX_BONES, skinned));
        String[] names = java.util.Arrays.stream(wide.bones).map(AvatarMesh.Bone::name).toArray(String[]::new);
        AvatarModel model = new AvatarModel(java.util.List.of(new AvatarRecording.Component("CharacterMesh0", "Wide", null, names, new String[0], 0)),
                Map.of("Wide", wide));
        float[] local = new float[10 * names.length];
        float root90 = (float) Math.sqrt(0.5);
        System.arraycopy(new float[] {root90, 0, 0, root90, 0, 0, 0, 1, 1, 1}, 0, local, 0, 10); // The root turned 90 degrees about X.
        for (int k = 1; k < names.length; k++) System.arraycopy(new float[] {0, 0, 0, 1, 0, 0, 1, 1, 1, 1}, 0, local, 10 * k, 10);
        var pose = new AvatarRecording.Pose(new float[][] {IDENTITY.clone()}, new float[][] {local}, new float[1][0], new float[1][]);
        FloatBuffer[] palettes = {FloatBuffer.allocate(12 * names.length)};
        model.palettes(pose, new Matrix4d(AvatarModel.UNREAL_TO_MINECRAFT), palettes, false);
        assert 4 * palettes[0].remaining() == AvatarRenderer.PALETTE_BYTES : "One palette fills the block";
        for (int v = 0; v < skinned.length; v++) {
            int at = v * AvatarMesh.VERTEX_BYTES, bone = Short.toUnsignedInt(wide.vertices.getShort(at + 32));
            double[] position = new double[3];
            for (int row = 0; row < 3; row++) {
                int m = bone * 12 + row * 4;
                for (int c = 0; c < 3; c++) position[row] += palettes[0].get(m + c) * wide.vertices.getFloat(at + 4 * c);
                position[row] += palettes[0].get(m + 3);
            }
            // (0, 0, k) cm turned about X is (0, -k, 0), Minecraft (0, 0, -k / 100).
            assert bone == skinned[v] && new Vector3d(position[0], position[1], position[2]).distance(0, 0, -bone / 100.0) < 1e-5
                    : "Bone " + bone + " at " + java.util.Arrays.toString(position);
        }
        try {
            AvatarMesh.parse("Wider", chain(AvatarMesh.MAX_BONES + 1, skinned));
            throw new AssertionError("A mesh past MAX_BONES bones was accepted");
        } catch (IllegalArgumentException expected) { }
        try (var in = AvatarRenderer.class.getResourceAsStream("/assets/stellar_bridge/shaders/avatar/avatar.vsh")) {
            assert new String(in.readAllBytes(), StandardCharsets.US_ASCII).contains("mat3x4 Bones[" + AvatarMesh.MAX_BONES + "];")
                    : "avatar.vsh's Palette holds MAX_BONES bones";
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        assert AvatarRenderer.paletteRefusal(65536, 14, 84) == null : "An RTX 5080's limits";
        String refusal = AvatarRenderer.paletteRefusal(16384, 12, 36); // GL 3.2's minimum block size.
        assert refusal != null && refusal.contains("24576 bytes") && AvatarRenderer.paletteRefusal(65536, 14, 15) != null;
    }

    /** A chain of bones, each one centimeter above its parent, with one vertex at each skinned bone, in triangles. */
    static ByteBuffer chain(int bones, int[] skinned) {
        ByteBuffer b = ByteBuffer.allocate(64 + 64 * bones + 64 * skinned.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("SBAVMESH".getBytes(StandardCharsets.US_ASCII)).putInt(2).putInt(bones);
        for (int k = 0; k < bones; k++) {
            name(b, "B" + k);
            b.putInt(k - 1);
            for (float v : k == 0 ? IDENTITY : new float[] {0, 0, 0, 1, 0, 0, 1, 1, 1, 1}) b.putFloat(v);
        }
        b.putInt(skinned.length);
        for (int bone : skinned) vertex(b, 0, 0, bone, bone);
        b.putInt(skinned.length);
        for (int i = 0; i < skinned.length; i++) b.putInt(i);
        b.putInt(1); name(b, "Skin"); b.putInt(0).putInt(skinned.length / 3);
        b.putInt(0);
        return b.flip();
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
