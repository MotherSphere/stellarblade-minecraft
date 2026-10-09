package org.stellarbridge;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * EVE's pose stream in network form (protocol/README.md, network pose): a names-only component table and quantized
 * poses, keyframes with deltas between them. Plain Java, so a dedicated server can check a table and read a pose's
 * header without client classes (ServerSafetyTest); the server never decodes a pose body. A pose keeps the stream's float
 * layout (per component its world transform, component-space bones and morph weights, then the effect records) and
 * Unreal axes, with world positions relative to the sender's link anchor, so a viewer feeds AvatarStream's decoder as is.
 */
final class NetPose {
    /** Payload bytes: each codec's trust boundary. A 466-bone outfit's keyframe takes about 5 KB. */
    static final int MAX_POSE = 16384, MAX_TABLE = 65536;
    /** Today's parse limits (AvatarStream): components, bones and morphs per component, effect assets, active effects. */
    static final int MAX_COMPONENTS = 16, MAX_BONES = 512, MAX_MORPHS = 1024, MAX_EFFECTS = 32, MAX_ACTIVE = 8;
    static final int EFFECT_FLOATS = 11, MAX_FLOATS = 65504; // AvatarStream.EFFECT_FLOATS and CAPACITY.
    /** Component status bits, as AvatarStream's; CLOTH never travels (remote ponytails follow bones). */
    static final int WORLD = 1, BONES = 2, MORPHS = 4, HIDDEN = 8;
    static final byte KEY = 0, DELTA = 1;
    private static final long MAX = 9007199254740991L; // Generations, sequences and times, as AvatarStream bounds them.
    // Fixed-point steps: bone translations 1/32 cm, scales 1/4096, morph weights 1/16384, effect positions 1/8 cm; a
    // rotation is the smallest three of its unit quaternion, 15 bits each, with the largest's index in 2 bits.
    private static final double TRANSLATION = 32, SCALE = 4096, WEIGHT = 16384, EFFECT = 8, ROTATION = 32767, SQRT2 = Math.sqrt(2);
    private static final int TRANSFORM_BYTES = 24, BONE_BYTES = 18, EFFECT_BYTES = 7;
    // Names: Lua's token set (printable ASCII without spaces), mesh names safe as pack file names, dimensions as identifiers.
    private static final Pattern TOKEN = Pattern.compile("[!-~]{1,128}"), MESH = Pattern.compile("[A-Za-z0-9_.-]{1,96}"),
            DIMENSION = Pattern.compile("[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,190}");

    private NetPose() { }

