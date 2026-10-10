package org.stellarbridge;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;

/**
 * The network pose: codec precision, keyframe and delta chains under loss and reordering, hostile payloads, the
 * names-only table (no process address), the relay's interest tiers and budget, and the clock floor under skew and jitter.
 */
public final class NetPoseTest {
    static final double[] ANCHOR = {-760000.5, -760000.25, -18000};
    static final Random random = new Random(20261009);

    public static void main(String[] args) {
        assert NetPose.MAX_ACTIVE == AvatarStream.MAX_EFFECTS && NetPose.EFFECT_FLOATS == AvatarStream.EFFECT_FLOATS
                && NetPose.MAX_FLOATS == AvatarStream.CAPACITY && NetPose.MAX_BONES == AvatarMesh.MAX_BONES
                && NetPose.WORLD == AvatarStream.WORLD && NetPose.BONES == AvatarStream.BONES && NetPose.MORPHS == AvatarStream.MORPHS
                && NetPose.HIDDEN == AvatarStream.HIDDEN : "The network form keeps the stream's limits and bits";
        NetPose.Table table = table();
        NetPose.Table back = NetPose.Table.decode(table.encode());
        assert Arrays.equals(back.encode(), table.encode()) && back.floats() == table.floats() : "The table round trips";
        precision(table);
        deltas(table);
        hostilePoses(table);
        hostileTables(table);
        addresses();
        relay(table);
        clock();
        System.out.println("Network pose tests passed: precision within 0.01 deg, 1/64 cm and 1/8192 scale, delta chains under loss and"
                + " reordering, hostile poses and tables refused, no process address in the table, relay tiers and budget, clock floor");
    }

    /** Body, face (a follower with morphs), sword (bones and morphs) and the drone; 3 effect assets; the stream's layout adds a parameter. */
    static NetPose.Table table() {
        List<AvatarRecording.Component> components = List.of(
                new AvatarRecording.Component("CharacterMesh0", "CH_P_EVE_52_TypeB", null, names("Bip001-B", 164), new String[0], 0),
                new AvatarRecording.Component("Mesh_Face", "CH_P_EVE_Face_Jali3", "CharacterMesh0", null, names("CTRL_m", 169), 169),
                new AvatarRecording.Component("SBSkeletalMeshComponent_2147482", "CH_W_Sword_01", null, names("Sword_b", 54), names("Mech", 24), 24),
                new AvatarRecording.Component("Drone.CharacterMesh0", "CH_NPC_Drone_Bear", null, names("Drone_b", 53), new String[0], 0));
        return new NetPose.Table(7, 120.5, 64, -33.5, 100, "minecraft:overworld", components, new long[] {1, -2, 3, 0},
                List.of("NS_EVE_Dodge", "NS_A_Weapon_Signal_Sword", "NS_Drone_Muzzle"), true);
    }

    static String[] names(String prefix, int count) {
        String[] names = new String[count];
        for (int i = 0; i < count; i++) names[i] = prefix + i;
        return names;
    }

    /** The stream table's float layout for t: components, one material parameter float, the effect records. */
    static int localFloats(NetPose.Table t) { return t.floats() - NetPose.MAX_ACTIVE * NetPose.EFFECT_FLOATS + 1 + NetPose.MAX_ACTIVE * NetPose.EFFECT_FLOATS; }
    static int effectsAt(NetPose.Table t) { return localFloats(t) - NetPose.MAX_ACTIVE * NetPose.EFFECT_FLOATS; }

    /** A random sample in the stream's layout: random rotations, translations and scales within range, morphs, effects. */
    static float[] sample(NetPose.Table t) {
        float[] p = new float[localFloats(t)];
        int at = 0;
        for (var c : t.components()) {
            transform(p, at, true);
            int bones = c.recorded() ? c.bones().length : 0;
            for (int b = 0; b < bones; b++) transform(p, at + 10 + 10 * b, false);
            for (int w = 0; w < c.weights(); w++) p[at + 10 + 10 * bones + w] = (float) (-0.021 + random.nextDouble() * 1.5);
            at += 10 + 10 * bones + c.weights();
        }
        p[at] = 0.75f; // The material parameter, which never travels.
        int e = effectsAt(t);
        for (int r = 0; r < NetPose.MAX_ACTIVE; r++) p[e + r * NetPose.EFFECT_FLOATS] = -1;
        for (int r = 0; r < 3; r++) effect(p, e + r * NetPose.EFFECT_FLOATS, r, 300);
        effect(p, e + 3 * NetPose.EFFECT_FLOATS, 5, 100); // An index beyond the assets: dropped.
        effect(p, e + 4 * NetPose.EFFECT_FLOATS, 1, 0);
        p[e + 4 * NetPose.EFFECT_FLOATS + 5] = p[4] + 9000; // Beyond 40 m of the body: dropped.
        return p;
    }

