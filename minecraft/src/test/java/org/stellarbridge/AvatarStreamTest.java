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
        String controller = "CONTROLLER 00007f0000005000 00007f0000006000 00000318 00007f0000007000 00007f0000008000 2.5 -2.5";
        assert AvatarStream.parse(fixture.replace("CONTROLLER none", controller)).controller();
        for (String scales : new String[] {"2.5 0", "NaN -2.5", "2.5 1e9", "2.5 0x1p1", "2.5"})
            assert AvatarStream.parse(fixture.replace("CONTROLLER none", controller.replace("2.5 -2.5", scales))) == null;
        for (String offset : new String[] {"00000319", "00004000", "00000000", "318"})
            assert AvatarStream.parse(fixture.replace("CONTROLLER none", controller.replace("00000318", offset))) == null;
        assert table.floats() == 52 && table.components().size() == 2;
        // Material parameters follow the components in the table and in the payload.
        String glowLine = "PARAMETER 1 1 Edge Opacity 00007f0000009000 00007f000000a000 00000168 2 4711 1\n";
        var glowing = AvatarStream.parse(fixture.replace("PARAMETERS 0\n", "PARAMETERS 1\n" + glowLine));
        assert glowing != null && glowing.parameters().size() == 1 && glowing.floats() == 53 && glowing.componentFloats() == 52;
        for (String bad : new String[] {glowLine.replace(" 1 1 Edge", " 1 3 Edge"), glowLine.replace("00000168", "00000164"),
                glowLine.replace(" 4711 1", " 4711 2"), glowLine.replace(" 4711 1", " 0 1"), glowLine.replace(" 2 4711", " 1024 4711")})
            assert AvatarStream.parse(fixture.replace("PARAMETERS 0\n", "PARAMETERS 1\n" + bad)) == null : bad;
        assert AvatarStream.parse(fixture.replace("PARAMETERS 0\n", "")) == null : "The parameter count is required";
        float[] glowPayload = new float[53];
        glowPayload[52] = 0.75f;
        var glowSample = new AvatarStream.Sample(1, 1, 1, new int[] {7, 5}, glowPayload, null, false, Float.NaN);
        assert AvatarStream.parameters(glowing, glowSample).get("Edge/Opacity")[0] == 0.75f;
        // Effects: the layout line and its assets, then MAX_EFFECTS records after the parameters.
        String effectLines = "EFFECTS 00000130 000006a8 000004b0 00000088 10 00007f000000b000 00007f000000c000 1\n"
                + "EFFECT 1 00007f000000d000 NS_A_Weapon_Signal_Sword\n";
        var sparkling = AvatarStream.parse(fixture.replace("EFFECTS none\n", effectLines));
        assert sparkling != null && sparkling.effects().equals(java.util.List.of("NS_A_Weapon_Signal_Sword")) && sparkling.effectRecords()
                && sparkling.floats() == 52 + AvatarStream.MAX_EFFECTS * AvatarStream.EFFECT_FLOATS && sparkling.componentFloats() == 52;
        assert AvatarStream.parse(fixture.replace("EFFECTS none\n", effectLines.replace(" 10 ", " 00 "))) == null : "A zero mask";
        assert AvatarStream.parse(fixture.replace("EFFECTS none\n", effectLines.replace(" 1\nEFFECT", " 2\nEFFECT"))) == null : "Missing asset";
        assert AvatarStream.parse(fixture.replace("EFFECTS none\n", "")) == null : "The effects line is required";
        float[] effectPayload = new float[sparkling.floats()];
        java.util.Arrays.fill(effectPayload, 52, effectPayload.length, 0);
        for (int r = 0; r < AvatarStream.MAX_EFFECTS; r++) effectPayload[52 + r * AvatarStream.EFFECT_FLOATS] = -1;
        effectPayload[52] = 0;
        effectPayload[52 + 5] = 100;
        effectPayload[52 + 7] = 300;
        var effects = AvatarStream.effects(sparkling, new AvatarStream.Sample(1, 1, 1, new int[] {7, 5}, effectPayload, null, false, Float.NaN));
        assert effects.size() == 1 && effects.get(0).name().equals("NS_A_Weapon_Signal_Sword") && effects.get(0).x() == 100 && effects.get(0).z() == 300;
        assert AvatarEffects.look("NS_A_Weapon_Signal_Sword") == AvatarEffects.Look.SIGNAL && AvatarEffects.look("P_A_AnimTrail_SwordLightAtk_01") == null
                && AvatarEffects.look("NS_A_ParTrail_SwordDefaultAtk_01") == AvatarEffects.Look.SPARKS
                && AvatarEffects.look("NS_PC_SpinTrail_01") == AvatarEffects.Look.SWEEP && AvatarEffects.look("NS_Unknown") == AvatarEffects.Look.GLINT;
        // Sprite recipes and trail styles follow the game's effect assets.
        assert AvatarSprites.recipe("P_A_AnimTrail_SwordLightAtk_01").isEmpty() : "Cascade trails are the ribbon's";
        assert AvatarSprites.recipe("NS_A_ParTrail_SwordDefaultAtk_01").getFirst().shape() == AvatarSprites.Shape.STREAK;
        var signal = AvatarSprites.recipe("NS_A_HoldBurst_Signal");
        assert signal.size() == 4 && signal.getFirst().shape() == AvatarSprites.Shape.HORIZON && signal.getFirst().r() > signal.getFirst().g() * 5
                : "The charged attack's signal is a red horizontal flare first";
        assert AvatarSprites.recipe("NS_A_Weapon_Electric_01").getFirst().place() == AvatarSprites.Place.BLADE;
        assert AvatarTrail.Style.of("P_A_AnimTrail_SwordStrongAtk_01") == AvatarTrail.Style.STRONG
                && AvatarTrail.Style.of("P_A_AnimTrail_SwordThrust_01") == AvatarTrail.Style.THRUST
                && AvatarTrail.Style.of("P_A_AnimTrail_SwordLightAtk_01") == AvatarTrail.Style.LIGHT && AvatarTrail.Style.of("NS_A_KickCharge") == null;
        // Combat: the defense at an impact from the latest presses, strike damage, and the blade's sweep through a box.
        long impact = 10_000_000_000L;
        assert AvatarCombat.defense(impact, impact - 200_000_000L, Long.MIN_VALUE, false) == AvatarCombat.Defense.PERFECT_DODGE
                : "A dodge at 2.8 s against an attack landing at 3 s";
        assert AvatarCombat.defense(impact, impact - 400_000_000L, Long.MIN_VALUE, false) == AvatarCombat.Defense.DODGE;
        assert AvatarCombat.defense(impact, impact - 900_000_000L, Long.MIN_VALUE, true) == AvatarCombat.Defense.GUARD;
        assert AvatarCombat.defense(impact, impact - 200_000_000L, impact - 100_000_000L, true) == AvatarCombat.Defense.PERFECT_PARRY;
        assert AvatarCombat.defense(impact, Long.MIN_VALUE, Long.MIN_VALUE, false) == AvatarCombat.Defense.HIT;
        assert AvatarCombat.defense(impact, impact + 1, Long.MIN_VALUE, false) == AvatarCombat.Defense.HIT : "A press after the impact";
        assert AvatarCombat.damage(AvatarTrail.Style.STRONG, true) == 2 * AvatarCombat.STRONG && AvatarCombat.damage(null, false) == AvatarCombat.SWING;
        var zombie = new net.minecraft.util.math.Box(-0.3, 0, -0.3, 0.3, 1.95, 0.3);
        assert !AvatarCombat.sweeps(zombie, new org.joml.Vector3d(-1, 1, -1), new org.joml.Vector3d(-1, 1, -2),
                new org.joml.Vector3d(1, 1, -1), new org.joml.Vector3d(1, 1, -2)) : "A swing in front of the box";
        assert AvatarCombat.sweeps(zombie, new org.joml.Vector3d(-0.5, 1, 1), new org.joml.Vector3d(-0.5, 1, -1),
                new org.joml.Vector3d(0.5, 1, 1), new org.joml.Vector3d(0.5, 1, -1)) : "A blade crossing the box between two frames";
        // Stellar Blade's HUD: a bar's lit columns, a gauge's cubes and quarters.
        assert AvatarHud.lit(20, 20) == 27 && AvatarHud.lit(1, 20) == 2 && AvatarHud.lit(0, 20) == 0 && AvatarHud.lit(5, 0) == 0;
        assert java.util.Arrays.equals(AvatarHud.cubes(450, 1000, 16), new int[] {5, 2, 1}) : "Two cubes, then a quarter of the third";
        assert java.util.Arrays.equals(AvatarHud.cubes(130, 1600, 20), new int[] {8, 0, 2}) : "A perfect dodge's 130 Burst";
        assert java.util.Arrays.equals(AvatarHud.cubes(1000, 1000, 16), new int[] {5, 5, 0}) && AvatarHud.cubes(0, 5000, 20)[0] == 20;
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

        // Steering: the left stick turns by the actual angle, the right stick closes the (unwrapped) error.
        assert java.util.Arrays.equals(ControlBridge.steer(0, 0, 32767), new int[] {0, 32767, 0});
        assert java.util.Arrays.equals(ControlBridge.steer(90, 0, 32767), new int[] {32767, 0, 32767}) : "Camera 90 degrees left: forward is stick right";
        assert java.util.Arrays.equals(ControlBridge.steer(-90, 0, 32767), new int[] {-32767, 0, -32767});
        assert ControlBridge.steer(10, 0, 0)[2] == (int) Math.round(10 / ControlBridge.STEER_DEGREES * 32767);
        assert ControlBridge.steer(0.5, 0, 0)[2] == 0 : "A sub-degree error is not chased";
        int[] diagonal = ControlBridge.steer(45, 23170, 23170);
        assert Math.abs(diagonal[0] - 32767) <= 1 && Math.abs(diagonal[1]) <= 1 : "Rotation keeps the diagonal's length";
        int[] behind = ControlBridge.steer(270, 0, 32767);
        assert behind[0] == -32767 && behind[2] == 32767 : "Movement uses the real angle while the camera keeps turning right";
        // Yaw error: Minecraft yaw + 90 is Unreal yaw; the start takes the shortest way, a fast spin never flips direction.
        ControlBridge.resetSteering();
        assert Math.abs(ControlBridge.yawError(350, 80) - 0) < 1e-9 : "Angles wrap at the start";
        ControlBridge.resetSteering();
        assert Math.abs(ControlBridge.yawError(0, 350) - 100) < 1e-9;
        double unreal = 350, minecraft = 0, error = 0;
        for (int frame = 0; frame < 20; frame++) { // Minecraft spins right 40 degrees per frame, Stellar Blade 10.
            minecraft += 40;
            unreal = (unreal + 10) % 360;
            error = ControlBridge.yawError(minecraft, unreal);
            assert error > 0 : "The camera never reverses during a fast right spin (frame " + frame + ", error " + error + ")";
        }
        assert error < 360 : "At most one turn behind";

        // Display interpolation between two poses.
        var single = new AvatarStream.Table(RUN, 1, "none", null, false, java.util.List.of(new AvatarRecording.Component("C", "M", null,
                new String[] {"Root"}, new String[] {"m"}, 1)), java.util.List.of(), java.util.List.of(), false, 21);
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
                    assert sample.counter() == 1 && sample.status()[0] == 7 && sample.payload()[4] == 100 && sample.rotation()[1] == 135
                            && !sample.lookApplied() && Float.isNaN(sample.cameraPitch());
                    assert (long) RawFrameBus.LONG.getAcquire(map, 272) == 1 : "Accepted counter is acknowledged";
                    assert stream.poll(table, now, System.nanoTime()) == null : "No new slot";
                    // Minecraft's camera goes to the adapter as one atomic word once the producer claimed the mapping.
                    stream.look(-15, 270, now);
                    assert map.getLong(288) == now && map.getInt(304) == 1 && map.getFloat(296) == -15 && map.getFloat(300) == 270;
                    publish(map, 2, 2, 1, now, new int[] {7, 5}, payload());
                    // Bit 1: turned toward Minecraft's camera; bit 2: dolly set, with the camera's pitch at 52.
                    map.putInt(AvatarStream.HEADER + 2 * AvatarStream.SLOT + 36, 7).putFloat(AvatarStream.HEADER + 2 * AvatarStream.SLOT + 52, -17);
                    var synced = stream.poll(table, now, System.nanoTime());
                    assert synced.lookApplied() && synced.cameraPitch() == -17;
                    stream.lookOff();
                    assert map.getInt(304) == 0;

                    AvatarMesh mesh = AvatarMesh.parse("CH_P_EVE_52_TypeB", AvatarModelTest.mesh("Bip001", b -> { }));
                    var renamed = new AvatarRecording.Component("CharacterMesh0", "CH_P_EVE_52_TypeB", null,
                            new String[] {"Root", "Bip001"}, new String[0], 0);
                    var twoBones = new AvatarStream.Table(RUN, 1, "none", null, false, java.util.List.of(renamed), java.util.List.of(), java.util.List.of(), false, 30);
                    AvatarModel model = new AvatarModel(twoBones.components(), Map.of("CH_P_EVE_52_TypeB", mesh));
                    var pose = AvatarStream.emptyPose(twoBones);
                    float[] values = new float[30];
                    System.arraycopy(new float[] {0, 0, 0, 1, 100, 200, 300, 1, 1, 1}, 0, values, 0, 10);
                    float root90 = (float) Math.sqrt(0.5);
                    // Component space: root turned 90 degrees about X; the child's component transform already includes it.
                    System.arraycopy(new float[] {root90, 0, 0, root90, 0, 0, 0, 1, 1, 1}, 0, values, 10, 10);
                    System.arraycopy(new float[] {root90, 0, 0, root90, 0, -100, 0, 1, 1, 1}, 0, values, 20, 10);
                    assert AvatarStream.decode(twoBones, new AvatarStream.Sample(2, now, 1, new int[] {3}, values, null, false, Float.NaN), model, pose);
                    FloatBuffer[] palettes = {FloatBuffer.allocate(24)};
                    model.palettes(pose, new Matrix4d(AvatarModel.UNREAL_TO_MINECRAFT), palettes, true);
                    AvatarModelTest.assertPoint(palettes[0], 1, 0, 0, 100, 1, 3, 1);
                    assert !AvatarStream.decode(twoBones, new AvatarStream.Sample(3, now, 1, new int[] {1}, values, null, false, Float.NaN), model, pose)
                            : "A drawn component without bones is incomplete";

                    // A slot of another generation asks the caller to reload the table.
                    publish(map, 0, 3, 2, now, new int[] {7, 5}, payload());
                    var newer = stream.poll(table, now, System.nanoTime());
                    assert newer.generation() == 2 && newer.payload() == null;
                    // Native statistics.
                    map.putInt(224, 40).putInt(228, 90);
                    RawFrameBus.LONG.setRelease(map, 216, 2L);
                    RawFrameBus.LONG.setRelease(map, 232, 120L);
                    long[] statistics = stream.statistics();
                    assert statistics[0] == 2 && statistics[1] == 40 && statistics[2] == 90 && statistics[3] == 60;
                    // EVE's gauges travel in the header once the adapter reads them.
                    assert stream.gauges() == null : "Unread gauges";
                    map.putFloat(324, 400).putFloat(328, 1000).putFloat(332, 130).putFloat(336, 1600).putFloat(340, 1);
                    RawFrameBus.INT.setRelease(map, 320, 1);
                    float[] gauges = stream.gauges();
                    assert gauges != null && gauges[0] == 400 && gauges[2] == 130 && gauges[3] == 1600 && gauges[4] == 1;
                    map.putFloat(332, 2000);
                    assert stream.gauges() == null : "A gauge above its maximum is not shown";
                    RawFrameBus.INT.setRelease(map, 320, 0);
                    // Native release, then reaping deletes the mapping and the request says stopped.
                    RawFrameBus.INT.setRelease(map, 196, 2);
                    try { stream.poll(table, now, System.nanoTime()); throw new AssertionError("A stopped producer must end the stream"); }
                    catch (java.io.IOException expected) { assert expected.getMessage().contains("stopped"); }
                    assert Files.readString(directory.resolve("eve-pose-request.txt")).contains("STATE stopped");
                    assert stream.reap() && !Files.exists(stream.path);
                }
            }
            // A refused slot ends the stream: bit 2 without bit 0, an unknown bit, a camera pitch without bit 2, a non-finite one.
            assert refused(directory, table, 4, 0).equals("Invalid controller rotation status");
            assert refused(directory, table, 9, 0).equals("Invalid controller rotation status");
            assert refused(directory, table, 1, -17).equals("Pose mapping reserved bytes changed");
            assert refused(directory, table, 5, Float.NaN).equals("Non-finite camera pitch");
            // Each bit decodes alone: 5 is the dolly set while the yaw follow stopped, 3 a turn without the dolly.
            var held = slot(directory, table, 5, -17);
            assert !held.lookApplied() && held.cameraPitch() == -17;
            var turned = slot(directory, table, 3, 0);
            assert turned.lookApplied() && Float.isNaN(turned.cameraPitch());
            // A stream the producer never claimed is cancelled and removed.
            try (var unclaimed = new AvatarStream(directory, "d".repeat(32), System.currentTimeMillis())) {
                assert unclaimed.reap() && !Files.exists(unclaimed.path);
            }
        } finally {
            try (var files = Files.walk(directory)) { files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete()); }
        }
        System.out.println("PASS avatar stream: shared table grammar, newest run slot, native mapping protocol, decoding, component-space skinning, release, steering, display interpolation and combat");
    }

    /** Why a fresh stream (its run named by status) refuses one native slot with this controller status and camera pitch. */
    private static String refused(Path directory, AvatarStream.Table table, int status, float pitch) throws Exception {
        try {
            slot(directory, table, status, pitch);
            throw new AssertionError("Accepted controller status " + status);
        } catch (java.io.IOException refusal) {
            return refusal.getMessage();
        }
    }

    /** What a fresh stream (its run named by status and pitch) decodes from one native slot with this controller status and camera pitch. */
    private static AvatarStream.Sample slot(Path directory, AvatarStream.Table table, int status, float pitch) throws Exception {
        long now = System.currentTimeMillis();
        try (var stream = new AvatarStream(directory, String.format("%024x%08x", status, Float.floatToRawIntBits(pitch)), now);
             var channel = FileChannel.open(stream.path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var map = channel.map(FileChannel.MapMode.READ_WRITE, 0, AvatarStream.TOTAL).order(ByteOrder.LITTLE_ENDIAN);
            assert (boolean) RawFrameBus.INT.compareAndSet(map, 192, 0, 777);
            RawFrameBus.LONG.setRelease(map, 208, now);
            RawFrameBus.INT.setRelease(map, 196, 1);
            publish(map, 1, 1, 1, now, new int[] {7, 5}, payload());
            map.putInt(AvatarStream.HEADER + AvatarStream.SLOT + 36, status).putFloat(AvatarStream.HEADER + AvatarStream.SLOT + 52, pitch);
            return stream.poll(table, now, System.nanoTime());
        }
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
