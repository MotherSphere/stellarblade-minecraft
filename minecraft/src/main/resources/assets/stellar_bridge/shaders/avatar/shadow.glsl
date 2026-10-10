uniform sampler2D Sampler7; // EVE's shadow map: depth seen from the key light, 1 where she is absent.
uniform mat4 ShadowMatrix; // Camera-relative blocks to the shadow map's clip space.
uniform float ShadowDepth; // Blocks between the shadow map's near and far planes.

// Share of the key light EVE leaves at a camera-relative point (1: lit) over a 3x3 texel tent, comparing depths less
// bias; occluder: how far toward the light, in blocks, the surfaces that block it lie (0 when lit).
float sunlit(vec3 at, float bias, out float occluder) {
    vec3 s = (ShadowMatrix * vec4(at, 1.0)).xyz * 0.5 + 0.5;
    float reference = min(s.z, 0.99999) - bias; // A point past the far plane is behind EVE, not behind the cleared depth.
    vec2 size = vec2(textureSize(Sampler7, 0)), texel = s.xy * size - 0.5, base = floor(texel), f = texel - base;
    float shade = 0.0, depth = 0.0;
    for (int y = -1; y <= 2; y++) for (int x = -1; x <= 2; x++) {
        float w = (x == -1 ? 1.0 - f.x : x == 2 ? f.x : 1.0) * (y == -1 ? 1.0 - f.y : y == 2 ? f.y : 1.0);
        float d = texelFetch(Sampler7, clamp(ivec2(base) + ivec2(x, y), ivec2(0), ivec2(size) - 1), 0).r;
        if (d < reference) { shade += w; depth += w * d; }
    }
    occluder = shade > 0.0 ? (s.z - depth / shade) * ShadowDepth : 0.0;
    return 1.0 - shade / 9.0;
}
