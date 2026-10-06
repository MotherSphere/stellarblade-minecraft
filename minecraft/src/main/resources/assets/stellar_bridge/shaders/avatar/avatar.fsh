#version 150

uniform sampler2D Sampler0; // Base color; alpha below AlphaCutoff is cut out. Overlays: premultiplied color.
uniform sampler2D Sampler1; // Tangent-space normal, Unreal convention: +X along +U, +Y along +V.
uniform sampler2D Sampler2; // Minecraft's lightmap.
uniform sampler2D Sampler3; // Surface: occlusion, roughness, metallic, specular.
uniform sampler2D Sampler4; // Emissive color.
uniform sampler2D Sampler5; // Hair: root (1) to tip (0), strand id, depth.
uniform sampler2D Sampler6; // Subsurface color: light scattered past the terminator; black for none.
uniform int Hair;
uniform int Overlay; // Translucent pass: premultiplied output, alpha 0 adds light.
uniform float Opacity; // Streamed opacity of a glow overlay; 1 otherwise.
uniform vec3 EmissiveTint; // Streamed emissive color times intensity of a glow overlay; white otherwise.
uniform vec3 SkyColor;
uniform float AlphaCutoff;
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

vec3 schlick(vec3 f0, float cosine) {
    return f0 + (1.0 - f0) * pow(1.0 - cosine, 5.0);
}

// Normalized Blinn-Phong lobe for one entity light.
vec3 specular(vec3 n, vec3 view, vec3 light, float power, vec3 f0) {
    vec3 h = normalize(light + view);
    return (power + 8.0) / 8.0 * pow(max(dot(n, h), 0.0), power) * schlick(f0, max(dot(view, h), 0.0)) * max(dot(n, light), 0.0);
}

// Unreal's analytic environment BRDF (Karis): scale and bias applied to f0 for a roughness and view angle.
vec2 environmentBrdf(float roughness, float nv) {
    vec4 r = roughness * vec4(-1.0, -0.0275, -0.572, 0.022) + vec4(1.0, 0.0425, 1.04, -0.04);
    float a004 = min(r.x * r.x, exp2(-9.28 * nv)) * r.x + r.y;
    return vec2(-1.04, 1.04) * a004 + r.zw;
}

// Kajiya-Kay strand highlight: strongest where the half vector is perpendicular to the strand, faded on the unlit side.
float strand(vec3 tangent, vec3 n, vec3 view, vec3 light, float power) {
    float th = dot(tangent, normalize(light + view));
    return pow(sqrt(max(1.0 - th * th, 0.0)), power) * smoothstep(-0.1, 0.3, dot(n, light));
}

