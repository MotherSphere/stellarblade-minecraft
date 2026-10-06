package org.stellarbridge;

import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Map;
import org.joml.Matrix4d;

/** Pose stream table grammar, the pose mapping against a simulated native producer, and component-space skinning. */
public final class AvatarStreamTest {
    static final String RUN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    public static void main(String[] args) throws Exception {
        if (args.length == 2) { interop(Path.of(args[0]), Integer.parseInt(args[1])); return; }
        String fixture = Files.readString(Path.of("protocol/fixtures/pose-components.txt"));
        AvatarStream.Table table = AvatarStream.parse(fixture);
        assert table != null && table.generation() == 1 && table.anchor() == null && table.session().equals("none") && !table.controller();
        assert AvatarStream.parse(fixture.replace("CONTROLLER none", "CONTROLLER 00007f0000005000 00007f0000006000 00000318")).controller();
        for (String offset : new String[] {"00000319", "00004000", "00000000", "318"})
            assert AvatarStream.parse(fixture.replace("CONTROLLER none", "CONTROLLER 00007f0000005000 00007f0000006000 " + offset)) == null;
        assert table.floats() == 52 && table.components().size() == 2;
        var body = table.components().get(0);
        var face = table.components().get(1);
        assert body.recorded() && body.bones().length == 3 && body.bones()[2].equals("Bip001-Head") && body.weights() == 0;
        assert !face.recorded() && face.leader().equals("CharacterMesh0") && face.weights() == 2 && face.morphs()[1].equals("blink");
        String anchored = fixture.replace("SESSION none\nANCHOR none", "SESSION " + "b".repeat(32) + "\nANCHOR 1.5 -2 3 100");
        assert AvatarStream.parse(anchored).anchor()[3] == 100 && AvatarStream.parse(anchored).anchor()[0] == 1.5;
        for (String bad : new String[] {fixture.replace("BONES 1 Root Bip001 Bip001-Head", "BONES 1 Root Bip001"),
                fixture.replace("COUNT 2", "COUNT 3"), fixture.replace("END 1", "END 2"), fixture.substring(0, fixture.length() - 1),
                fixture.replace("ANCHOR none", "ANCHOR 1 2 3 0"), fixture.replace("00007f0000001000", "7f0000001000"),
                fixture.replace(" 3 0 CharacterMesh0", " 3 0 CharacterMesh0").replace("CH_P_EVE_52_TypeB -", "CH_P_EVE_52_TypeB Leader")})
            assert AvatarStream.parse(bad) == null : bad;
        String second = fixture.replace("GENERATION 1", "GENERATION 2").replace("END 1", "END 2");
        assert AvatarStream.select(fixture, second, RUN).generation() == 2 && AvatarStream.select(second, null, RUN).generation() == 2;
        assert AvatarStream.select(fixture.replace(RUN, "c".repeat(32)), null, RUN) == null : "Another run's table is ignored";

        // Steering: Minecraft yaw + 90 is Unreal yaw; the left stick turns by the error, the right stick closes it.
        assert java.util.Arrays.equals(ControlBridge.steer(0, 90, 0, 32767), new int[] {0, 32767, 0});
        assert java.util.Arrays.equals(ControlBridge.steer(0, 0, 0, 32767), new int[] {32767, 0, 32767}) : "Camera 90 degrees left: forward is stick right";
        assert java.util.Arrays.equals(ControlBridge.steer(0, 180, 0, 32767), new int[] {-32767, 0, -32767});
        assert java.util.Arrays.equals(ControlBridge.steer(350, 80, 23170, 23170), new int[] {23170, 23170, 0}) : "Angles wrap";
        assert ControlBridge.steer(0, 80, 0, 0)[2] == (int) Math.round(10 / ControlBridge.STEER_DEGREES * 32767);
        assert ControlBridge.steer(0, 89.5, 0, 0)[2] == 0 : "A sub-degree error is not chased";
        int[] diagonal = ControlBridge.steer(0, 45, 23170, 23170);
        assert Math.abs(diagonal[0] - 32767) <= 1 && Math.abs(diagonal[1]) <= 1 : "Rotation keeps the diagonal's length";
        // Display interpolation between two poses.
        var single = new AvatarStream.Table(RUN, 1, "none", null, false, java.util.List.of(new AvatarRecording.Component("C", "M", null,
                new String[] {"Root"}, new String[] {"m"}, 1)), 21);
        var start = AvatarStream.emptyPose(single);
        var end = AvatarStream.emptyPose(single);
        var mid = AvatarStream.emptyPose(single);
        start.world()[0][3] = end.world()[0][3] = 1; start.local()[0][3] = end.local()[0][3] = 1;
        end.world()[0][4] = 10; end.weights()[0][0] = 1;
        AvatarView.blend(start, end, 0.25f, mid);
        assert mid.world()[0][4] == 2.5f && mid.weights()[0][0] == 0.25f && mid.world()[0][3] == 1;
        // Interpolation on publication times: bracketing samples, oldest clamp, bounded extrapolation, ring wrap.
        AvatarRecording.Pose[] ring = new AvatarRecording.Pose[4];
        double[] times = new double[4];
        for (int i = 0; i < 4; i++) {
            ring[i] = AvatarStream.emptyPose(single);
            ring[i].world()[0][3] = ring[i].local()[0][3] = 1;
        }
        int[] order = {2, 3, 0}; // Newest at index 0 after wrapping.
        double[] stamps = {0, 16, 32};
        for (int k = 0; k < 3; k++) { times[order[k]] = stamps[k]; ring[order[k]].world()[0][4] = (float) stamps[k]; }
        AvatarView.interpolate(ring, times, 0, 3, 8, mid);
        assert mid.world()[0][4] == 8 : mid.world()[0][4];
        AvatarView.interpolate(ring, times, 0, 3, 24, mid);
        assert mid.world()[0][4] == 24;
        AvatarView.interpolate(ring, times, 0, 3, 40, mid);
        assert mid.world()[0][4] == 40 : "Extrapolates up to half an interval";
        AvatarView.interpolate(ring, times, 0, 3, 90, mid);
        assert mid.world()[0][4] == 40 : "Extrapolation is bounded";
        AvatarView.interpolate(ring, times, 0, 3, -5, mid);
        assert mid.world()[0][4] == 0 : "Older than every sample shows the oldest";
        AvatarView.interpolate(ring, times, 0, 1, 50, mid);
        assert mid.world()[0][4] == 32 : "A single sample is shown as is";

        Path directory = Files.createTempDirectory("stellar-pose-");
        try {
            long now = System.currentTimeMillis();
            try (var stream = new AvatarStream(directory, RUN, now)) {
                assert Files.size(stream.path) == AvatarStream.TOTAL;
                assert Files.readString(directory.resolve("eve-pose-request.txt"))
                        .equals("SBMC_POSE_REQUEST 1\nRUN " + RUN + "\nTIME " + now + "\nSTATE active\nEND\n");
                Files.writeString(directory.resolve("eve-pose-components.1"), fixture);
                assert stream.table().generation() == 1;
                try (var channel = FileChannel.open(stream.path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                    var map = channel.map(FileChannel.MapMode.READ_WRITE, 0, AvatarStream.TOTAL).order(ByteOrder.LITTLE_ENDIAN);
                    assert map.getInt(96) != 0 && (int) RawFrameBus.INT.getAcquire(map, 256) == 1 && map.getInt(16) == 65536;
                    assert stream.poll(table, now, System.nanoTime()) == null : "Unclaimed: nothing yet";
                    // The native claim, then one published slot.
                    assert (boolean) RawFrameBus.INT.compareAndSet(map, 192, 0, 777);
                    RawFrameBus.LONG.setRelease(map, 208, now);
                    RawFrameBus.INT.setRelease(map, 196, 1);
                    publish(map, 1, 1, 1, now, new int[] {7, 5}, payload());
                    map.putInt(AvatarStream.HEADER + AvatarStream.SLOT + 36, 1).putFloat(AvatarStream.HEADER + AvatarStream.SLOT + 44, 135);
                    var sample = stream.poll(table, now, System.nanoTime());
                    assert sample.counter() == 1 && sample.status()[0] == 7 && sample.payload()[4] == 100 && sample.rotation()[1] == 135;
                    assert (long) RawFrameBus.LONG.getAcquire(map, 272) == 1 : "Accepted counter is acknowledged";
                    assert stream.poll(table, now, System.nanoTime()) == null : "No new slot";

                    AvatarMesh mesh = AvatarMesh.parse("CH_P_EVE_52_TypeB", AvatarModelTest.mesh("Bip001", b -> { }));
                    var renamed = new AvatarRecording.Component("CharacterMesh0", "CH_P_EVE_52_TypeB", null,
                            new String[] {"Root", "Bip001"}, new String[0], 0);
                    var twoBones = new AvatarStream.Table(RUN, 1, "none", null, false, java.util.List.of(renamed), 30);
                    AvatarModel model = new AvatarModel(twoBones.components(), Map.of("CH_P_EVE_52_TypeB", mesh));
                    var pose = AvatarStream.emptyPose(twoBones);
                    float[] values = new float[30];
                    System.arraycopy(new float[] {0, 0, 0, 1, 100, 200, 300, 1, 1, 1}, 0, values, 0, 10);
                    float root90 = (float) Math.sqrt(0.5);
                    // Component space: root turned 90 degrees about X; the child's component transform already includes it.
                    System.arraycopy(new float[] {root90, 0, 0, root90, 0, 0, 0, 1, 1, 1}, 0, values, 10, 10);
                    System.arraycopy(new float[] {root90, 0, 0, root90, 0, -100, 0, 1, 1, 1}, 0, values, 20, 10);
                    assert AvatarStream.decode(twoBones, new AvatarStream.Sample(2, now, 1, new int[] {3}, values, null), model, pose);
                    FloatBuffer[] palettes = {FloatBuffer.allocate(24)};
                    model.palettes(pose, new Matrix4d(AvatarModel.UNREAL_TO_MINECRAFT), palettes, true);
                    AvatarModelTest.assertPoint(palettes[0], 1, 0, 0, 100, 1, 3, 1);
                    assert !AvatarStream.decode(twoBones, new AvatarStream.Sample(3, now, 1, new int[] {1}, values, null), model, pose)
                            : "A drawn component without bones is incomplete";

                    // A slot of another generation asks the caller to reload the table.
                    publish(map, 0, 2, 2, now, new int[] {7, 5}, payload());
                    var newer = stream.poll(table, now, System.nanoTime());
                    assert newer.generation() == 2 && newer.payload() == null;
                    // Native statistics.
                    map.putInt(224, 40).putInt(228, 90);
                    RawFrameBus.LONG.setRelease(map, 216, 2L);
                    RawFrameBus.LONG.setRelease(map, 232, 120L);
                    long[] statistics = stream.statistics();
                    assert statistics[0] == 2 && statistics[1] == 40 && statistics[2] == 90 && statistics[3] == 60;
                    // Native release, then reaping deletes the mapping and the request says stopped.
                    RawFrameBus.INT.setRelease(map, 196, 2);
                    try { stream.poll(table, now, System.nanoTime()); throw new AssertionError("A stopped producer must end the stream"); }
                    catch (java.io.IOException expected) { assert expected.getMessage().contains("stopped"); }
                    assert Files.readString(directory.resolve("eve-pose-request.txt")).contains("STATE stopped");
                    assert stream.reap() && !Files.exists(stream.path);
                }
            }
            // A stream the producer never claimed is cancelled and removed.
            try (var unclaimed = new AvatarStream(directory, "d".repeat(32), System.currentTimeMillis())) {
                assert unclaimed.reap() && !Files.exists(unclaimed.path);
            }
        } finally {
            try (var files = Files.walk(directory)) { files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete()); }
        }
        System.out.println("PASS avatar stream: shared table grammar, newest run slot, native mapping protocol, decoding, component-space skinning, release, steering and display interpolation");
    }

