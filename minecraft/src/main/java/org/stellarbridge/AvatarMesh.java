package org.stellarbridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.joml.Matrix4d;

/**
 * One skinned mesh from a private avatar pack (SBAVMESH 2, Unreal space: centimeters, Z up).
 * Vertices keep the file layout so they upload unchanged: 3 floats position, 3 floats normal,
 * 2 floats UV, 4 unsigned shorts bone indices, 4 floats weights. Morph targets follow the
 * mesh's Unreal MorphTargets order as sparse deltas: vertex, 3 floats position, 3 floats normal.
 */
final class AvatarMesh {
    static final int VERTEX_BYTES = 56, MAX_BONES = 256;
    record Bone(String name, int parent, float[] reference) { }
    record Section(String material, int firstIndex, int indexCount) { }
    record Morph(String name, int[] vertices, float[] deltas) { }

    final String name;
    final Bone[] bones;
    final ByteBuffer vertices, indices;
    final Section[] sections;
    final Morph[] morphs;
    final int vertexCount;
    final Matrix4d[] inverseReference; // Inverse bind pose in component space.

    private AvatarMesh(String name, Bone[] bones, ByteBuffer vertices, ByteBuffer indices, Section[] sections, Morph[] morphs) {
        this.name = name;
        this.bones = bones;
        this.vertices = vertices;
        this.indices = indices;
        this.sections = sections;
        this.morphs = morphs;
        this.vertexCount = vertices.capacity() / VERTEX_BYTES;
        Matrix4d[] reference = new Matrix4d[bones.length];
        inverseReference = new Matrix4d[bones.length];
        for (int i = 0; i < bones.length; i++) {
            Matrix4d local = transform(bones[i].reference, 0, new Matrix4d());
            reference[i] = bones[i].parent < 0 ? local : new Matrix4d(reference[bones[i].parent]).mul(local);
            inverseReference[i] = new Matrix4d(reference[i]).invert();
        }
    }

    static AvatarMesh read(Path file) throws IOException {
        String name = file.getFileName().toString().replaceFirst("\\.sbav$", "");
        try {
            return parse(name, ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN));
        } catch (RuntimeException failure) {
            throw new IOException(name + ": invalid avatar mesh (" + failure.getMessage() + ")", failure);
        }
    }

    static AvatarMesh parse(String name, ByteBuffer in) {
        byte[] magic = new byte[8];
        in.get(magic);
        require(new String(magic, StandardCharsets.US_ASCII).equals("SBAVMESH") && in.getInt() == 2, "not SBAVMESH version 2");
        int boneCount = in.getInt();
        require(boneCount > 0 && boneCount <= MAX_BONES, "unsupported bone count " + boneCount);
        Bone[] bones = new Bone[boneCount];
        for (int i = 0; i < boneCount; i++) {
            String bone = text(in);
            int parent = in.getInt();
            require(parent >= -1 && parent < i, "bone parents must precede children");
            float[] reference = new float[10];
            for (int k = 0; k < 10; k++) reference[k] = finite(in.getFloat());
            bones[i] = new Bone(bone, parent, reference);
        }
        int vertexCount = in.getInt();
        require(vertexCount > 0 && vertexCount <= in.remaining() / VERTEX_BYTES, "invalid vertex count");
        ByteBuffer vertices = slice(in, vertexCount * VERTEX_BYTES);
        for (int v = 0; v < vertexCount; v++) {
            int at = v * VERTEX_BYTES;
            for (int k = 0; k < 8; k++) finite(vertices.getFloat(at + 4 * k));
            for (int k = 0; k < 4; k++) {
                require(Short.toUnsignedInt(vertices.getShort(at + 32 + 2 * k)) < boneCount, "vertex bone index out of range");
                finite(vertices.getFloat(at + 40 + 4 * k));
            }
        }
        int indexCount = in.getInt();
        require(indexCount > 0 && indexCount % 3 == 0 && indexCount <= in.remaining() / 4, "invalid index count");
        ByteBuffer indices = slice(in, indexCount * 4);
        for (int i = 0; i < indexCount; i++)
            require(Integer.toUnsignedLong(indices.getInt(4 * i)) < vertexCount, "index out of range");
        int sectionCount = in.getInt();
        require(sectionCount > 0 && sectionCount <= 64, "invalid section count");
        Section[] sections = new Section[sectionCount];
        for (int i = 0; i < sectionCount; i++) {
            String material = text(in);
            long first = Integer.toUnsignedLong(in.getInt()), faces = Integer.toUnsignedLong(in.getInt());
            require(first % 3 == 0 && first + 3 * faces <= indexCount, "section outside the index buffer");
            sections[i] = new Section(material, (int) first, (int) (3 * faces));
        }
        int morphCount = in.getInt();
        require(morphCount >= 0 && morphCount <= 1024, "invalid morph count");
        Morph[] morphs = new Morph[morphCount];
        for (int m = 0; m < morphCount; m++) {
            String morph = text(in);
            int count = in.getInt();
            require(count >= 0 && count <= vertexCount && count <= in.remaining() / 28, "invalid morph delta count");
            int[] targets = new int[count];
            float[] deltas = new float[6 * count];
            for (int d = 0; d < count; d++) {
                targets[d] = in.getInt();
                require(targets[d] >= 0 && targets[d] < vertexCount, "morph vertex out of range");
                for (int k = 0; k < 6; k++) deltas[6 * d + k] = finite(in.getFloat());
            }
            morphs[m] = new Morph(morph, targets, deltas);
        }
        require(!in.hasRemaining(), "trailing bytes");
        return new AvatarMesh(name, bones, vertices, indices, sections, morphs);
    }

    /** Weighted sum of morph deltas per vertex (3 floats position, 3 floats normal) into target. */
    void morph(float[] weights, FloatBuffer target) {
        target.clear();
        for (int i = 0; i < 6 * vertexCount; i++) target.put(i, 0);
        for (int m = 0; m < morphs.length; m++) {
            float weight = weights[m];
            if (Math.abs(weight) < 1e-4f) continue;
            int[] targets = morphs[m].vertices;
            float[] deltas = morphs[m].deltas;
            for (int d = 0; d < targets.length; d++)
                for (int k = 0; k < 6; k++) target.put(6 * targets[d] + k, target.get(6 * targets[d] + k) + weight * deltas[6 * d + k]);
        }
        target.limit(6 * vertexCount);
    }

    /** Unreal FTransform (quaternion, translation, scale) as T * R * S for column vectors. */
    static Matrix4d transform(float[] values, int at, Matrix4d target) {
        return target.translationRotateScale(values[at + 4], values[at + 5], values[at + 6],
                values[at], values[at + 1], values[at + 2], values[at + 3], values[at + 7], values[at + 8], values[at + 9]);
    }

    private static ByteBuffer slice(ByteBuffer in, int bytes) {
        ByteBuffer copy = ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
        copy.put(in.slice(in.position(), bytes)).flip();
        in.position(in.position() + bytes);
        return copy;
    }
    private static String text(ByteBuffer in) {
        byte[] bytes = new byte[Short.toUnsignedInt(in.getShort())];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
    private static float finite(float value) {
        require(Float.isFinite(value), "non-finite value");
        return value;
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
