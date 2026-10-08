package org.stellarbridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A bounded EVE pose recording written by the Stellar Blade pose probe (eve-pose-probe.txt and .bin).
 * Transforms are Unreal FTransforms as 10 floats: quaternion xyzw, translation xyz, scale xyz.
 */
final class AvatarRecording {
    static final int TRANSFORM = 10;
    /** One skeletal mesh component; bones and morph weights are recorded only for components without a leader. */
    record Component(String name, String mesh, String leader, String[] bones, String[] morphs, int weights) {
        boolean recorded() { return bones != null; }
    }
    /**
     * Component world transforms plus local (parent bone space) bone transforms and morph weights of recorded components,
     * and per component Stellar Blade's cloth as component-space positions (3 floats per mesh vertex), or null: bones.
     */
    record Pose(float[][] world, float[][] local, float[][] weights, float[][] cloth) { }

    final List<Component> components;
    final double[] seconds;
    final Pose[] frames;

    private AvatarRecording(List<Component> components, double[] seconds, Pose[] frames) {
        this.components = components;
        this.seconds = seconds;
        this.frames = frames;
    }

    static AvatarRecording read(Path directory) throws IOException {
        try {
            return parse(Files.readString(directory.resolve("eve-pose-probe.txt"), StandardCharsets.UTF_8),
                    ByteBuffer.wrap(Files.readAllBytes(directory.resolve("eve-pose-probe.bin"))).order(ByteOrder.LITTLE_ENDIAN));
        } catch (RuntimeException failure) {
            throw new IOException("Invalid pose recording: " + failure.getMessage(), failure);
        }
    }

    static AvatarRecording parse(String summary, ByteBuffer data) {
        String[] lines = summary.split("\n");
        require(lines.length > 2 && lines[0].equals("SBMC_POSE_PROBE 1") && lines[lines.length - 1].equals("END"), "not a complete pose probe summary");
        List<Map<String, String>> fields = new ArrayList<>();
        Map<Integer, String[]> bones = new HashMap<>(), morphs = new HashMap<>();
        for (String line : lines) {
            String[] f = line.split(" ", -1);
            if (f[0].equals("COMPONENT")) {
                require(Integer.parseInt(f[1]) == fields.size() + 1, "components out of order");
                Map<String, String> values = new HashMap<>(Map.of("name", f[2]));
                for (int i = 3; i < f.length; i++) {
                    int at = f[i].indexOf('=');
                    require(at > 0, "malformed component field");
                    values.put(f[i].substring(0, at), f[i].substring(at + 1));
                }
                fields.add(values);
            } else if (f[0].equals("BONES") || f[0].equals("MORPHS")) {
                List<String> names = new ArrayList<>();
                for (int i = 2; i < f.length; i++) if (!f[i].isEmpty()) names.add(f[i]);
                (f[0].equals("BONES") ? bones : morphs).put(Integer.parseInt(f[1]) - 1, names.toArray(String[]::new));
            }
        }
        require(!fields.isEmpty(), "no components");
        List<Component> components = new ArrayList<>();
        int frameBytes = 12;
        for (int i = 0; i < fields.size(); i++) {
            Map<String, String> c = fields.get(i);
            boolean recorded = c.get("recorded").equals("true");
            String[] names = recorded ? bones.getOrDefault(i, new String[0]) : null;
            int weights = Integer.parseInt(c.get("weights"));
            require(weights >= 0 && (recorded || weights == 0), "invalid weight count");
            String leader = c.get("leader");
            components.add(new Component(c.get("name"), c.get("mesh"), leader.equals("-") ? null : leader, names,
                    morphs.getOrDefault(i, new String[0]), weights));
            frameBytes += 4 * TRANSFORM + (recorded ? 4 * (TRANSFORM * names.length + weights) : 0);
        }
        require(data.remaining() > 0 && data.remaining() % frameBytes == 0, "recording size does not match the summary");
        int count = data.remaining() / frameBytes;
        double[] seconds = new double[count];
        Pose[] frames = new Pose[count];
        for (int f = 0; f < count; f++) {
            data.getInt();
            seconds[f] = data.getDouble();
            require(Double.isFinite(seconds[f]) && (f == 0 || seconds[f] > seconds[f - 1]), "frame times must increase");
            float[][] world = new float[components.size()][], local = new float[components.size()][], weights = new float[components.size()][];
            for (int i = 0; i < components.size(); i++) {
                Component c = components.get(i);
                world[i] = floats(data, TRANSFORM);
                if (c.recorded()) {
                    local[i] = floats(data, TRANSFORM * c.bones.length);
                    weights[i] = floats(data, c.weights);
                }
            }
            frames[f] = new Pose(world, local, weights, new float[components.size()][]);
        }
        return new AvatarRecording(List.copyOf(components), seconds, frames);
    }

    double duration() { return seconds[seconds.length - 1] - seconds[0]; }

    /** Interpolated pose at a time since the first frame, looping over the recording. */
    Pose sample(double time, Pose target) {
        double t = seconds[0] + (duration() > 0 ? time - Math.floor(time / duration()) * duration() : 0);
        int low = 0, high = seconds.length - 1;
        while (high - low > 1) {
            int middle = (low + high) >>> 1;
            if (seconds[middle] <= t) low = middle; else high = middle;
        }
        double span = seconds[high] - seconds[low];
        float alpha = span > 0 ? (float) Math.min(1, Math.max(0, (t - seconds[low]) / span)) : 0;
        Pose a = frames[low], b = frames[high];
        for (int i = 0; i < a.world.length; i++) {
            blend(a.world[i], b.world[i], alpha, target.world[i]);
            if (a.local[i] == null) continue;
            blend(a.local[i], b.local[i], alpha, target.local[i]);
            for (int k = 0; k < a.weights[i].length; k++) target.weights[i][k] = a.weights[i][k] + (b.weights[i][k] - a.weights[i][k]) * alpha;
        }
        return target;
    }

    Pose emptyPose() {
        float[][] world = new float[components.size()][TRANSFORM], local = new float[components.size()][], weights = new float[components.size()][];
        for (int i = 0; i < local.length; i++) {
            if (frames[0].local[i] == null) continue;
            local[i] = new float[frames[0].local[i].length];
            weights[i] = new float[frames[0].weights[i].length];
        }
        return new Pose(world, local, weights, new float[components.size()][]);
    }

    /** Normalized quaternion interpolation on the shorter arc; linear translation and scale. */
    static void blend(float[] a, float[] b, float alpha, float[] out) {
        for (int at = 0; at < a.length; at += TRANSFORM) {
            float dot = a[at] * b[at] + a[at + 1] * b[at + 1] + a[at + 2] * b[at + 2] + a[at + 3] * b[at + 3];
            float sign = dot < 0 ? -1 : 1, length = 0;
            for (int k = 0; k < 4; k++) {
                out[at + k] = a[at + k] + (sign * b[at + k] - a[at + k]) * alpha;
                length += out[at + k] * out[at + k];
            }
            length = length > 0 ? (float) (1 / Math.sqrt(length)) : 0;
            for (int k = 0; k < 4; k++) out[at + k] *= length;
            for (int k = 4; k < TRANSFORM; k++) out[at + k] = a[at + k] + (b[at + k] - a[at + k]) * alpha;
        }
    }

    private static float[] floats(ByteBuffer data, int count) {
        float[] values = new float[count];
        for (int i = 0; i < count; i++) {
            values[i] = data.getFloat();
            require(Float.isFinite(values[i]), "non-finite transform");
        }
        return values;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
