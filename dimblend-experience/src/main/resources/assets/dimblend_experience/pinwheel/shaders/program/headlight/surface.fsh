#version 410 core

// occlusion nests beam (single include: see volume.glsl).
#include dimblend_experience:headlight/occlusion

in vec2 texCoord;
out vec4 fragColor;

uniform sampler2D SceneSampler;
uniform sampler2D DepthSampler;
uniform sampler2D AlbedoSampler;
uniform sampler2D NormalSampler;
uniform int DebugView;

// Lifts the shadow lookup off the receiving face: the trace stops exactly at the block face it lit.
const float NormalOffset = 0.08;

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
        float light = cone * attenuation(distance) * diffuse;
        if (light <= 0.0) continue;
        added += albedo * HeadlightColor * light * visibility(position + normal * NormalOffset, i);
    }
    return added;
}

// Debug: inside the cones, lit = white and shadowed = red (scaled by the cone), over a dimmed scene.
vec3 occlusionDebug(vec3 position, vec3 normal, vec3 scene) {
    vec3 color = scene * 0.25;
    for (int i = 0; i < LampCount; ++i) {
        vec3 offset = position - LampPosition[i];
        float distance = length(offset);
        if (distance >= Range) continue;
        float cone = step(ConeCosines.x, dot(offset / max(distance, 0.0001), LampDirection[i]));
        if (cone <= 0.0) continue;
        float visible = visibility(position + normal * NormalOffset, i);
        color = mix(color, mix(vec3(1.0, 0.0, 0.0), vec3(1.0), visible), 0.85);
    }
    return color;
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
    if (DebugView == 6) { fragColor = vec4(occlusionDebug(position, normal, texture(SceneSampler, texCoord).rgb), 1.0); return; }
    vec3 added = surfaceLight(position, normal, albedo);
    fragColor = vec4(added * fogFade(position), 1.0);
}
