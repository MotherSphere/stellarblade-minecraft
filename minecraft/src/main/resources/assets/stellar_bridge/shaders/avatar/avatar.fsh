#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler2;
uniform ivec2 LightUv;
uniform float FogStart;
uniform float FogEnd;
uniform vec4 FogColor;

in vec2 texCoord;
in vec3 normal;
in vec3 relative;
out vec4 fragColor;

// Minecraft's world-fixed entity light directions and mix (0.6 directional, 0.4 ambient).
const vec3 LIGHT0 = normalize(vec3(0.2, 1.0, -0.7));
const vec3 LIGHT1 = normalize(vec3(-0.2, 1.0, 0.7));

void main() {
    vec4 color = texture(Sampler0, texCoord);
    if (color.a < 0.5) discard;
    vec3 n = normalize(normal);
    if (dot(n, relative) > 0.0) n = -n; // Two-sided cards: light the side facing the camera.
    float diffuse = min(1.0, (max(0.0, dot(LIGHT0, n)) + max(0.0, dot(LIGHT1, n))) * 0.6 + 0.4);
    vec3 lit = color.rgb * diffuse * texelFetch(Sampler2, LightUv, 0).rgb;
    float vertexDistance = length(relative);
    float fog = vertexDistance <= FogStart ? 0.0 : (vertexDistance < FogEnd ? smoothstep(FogStart, FogEnd, vertexDistance) : 1.0);
    fragColor = vec4(mix(lit, FogColor.rgb, fog * FogColor.a), 1.0);
}