    static void transform(float[] p, int at, boolean world) {
        double[] q = new double[4];
        double norm = 0;
        for (int k = 0; k < 4; k++) norm += (q[k] = random.nextGaussian()) * q[k];
        for (int k = 0; k < 4; k++) p[at + k] = (float) (q[k] / Math.sqrt(norm));
        for (int k = 0; k < 3; k++)
            p[at + 4 + k] = (float) (world ? ANCHOR[k] - 255 + random.nextDouble() * 510 : -42 + random.nextDouble() * 211);
        double[] scales = {1, -1.06, 0.049, -7.9 + random.nextDouble() * 15.8};
        for (int k = 0; k < 3; k++) p[at + 7 + k] = (float) scales[random.nextInt(scales.length)];
    }

    static void effect(float[] p, int at, int index, double spread) {
        p[at] = index;
        for (int k = 0; k < 3; k++) p[at + 5 + k] = (float) (p[4 + k] + (random.nextDouble() * 2 - 1) * spread);
        p[at + 4] = 1;
    }

    static NetPose.Pose decode(NetPose.Table t, byte[] data) {
        return new NetPose.Decoder().decode(t, NetPose.header(data), data);
    }

    static void precision(NetPose.Table t) {
        double rotation = 0, translation = 0, world = 0, scale = 0, weight = 0, effect = 0;
        for (int n = 0; n < 200; n++) {
            float[] p = sample(t);
            int[] status = {NetPose.WORLD | NetPose.BONES | 16 /* CLOTH never travels */, NetPose.WORLD | NetPose.MORPHS,
                    NetPose.WORLD | NetPose.BONES | NetPose.MORPHS, n % 2 == 0 ? NetPose.WORLD | NetPose.BONES | NetPose.HIDDEN : 0};
            byte[] data = new NetPose.Encoder(1).encode(t, ANCHOR, status, p, effectsAt(t), 1_760_000_000_000L + n);
            assert data != null && data.length <= NetPose.MAX_POSE;
            NetPose.Pose pose = decode(t, data);
            assert pose.status()[0] == (NetPose.WORLD | NetPose.BONES) && pose.status()[3] == status[3] && pose.publication() == 1_760_000_000_000L + n;
            assert pose.payload().length == t.floats();
            int at = 0;
            for (int i = 0; i < t.components().size(); i++) {
                var c = t.components().get(i);
                int bones = c.recorded() ? c.bones().length : 0;
                if ((status[i] & NetPose.WORLD) != 0) {
                    rotation = Math.max(rotation, angle(p, at, pose.payload(), at));
                    for (int k = 0; k < 3; k++) {
                        world = Math.max(world, Math.abs(pose.payload()[at + 4 + k] - (p[at + 4 + k] - ANCHOR[k])));
                        scale = Math.max(scale, Math.abs(pose.payload()[at + 7 + k] - p[at + 7 + k]));
                    }
                } else assert pose.payload()[at + 3] == 1 && pose.payload()[at + 7] == 1 : "An absent transform is the identity";
                if ((status[i] & NetPose.BONES) != 0)
                    for (int b = 0, k0 = at + 10; b < bones; b++, k0 += 10) {
                        rotation = Math.max(rotation, angle(p, k0, pose.payload(), k0));
                        for (int k = 4; k < 7; k++) translation = Math.max(translation, Math.abs(pose.payload()[k0 + k] - p[k0 + k]));
                        for (int k = 7; k < 10; k++) scale = Math.max(scale, Math.abs(pose.payload()[k0 + k] - p[k0 + k]));
                    }
                if ((status[i] & NetPose.MORPHS) != 0)
                    for (int w = 0, k0 = at + 10 + 10 * bones; w < c.weights(); w++)
                        weight = Math.max(weight, Math.abs(pose.payload()[k0 + w] - p[k0 + w]));
                at += 10 + 10 * bones + c.weights();
            }
            // Effects: the three valid, near ones, in order, at the stream's record positions; the rest index -1.
            var stream = RemoteEves.stream(t);
            var sample = new AvatarStream.Sample(n + 1, pose.publication(), t.generation(), pose.status(), pose.payload(), null, false, Float.NaN, false, 0);
            var effects = AvatarStream.effects(stream, sample);
            assert effects.size() == 3 && effects.get(1).name().equals("NS_A_Weapon_Signal_Sword") : effects;
            for (int r = 0; r < 3; r++) {
                int e = effectsAt(t) + r * NetPose.EFFECT_FLOATS;
                var got = effects.get(r);
                effect = Math.max(effect, Math.max(Math.abs(got.x() - (p[e + 5] - ANCHOR[0])),
                        Math.max(Math.abs(got.y() - (p[e + 6] - ANCHOR[1])), Math.abs(got.z() - (p[e + 7] - ANCHOR[2])))));
            }
        }
        assert rotation <= 0.01 : "rotation error " + rotation + " deg";
        assert translation <= 1.0 / 64 + 1e-6 && world <= 1.0 / 64 : "translation error " + translation + ", world " + world;
        assert scale <= 1.0 / 8192 + 1e-6 : "scale error " + scale;
        assert weight <= 1.0 / 32768 + 1e-7 : "morph error " + weight;
        assert effect <= 1.0 / 16 + 1.0 / 64 : "effect error " + effect;
        System.out.printf("Worst errors: rotation %.5f deg, bone translation %.5f cm, world %.5f cm, scale %.6f, morph %.7f, effect %.4f cm%n",
                rotation, translation, world, scale, weight, effect);
    }

