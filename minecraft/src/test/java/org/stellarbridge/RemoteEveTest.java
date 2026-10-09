package org.stellarbridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Other players' EVEs drawn from this pack, without GL or game files: stand-ins by role for meshes this pack lacks or
 * holds in another version, half-resolution textures, the lenient model driving a mesh by bone name, the HairB filter on remote ponytails, the
 * clock floor and display time through a remote's real accept path, and the network recording's file format.
 */
public final class RemoteEveTest {
    private static final Random random = new Random(11);

    public static void main(String[] args) throws java.io.IOException {
        substitution();
        halfTextures();
        lenient();
        hair();
        clock();
        recording();
        System.out.println("Remote EVE tests passed");
    }

    private static AvatarRecording.Component recorded(String name, String mesh, String... bones) {
        return new AvatarRecording.Component(name, mesh, null, bones, new String[0], 0);
    }

    /**
     * A sender's outfit against this pack: the same mesh with the same fingerprint (or an unknown one) stays, a missing or
     * different mesh takes its role's stand-in when this pack has it, else none; accessories and weapons have no role.
     */
    static void substitution() {
        String[] bones = {"Root", "Bip001-Head"};
        var components = List.of(recorded("CharacterMesh0", "CH_P_EVE_99_Suit", bones), recorded("SBHair", "EVE_HR_07", bones),
                new AvatarRecording.Component("SBPonytail", "CH_P_EVE_Hair_PonyTail_9", "CharacterMesh0", null, new String[0], 0),
                recorded("SBAccSlot1", "ACC_EAR_19M", bones), recorded("SBSkeletalMeshComponent_7", "CH_W_Sword_01", bones),
                recorded("Drone.CharacterMesh0", "CH_NPC_Drone_Nier", bones), recorded("SBAccSlot3", "none"),
                recorded("Mesh_Glasses", "ACC_GLA_01M", bones));
        long[] theirs = {11, 22, 33, 44, 55, 66, 0, 0};
        NetPose.Table table = new NetPose.Table(4, 0, 64, 0, 100, "minecraft:overworld", components, theirs, List.of(), false);
        // This pack: the sword as theirs, another hair version, the glasses (theirs unknown), the role meshes but no ponytail.
        Map<String, Long> mine = Map.of("CH_W_Sword_01", 55L, "EVE_HR_07", 99L, "ACC_GLA_01M", 77L, "CH_P_EVE_52_TypeB", 1L,
                "EVE_HR_01", 2L, "CH_NPC_Drone_Bear", 3L);
        List<String> notes = new ArrayList<>();
        var drawn = RemoteEves.substitute(table, mesh -> mine.getOrDefault(mesh, 0L), notes);
        assert drawn.stream().map(AvatarRecording.Component::mesh).toList().equals(List.of("CH_P_EVE_52_TypeB", "EVE_HR_01", "none", "none",
                "CH_W_Sword_01", "CH_NPC_Drone_Bear", "none", "ACC_GLA_01M")) : drawn;
        assert notes.equals(List.of("CH_P_EVE_99_Suit missing, drawn as CH_P_EVE_52_TypeB", "EVE_HR_07 differs, drawn as EVE_HR_01",
                "CH_P_EVE_Hair_PonyTail_9 missing, left out", "ACC_EAR_19M missing, left out", "CH_NPC_Drone_Nier missing, drawn as CH_NPC_Drone_Bear")) : notes;
        for (int i = 0; i < drawn.size(); i++) { // Only the mesh changes: the stream's layout and names stay the sender's.
            var a = drawn.get(i);
            var b = components.get(i);
            assert a.name().equals(b.name()) && Arrays.equals(a.bones(), b.bones()) && a.weights() == b.weights() && java.util.Objects.equals(a.leader(), b.leader());
        }
    }

