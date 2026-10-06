#version 150

uniform sampler2D Sampler0; // An effect texture's brightness in red.
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in vec2 texCoord;
in vec4 color; // Tint and intensity already multiplied in.
in float vertexDistance;
out vec4 fragColor;

void main() {
    float glow = texture(Sampler0, texCoord).r;
    float fog = vertexDistance <= FogStart ? 0.0 : (vertexDistance < FogEnd ? smoothstep(FogStart, FogEnd, vertexDistance) : 1.0);
    fragColor = vec4(color.rgb * glow * (1.0 - fog * FogColor.a), 0.0); // Additive: alpha stays.
}
