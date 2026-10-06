#version 150

uniform sampler2D Sampler0; // The game's trail gradient in red, its streak noise in green.
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in vec2 texCoord;
in vec4 color;
in float vertexDistance;
out vec4 fragColor;

void main() {
    vec4 trail = texture(Sampler0, texCoord);
    // Unreal lifts this gradient's bright band through HDR and bloom while its dim rest stays faint: keep the band only.
    float glow = smoothstep(0.15, 0.8, trail.r) * (0.7 + 0.6 * trail.g) * color.a;
    float fog = vertexDistance <= FogStart ? 0.0 : (vertexDistance < FogEnd ? smoothstep(FogStart, FogEnd, vertexDistance) : 1.0);
    fragColor = vec4(color.rgb * glow * (1.0 - fog * FogColor.a), 0.0); // Additive: alpha stays.
}