    /**
     * Another player's EVE decodes a texture at half resolution (a quarter of the memory, freed as memAlloc'd), unless this
     * player's EVE holds it at full resolution already: then nothing is decoded and both draw the full one. The keys differ.
     */
    static void halfTextures() throws java.io.IOException {
        java.nio.file.Path pack = java.nio.file.Files.createTempDirectory("stellar-pack-");
        try {
            ByteBuffer rgba = org.lwjgl.BufferUtils.createByteBuffer(4 * 8 * 4);
            for (int i = 0; i < 32; i++) rgba.put((byte) (i * 8)).put((byte) 100).put((byte) 50).put((byte) 255);
            assert org.lwjgl.stb.STBImageWrite.stbi_write_png(pack.resolve("Skin.png").toString(), 8, 4, 4, rgba.flip(), 32);
            var half = new AvatarTextures.Texture("Skin.png", 0, null, true);
            assert half.key().equals("Skin.png#half") && half.full().key().equals("Skin.png");
            AvatarTextures.Decoded decoded = AvatarTextures.decode(pack, half);
            assert decoded.width() == 4 && decoded.height() == 2 && !decoded.stb() && decoded.bytes() == 4L * 4 * 2 * 4 / 3
                    && (decoded.levels()[0].get(0) & 0xff) == (0 + 8 + 64 + 72 + 2) / 4 : "A 2x2 box per texel";
            decoded.free();
            AvatarTextures textures = new AvatarTextures(1 << 20, id -> { });
            assert textures.decode(pack, List.of(half)).keySet().equals(java.util.Set.of("Skin.png#half"));
            textures.keep("Skin.png", 1, 128); // This player's EVE holds it at full resolution.
            assert textures.decode(pack, List.of(half)).isEmpty() : "The full texture serves";
            textures.close();
        } finally {
            try (var files = java.nio.file.Files.list(pack)) { for (var file : files.toList()) java.nio.file.Files.delete(file); }
            java.nio.file.Files.delete(pack);
        }
    }