    /**
     * The names-only component table of one generation: the sender's Minecraft origin, Unreal units per block and
     * dimension, per component its name, mesh, leader (null: it carries its own bones), bone and morph names and the
     * fingerprint of the sender's mesh file (0: unknown), the effect asset names and whether poses carry effects. It is
     * built from the stream's parsed table, never forwarded as text: that text holds Stellar Blade process addresses.
     */
    record Table(long generation, double originX, double originY, double originZ, double units, String dimension,
                 List<AvatarRecording.Component> components, long[] fingerprints, List<String> effects, boolean effectRecords) {
        /** Floats of a decoded pose: the components', then the effect records (no material parameters travel). */
        int floats() {
            int total = effectRecords ? MAX_ACTIVE * EFFECT_FLOATS : 0;
            for (var c : components) total += 10 + 10 * bones(c) + c.weights();
            return total;
        }

        /** The table's bytes; refuses names, counts or a size a viewer would refuse. */
        byte[] encode() {
            validate(this);
            ByteBuffer out = ByteBuffer.allocate(MAX_TABLE + 1);
            try {
                out.putLong(generation).putDouble(originX).putDouble(originY).putDouble(originZ).putDouble(units);
                string(out, dimension);
                out.put((byte) (effectRecords ? 1 : 0)).put((byte) components.size());
                for (int i = 0; i < components.size(); i++) {
                    var c = components.get(i);
                    string(out, c.name());
                    string(out, c.mesh());
                    string(out, c.leader() == null ? "" : c.leader());
                    names(out, c.bones() == null ? new String[0] : c.bones());
                    names(out, c.morphs());
                    out.putLong(fingerprints[i]);
                }
                out.put((byte) effects.size());
                for (String effect : effects) string(out, effect);
            } catch (java.nio.BufferOverflowException tooLarge) {
                throw new IllegalArgumentException("component table over " + MAX_TABLE + " bytes");
            }
            if (out.position() > MAX_TABLE) throw new IllegalArgumentException("component table over " + MAX_TABLE + " bytes");
            return java.util.Arrays.copyOf(out.array(), out.position());
        }

        /** A table from its bytes; refuses any count, name, value or trailing byte beyond the bounds. */
        static Table decode(byte[] data) {
            require(data.length <= MAX_TABLE, "component table over " + MAX_TABLE + " bytes");
            ByteBuffer in = ByteBuffer.wrap(data);
            try {
                long generation = in.getLong();
                double x = in.getDouble(), y = in.getDouble(), z = in.getDouble(), units = in.getDouble();
                String dimension = string(in);
                int flags = in.get(), count = Byte.toUnsignedInt(in.get());
                require((flags & ~1) == 0, "table flags " + flags);
                require(count >= 1 && count <= MAX_COMPONENTS, count + " components");
                List<AvatarRecording.Component> components = new ArrayList<>(count);
                long[] fingerprints = new long[count];
                for (int i = 0; i < count; i++) {
                    String name = string(in), mesh = string(in), leader = string(in);
                    String[] bones = names(in, MAX_BONES), morphs = names(in, MAX_MORPHS);
                    require(leader.isEmpty() || bones.length == 0, "a follower carries no bones");
                    fingerprints[i] = in.getLong();
                    components.add(new AvatarRecording.Component(name, mesh, leader.isEmpty() ? null : leader,
                            leader.isEmpty() ? bones : null, morphs, morphs.length));
                }
                int effectCount = Byte.toUnsignedInt(in.get());
                require(effectCount <= MAX_EFFECTS, effectCount + " effects");
                List<String> effects = new ArrayList<>(effectCount);
                for (int i = 0; i < effectCount; i++) effects.add(string(in));
                require(!in.hasRemaining(), "trailing table bytes");
                Table table = new Table(generation, x, y, z, units, dimension, List.copyOf(components), fingerprints, List.copyOf(effects), flags == 1);
                validate(table);
                return table;
            } catch (BufferUnderflowException truncated) {
                throw new IllegalArgumentException("truncated component table");
            }
        }
    }

    private static int bones(AvatarRecording.Component c) { return c.recorded() ? c.bones().length : 0; }

    private static void validate(Table t) {
        require(t.generation > 0 && t.generation <= MAX, "generation " + t.generation);
        require(Double.isFinite(t.originX) && Double.isFinite(t.originY) && Double.isFinite(t.originZ), "origin");
        require(Double.isFinite(t.units) && t.units > 0, "units per block " + t.units);
        require(DIMENSION.matcher(t.dimension).matches(), "dimension");
        int count = t.components.size();
        require(count >= 1 && count <= MAX_COMPONENTS && t.fingerprints.length == count, count + " components");
        for (var c : t.components) {
            require(TOKEN.matcher(c.name()).matches() && MESH.matcher(c.mesh()).matches(), "component name or mesh");
            require(c.leader() == null ? c.recorded() : !c.recorded() && TOKEN.matcher(c.leader()).matches(), "component leader");
            require(bones(c) <= MAX_BONES && c.morphs().length <= MAX_MORPHS && c.weights() == c.morphs().length, "component counts");
            if (c.recorded()) for (String bone : c.bones()) require(TOKEN.matcher(bone).matches(), "bone name");
            for (String morph : c.morphs()) require(TOKEN.matcher(morph).matches(), "morph name");
        }
        require(t.effects.size() <= MAX_EFFECTS, t.effects.size() + " effects");
        for (String effect : t.effects) require(TOKEN.matcher(effect).matches(), "effect name");
        require(t.floats() <= MAX_FLOATS, "pose layout of " + t.floats() + " floats");
    }

