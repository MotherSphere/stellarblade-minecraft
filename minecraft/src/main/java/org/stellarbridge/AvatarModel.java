package org.stellarbridge;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Binds recorded EVE components to pack meshes and turns one pose into per-bone skinning matrices. */
final class AvatarModel {
    /** Unreal centimeters (X forward, Y right, Z up) to Minecraft blocks (X, Y up, Z): the bridge's axis mapping. */
    static final Matrix4d UNREAL_TO_MINECRAFT = unrealToMinecraft(100);
    static Matrix4d unrealToMinecraft(double unitsPerBlock) {
        double s = 1 / unitsPerBlock;
        return new Matrix4d().zero().m00(s).m21(s).m12(s).m33(1);
    }

    /**
     * A drawn component: its mesh, for leader-pose followers the leader bone for each mesh bone (-1: reference pose),
     * and for each mesh morph the component's recorded weight index (-1: weight stays zero). bind (a part driven by name,
     * lenient): for each mesh bone, the bone whose skinning it takes, itself or its nearest ancestor the driver has, so a
     * bone of another suit's skeleton follows that ancestor rigidly instead of keeping the reference pose; -1: none.
     */
    record Part(int component, AvatarMesh mesh, int driver, int[] leaderBones, int[] morphWeights, int[] bind) {
        Part(int component, AvatarMesh mesh, int driver, int[] leaderBones, int[] morphWeights) {
            this(component, mesh, driver, leaderBones, morphWeights, null);
        }
    }

    final List<Part> parts;
    final List<String> skipped;
    final float[][] morphWeights; // Per part, in mesh morph order; filled by palettes.
    final float[][] cloth; // Per part, the pose's cloth positions (component space) or null; set by palettes.
    private final Matrix4d[][] componentSpace; // Per recorded component, reused every frame.
    private final Matrix4d world = new Matrix4d(), bone = new Matrix4d(), socket = new Matrix4d();

    /** Components come from a pose recording or a stream table; recorded ones carry their bone names. */
    AvatarModel(List<AvatarRecording.Component> components, Map<String, AvatarMesh> meshes) {
        this(components, meshes, false);
    }
    /**
     * lenient (another player's EVE drawn from this pack): a recorded component whose bones differ from its mesh's (a
     * substituted or stale mesh) drives it by bone name, as followers are driven; mesh bones it lacks keep their reference
     * pose, and a mesh sharing no bone with it is skipped. Such a part needs component-space bones (the stream's).
     */
    AvatarModel(List<AvatarRecording.Component> components, Map<String, AvatarMesh> meshes, boolean lenient) {
        List<Part> parts = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        componentSpace = new Matrix4d[components.size()][];
        for (int i = 0; i < components.size(); i++) {
            AvatarRecording.Component c = components.get(i);
            AvatarMesh mesh = meshes.get(c.mesh());
            if (mesh == null) {
                if (!c.mesh().equals("none")) skipped.add(c.name() + " (" + c.mesh() + " not in pack)");
                continue;
            }
            if (c.recorded()) {
                String[] names = Arrays.stream(mesh.bones).map(AvatarMesh.Bone::name).toArray(String[]::new);
                if (!Arrays.equals(names, c.bones())) {
                    int[] map = lenient ? byName(mesh, c.bones()) : null;
                    if (map != null && Arrays.stream(map).anyMatch(k -> k >= 0)) {
                        int[] bind = new int[map.length];
                        for (int k = 0; k < map.length; k++) bind[k] = map[k] >= 0 || mesh.bones[k].parent() < 0 ? k : bind[mesh.bones[k].parent()];
                        parts.add(new Part(i, mesh, i, map, morphSources(mesh, c), bind));
                        continue;
                    }
                    // The drone is optional: another skin's skeleton leaves it out; EVE's own parts must match.
                    if (!lenient && !c.name().startsWith(AvatarStream.OPTIONAL)) throw new IllegalArgumentException(c.name() + ": recorded bones differ from " + mesh.name);
                    skipped.add(c.name() + " (bones differ from " + mesh.name + ")");
                    continue;
                }
                parts.add(new Part(i, mesh, i, null, morphSources(mesh, c)));
                continue;
            }
            int leader = -1;
            for (int k = 0; k < components.size(); k++)
                if (components.get(k).name().equals(c.leader())) leader = k;
            AvatarMesh leaderMesh = leader < 0 ? null : meshes.get(components.get(leader).mesh());
            if (leaderMesh == null || !components.get(leader).recorded()) {
                skipped.add(c.name() + " (leader " + c.leader() + " not drawable)");
                continue;
            }
            parts.add(new Part(i, mesh, leader, byName(mesh, components.get(leader).bones()), morphSources(mesh, c)));
        }
        for (Part part : parts) {
            int bones = components.get(part.driver).bones().length; // The driver's streamed (or recorded) bones.
            if (componentSpace[part.driver] == null) {
                componentSpace[part.driver] = new Matrix4d[bones];
                for (int k = 0; k < bones; k++) componentSpace[part.driver][k] = new Matrix4d();
            }
        }
        this.parts = List.copyOf(parts);
        this.skipped = List.copyOf(skipped);
        morphWeights = new float[parts.size()][];
        cloth = new float[parts.size()][];
        for (int p = 0; p < parts.size(); p++) morphWeights[p] = new float[parts.get(p).mesh.morphs.length];
    }