    /**
     * Degrees between two quaternions (either sign), from the chord between them once normalized: 1 - dot² would turn
     * float rounding of the norms (1e-7) into hundredths of a degree.
     */
    static double angle(float[] a, int i, float[] b, int j) {
        double na = 0, nb = 0, dot = 0;
        for (int k = 0; k < 4; k++) {
            na += (double) a[i + k] * a[i + k];
            nb += (double) b[j + k] * b[j + k];
            dot += (double) a[i + k] * b[j + k];
        }
        double sign = dot < 0 ? -1 : 1, chord = 0;
        for (int k = 0; k < 4; k++) chord += Math.pow(a[i + k] / Math.sqrt(na) - sign * b[j + k] / Math.sqrt(nb), 2);
        return Math.toDegrees(4 * Math.asin(Math.sqrt(chord) / 2));
    }

    /** Motion: a rest pose with slow sines on every value, as EVE moves between 50 ms samples. */
    static List<float[]> motion(NetPose.Table t, int count) {
        float[] rest = sample(t);
        List<float[]> poses = new ArrayList<>();
        for (int n = 0; n < count; n++) {
            float[] p = rest.clone();
            for (int k = 0; k < effectsAt(t); k++) {
                if (k % 10 < 4) continue; // Rotations stay unit; only translations, scales and weights drift.
                p[k] += (float) (0.3 * Math.sin(n * 0.05 * 6 + k));
            }
            poses.add(p);
        }
        return poses;
    }

    static final int[] ALL = {NetPose.WORLD | NetPose.BONES, NetPose.WORLD | NetPose.MORPHS, NetPose.WORLD | NetPose.BONES | NetPose.MORPHS,
            NetPose.WORLD | NetPose.BONES};