    private static void string(ByteBuffer out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        require(bytes.length <= 255, "name length");
        out.put((byte) bytes.length).put(bytes);
    }

    private static String string(ByteBuffer in) {
        byte[] bytes = new byte[Byte.toUnsignedInt(in.get())];
        in.get(bytes);
        for (byte b : bytes) require(b > ' ' && b < 127, "name bytes");
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private static void names(ByteBuffer out, String[] names) {
        out.putShort((short) names.length);
        for (String name : names) string(out, name);
    }

    private static String[] names(ByteBuffer in, int max) {
        int count = Short.toUnsignedInt(in.getShort());
        require(count <= max, count + " names");
        String[] names = new String[count];
        for (int i = 0; i < count; i++) names[i] = string(in);
        return names;
    }

    /** A pose's header: KEY or DELTA, the table generation, the sender's sequence, a delta's base sequence, SB's publication time. */
    record Header(byte kind, long generation, long sequence, long base, long publication, int bodyAt) {
        boolean keyframe() { return kind == KEY; }
    }

    /** Reads and checks a pose payload's header (the body after it stays opaque); the server's whole view of a pose. */
    static Header header(byte[] data) {
        require(data.length <= MAX_POSE, "pose over " + MAX_POSE + " bytes");
        ByteBuffer in = ByteBuffer.wrap(data);
        try {
            byte kind = in.get();
            require(kind == KEY || kind == DELTA, "pose kind " + kind);
            long generation = varlong(in), sequence = varlong(in), base = kind == DELTA ? varlong(in) : 0, publication = in.getLong();
            require(generation > 0 && generation <= MAX && sequence > 0 && sequence <= MAX, "pose generation or sequence");
            require(kind == KEY || base > 0 && base < sequence, "delta base " + base);
            require(publication > 0 && publication <= MAX, "publication time " + publication);
            require(in.hasRemaining(), "pose without a body");
            return new Header(kind, generation, sequence, base, publication, in.position());
        } catch (BufferUnderflowException truncated) {
            throw new IllegalArgumentException("truncated pose header");
        }
    }

    private static void varlong(ByteArrayOutputStream out, long value) {
        while ((value & ~0x7FL) != 0) {
            out.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    private static long varlong(ByteBuffer in) {
        long value = 0;
        for (int shift = 0; shift < 63; shift += 7) {
            byte b = in.get();
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return value;
        }
        throw new IllegalArgumentException("varlong too long");
    }

    /** The largest body a pose of the table can have: every status bit and every effect. */
    static int maxBody(Table t) {
        int total = 2 + t.components.size() + MAX_ACTIVE * EFFECT_BYTES;
        for (var c : t.components) total += TRANSFORM_BYTES + BONE_BYTES * bones(c) + 2 * c.weights();
        return total;
    }

    /**
     * The quantized body of one stream sample: status (u8 each, CLOTH dropped), per component its world transform
     * (translation relative to anchor as 3 f32, rotation, scale) when WORLD, its bones when BONES, its weights when
     * MORPHS, then the active effects (asset index, position relative to the first component in 1/8 cm; one beyond
     * reach is left out). payload has the stream table's layout; effectsAt is its first effect record, or -1.
     */
    static byte[] body(Table t, double[] anchor, int[] status, float[] payload, int effectsAt) {
        ByteBuffer out = ByteBuffer.allocate(maxBody(t));
        int n = t.components.size();
        require(status.length == n, "status count");
        out.put((byte) n);
        for (int s : status) out.put((byte) (s & (WORLD | BONES | MORPHS | HIDDEN)));
        int at = 0;
        for (int i = 0; i < n; i++) {
            var c = t.components.get(i);
            int bones = bones(c);
            if ((status[i] & WORLD) != 0) {
                out.putFloat((float) (payload[at + 4] - anchor[0])).putFloat((float) (payload[at + 5] - anchor[1])).putFloat((float) (payload[at + 6] - anchor[2]));
                putRotation(out, payload, at);
                for (int k = 7; k < 10; k++) out.putShort(fixed(payload[at + k], SCALE));
            }
            if ((status[i] & BONES) != 0)
                for (int b = 0, p = at + 10; b < bones; b++, p += 10) {
                    putRotation(out, payload, p);
                    for (int k = 4; k < 7; k++) out.putShort(fixed(payload[p + k], TRANSLATION));
                    for (int k = 7; k < 10; k++) out.putShort(fixed(payload[p + k], SCALE));
                }
            if ((status[i] & MORPHS) != 0)
                for (int w = 0, p = at + 10 + 10 * bones; w < c.weights(); w++) out.putShort(fixed(payload[p + w], WEIGHT));
            at += 10 + 10 * bones + c.weights();
        }
        int countAt = out.position(), count = 0;
        out.put((byte) 0);
        if (t.effectRecords && effectsAt >= 0 && (status[0] & WORLD) != 0) {
            for (int r = 0, p = effectsAt; r < MAX_ACTIVE; r++, p += EFFECT_FLOATS) {
                int index = Math.round(payload[p]);
                if (index < 0 || index >= t.effects.size()) continue;
                double[] offset = new double[3];
                boolean reach = true;
                for (int k = 0; k < 3; k++) {
                    offset[k] = Math.rint((payload[p + 5 + k] - payload[4 + k]) * EFFECT);
                    reach &= Math.abs(offset[k]) <= Short.MAX_VALUE;
                }
                if (!reach) continue;
                out.put((byte) index);
                for (double v : offset) out.putShort((short) v);
                count++;
            }
        }
        out.put(countAt, (byte) count);
        return java.util.Arrays.copyOf(out.array(), out.position());
    }

    /** A decoded pose in the table's float layout (Table.floats); absent effect records carry index -1. */
    record Pose(long sequence, long publication, int[] status, float[] payload) { }

    /** Dequantizes a body into a pose of the table's layout; refuses any count, bit, index or byte beyond it. */
    static Pose pose(Table t, Header header, byte[] body) {
        ByteBuffer in = ByteBuffer.wrap(body);
        float[] payload = new float[t.floats()];
        int n = t.components.size();
        try {
            require(Byte.toUnsignedInt(in.get()) == n, "pose component count");
            int[] status = new int[n];
            for (int i = 0; i < n; i++) {
                status[i] = Byte.toUnsignedInt(in.get());
                require((status[i] & ~(WORLD | BONES | MORPHS | HIDDEN)) == 0, "pose status " + status[i]);
            }
            int at = 0;
            for (int i = 0; i < n; i++) {
                var c = t.components.get(i);
                int bones = bones(c);
                if ((status[i] & WORLD) != 0) {
                    for (int k = 4; k < 7; k++) {
                        payload[at + k] = in.getFloat();
                        require(Float.isFinite(payload[at + k]), "non-finite translation");
                    }
                    getRotation(in, payload, at);
                    for (int k = 7; k < 10; k++) payload[at + k] = (float) (in.getShort() / SCALE);
                } else identity(payload, at);
                for (int b = 0, p = at + 10; b < bones; b++, p += 10) {
                    if ((status[i] & BONES) == 0) { identity(payload, p); continue; }
                    getRotation(in, payload, p);
                    for (int k = 4; k < 7; k++) payload[p + k] = (float) (in.getShort() / TRANSLATION);
                    for (int k = 7; k < 10; k++) payload[p + k] = (float) (in.getShort() / SCALE);
                }
                if ((status[i] & MORPHS) != 0)
                    for (int w = 0, p = at + 10 + 10 * bones; w < c.weights(); w++) payload[p + w] = (float) (in.getShort() / WEIGHT);
                at += 10 + 10 * bones + c.weights();
            }
            int count = Byte.toUnsignedInt(in.get());
            require(count <= MAX_ACTIVE && (t.effectRecords || count == 0) && (count == 0 || (status[0] & WORLD) != 0), count + " active effects");
            if (t.effectRecords) {
                for (int r = 0, p = at; r < MAX_ACTIVE; r++, p += EFFECT_FLOATS) {
                    payload[p] = -1;
                    identity(payload, p + 1);
                }
                for (int r = 0, p = at; r < count; r++, p += EFFECT_FLOATS) {
                    int index = Byte.toUnsignedInt(in.get());
                    require(index < t.effects.size(), "effect index " + index);
                    payload[p] = index;
                    for (int k = 0; k < 3; k++) payload[p + 5 + k] = (float) (payload[4 + k] + in.getShort() / EFFECT);
                }
            }
            require(!in.hasRemaining(), "trailing pose bytes");
            return new Pose(header.sequence(), header.publication(), status, payload);
        } catch (BufferUnderflowException truncated) {
            throw new IllegalArgumentException("truncated pose body");
        }
    }

    private static void identity(float[] payload, int at) {
        java.util.Arrays.fill(payload, at, at + 10, 0);
        payload[at + 3] = 1;
        payload[at + 7] = payload[at + 8] = payload[at + 9] = 1;
    }

    private static short fixed(double value, double scale) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, Math.rint(value * scale)));
    }

