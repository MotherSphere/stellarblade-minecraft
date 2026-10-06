#version 150

in vec3 Position;
in vec3 Normal;
in vec2 UV0;
in uvec4 BoneIds;
in vec4 BoneWeights;
in vec3 MorphPosition;
in vec3 MorphNormal;

// Affine rows per bone, already in camera-relative Minecraft blocks.
uniform mat3x4 Bones[256];
uniform mat4 ViewProjection;

out vec2 texCoord;
out vec3 normal;
out vec3 relative;

void main() {
    mat3x4 skin = Bones[BoneIds.x] * BoneWeights.x + Bones[BoneIds.y] * BoneWeights.y
            + Bones[BoneIds.z] * BoneWeights.z + Bones[BoneIds.w] * BoneWeights.w;
    vec3 position = vec4(Position + MorphPosition, 1.0) * skin;
    gl_Position = ViewProjection * vec4(position, 1.0);
    texCoord = UV0;
    normal = vec4(Normal + MorphNormal, 0.0) * skin;
    relative = position;
}