    static void deltas(NetPose.Table t) {
        List<float[]> poses = motion(t, 30);
        NetPose.Encoder chained = new NetPose.Encoder(10), keys = new NetPose.Encoder(1);
        List<byte[]> wire = new ArrayList<>();
        long keyBytes = 0, deltaBytes = 0;
        NetPose.Decoder decoder = new NetPose.Decoder();
        for (int n = 0; n < poses.size(); n++) {
            byte[] data = chained.encode(t, ANCHOR, ALL, poses.get(n), effectsAt(t), 1000 + 50L * n);
            byte[] key = keys.encode(t, ANCHOR, ALL, poses.get(n), effectsAt(t), 1000 + 50L * n);
            NetPose.Header header = NetPose.header(data);
            assert header.keyframe() == (n % 10 == 0) && header.sequence() == n + 1 && (header.keyframe() || header.base() == n) : header;
            if (header.keyframe()) keyBytes += data.length; else deltaBytes += data.length;
            wire.add(data);
            // Every delta rebuilds exactly the keyframe a keyframe-only sender would have sent.
            assert Arrays.equals(decoder.decode(t, header, data).payload(), decode(t, key).payload()) : "pose " + n;
        }
        assert chained.keys == 3 && chained.deltas == 27;
        System.out.printf("Synthetic motion: keyframes %d B, deltas %d B on average (the delta's share of a stream at 20 Hz: %.0f%%)%n",
                keyBytes / 3, deltaBytes / 27, 100.0 * (keyBytes + deltaBytes) / (10.0 * keyBytes));
        assert deltaBytes / 27 < keyBytes / 3 : "Deltas are smaller than keyframes";

        // A lost delta (sequence 4) breaks its chain until the next keyframe (sequence 11).
        NetPose.Decoder lossy = new NetPose.Decoder();
        for (int n = 0; n < 12; n++) {
            if (n == 3) continue;
            NetPose.Pose pose = lossy.decode(t, NetPose.header(wire.get(n)), wire.get(n));
            assert (pose != null) == (n < 3 || n >= 10) : "pose " + n;
        }
        assert lossy.broken == 6 && lossy.stale == 0;
        // Reordered: 14 before 13 is dropped, 13 decodes, 15 (built on 14) waits for the keyframe 21; a repeat is stale.
        NetPose.Decoder reordered = new NetPose.Decoder();
        int[] order = {10, 11, 12, 14, 13, 15, 16, 20, 20, 19};
        boolean[] decoded = {true, true, true, false, true, false, false, true, false, false};
        for (int k = 0; k < order.length; k++) {
            NetPose.Pose pose = reordered.decode(t, NetPose.header(wire.get(order[k])), wire.get(order[k]));
            assert (pose != null) == decoded[k] : "position " + k;
        }
        assert reordered.broken == 3 && reordered.stale == 2;
        // Another table generation is dropped, and a new table needs a keyframe first.
        NetPose.Table next = new NetPose.Table(8, 0, 0, 0, 100, "minecraft:overworld", t.components(), t.fingerprints(), t.effects(), true);
        assert reordered.decode(next, NetPose.header(wire.get(21)), wire.get(21)) == null && reordered.foreign == 1;
        // A sender that starts counting again (a reconnect, a new netplay) sends its table first: its poses decode at once.
        reordered.restart();
        NetPose.Encoder again = new NetPose.Encoder(10);
        for (int n = 0; n < 3; n++) {
            byte[] data = again.encode(t, ANCHOR, ALL, poses.get(n), effectsAt(t), 9000 + 50L * n);
            assert reordered.decode(t, NetPose.header(data), data) != null : "pose " + n + " after a restart";
        }
        // An encoder restart (a new table sent) and a new generation both start with a keyframe.
        chained.restart();
        assert NetPose.header(chained.encode(t, ANCHOR, ALL, poses.get(0), effectsAt(t), 5000)).keyframe();
        assert !NetPose.header(chained.encode(t, ANCHOR, ALL, poses.get(1), effectsAt(t), 5050)).keyframe();
        assert NetPose.header(chained.encode(next, ANCHOR, ALL, poses.get(2), effectsAt(t), 5100)).keyframe();
        // Effects starting and ending keep the layout: the chain goes on (a keyframe every 10 poses only).
        NetPose.Encoder effects = new NetPose.Encoder(10);
        int keyframes = 0;
        for (int n = 0; n < 40; n++) {
            float[] p = poses.get(n % poses.size()).clone();
            int e = effectsAt(t);
            for (int r = 0; r < NetPose.MAX_ACTIVE; r++) p[e + r * NetPose.EFFECT_FLOATS] = -1;
            for (int r = 0; r < n % 4; r++) effect(p, e + r * NetPose.EFFECT_FLOATS, r % 3, 100);
            if (NetPose.header(effects.encode(t, ANCHOR, ALL, p, e, 7000 + 50L * n)).keyframe()) keyframes++;
        }
        assert keyframes == 4 : keyframes + " keyframes in 40 poses whose effects come and go";
        // A change of status changes the layout: a keyframe too.
        assert NetPose.header(chained.encode(next, ANCHOR, new int[] {NetPose.WORLD | NetPose.BONES, 0, 0, 0}, poses.get(3), effectsAt(t), 5150)).keyframe();
    }