    /** Smallest three: the unit quaternion at payload[at..at+3] (xyzw) with its largest component made positive. */
    private static void putRotation(ByteBuffer out, float[] payload, int at) {
        double[] q = {payload[at], payload[at + 1], payload[at + 2], payload[at + 3]};
        double norm = Math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]);
        if (!(norm > 1e-6)) q = new double[] {0, 0, 0, 1};
        else for (int k = 0; k < 4; k++) q[k] /= norm;
        int largest = 0;
        for (int k = 1; k < 4; k++) if (Math.abs(q[k]) > Math.abs(q[largest])) largest = k;
        double sign = q[largest] < 0 ? -1 : 1;
        long bits = largest;
        for (int k = 0; k < 4; k++)
            if (k != largest) bits = bits << 15 | (long) Math.max(0, Math.min(ROTATION, Math.rint((sign * q[k] * SQRT2 + 1) / 2 * ROTATION)));
        out.putShort((short) (bits >>> 32)).putInt((int) bits);
    }

    private static void getRotation(ByteBuffer in, float[] payload, int at) {
        long bits = (long) Short.toUnsignedInt(in.getShort()) << 32 | Integer.toUnsignedLong(in.getInt());
        int largest = (int) (bits >>> 45) & 3;
        double sum = 0;
        for (int k = 3, shift = 0; k >= 0; k--) {
            if (k == largest) continue;
            double v = ((bits >>> shift & 0x7FFF) / ROTATION * 2 - 1) / SQRT2;
            payload[at + k] = (float) v;
            sum += v * v;
            shift += 15;
        }
        payload[at + largest] = (float) Math.sqrt(Math.max(0, 1 - sum));
    }

    /**
     * The sender's side: each pose becomes a keyframe every keyframes poses (and at a new generation or layout) or a
     * delta from the pose sent just before it (its base). The deflated body keeps a pose within MAX_POSE on any
     * connection; vanilla compression would skip a local one. One per sending client, on its render thread.
     */
    static final class Encoder {
        private final int keyframes;
        private final Deflater deflater = new Deflater();
        private long sequence, generation, previousSequence;
        private byte[] previous; // The body sent last, null when the next pose must be a keyframe.
        private int sinceKey;
        long keys, deltas, bytes, oversized; // Sent so far, for /stellaravatar status.

        Encoder(int keyframes) { this.keyframes = Math.max(1, keyframes); }

        /** The payload for one sample (see body), or null when even deflated it exceeds MAX_POSE. */
        byte[] encode(Table t, double[] anchor, int[] status, float[] payload, int effectsAt, long publication) {
            byte[] body = body(t, anchor, status, payload, effectsAt);
            boolean key = previous == null || generation != t.generation || previous.length != body.length || sinceKey + 1 >= keyframes;
            byte[] wire = body;
            if (!key) {
                wire = new byte[body.length];
                for (int i = 0; i < body.length; i++) wire[i] = (byte) (body[i] - previous[i]);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 + body.length / 2);
            out.write(key ? KEY : DELTA);
            varlong(out, t.generation);
            varlong(out, ++sequence);
            if (!key) varlong(out, previousSequence);
            ByteBuffer time = ByteBuffer.allocate(8).putLong(publication);
            out.write(time.array(), 0, 8);
            deflater.reset();
            deflater.setInput(wire);
            deflater.finish();
            byte[] chunk = new byte[4096];
            while (!deflater.finished()) out.write(chunk, 0, deflater.deflate(chunk));
            generation = t.generation;
            if (out.size() > MAX_POSE) {
                previous = null;
                oversized++;
                return null;
            }
            previous = body;
            previousSequence = sequence;
            sinceKey = key ? 0 : sinceKey + 1;
            if (key) keys++; else deltas++;
            bytes += out.size();
            return out.toByteArray();
        }

        /** The next pose is a keyframe (a new table to send first, a new link). */
        void restart() { previous = null; }
    }

    /**
     * A viewer's side for one remote player: keyframes decode alone; a delta decodes only on the pose it was made from,
     * the last decoded one. A lost or reordered delta therefore waits for the next keyframe, and an older pose than the
     * last decoded is dropped. Render thread.
     */
    static final class Decoder {
        private long sequence; // The last decoded pose's, 0 before the first.
        private byte[] body;
        long stale, broken, foreign; // Poses dropped: older than the last decoded, a delta on another base, another generation.

        /** The pose, or null when dropped (counted); throws IllegalArgumentException on a malformed payload. */
        Pose decode(Table t, Header header, byte[] data) {
            if (header.generation() != t.generation) { foreign++; return null; }
            if (header.sequence() <= sequence) { stale++; return null; }
            if (!header.keyframe() && (body == null || header.base() != sequence)) { broken++; return null; }
            byte[] wire = inflate(data, header.bodyAt(), maxBody(t));
            if (!header.keyframe()) {
                require(wire.length == body.length, "delta of another layout");
                for (int i = 0; i < wire.length; i++) wire[i] = (byte) (wire[i] + body[i]);
            }
            Pose pose = pose(t, header, wire);
            body = wire;
            sequence = header.sequence();
            return pose;
        }

        /**
         * A new table: the next pose needs a keyframe, and may count from 1 again (a sender that reconnected, or a new
         * netplay): a table always comes before its poses on the same connection.
         */
        void restart() {
            body = null;
            sequence = 0;
        }
    }

    private static byte[] inflate(byte[] data, int offset, int max) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, data.length - offset);
            byte[] out = new byte[max + 1];
            int total = 0;
            while (!inflater.finished() && total < out.length) {
                int n = inflater.inflate(out, total, out.length - total);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                total += n;
            }
            require(inflater.finished() && total <= max && inflater.getRemaining() == 0, "pose body over its table's size or malformed");
            return java.util.Arrays.copyOf(out, total);
        } catch (DataFormatException malformed) {
            throw new IllegalArgumentException("malformed pose body");
        } finally {
            inflater.end();
        }
    }

    /** The first 8 bytes of a mesh file's SHA-256 (0: no such file), so a viewer can tell its own copy of a mesh is the same. */
    static long fingerprint(Path file) {
        if (!Files.isRegularFile(file)) return 0;
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] chunk = new byte[1 << 16];
            for (int n; (n = in.read(chunk)) > 0; ) digest.update(chunk, 0, n);
            return ByteBuffer.wrap(digest.digest()).getLong();
        } catch (IOException | NoSuchAlgorithmException unreadable) {
            return 0;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