    /** Reads poses from the external native fixture (pose_stream_fixture), which plays Lua and the game thread. */
    private static void interop(Path directory, int wanted) throws Exception {
        Files.createDirectories(directory);
        String run = "e".repeat(32);
        try (var stream = new AvatarStream(directory, run, System.currentTimeMillis())) {
            System.out.println("READY " + directory + " " + run + " " + wanted);
            long until = System.nanoTime() + 30_000_000_000L, beat = 0;
            AvatarStream.Table table = null;
            int decoded = 0;
            float lastBone = 0;
            while (decoded < wanted) {
                if (System.nanoTime() >= until) throw new AssertionError("Native pose producer deadline");
                if (System.nanoTime() - beat > 500_000_000L) { stream.heartbeat(System.currentTimeMillis()); beat = System.nanoTime(); }
                var sample = stream.poll(table, System.currentTimeMillis(), System.nanoTime());
                if (sample == null) { Thread.sleep(1); continue; }
                if (sample.payload() == null) { table = stream.table(); continue; }
                assert table.components().size() == 2 && sample.status()[0] == (AvatarStream.WORLD | AvatarStream.BONES)
                        && sample.status()[1] == (AvatarStream.WORLD | AvatarStream.MORPHS) : java.util.Arrays.toString(sample.status());
                float[] p = sample.payload();
                assert p[4] == 100 && p[5] == 200 && p[6] == 300 && p[13] == 1 && p[10 + 10 + 6] == 1 : "World and bone transforms survive";
                float bone = p[10 + 10 + 4];
                assert bone > lastBone && (p[51] == 0 || p[51] == 1) : "Poses arrive in order";
                lastBone = bone;
                decoded++;
            }
            stream.stop();
            long release = System.nanoTime() + 10_000_000_000L;
            while (!stream.reap()) {
                if (System.nanoTime() > release) throw new AssertionError("Native producer did not release the run");
                Thread.sleep(10);
            }
            System.out.println("PASS pose interop: " + decoded + " ordered native poses read, run released and deleted");
        }
    }