    static byte[] wire(byte kind, long generation, long sequence, long base, long publication, byte[] body, boolean compress) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(kind);
        for (long v : kind == NetPose.DELTA ? new long[] {generation, sequence, base} : new long[] {generation, sequence}) varlong(out, v);
        out.writeBytes(ByteBuffer.allocate(8).putLong(publication).array());
        if (!compress) { out.writeBytes(body); return out.toByteArray(); }
        Deflater deflater = new Deflater();
        deflater.setInput(body);
        deflater.finish();
        byte[] chunk = new byte[4096];
        while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk));
        return out.toByteArray();
    }

    static void varlong(ByteArrayOutputStream out, long v) {
        while ((v & ~0x7FL) != 0) { out.write((int) (v & 0x7F) | 0x80); v >>>= 7; }
        out.write((int) v);
    }

    static void refusedPose(NetPose.Table t, byte[] data) {
        try {
            NetPose.Pose pose = decode(t, data);
            throw new AssertionError("Decoded a hostile pose: " + pose);
        } catch (IllegalArgumentException expected) {
            // Refused with its reason.
        }
    }

    static void hostilePoses(NetPose.Table t) {
        byte[] body = NetPose.body(t, ANCHOR, ALL, sample(t), effectsAt(t));
        assert body.length <= NetPose.maxBody(t);
        // Headers: kind, bases, generation, publication, truncation, no body, size.
        for (byte[] bad : new byte[][] {new byte[0], wire((byte) 2, 7, 1, 0, 1, body, true), wire(NetPose.DELTA, 7, 5, 5, 1, body, true),
                wire(NetPose.DELTA, 7, 5, 0, 1, body, true), wire(NetPose.KEY, 0, 1, 0, 1, body, true), wire(NetPose.KEY, 7, 0, 0, 1, body, true),
                wire(NetPose.KEY, 7, 1, 0, 0, body, true), wire(NetPose.KEY, 7, 1, 0, 1, new byte[0], false),
                {0, (byte) 0x87, (byte) 0x80}, new byte[NetPose.MAX_POSE + 1]})
            try {
                throw new AssertionError("Read a hostile header: " + NetPose.header(bad));
            } catch (IllegalArgumentException expected) {
                // Refused.
            }
        // Bodies: a zip bomb, raw bytes, trailing input, and tampered counts, bits and indices.
        refusedPose(t, wire(NetPose.KEY, 7, 1, 0, 1, new byte[1 << 20], true));
        refusedPose(t, wire(NetPose.KEY, 7, 1, 0, 1, body, false));
        byte[] trailing = wire(NetPose.KEY, 7, 1, 0, 1, body, true);
        refusedPose(t, Arrays.copyOf(trailing, trailing.length + 1));
        refusedPose(t, Arrays.copyOf(trailing, trailing.length - 3));
        Consumer<Consumer<byte[]>> tampered = change -> {
            byte[] copy = body.clone();
            change.accept(copy);
            refusedPose(t, wire(NetPose.KEY, 7, 1, 0, 1, copy, true));
        };
        tampered.accept(b -> b[0] = 5); // Component count.
        tampered.accept(b -> b[1] |= 16); // The CLOTH bit.
        tampered.accept(b -> b[b.length - 7] = 3); // The last effect's asset index, beyond the 3 assets.
        tampered.accept(b -> b[b.length - 1 - NetPose.MAX_ACTIVE * 7] = 9); // The effect slot count, other than MAX_ACTIVE.
        tampered.accept(b -> b[b.length - 1 - NetPose.MAX_ACTIVE * 7] = 0);
        tampered.accept(b -> { b[5] = (byte) 0x7f; b[6] = (byte) 0xc0; b[7] = 0; b[8] = 0; }); // A NaN world translation.
        refusedPose(t, wire(NetPose.KEY, 7, 1, 0, 1, Arrays.copyOf(body, body.length - 1), true)); // Truncated.
        refusedPose(t, wire(NetPose.KEY, 7, 1, 0, 1, Arrays.copyOf(body, body.length + 1), true)); // Trailing.
        // A delta of another length than its base.
        NetPose.Decoder decoder = new NetPose.Decoder();
        byte[] key = wire(NetPose.KEY, 7, 1, 0, 1, body, true);
        assert decoder.decode(t, NetPose.header(key), key) != null;
        byte[] shorter = wire(NetPose.DELTA, 7, 2, 1, 2, new byte[body.length - 7], true);
        try {
            throw new AssertionError("Decoded a delta of another layout: " + decoder.decode(t, NetPose.header(shorter), shorter));
        } catch (IllegalArgumentException expected) {
            // Refused.
        }
        // A pose too large even deflated is not sent, and the next is a keyframe.
        NetPose.Table huge = new NetPose.Table(9, 0, 0, 0, 100, "minecraft:overworld", List.of(
                new AvatarRecording.Component("A", "M", null, names("b", 512), new String[0], 0),
                new AvatarRecording.Component("B", "M", null, names("b", 512), new String[0], 0),
                new AvatarRecording.Component("C", "M", null, names("b", 512), new String[0], 0)), new long[3], List.of(), false);
        float[] noise = new float[huge.floats()];
        for (int k = 0; k < noise.length; k++) noise[k] = (float) (random.nextDouble() * 200 - 100);
        NetPose.Encoder encoder = new NetPose.Encoder(10);
        int[] full = {NetPose.WORLD | NetPose.BONES, NetPose.WORLD | NetPose.BONES, NetPose.WORLD | NetPose.BONES};
        assert encoder.encode(huge, ANCHOR, full, noise, -1, 1) == null && encoder.oversized == 1;
    }

    static void refusedTable(byte[] data) {
        try {
            throw new AssertionError("Decoded a hostile table: " + NetPose.Table.decode(data));
        } catch (IllegalArgumentException expected) {
            // Refused with its reason.
        }
    }

    static void refusedEncode(NetPose.Table t) {
        try {
            throw new AssertionError("Encoded a table a viewer would refuse: " + t.encode().length);
        } catch (IllegalArgumentException expected) {
            // Refused.
        }
    }

    static NetPose.Table with(NetPose.Table t, List<AvatarRecording.Component> components) {
        return new NetPose.Table(t.generation(), t.originX(), t.originY(), t.originZ(), t.units(), t.dimension(), components,
                new long[components.size()], t.effects(), t.effectRecords());
    }

    static void hostileTables(NetPose.Table t) {
        byte[] good = t.encode();
        refusedTable(Arrays.copyOf(good, good.length + 1));
        refusedTable(Arrays.copyOf(good, good.length - 1));
        refusedTable(new byte[NetPose.MAX_TABLE + 1]);
        int count = 8 * 5 + 1 + "minecraft:overworld".length() + 1; // After generation, origin, units, dimension and flags.
        byte[] flags = good.clone();
        flags[count - 1] = 2;
        refusedTable(flags);
        byte[] components = good.clone();
        components[count + 1] = 17;
        refusedTable(components);
        components[count + 1] = 0;
        refusedTable(components);
        // Names outside the token set: a space in a bone name, a slash in a mesh name.
        String text = new String(good, StandardCharsets.ISO_8859_1);
        refusedTable(text.replace("Bip001-B12", "Bip001 B12").getBytes(StandardCharsets.ISO_8859_1));
        refusedTable(text.replace("CH_W_Sword_01", "CH_W/Sword_01").getBytes(StandardCharsets.ISO_8859_1));
        refusedTable(text.replace("minecraft:overworld", "Minecraft:Overworld").getBytes(StandardCharsets.ISO_8859_1));
        // Whole tables a sender must not encode either.
        var body = t.components().get(0);
        refusedEncode(with(t, java.util.Collections.nCopies(17, body)));
        refusedEncode(with(t, List.of(new AvatarRecording.Component("A", "M", null, names("b", 513), new String[0], 0))));
        refusedEncode(with(t, List.of(new AvatarRecording.Component("A", "M", null, new String[0], names("m", 1025), 1025))));
        refusedEncode(with(t, List.of(new AvatarRecording.Component("A", "M", "B", new String[] {"x"}, new String[0], 0)))); // Leader and bones.
        refusedEncode(with(t, List.of(new AvatarRecording.Component("A", "M", null, new String[] {"x".repeat(129)}, new String[0], 0))));
        refusedEncode(with(t, List.of(new AvatarRecording.Component("A", "../M", null, new String[0], new String[0], 0))));
        refusedEncode(with(t, List.of(new AvatarRecording.Component("A", "M", null, new String[0], names("m", 2), 3)))); // Weights.
        refusedEncode(with(t, java.util.Collections.nCopies(16, new AvatarRecording.Component("A", "M", null, names("b", 512), new String[0], 0))));
        refusedEncode(new NetPose.Table(0, 0, 0, 0, 100, "minecraft:overworld", List.of(body), new long[1], List.of(), false));
        refusedEncode(new NetPose.Table(1, 0, Double.NaN, 0, 100, "minecraft:overworld", List.of(body), new long[1], List.of(), false));
        refusedEncode(new NetPose.Table(1, 0, 0, 0, 0, "minecraft:overworld", List.of(body), new long[1], List.of(), false));
        refusedEncode(new NetPose.Table(1, 0, 0, 0, 100, "minecraft:overworld", List.of(body), new long[2], List.of(), false));
        refusedEncode(new NetPose.Table(1, 0, 0, 0, 100, "minecraft:overworld", List.of(body), new long[1], List.of(names("e", 33)), true));
        // A table over MAX_TABLE: 16 components of 300 long-named bones.
        refusedEncode(with(t, java.util.Collections.nCopies(16, new AvatarRecording.Component("A", "M", null, names("b".repeat(100), 300), new String[0], 0))));
    }

    /** The parsed stream table, re-encoded with names only, holds none of the text table's process addresses. */
    static void addresses() {
        String text = "SBMC_POSE_COMPONENTS 1\nRUN aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\nGENERATION 3\nSESSION bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n"
                + "ANCHOR -760000.5 -760000.25 -18000 100\n"
                + "CONTROLLER 00007f0000005000 00007f0000006000 00000318 00007f0000007000 00007f0000008000 2.5 -2.5\nCOUNT 2\n"
                + "COMPONENT 1 00007f0000001000 00007f0000002000 00007f0000003000 3 0 CharacterMesh0 CH_P_EVE_52_TypeB -\n"
                + "BONES 1 Root Bip001 Bip001-Head\n"
                + "COMPONENT 2 00007f0000001800 00007f0000002000 00007f0000003800 0 2 Mesh_Face CH_P_EVE_Face_Jali3 CharacterMesh0\n"
                + "MORPHS 2 jawOpen blink\nPARAMETERS 1\nPARAMETER 1 1 Edge Opacity 00007f0000009000 00007f000000a000 00000168 2 4711 1\n"
                + "EFFECTS 00000130 000006a8 000004b0 00000088 10 00007f000000b000 00007f000000c000 1\n"
                + "EFFECT 1 00007f000000d000 NS_A_Weapon_Signal_Sword\nCLOTH none\nEND 3\n";
        AvatarStream.Table stream = AvatarStream.parse(text);
        assert stream != null && stream.anchor() != null && stream.effectRecords();
        NetPose.Table table = RemoteEves.outgoing(stream, 10.5, 64, -3.5, "minecraft:overworld", new long[] {0x1122334455667788L, 0});
        byte[] bytes = table.encode();
        String wire = new String(bytes, StandardCharsets.ISO_8859_1);
        Matcher hex = Pattern.compile("\\b[0-9a-f]{8,16}\\b").matcher(text);
        int addresses = 0;
        while (hex.find()) {
            String address = hex.group();
            if (address.chars().allMatch(c -> c == 'a' || c == 'b')) continue; // The run and session identities.
            addresses++;
            assert !wire.contains(address) : "The table carries " + address;
            long value = Long.parseUnsignedLong(address, 16);
            if (address.length() == 16) {
                byte[] big = ByteBuffer.allocate(8).putLong(value).array(), little = ByteBuffer.allocate(8).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(value).array();
                assert indexOf(bytes, big) < 0 && indexOf(bytes, little) < 0 : "The table carries " + address + " in binary";
            }
        }
        assert addresses == 21 : addresses;
        assert !wire.contains("bbbbbbbbbbbbbbbb") && !wire.contains("aaaaaaaaaaaaaaaa") && !wire.contains("Opacity") : "Nor the identities or parameters";
        NetPose.Table back = NetPose.Table.decode(bytes);
        assert back.generation() == 3 && back.units() == 100 && back.components().get(1).leader().equals("CharacterMesh0")
                && back.components().get(1).bones() == null && Arrays.equals(back.components().get(0).bones(), new String[] {"Root", "Bip001", "Bip001-Head"})
                && back.fingerprints()[0] == 0x1122334455667788L && back.effects().equals(List.of("NS_A_Weapon_Signal_Sword"));
        AvatarStream.Table remote = RemoteEves.stream(back);
        assert remote.floats() == stream.floats() - 1 && remote.componentFloats() == stream.componentFloats() && remote.cloth() == -1
                && remote.anchor()[0] == 0 && remote.anchor()[3] == 100 && remote.parameters().isEmpty() : "No parameter floats travel";
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int k = 0; k < needle.length; k++) if (haystack[i + k] != needle[k]) continue outer;
            return i;
        }
        return -1;
    }

    static void relay(NetPose.Table t) {
        List<float[]> poses = motion(t, 12);
        NetPose.Encoder encoder = new NetPose.Encoder(10);
        List<NetPose.Header> headers = new ArrayList<>();
        for (int n = 0; n < poses.size(); n++) headers.add(NetPose.header(encoder.encode(t, ANCHOR, ALL, poses.get(n), effectsAt(t), 1000 + 50L * n)));
        long now = 1_000_000_000L;
        // Near: everything in chain order; a viewer without the previous pose waits for the keyframe.
        PoseRelay.Viewer near = new PoseRelay.Viewer();
        Long last = null;
        for (NetPose.Header h : headers) {
            assert PoseRelay.forward(h, last, true, near, 5000, now);
            last = h.sequence();
        }
        assert near.poses == 12 && near.withheld == 0;
        PoseRelay.Viewer newcomer = new PoseRelay.Viewer();
        assert !PoseRelay.forward(headers.get(5), null, true, newcomer, 5000, now) && PoseRelay.forward(headers.get(10), null, true, newcomer, 5000, now);
        assert !PoseRelay.forward(headers.get(5), 3L, true, newcomer, 5000, now) : "A delta on another base is withheld";
        // Far: keyframes only.
        PoseRelay.Viewer far = new PoseRelay.Viewer();
        last = null;
        for (NetPose.Header h : headers) if (PoseRelay.forward(h, last, false, far, 5000, now)) last = h.sequence();
        assert far.poses == 2 && far.withheld == 10;
        // Budget: a second's worth at most; a pose dropped over it breaks the chain until the next keyframe.
        PoseRelay.Viewer tight = new PoseRelay.Viewer();
        int big = (int) (PoseRelay.BUDGET / 4) + 1;
        last = null;
        int sent = 0;
        for (NetPose.Header h : headers) if (PoseRelay.forward(h, last, true, tight, big, now)) { last = h.sequence(); sent++; }
        assert sent == 3 && tight.dropped == 2 && tight.withheld == 7 : sent + " " + tight.dropped + " " + tight.withheld;
        assert PoseRelay.forward(headers.get(10), last, true, tight, big, now + 1_000_000_000L) : "The budget refills";
        // The sender's caps: poses of its cached table's generation only, RATE poses and SENT bytes a second.
        PoseRelay.Sender sender = new PoseRelay.Sender();
        assert !PoseRelay.admit(sender, headers.get(0), 5000, now) : "No table yet";
        PoseRelay.cache(sender, t.encode());
        assert !PoseRelay.admit(sender, new NetPose.Header(NetPose.KEY, t.generation() + 1, 1, 0, 1000, 12), 5000, now) : "Another generation";
        int taken = 0;
        for (int k = 0; k < 50; k++) if (PoseRelay.admit(sender, headers.get(0), 100, now)) taken++;
        assert taken == (int) PoseRelay.RATE && PoseRelay.admit(sender, headers.get(0), 100, now + 1_000_000_000L / 30) : taken;
        assert sender.poses == taken + 1 && sender.refused == 50 - taken + 2 : sender.poses + " " + sender.refused;
        PoseRelay.Sender heavy = new PoseRelay.Sender();
        PoseRelay.cache(heavy, t.encode());
        taken = 0;
        for (int k = 0; k < 35; k++) if (PoseRelay.admit(heavy, headers.get(0), NetPose.MAX_POSE, now)) taken++;
        assert taken == (int) (PoseRelay.SENT / NetPose.MAX_POSE) && taken * NetPose.MAX_POSE < PoseRelay.BUDGET / 2 : taken;
        // Tables: each goes out once, 2 a second; one over the cap waits for a later pose, and only the latest goes.
        PoseRelay.Sender tables = new PoseRelay.Sender();
        PoseRelay.cache(tables, t.encode());
        assert PoseRelay.release(tables, now) && !PoseRelay.release(tables, now) : "A table goes once";
        PoseRelay.cache(tables, t.encode());
        assert PoseRelay.release(tables, now);
        PoseRelay.cache(tables, t.encode());
        PoseRelay.cache(tables, t.encode());
        assert !PoseRelay.release(tables, now) && PoseRelay.release(tables, now + 500_000_000L) && !PoseRelay.release(tables, now + 500_000_000L)
                && tables.tables == 4 : "Over the cap, the latest table waits";
        assert PoseRelay.BUDGET == 512 * 1024 : "512 KiB a second by default";
    }

    /**
     * The clock floor: a sender clock 3 s ahead, transit 80 ms with ±20 ms jitter, publications every 50 ms, a drift of
     * -0.5 ms a second for two minutes. The display time always falls among the received publications, at most half an
     * interval past the newest, and never steps back.
     */
    static void clock() {
        double floor = Double.NaN, previousAt = Double.NEGATIVE_INFINITY, previousArrival = 0;
        List<double[]> received = new ArrayList<>(); // Publication (sender clock), arrival (viewer clock).
        int frames = 0;
        double viewer = 0;
        for (int k = 0; k < 2400; k++) {
            double publication = 50.0 * k + 3000 - 0.0005 * 50.0 * k; // The sender's clock: ahead, and slowing.
            double arrival = 50.0 * k + 80 + (random.nextDouble() * 40 - 20);
            arrival = Math.max(arrival, previousArrival); // TCP keeps them in order.
            for (; viewer < arrival; viewer += 7) { // Frames until this pose arrives.
                if (received.size() < 20) continue;
                double at = RemoteEves.displayTime(viewer, floor, 50);
                double newest = received.get(received.size() - 1)[0], oldest = received.get(received.size() - RemoteEves.RING)[0];
                assert at >= oldest && at <= newest + 25 : "frame at " + viewer + ": " + at + " outside " + oldest + ".." + newest;
                assert at >= previousAt - 1e-9 : "The display time steps back at " + viewer;
                previousAt = at;
                frames++;
            }
            floor = RemoteEves.floor(floor, arrival - publication, (arrival - previousArrival) / 1000);
            previousArrival = arrival;
            received.add(new double[] {publication, arrival});
        }
        // The floor ends at the least transit (60 ms) less the clock offset, which the drift moved by 60 ms over two minutes.
        assert frames > 10000 && floor > -3000 + 60 + 60 - 2 && floor < -3000 + 60 + 60 + 40 : floor;
    }
}