    /** For each mesh bone, the index of the driver bone of the same name, or -1. */
    private static int[] byName(AvatarMesh mesh, String[] driverBones) {
        Map<String, Integer> index = new HashMap<>();
        for (int k = 0; k < driverBones.length; k++) index.put(driverBones[k], k);
        int[] map = new int[mesh.bones.length];
        for (int k = 0; k < map.length; k++) map[k] = index.getOrDefault(mesh.bones[k].name(), -1);
        return map;
    }

    /** Recorded weights are in the component's MORPHS order; map them to mesh morphs by name. */
    private static int[] morphSources(AvatarMesh mesh, AvatarRecording.Component component) {
        Map<String, Integer> recorded = new HashMap<>();
        if (component.weights() == component.morphs().length)
            for (int k = 0; k < component.morphs().length; k++) recorded.put(component.morphs()[k], k);
        int[] sources = new int[mesh.morphs.length];
        for (int m = 0; m < sources.length; m++) sources[m] = recorded.getOrDefault(mesh.morphs[m].name(), -1);
        return sources;
    }

    /** Loads the pack meshes the components name; cache keeps meshes already read for a later table. */
    static AvatarModel load(Path pack, List<AvatarRecording.Component> components, Map<String, AvatarMesh> cache) throws IOException {
        return load(pack, components, cache, false);
    }
    /** The same, lenient as the constructor's. */
    static AvatarModel load(Path pack, List<AvatarRecording.Component> components, Map<String, AvatarMesh> cache, boolean lenient) throws IOException {
        Map<String, AvatarMesh> meshes = new HashMap<>();
        for (AvatarRecording.Component c : components) {
            Path file = pack.resolve(c.mesh() + ".sbav");
            if (c.mesh().equals("none") || meshes.containsKey(c.mesh()) || !file.normalize().startsWith(pack.normalize())) continue;
            AvatarMesh mesh = cache.get(c.mesh());
            if (mesh == null && Files.isRegularFile(file)) cache.put(c.mesh(), mesh = AvatarMesh.read(file));
            if (mesh != null) meshes.put(c.mesh(), mesh);
        }
        try {
            return new AvatarModel(components, meshes, lenient);
        } catch (IllegalArgumentException failure) {
            throw new IOException(failure.getMessage(), failure);
        }
    }

    /**
     * Drops from cache, least recently used first (an access-ordered map), meshes model does not draw while it holds more
     * than budget bytes.
     */
    static void trim(Map<String, AvatarMesh> cache, AvatarModel model, long budget) {
        long total = 0;
        for (AvatarMesh mesh : cache.values()) total += mesh.bytes();
        List<AvatarMesh> drawn = model.parts.stream().map(Part::mesh).toList();
        for (var it = cache.values().iterator(); total > budget && it.hasNext(); ) {
            AvatarMesh mesh = it.next();
            if (drawn.contains(mesh)) continue;
            total -= mesh.bytes();
            it.remove();
        }
    }

