#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform mat4 MinecraftProjection;
uniform float UnitsPerBlock;
uniform float AlphaCutoff;
in vec2 captureUv;
out vec4 fragColor;

void main() {
    vec4 color = texture(Sampler0, captureUv);
    float distance = texture(Sampler1, captureUv).r / UnitsPerBlock;
    if (color.a < AlphaCutoff || !(distance > 0.0) || isnan(distance) || isinf(distance)) discard;
    vec4 clip = MinecraftProjection * vec4(0.0, 0.0, -distance, 1.0);
    float depth = 0.5 * (clip.z / clip.w) + 0.5;
    if (isnan(depth) || isinf(depth) || depth < 0.0 || depth > 1.0) discard;
    gl_FragDepth = depth;
    fragColor = color;
}