void main() {
    // Every texture sample and derivative comes before the cut-out discard.
    vec4 base = texture(Sampler0, texCoord);
    vec3 mapped = texture(Sampler1, texCoord).xyz * 2.0 - 1.0;
    vec4 surface = texture(Sampler3, texCoord);
    vec3 emissive = texture(Sampler4, texCoord).rgb;
    vec4 hair = texture(Sampler5, texCoord);
    vec3 scatter = texture(Sampler6, texCoord).rgb;
    vec3 dp1 = dFdx(relative), dp2 = dFdy(relative);
    vec2 duv1 = dFdx(texCoord), duv2 = dFdy(texCoord);
    if (base.a < AlphaCutoff) discard;

    vec3 view = normalize(-relative);
    vec3 n = normalize(normal);
    if (dot(n, view) < 0.0) n = -n; // Two-sided cards: light the side facing the camera.
    // Cotangent frame from screen-space derivatives: directions of increasing U and V on the surface.
    vec3 dp2perp = cross(dp2, n), dp1perp = cross(n, dp1);
    vec3 tangent = dp2perp * duv1.x + dp1perp * duv2.x;
    vec3 bitangent = dp2perp * duv1.y + dp1perp * duv2.y;
    float frame = inversesqrt(max(max(dot(tangent, tangent), dot(bitangent, bitangent)), 1e-30));
    tangent *= frame;
    bitangent *= frame;

    vec3 light = texelFetch(Sampler2, LightUv, 0).rgb;
    vec3 lit;
    if (Hair == 1) {
        // Strands run along V from root to tip. The depth channel shifts each strand's highlights.
        vec3 along = normalize(bitangent);
        float shift = hair.b - 0.5;
        vec3 primary = normalize(along + n * (0.08 + 0.25 * shift)), secondary = normalize(along + n * (-0.12 + 0.25 * shift));
        float occlusion = mix(1.0, 0.55, hair.r * hair.r); // Roots sit inside the hair volume.
        float diffuse = 0.0;
        vec3 shine = vec3(0.0);
        for (int k = 0; k < 2; k++) {
            vec3 l = k == 0 ? LIGHT0 : LIGHT1;
            diffuse += max(0.0, 0.3 + 0.7 * dot(n, l)) * 0.6;
            shine += (vec3(0.18) * strand(primary, n, view, l, 120.0) + base.rgb * 0.35 * strand(secondary, n, view, l, 30.0)) * 0.6;
        }
        diffuse = min(1.0, diffuse + 0.4) * occlusion;
        lit = (base.rgb * diffuse + shine * (0.4 + 0.6 * (1.0 - hair.r))) * light;
    } else {
        vec3 shading = normalize(tangent * mapped.x + bitangent * mapped.y + n * max(mapped.z, 0.05));
        float occlusion = surface.r, roughness = clamp(surface.g, 0.06, 1.0), metallic = surface.b;
        // Occlusion darkens the ambient share only, as in Unreal, not the entity lights. Subsurface scattering lets
        // light wrap past the terminator, tinted by the scatter color, along the smooth geometric normal rather than
        // the pores of the normal map.
        vec3 diffuse = vec3(0.4 * occlusion);
        for (int k = 0; k < 2; k++) {
            vec3 l = k == 0 ? LIGHT0 : LIGHT1;
            float lambert = max(dot(shading, l), 0.0), wrapped = max((dot(n, l) + 0.5) / 1.5, 0.0);
            diffuse += (lambert + scatter * (wrapped - lambert)) * 0.6;
        }
        diffuse = min(diffuse, vec3(1.0));
        vec3 f0 = mix(vec3(0.08 * surface.a), base.rgb, metallic);
        float alpha = roughness * roughness, power = clamp(2.0 / (alpha * alpha) - 2.0, 2.0, 4096.0); // GGX-like width.
        vec3 shine = (specular(shading, view, LIGHT0, power, f0) + specular(shading, view, LIGHT1, power, f0)) * 0.6;
        // Reflected environment: Minecraft's sky above the horizon, its fog color at the horizon and a darker ground
        // below; rough surfaces see the average. It reaches EVE in proportion to the sky light at her position.
        vec3 r = reflect(-view, shading), horizon = FogColor.rgb;
        vec3 environment = r.y >= 0.0 ? mix(horizon, SkyColor, smoothstep(0.0, 0.6, r.y))
                : horizon * mix(1.0, 0.35, smoothstep(0.0, 0.4, -r.y));
        environment = mix(environment, (SkyColor + horizon) * 0.4, roughness);
        vec2 brdf = environmentBrdf(roughness, max(dot(shading, view), 0.0));
        vec3 reflection = environment * (f0 * brdf.x + brdf.y) * (float(LightUv.y) / 15.0) * occlusion;
        // Under a roof, metals still catch the local light.
        vec3 ambient = f0 * (1.0 - 0.6 * roughness) * 0.2 * occlusion;
        lit = (base.rgb * (1.0 - metallic) * diffuse + shine + ambient) * light + reflection;
    }
    lit += emissive * EmissiveTint;
    float vertexDistance = length(relative);
    float fog = vertexDistance <= FogStart ? 0.0 : (vertexDistance < FogEnd ? smoothstep(FogStart, FogEnd, vertexDistance) : 1.0);
    float opacity = Overlay == 1 ? base.a * Opacity : 1.0;
    if (Overlay == 1) lit *= Opacity;
    fragColor = vec4(mix(lit, FogColor.rgb * opacity, fog * FogColor.a), opacity);
}
