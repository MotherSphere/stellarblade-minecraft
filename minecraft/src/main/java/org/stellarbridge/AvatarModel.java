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

/** Binds recorded EVE components to pack meshes and turns one pose into per-bone skinning matrices. */
final class AvatarModel {
    /** Unreal centimeters (X forward, Y right, Z up) to Minecraft blocks (X, Y up, Z): the bridge's axis mapping. */
    static final Matrix4d UNREAL_TO_MINECRAFT = new Matrix4d().zero().m00(0.01).m21(0.01).m12(0.01).m33(1);

    /**
     * A drawn component: its mesh, for leader-pose followers the leader bone for each mesh bone (-1: reference pose),
     * and for each mesh morph the component's recorded weight index (-1: weight stays zero).
     */
    record Part(int component, AvatarMesh mesh, int driver, int[] leaderBones, int[] morphWeights) { }

    final List<Part> parts;
    final List<String> skipped;
    final float[][] morphWeights; // Per part, in mesh morph order; filled by palettes.
    private final Matrix4d[][] componentSpace; // Per recorded component, reused every frame.
    private final Matrix4d world = new Matrix4d(), bone = new Matrix4d();

    AvatarModel(AvatarRecording recording, Map<String, AvatarMesh> meshes) {
        List<Part> parts = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        componentSpace = new Matrix4d[recording.components.size()][];
        for (int i = 0; i < recording.components.size(); i++) {
            AvatarRecording.Component c = recording.components.get(i);
            AvatarMesh mesh = meshes.get(c.mesh());
            if (mesh == null) {
                if (!c.mesh().equals("none")) skipped.add(c.name() + " (" + c.mesh() + " not in pack)");
                continue;
            }
            if (c.recorded()) {
                String[] names = Arrays.stream(mesh.bones).map(AvatarMesh.Bone::name).toArray(String[]::new);
                if (!Arrays.equals(names, c.bones())) throw new IllegalArgumentException(c.name() + ": recorded bones differ from " + mesh.name);
                parts.add(new Part(i, mesh, i, null, morphSources(mesh, c)));
                continue;
            }
            int leader = -1;
            for (int k = 0; k < recording.components.size(); k++)
                if (recording.components.get(k).name().equals(c.leader())) leader = k;
            AvatarMesh leaderMesh = leader < 0 ? null : meshes.get(recording.components.get(leader).mesh());
            if (leaderMesh == null || !recording.components.get(leader).recorded()) {
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
            AvatarMesh driver = meshes.get(recording.components.get(part.driver).mesh());
            if (componentSpace[part.driver] == null) {
                componentSpace[part.driver] = new Matrix4d[driver.bones.length];
                for (int k = 0; k < driver.bones.length; k++) componentSpace[part.driver][k] = new Matrix4d();
            }
        }
        this.parts = List.copyOf(parts);
        this.skipped = List.copyOf(skipped);
        morphWeights = new float[parts.size()][];
        for (int p = 0; p < parts.size(); p++) morphWeights[p] = new float[parts.get(p).mesh.morphs.length];
    }

    /** Recorded weights are in the component's MORPHS order; map them to mesh morphs by name. */
    private static int[] morphSources(AvatarMesh mesh, AvatarRecording.Component component) {
        Map<String, Integer> recorded = new HashMap<>();
        if (component.recorded() && component.weights() == component.morphs().length)
            for (int k = 0; k < component.morphs().length; k++) recorded.put(component.morphs()[k], k);
        int[] sources = new int[mesh.morphs.length];
        for (int m = 0; m < sources.length; m++) sources[m] = recorded.getOrDefault(mesh.morphs[m].name(), -1);
        return sources;
    }

    static AvatarModel load(Path pack, AvatarRecording recording) throws IOException {
        Map<String, AvatarMesh> meshes = new HashMap<>();
        for (AvatarRecording.Component c : recording.components) {
            Path file = pack.resolve(c.mesh() + ".sbav");
            if (!c.mesh().equals("none") && !meshes.containsKey(c.mesh()) && Files.isRegularFile(file))
                meshes.put(c.mesh(), AvatarMesh.read(file));
        }
        try {
            return new AvatarModel(recording, meshes);
        } catch (IllegalArgumentException failure) {
            throw new IOException(failure.getMessage(), failure);
        }
    }

    /**
     * Writes each part's skinning matrices as Minecraft-space affine rows (12 floats per bone, the GLSL mat3x4 layout).
     * placement maps Unreal world space to the target space, e.g. camera-relative Minecraft blocks.
     */
    void palettes(AvatarRecording.Pose pose, Matrix4d placement, FloatBuffer[] out) {
        for (int c = 0; c < componentSpace.length; c++) {
            Matrix4d[] cs = componentSpace[c];
            if (cs == null) continue;
            AvatarMesh mesh = driverMesh(c);
            float[] local = pose.local()[c];
            for (int k = 0; k < cs.length; k++) {
                AvatarMesh.transform(local, k * AvatarRecording.TRANSFORM, cs[k]);
                int parent = mesh.bones[k].parent();
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
            for (int k = 0; k < part.mesh.bones.length; k++) {
                int source = part.leaderBones == null ? k : part.leaderBones[k];
                if (source >= 0) world.mul(cs[source], bone).mul(part.mesh.inverseReference[k]);
                else bone.set(world); // A follower bone absent from the leader keeps its reference pose.
                for (int row = 0; row < 3; row++)
                    target.put((float) bone.get(0, row)).put((float) bone.get(1, row)).put((float) bone.get(2, row)).put((float) bone.get(3, row));
            }
            target.flip();
        }
    }

    private AvatarMesh driverMesh(int component) {
        for (Part part : parts) if (part.driver == component && part.leaderBones == null) return part.mesh;
        throw new IllegalStateException("Driver component without its own mesh");
    }
}
