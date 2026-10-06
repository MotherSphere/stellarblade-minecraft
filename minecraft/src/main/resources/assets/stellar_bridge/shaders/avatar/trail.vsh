#version 150

in vec3 Position; // Camera-relative Minecraft blocks.
in vec2 UV0;      // Along the trail from its head (0) to its tail (1); across from the first (0) to the second socket (1).
in vec4 Color;    // Tint and intensity.

uniform mat4 ViewProjection;

out vec2 texCoord;
out vec4 color;
out float vertexDistance;

void main() {
    gl_Position = ViewProjection * vec4(Position, 1.0);
    texCoord = UV0;
    color = Color;
    vertexDistance = length(Position);
}
