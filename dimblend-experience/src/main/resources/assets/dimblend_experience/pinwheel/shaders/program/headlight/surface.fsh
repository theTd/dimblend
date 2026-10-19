#version 410 core

#include dimblend_experience:headlight/beam

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform sampler2D AlbedoSampler;
uniform sampler2D NormalSampler;
uniform int DebugView;

vec3 surfaceLight(vec3 position, vec3 normal, vec3 albedo) {
    vec3 added = vec3(0.0);
    for (int i = 0; i < LampCount; ++i) {
        vec3 offset = position - LampPosition[i];
        float distance = length(offset);
        if (distance >= Range) continue;
        vec3 direction = offset / max(distance, 0.0001);
        float cone = coneFactor(dot(direction, LampDirection[i]));
        // Slightly wrapped Lambert; reverse-facing surfaces still receive zero.
        float diffuse = clamp((dot(normal, -direction) + 0.15) / 1.15, 0.0, 1.0);
        // Later voxel visibility multiplies this contribution; volumetrics use the same frame beams.
        added += albedo * HeadlightColor * cone * attenuation(distance) * diffuse;
    }
    return added;
}

void main() {
    vec4 albedoSample = texture(AlbedoSampler, texCoord);
    vec3 normalVS = texture(NormalSampler, texCoord).xyz;
    if (DebugView == 1) { fragColor = vec4(albedoSample.rgb, 1.0); return; }
    if (DebugView == 2) { fragColor = vec4(normalVS * 0.5 + 0.5, 1.0); return; }
    float depth = texture(DepthSampler, texCoord).r;
    if (depth >= 1.0) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec3 viewPosition = reconstructView(texCoord, depth);
    vec3 position = (InverseView * vec4(viewPosition, 1.0)).xyz;
    // The event view matrix excludes camera translation, so this remains camera-relative.
    if (DebugView == 3) { fragColor = vec4(vec3(clamp(length(viewPosition) / Range, 0.0, 1.0)), 1.0); return; }
    vec3 geometricNormal = cross(dFdx(position), dFdy(position));
    geometricNormal /= max(length(geometricNormal), 0.0001);
    if (dot(geometricNormal, -position) < 0.0) geometricNormal = -geometricNormal;
    vec3 normal = length(normalVS) > 0.01 ? normalize(mat3(InverseView) * normalVS) : geometricNormal;
    vec3 albedo = albedoSample.a > 0.001 ? albedoSample.rgb
        : clamp(texture(SceneSampler, texCoord).rgb, vec3(0.0), vec3(0.8));
    vec3 added = surfaceLight(position, normal, albedo);
    fragColor = vec4(added * fogFade(position), 1.0);
}