    /** A mesh of named bones, each 10 cm along -Y from its parent (the first at the origin), one vertex on each of the first three. */
    static AvatarMesh named(String mesh, String[] names, int[] parents) {
        ByteBuffer b = ByteBuffer.allocate(256 + 64 * names.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("SBAVMESH".getBytes(StandardCharsets.US_ASCII)).putInt(2).putInt(names.length);
        for (int k = 0; k < names.length; k++) {
            AvatarModelTest.name(b, names[k]);
            b.putInt(parents[k]);
            for (float v : parents[k] < 0 ? AvatarModelTest.IDENTITY : new float[] {0, 0, 0, 1, 0, -10, 0, 1, 1, 1}) b.putFloat(v);
        }
        b.putInt(3);
        for (int k = 0; k < 3; k++) AvatarModelTest.vertex(b, 0, -10 * k, 0, Math.min(k, names.length - 1));
        b.putInt(3).putInt(0).putInt(1).putInt(2);
        b.putInt(1); AvatarModelTest.name(b, "Skin"); b.putInt(0).putInt(1);
        b.putInt(0);
        return AvatarMesh.parse(mesh, b.flip());
    }
    private static int[] chain(int n) {
        int[] parents = new int[n];
        for (int k = 0; k < n; k++) parents[k] = k - 1;
        return parents;
    }

    /**
     * A stand-in mesh whose skeleton differs from the streamed one is driven by bone name: streamed bones in another order
     * drive the same bones as in order, a mesh bone the stream lacks follows its nearest streamed ancestor rigidly, and a
     * mesh sharing no bone is left out. The strict model still refuses such a mesh.
     */
    static void lenient() {
        AvatarMesh suit = named("Suit", new String[] {"Root", "Spine", "Skirt"}, chain(3)); // Skirt: not in the stream.
        AvatarMesh other = named("Other", new String[] {"X", "Y"}, chain(2));
        var body = recorded("CharacterMesh0", "Suit", "Spine", "Root"); // Streamed in another order, without Skirt.
        var hat = recorded("SBAccSlot1", "Other", "Spine", "Root");
        try {
            new AvatarModel(List.of(body), Map.of("Suit", suit));
            throw new AssertionError("The strict model refuses other bones");
        } catch (IllegalArgumentException expected) { }
        AvatarModel model = new AvatarModel(List.of(body, hat), Map.of("Suit", suit, "Other", other), true);
        assert model.parts.size() == 1 && model.parts.get(0).component() == 0 && Arrays.equals(model.parts.get(0).leaderBones(), new int[] {1, 0, -1})
                && Arrays.equals(model.parts.get(0).bind(), new int[] {0, 1, 1}) : Arrays.toString(model.parts.get(0).leaderBones());
        // Component space: Root at the origin, Spine turned 90 degrees about Z at its bind place (0, -10, 0).
        float r = (float) Math.sqrt(0.5);
        float[] local = {0, 0, r, r, 0, -10, 0, 1, 1, 1, /* Root */ 0, 0, 0, 1, 0, 0, 0, 1, 1, 1};
        var pose = new AvatarRecording.Pose(new float[][] {AvatarModelTest.IDENTITY.clone(), AvatarModelTest.IDENTITY.clone()},
                new float[][] {local, new float[20]}, new float[][] {new float[0], new float[0]}, new float[2][]);
        FloatBuffer[] palettes = {FloatBuffer.allocate(12 * 3)};
        model.palettes(pose, new Matrix4d(), palettes, true);
        AvatarModelTest.assertPoint(palettes[0], 0, 5, 0, 0, 5, 0, 0); // Root stays.
        // Spine's bind point (0, -10, 0) stays; a point 10 cm further along -Y turns with it to (10, -10, 0).
        AvatarModelTest.assertPoint(palettes[0], 1, 0, -10, 0, 0, -10, 0);
        AvatarModelTest.assertPoint(palettes[0], 1, 0, -20, 0, 10, -10, 0);
        // Skirt (bind at (0, -20, 0)) follows Spine rigidly: its bind point lands where Spine carries it.
        AvatarModelTest.assertPoint(palettes[0], 2, 0, -20, 0, 10, -10, 0);
    }

    private static final String[] HAIR = {"Root", "Bip001-Head", "Ab-TL-HairB01", "Ab-TL-HairB02", "Ab-TL-HairB03", "Ab-TL-HairB04",
            "Ab-TL-HairB05", "Ab-TL-HairB06", "Ab-TL-HairB07", "Ab-TL-HairB08", "Ab-TL-HairB09"};

    /**
     * The HairB filter: a still, straight tail stays as it is; a tail whose joints zig-zag and twist about its axis from
     * pose to pose bends and reverses far less after it; its time average follows Stellar Blade's 60 Hz frames whatever
     * the pose rate; every rotation stays a unit quaternion.
     */
    static void hair() {
        AvatarMesh body = named("Body", HAIR, chain(HAIR.length));
        var component = recorded("CharacterMesh0", "Body", HAIR);
        AvatarModel model = new AvatarModel(List.of(component), Map.of("Body", body), true);
        assert AvatarModel.HairFilter.of(model, List.of(recorded("CharacterMesh0", "Body", "Root", "Bip001-Head"))) == null : "No chain, no filter";
        AvatarModel.HairFilter still = AvatarModel.HairFilter.of(model, List.of(component));
        AvatarRecording.Pose straight = tail(new Quaterniond(), new Vector3d(0, 0, 170), 0, 0);
        float[] before = straight.local()[0].clone();
        for (int k = 0; k < 5; k++) still.apply(straight, 1000.0 / 60);
        for (int i = 0; i < before.length; i++) assert Math.abs(before[i] - straight.local()[0][i]) < 1e-3 : "A still tail moves at " + i;

        AvatarModel.HairFilter filter = AvatarModel.HairFilter.of(model, List.of(component));
        List<Vector3d[]> raw = new ArrayList<>(), filtered = new ArrayList<>();
        double twistRaw = 0, twistFiltered = 0;
        Quaterniond previousRaw = null, previousFiltered = null;
        for (int f = 0; f < 300; f++) {
            Quaterniond head = new Quaterniond().rotateZ(f * 0.01).rotateX(0.2 * Math.sin(f * 0.05));
            AvatarRecording.Pose pose = tail(head, new Vector3d(f * 0.5, 0, 170), 3, 0.8, 15 * Math.sin(f * 0.1)); // A swing, and noise.
            raw.add(joints(pose));
            Quaterniond middleRaw = rotation(pose, 6);
            filter.apply(pose, 1000.0 / 60);
            filtered.add(joints(pose));
            Quaterniond middle = rotation(pose, 6);
            for (int k = 2; k < HAIR.length; k++) assert Math.abs(rotation(pose, k).lengthSquared() - 1) < 1e-4 : "unit rotations";
            if (previousRaw != null) {
                twistRaw += middleRaw.difference(previousRaw, new Quaterniond()).angle();
                twistFiltered += middle.difference(previousFiltered, new Quaterniond()).angle();
            }
            previousRaw = middleRaw;
            previousFiltered = middle;
        }
        double[] a = metrics(raw), b = metrics(filtered);
        assert b[0] < 0.6 * a[0] && b[1] < 0.6 * a[1] && twistFiltered < 0.5 * twistRaw
                : String.format("bend %.1f to %.1f deg, reversals %.2f to %.2f, twist %.1f to %.1f", a[0], b[0], a[1], b[1], twistRaw, twistFiltered);

        // A step of the tail's joints: three 60 Hz frames or one 50 ms pose leave the time average at the same place.
        AvatarModel.HairFilter fast = AvatarModel.HairFilter.of(model, List.of(component)), slow = AvatarModel.HairFilter.of(model, List.of(component));
        AvatarRecording.Pose p60 = tail(new Quaterniond(), new Vector3d(), 0, 0), p20 = tail(new Quaterniond(), new Vector3d(), 0, 0);
        fast.apply(p60, 0);
        slow.apply(p20, 0);
        for (int k = 0; k < 3; k++) { p60 = tail(new Quaterniond(), new Vector3d(), 0, 0, 20); fast.apply(p60, 1000.0 / 60); }
        p20 = tail(new Quaterniond(), new Vector3d(), 0, 0, 20);
        slow.apply(p20, 50);
        for (int i = 0; i < p60.local()[0].length; i++) assert Math.abs(p60.local()[0][i] - p20.local()[0][i]) < 1e-3 : "rate-dependent at " + i;
        assert Math.abs(p20.local()[0][10 * 10 + 4] - 20 * 0.875) < 1e-3 : "One 50 ms pose moves 1 - 0.5^3 of a step: " + p20.local()[0][104];
    }
    /** A tail hanging along -Y in head space, its joints shifted along X by noise cm and twisted about the chain by up to twist radians. */
    private static AvatarRecording.Pose tail(Quaterniond head, Vector3d at, double noise, double twist) {
        return tail(head, at, noise, twist, 0);
    }
    private static AvatarRecording.Pose tail(Quaterniond head, Vector3d at, double noise, double twist, double shift) {
        float[] local = new float[10 * HAIR.length];
        put(local, 0, new Quaterniond(), new Vector3d());
        put(local, 1, head, at);
        for (int k = 2; k < HAIR.length; k++) {
            Vector3d joint = new Vector3d(shift + (k > 2 ? noise * (random.nextDouble() * 2 - 1) : 0), -10 * (k - 1), 0);
            Quaterniond rotation = new Quaterniond(head).rotateY(twist * (random.nextDouble() * 2 - 1));
            put(local, k, rotation, head.transform(joint).add(at));
        }
        return new AvatarRecording.Pose(new float[][] {AvatarModelTest.IDENTITY.clone()}, new float[][] {local}, new float[][] {new float[0]}, new float[1][]);
    }
    private static void put(float[] local, int bone, Quaterniond q, Vector3d t) {
        float[] v = {(float) q.x, (float) q.y, (float) q.z, (float) q.w, (float) t.x, (float) t.y, (float) t.z, 1, 1, 1};
        System.arraycopy(v, 0, local, 10 * bone, 10);
    }
    private static Quaterniond rotation(AvatarRecording.Pose pose, int bone) {
        float[] l = pose.local()[0];
        return new Quaterniond(l[10 * bone], l[10 * bone + 1], l[10 * bone + 2], l[10 * bone + 3]);
    }
    /** The chain's joints in head space. */
    private static Vector3d[] joints(AvatarRecording.Pose pose) {
        float[] l = pose.local()[0];
        Quaterniond inverse = rotation(pose, 1).invert();
        Vector3d head = new Vector3d(l[14], l[15], l[16]);
        Vector3d[] joints = new Vector3d[HAIR.length - 2];
        for (int k = 2; k < HAIR.length; k++) joints[k - 2] = inverse.transform(new Vector3d(l[10 * k + 4], l[10 * k + 5], l[10 * k + 6]).sub(head));
        return joints;
    }
    /** The median joint bend in degrees and the share of joint velocities reversing from frame to frame (the research's filt.py). */
    private static double[] metrics(List<Vector3d[]> frames) {
        List<Double> bends = new ArrayList<>();
        int reversals = 0, steps = 0;
        for (int f = 0; f < frames.size(); f++) {
            Vector3d[] p = frames.get(f);
            for (int k = 1; k + 1 < p.length; k++)
                bends.add(Math.toDegrees(new Vector3d(p[k]).sub(p[k - 1]).angle(new Vector3d(p[k + 1]).sub(p[k]))));
            if (f < 2) continue;
            for (int k = 0; k < p.length; k++) {
                Vector3d v1 = new Vector3d(p[k]).sub(frames.get(f - 1)[k]), v0 = new Vector3d(frames.get(f - 1)[k]).sub(frames.get(f - 2)[k]);
                steps++;
                if (v1.dot(v0) < 0) reversals++;
            }
        }
        bends.sort(null);
        return new double[] {bends.get(bends.size() / 2), (double) reversals / steps};
    }

    /**
     * The clock through a remote's own accept path: the sender's clock 3 s ahead and drifting, transit 80 ms with ±20 ms
     * jitter, poses every 50 ms. Drawn at RemoteEves.displayTime on a ring of RING poses, as AvatarView does, the shown
     * publication time never steps back and stays among the received poses (at most half an interval past the newest,
     * in under a tenth of the frames). The echo's second of delay goes into the floor the same way.
     */
    static void clock() {
        for (double extra : new double[] {0, 1000}) {
            RemoteEves.Remote remote = new RemoteEves.Remote(UUID.randomUUID());
            remote.table = new NetPose.Table(1, 0, 0, 0, 100, "minecraft:overworld", List.of(recorded("CharacterMesh0", "Body", "Root")),
                    new long[1], List.of(), false);
            AvatarRecording.Pose[] ring = new AvatarRecording.Pose[RemoteEves.RING];
            double[] times = new double[RemoteEves.RING];
            int newest = -1, count = 0, frames = 0, late = 0;
            double viewer = 0, previousArrival = 0, previousShown = Double.NEGATIVE_INFINITY;
            AvatarRecording.Pose shown = pose(0);
            for (int k = 0; k < 2400; k++) {
                long publication = Math.round(50.0 * k + 3000 - 0.0005 * 50.0 * k);
                double arrival = Math.max(previousArrival, 50.0 * k + 80 + extra + (random.nextDouble() * 40 - 20)); // TCP keeps order.
                for (; viewer < arrival; viewer += 1000.0 / 144) {
                    if (count < 4) continue;
                    double at = RemoteEves.displayTime(viewer, remote.floor, remote.interval);
                    AvatarView.interpolate(ring, times, newest, count, at, shown);
                    double value = shown.world()[0][4];
                    assert value >= previousShown - 1e-6 : "steps back at " + viewer;
                    assert value >= times[(newest + 1) % RemoteEves.RING] - 1e-6 && value <= times[newest] + 0.5 * remote.interval + 1 : value;
                    if (at > times[newest]) late++;
                    previousShown = value;
                    frames++;
                }
                remote.accept(new NetPose.Pose(k + 1, publication, new int[] {NetPose.WORLD}, new float[10]), 100, arrival, (long) (arrival * 1e6));
                previousArrival = arrival;
                newest = (newest + 1) % RemoteEves.RING;
                ring[newest] = pose(publication);
                times[newest] = publication;
                count = Math.min(count + 1, RemoteEves.RING);
            }
            // Past the newest pose only while a pose is late beyond the 1.5 intervals' margin (jitter of 40 ms against 75).
            assert frames > 15000 && late < frames / 10 : late + " of " + frames + " frames past the newest pose";
            assert Math.abs(remote.interval - 50) < 1 && remote.stalls == 0 && remote.floor > -3000 + 60 + extra && remote.floor < -3000 + 160 + extra
                    : remote.interval + " " + remote.stalls + " " + remote.floor;
        }
    }
    /** A one-component pose whose world X is a publication time, so interpolation shows the time it draws. */
    private static AvatarRecording.Pose pose(double time) {
        float[] world = AvatarModelTest.IDENTITY.clone();
        world[4] = (float) time;
        return new AvatarRecording.Pose(new float[][] {world}, new float[][] {null}, new float[][] {null}, new float[1][]);
    }

    /** netrecord's file: a round trip, then refusals of another file, no table first, a bad kind, time or length, a truncation. */
    static void recording() {
        var entries = List.of(new RemoteEves.Entry(RemoteEves.TABLE, 0, new byte[] {1, 2, 3}), new RemoteEves.Entry(RemoteEves.POSE, 40, new byte[] {4}),
                new RemoteEves.Entry(RemoteEves.POSE, 90, new byte[] {5, 6}));
        byte[] bytes = RemoteEves.recording(entries);
        var back = RemoteEves.recording(bytes);
        assert back.size() == 3 && back.get(2).millis() == 90 && Arrays.equals(back.get(2).data(), new byte[] {5, 6}) && back.get(0).kind() == RemoteEves.TABLE;
        refused(Arrays.copyOf(bytes, bytes.length - 1), "truncated");
        byte[] other = bytes.clone();
        other[0] = 'X';
        refused(other, "another file");
        refused(RemoteEves.recording(List.of(entries.get(1), entries.get(2))), "no table first");
        refused(RemoteEves.recording(List.of(entries.get(0))), "no pose");
        refused(RemoteEves.recording(List.of(entries.get(0), new RemoteEves.Entry((byte) 'Q', 1, new byte[] {1}))), "kind");
        refused(RemoteEves.recording(List.of(entries.get(0), entries.get(2), entries.get(1))), "time going back");
        refused(RemoteEves.recording(List.of(entries.get(0), new RemoteEves.Entry(RemoteEves.POSE, 5, new byte[NetPose.MAX_POSE + 1]))), "length");
    }
    private static void refused(byte[] data, String why) {
        try {
            RemoteEves.recording(data);
            throw new AssertionError("Accepted a recording with " + why);
        } catch (IllegalArgumentException expected) { }
    }
}