    /**
     * Writes each part's skinning matrices as Minecraft-space affine rows (12 floats per bone, the GLSL mat3x4 layout).
     * placement maps Unreal world space to the target space, e.g. camera-relative Minecraft blocks. Bone transforms are
     * parent-relative (recordings) or already in component space (the native stream). A part with cloth in the pose gets
     * its component's world transform for every bone: its weights sum to 1, so the vertex shader then places the cloth
     * positions its morph deltas carry exactly.
     */
    void palettes(AvatarRecording.Pose pose, Matrix4d placement, FloatBuffer[] out, boolean componentSpaceBones) {
        for (int c = 0; c < componentSpace.length; c++) {
            Matrix4d[] cs = componentSpace[c];
            if (cs == null) continue;
            AvatarMesh mesh = componentSpaceBones ? null : driverMesh(c);
            float[] local = pose.local()[c];
            for (int k = 0; k < cs.length; k++) {
                AvatarMesh.transform(local, k * AvatarRecording.TRANSFORM, cs[k]);
                int parent = mesh == null ? -1 : mesh.bones[k].parent();
                if (parent >= 0) cs[parent].mul(cs[k], cs[k]);
            }
        }
        for (int p = 0; p < parts.size(); p++) {
            Part part = parts.get(p);
            AvatarMesh.transform(pose.world()[part.component], 0, world);
            placement.mul(world, world);
            for (int m = 0; m < part.morphWeights.length; m++)
                morphWeights[p][m] = part.morphWeights[m] < 0 ? 0 : pose.weights()[part.component][part.morphWeights[m]];
            FloatBuffer target = out[p].clear();
            Matrix4d[] cs = componentSpace[part.driver];
            cloth[p] = pose.cloth()[part.component];
            for (int k = 0; k < part.mesh.bones.length; k++) {
                int b = part.bind == null ? k : part.bind[k], source = part.leaderBones == null ? k : part.leaderBones[b];
                if (cloth[p] != null) bone.set(world);
                else if (source >= 0) world.mul(cs[source], bone).mul(part.mesh.inverseReference[b]);
                else bone.set(world); // A follower bone absent from the leader keeps its reference pose.
                for (int row = 0; row < 3; row++)
                    target.put((float) bone.get(0, row)).put((float) bone.get(1, row)).put((float) bone.get(2, row)).put((float) bone.get(3, row));
            }
            target.flip();
        }
    }

    /** After palettes with the same placement: a point in a part's bone space (Unreal units) as camera-relative blocks. */
    Vector3d point(AvatarRecording.Pose pose, Matrix4d placement, int part, int bone, Vector3d local, Vector3d out) {
        Part p = parts.get(part);
        int source = p.leaderBones == null ? bone : p.leaderBones[bone];
        AvatarMesh.transform(pose.world()[p.component], 0, socket);
        placement.mul(socket, socket);
        if (source >= 0) socket.mul(componentSpace[p.driver][source]);
        return socket.transformPosition(local, out);
    }

    private AvatarMesh driverMesh(int component) {
        for (Part part : parts) if (part.driver == component && part.leaderBones == null) return part.mesh;
        throw new IllegalStateException("Driver component without its own mesh");
    }

    /**
     * Calms another player's ponytail, drawn from bones (research 2026-10-08, ponytail wobble, option C): Stellar Blade's
     * Ab-TL-HairB01..09 chain twists and zig-zags from frame to frame. Per received pose, the chain's joints in head space
     * take a 1-2-1 average along the chain, then an exponential average over time (EMA, 0.5 per 60 Hz frame whatever the
     * pose rate, as validated); each bone's rotation is then
     * rebuilt as its bind rotation under its filtered parent, aimed (bone -Y) at the next filtered joint, so it carries no
     * twist of its own. Measured on the 15 s probe recording: joint bend 35 to 16 degrees, velocity reversals 25 to 8 %,
     * at about one pose of extra tail lag. ponytail: no alpha clamp on the tail; remotes rarely extrapolate (drawn 1.5
     * intervals back).
     */
    static final class HairFilter {
        static final String HEAD = "Bip001-Head", CHAIN = "Ab-TL-HairB0";
        static final int LENGTH = 9;
        static final double EMA = 0.5, FRAME = 1000.0 / 60; // The EMA's weight per FRAME milliseconds.
        private final int component, head;
        private final int[] chain = new int[LENGTH]; // Bone indices in the component's streamed bones.
        private final Quaterniond[] bind = new Quaterniond[LENGTH]; // Each chain bone's bind rotation in its parent.
        private final Vector3d[] smoothed = new Vector3d[LENGTH]; // Head-space joints after the filter, null before a pose.
        private final Vector3d[] joints = new Vector3d[LENGTH], spatial = new Vector3d[LENGTH];
        private final Quaterniond headRotation = new Quaterniond(), inverse = new Quaterniond(), parent = new Quaterniond(),
                rotation = new Quaterniond(), aim = new Quaterniond();
        private final Vector3d axis = new Vector3d(), toward = new Vector3d(), headAt = new Vector3d();