    /** CharacterMesh0 world + 3 bones, then Mesh_Face world + 2 morph weights. */
    static float[] payload() {
        float[] values = new float[52];
        System.arraycopy(new float[] {0, 0, 0, 1, 100, 200, 300, 1, 1, 1}, 0, values, 0, 10);
        for (int k = 0; k < 3; k++) values[10 + 10 * k + 3] = 1;
        values[40 + 3] = 1;
        values[50] = 0.25f;
        values[51] = 1;
        return values;
    }

    /** Writes a native slot into back and exchanges it into the middle, as the adapter does. */
    static void publish(java.nio.ByteBuffer map, int back, long counter, long generation, long now, int[] status, float[] payload) {
        int at = AvatarStream.HEADER + back * AvatarStream.SLOT;
        for (int i = 0; i < AvatarStream.SLOT_HEADER; i++) map.put(at + i, (byte) 0);
        map.putLong(at, counter).putLong(at + 8, now).putLong(at + 16, generation).putInt(at + 24, 1)
                .putInt(at + 28, status.length).putInt(at + 32, payload.length);
        for (int i = 0; i < status.length; i++) map.putInt(at + 64 + 4 * i, status[i]);
        for (int i = 0; i < payload.length; i++) map.putFloat(at + AvatarStream.SLOT_HEADER + 4 * i, payload[i]);
        RawFrameBus.INT.getAndSet(map, 128, back | 4);
    }
}
