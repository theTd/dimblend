// Headlight occlusion lookup, shared by the surface and haze passes and the distance-map trace.
// Veil prepends every #include to the top of the including file: programs include only this file (or volume,
// which includes this one), and beam comes in nested here.
#include dimblend_experience:headlight/beam

// Per-lamp distance map: tile `lamp` of an R32F atlas (OcclusionResolution square tiles stacked along y).
// Texel = direction from the lens through the tangent plane at unit distance, scaled by the trace cone; the
// value is the distance from the lens to the first occluding block along that direction.
uniform sampler2D OcclusionSampler;
uniform int OcclusionEnabled;
uniform int OcclusionResolution;
uniform float OcclusionTanHalf;

// A receiver farther than the stored distance by more than this is shadowed. Surfaces add a normal offset,
// so the block face that ended the trace is never its own occluder.
const float OcclusionBias = 0.06;

// Orthonormal lamp frame; the trace and the lookups must build the identical basis.
void lampBasis(vec3 axis, out vec3 tangent, out vec3 bitangent) {
    vec3 up = abs(axis.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
    tangent = normalize(cross(up, axis));
    bitangent = cross(axis, tangent);
}

// 0 = lit .. 1 = shadowed fraction is the complement; 2x2 percentage-closer filtering over the map texels.
float visibility(vec3 position, int lamp) {
    if (OcclusionEnabled == 0) return 1.0;
    vec3 offset = position - LampPosition[lamp];
    float distance = length(offset);
    vec3 axis = LampDirection[lamp];
    float z = dot(offset, axis);
    if (z <= 1e-3 * distance) return 1.0;
    vec3 tangent, bitangent;
    lampBasis(axis, tangent, bitangent);
    vec2 plane = vec2(dot(offset, tangent), dot(offset, bitangent)) / (z * OcclusionTanHalf);
    if (any(greaterThan(abs(plane), vec2(1.0)))) return 1.0;
    float resolution = float(OcclusionResolution);
    vec2 texel = (plane * 0.5 + 0.5) * resolution - 0.5;
    ivec2 base = ivec2(floor(texel));
    vec2 f = texel - vec2(base);
    ivec2 tile = ivec2(0, lamp * OcclusionResolution);
    float lit = 0.0;
    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 2; ++x) {
            ivec2 at = clamp(base + ivec2(x, y), ivec2(0), ivec2(OcclusionResolution - 1)) + tile;
            float hit = texelFetch(OcclusionSampler, at, 0).r;
            vec2 w = mix(1.0 - f, f, vec2(x, y));
            lit += distance <= hit + OcclusionBias ? w.x * w.y : 0.0;
        }
    }
    return lit;
}