        private HairFilter(int component, int head, int[] chain, Quaterniond[] bind) {
            this.component = component;
            this.head = head;
            System.arraycopy(chain, 0, this.chain, 0, LENGTH);
            System.arraycopy(bind, 0, this.bind, 0, LENGTH);
            for (int k = 0; k < LENGTH; k++) { joints[k] = new Vector3d(); spatial[k] = new Vector3d(); }
        }

        /**
         * The filter for a model's tail: the first recorded component streaming the head and the whole chain, with the
         * bind rotations of a drawn mesh holding that chain under the head; null when there is none.
         */
        static HairFilter of(AvatarModel model, List<AvatarRecording.Component> components) {
            for (int c = 0; c < components.size(); c++) {
                String[] bones = components.get(c).bones();
                if (bones == null) continue;
                List<String> names = Arrays.asList(bones);
                int head = names.indexOf(HEAD);
                int[] chain = new int[LENGTH];
                for (int k = 0; k < LENGTH; k++) chain[k] = names.indexOf(CHAIN + (k + 1));
                if (head < 0 || Arrays.stream(chain).anyMatch(k -> k < 0)) continue;
                for (Part part : model.parts) {
                    if (part.driver != c) continue;
                    Quaterniond[] bind = bind(part.mesh);
                    if (bind != null) return new HairFilter(c, head, chain, bind);
                }
            }
            return null;
        }
        private static Quaterniond[] bind(AvatarMesh mesh) {
            Map<String, Integer> index = new HashMap<>();
            for (int k = 0; k < mesh.bones.length; k++) index.put(mesh.bones[k].name(), k);
            Quaterniond[] bind = new Quaterniond[LENGTH];
            Integer previous = index.get(HEAD);
            for (int k = 0; k < LENGTH; k++) {
                Integer bone = index.get(CHAIN + (k + 1));
                if (previous == null || bone == null || mesh.bones[bone].parent() != previous) return null;
                float[] r = mesh.bones[bone].reference();
                bind[k] = new Quaterniond(r[0], r[1], r[2], r[3]).normalize();
                previous = bone;
            }
            return bind;
        }

        /** Filters one decoded pose's chain in place (component-space bones of the stream), elapsed ms after the previous one. */
        void apply(AvatarRecording.Pose pose, double elapsed) {
            double weight = 1 - Math.pow(1 - EMA, Math.max(0, elapsed) / FRAME);
            float[] local = pose.local()[component];
            if (local == null) return;
            int h = head * AvatarRecording.TRANSFORM;
            headRotation.set(local[h], local[h + 1], local[h + 2], local[h + 3]).normalize();
            headRotation.invert(inverse);
            headAt.set(local[h + 4], local[h + 5], local[h + 6]);
            for (int k = 0; k < LENGTH; k++) {
                int at = chain[k] * AvatarRecording.TRANSFORM;
                inverse.transform(joints[k].set(local[at + 4], local[at + 5], local[at + 6]).sub(headAt));
            }
            for (int k = 0; k < LENGTH; k++) {
                if (k == 0 || k == LENGTH - 1) spatial[k].set(joints[k]);
                else spatial[k].set(joints[k]).mul(0.5).fma(0.25, joints[k - 1]).fma(0.25, joints[k + 1]);
                if (smoothed[k] == null) smoothed[k] = new Vector3d(spatial[k]);
                else smoothed[k].lerp(spatial[k], weight);
            }
            parent.set(headRotation);
            for (int k = 0; k < LENGTH; k++) {
                parent.mul(bind[k], rotation);
                if (k + 1 < LENGTH) {
                    rotation.transform(axis.set(0, -1, 0));
                    headRotation.transform(toward.set(smoothed[k + 1]).sub(smoothed[k]));
                    if (toward.lengthSquared() > 1e-6) rotation.premul(aim.rotationTo(axis, toward.normalize()));
                }
                rotation.normalize();
                int at = chain[k] * AvatarRecording.TRANSFORM;
                headRotation.transform(toward.set(smoothed[k])).add(headAt);
                local[at] = (float) rotation.x; local[at + 1] = (float) rotation.y; local[at + 2] = (float) rotation.z; local[at + 3] = (float) rotation.w;
                local[at + 4] = (float) toward.x; local[at + 5] = (float) toward.y; local[at + 6] = (float) toward.z;
                parent.set(rotation);
            }
        }
    }
}
