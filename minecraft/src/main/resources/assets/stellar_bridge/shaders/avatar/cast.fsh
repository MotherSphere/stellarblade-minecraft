#version 150

#include shadow.glsl

uniform vec3 ShadowDirection; // Toward the light that casts EVE's shadow.
uniform float FogStart;
uniform float FogEnd;

in vec3 relative;
in vec3 normal;
in float brightness;
out vec4 fragColor;

void main() {
    float occluder;
    float lit = sunlit(relative, 0.0, occluder);
    if (lit >= 1.0) discard;
    // Minecraft's entity shadow: black at half opacity times the face's brightness, fading as the entity rises above
    // the face; here over EVE's silhouette, fading with her height above the face, on faces turned to the light.
    float height = occluder * ShadowDirection.y;
    float fade = 1.0 - smoothstep(2.0, 6.0, height);
    float facing = smoothstep(0.0, 0.15, dot(normal, ShadowDirection));
    float distance = length(relative);
    float fog = distance <= FogStart ? 0.0 : (distance < FogEnd ? smoothstep(FogStart, FogEnd, distance) : 1.0);
    fragColor = vec4(0.0, 0.0, 0.0, 0.5 * brightness * (1.0 - lit) * fade * facing * (1.0 - fog));
}
