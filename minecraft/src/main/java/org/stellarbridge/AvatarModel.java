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
     * and for each mesh morph the component's recorded weight index (-1: weight stays zero).
     */
    record Part(int component, AvatarMesh mesh, int driver, int[] leaderBones, int[] morphWeights) { }

    final List<Part> parts;
    final List<String> skipped;
    final float[][] morphWeights; // Per part, in mesh morph order; filled by palettes.
    final float[][] cloth; // Per part, the pose's cloth positions (component space) or null; set by palettes.
    private final Matrix4d[][] componentSpace; // Per recorded component, reused every frame.
    private final Matrix4d world = new Matrix4d(), bone = new Matrix4d(), socket = new Matrix4d();

    /** Components come from a pose recording or a stream table; recorded ones carry their bone names. */
    AvatarModel(List<AvatarRecording.Component> components, Map<String, AvatarMesh> meshes) {
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
                    // The drone is optional: another skin's skeleton leaves it out; EVE's own parts must match.
                    if (!c.name().startsWith(AvatarStream.OPTIONAL)) throw new IllegalArgumentException(c.name() + ": recorded bones differ from " + mesh.name);
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
            Map<String, Integer> byName = new HashMap<>();
            for (int k = 0; k < leaderMesh.bones.length; k++) byName.put(leaderMesh.bones[k].name(), k);
            int[] map = new int[mesh.bones.length];
            for (int k = 0; k < map.length; k++) map[k] = byName.getOrDefault(mesh.bones[k].name(), -1);
            parts.add(new Part(i, mesh, leader, map, morphSources(mesh, c)));
        }
        for (Part part : parts) {
            AvatarMesh driver = meshes.get(components.get(part.driver).mesh());
            if (componentSpace[part.driver] == null) {
                componentSpace[part.driver] = new Matrix4d[driver.bones.length];
                for (int k = 0; k < driver.bones.length; k++) componentSpace[part.driver][k] = new Matrix4d();
            }
        }
        this.parts = List.copyOf(parts);
        this.skipped = List.copyOf(skipped);
        morphWeights = new float[parts.size()][];
        cloth = new float[parts.size()][];
        for (int p = 0; p < parts.size(); p++) morphWeights[p] = new float[parts.get(p).mesh.morphs.length];
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
        Map<String, AvatarMesh> meshes = new HashMap<>();
        for (AvatarRecording.Component c : components) {
            Path file = pack.resolve(c.mesh() + ".sbav");
            if (c.mesh().equals("none") || meshes.containsKey(c.mesh()) || !file.normalize().startsWith(pack.normalize())) continue;
            AvatarMesh mesh = cache.get(c.mesh());
            if (mesh == null && Files.isRegularFile(file)) cache.put(c.mesh(), mesh = AvatarMesh.read(file));
            if (mesh != null) meshes.put(c.mesh(), mesh);
        }
        try {
            return new AvatarModel(components, meshes);
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
            AvatarMesh mesh = driverMesh(c);
            float[] local = pose.local()[c];
            for (int k = 0; k < cs.length; k++) {
                AvatarMesh.transform(local, k * AvatarRecording.TRANSFORM, cs[k]);
                int parent = mesh.bones[k].parent();
                if (parent >= 0 && !componentSpaceBones) cs[parent].mul(cs[k], cs[k]);
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
                int source = part.leaderBones == null ? k : part.leaderBones[k];
                if (cloth[p] != null) bone.set(world);
                else if (source >= 0) world.mul(cs[source], bone).mul(part.mesh.inverseReference[k]);
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
}
