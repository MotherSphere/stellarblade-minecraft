#version 150

in vec3 Position; // Camera-relative blocks.
in vec3 Normal;
in float Brightness; // Minecraft's brightness of the light level in front of the face.

uniform mat4 ViewProjection;

out vec3 relative;
out vec3 normal;
out float brightness;

void main() {
    // As Minecraft's VIEW_OFFSET_Z_LAYERING for its entity shadow: slightly toward the camera, over the face it darkens.
    gl_Position = ViewProjection * vec4(Position * 0.99975586, 1.0);
    relative = Position;
    normal = Normal;
    brightness = Brightness;
}
