#version 410 core

// Only beam: a single include keeps Veil's hoisted include order trivially correct.
#include dimblend_experience:headlight/beam

in vec2 texCoord;
out vec4 fragColor;
uniform sampler2D FogSampler;
uniform sampler2D DepthSampler;

// Joint bilateral upsample from the four nearest low-resolution texels (bilinear spatial weights times a
// distance-similarity weight against this pixel's depth).
vec3 upsampleHaze(float distance) {
    ivec2 size = textureSize(FogSampler, 0);
    vec2 samplePosition = texCoord * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(samplePosition));
    vec2 f = fract(samplePosition);
    float depthSigma = max(0.1, distance * 0.01);
    vec3 sum = vec3(0.0);
    float weights = 0.0;
    vec3 closest = vec3(0.0);
    float closestDelta = 1e30;
    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 2; ++x) {
            vec4 fog = texelFetch(FogSampler, clamp(base + ivec2(x, y), ivec2(0), size - 1), 0);
            vec2 spatial = mix(1.0 - f, f, vec2(x, y));
            float delta = abs(fog.a - distance);
            float weight = (spatial.x * spatial.y + 1e-3) * exp(-delta / depthSigma);
            sum += fog.rgb * weight;
            weights += weight;
            if (delta < closestDelta) {
                closestDelta = delta;
                closest = fog.rgb;
            }
        }
    }
    // No depth match at a thin silhouette: the texel at the most similar distance, never a black hole.
    return weights > 1e-6 ? sum / weights : closest;
}

// Lens glare: a small core of roughly lens size plus a soft halo, measured in blocks at the lens plane with an
// angular floor so distant lamps keep a few pixels. Shown only towards the lamp's front and where the scene at
// that pixel is not in front of the lens (the housing sits within LensDepthSlack of the lens centre).
const float GlowCore = 0.14;
const float GlowHalo = 0.55;
const float GlowHaloWeight = 0.25;
const float GlowMinAngle = 0.0025;
const float GlowStrength = 0.55;
const float LensDepthSlack = 0.7;

float lensGlow(vec3 ray, float sceneDistance) {
    float glow = 0.0;
    for (int lamp = 0; lamp < LampCount; ++lamp) {
        vec3 lens = LampPosition[lamp];
        float lensDistance = length(lens);
        if (lensDistance < 0.5) continue;
        vec3 toLens = lens / lensDistance;
        float cosine = dot(ray, toLens);
        float facing = -dot(toLens, LampDirection[lamp]);
        if (cosine <= 0.0 || facing <= 0.0) continue;
        float lateral = length(cross(ray, toLens)) / cosine * lensDistance;
        float core = max(GlowCore, lensDistance * GlowMinAngle);
        float halo = max(GlowHalo, lensDistance * GlowMinAngle * 4.0);
        float shape = exp(-(lateral * lateral) / (core * core)) + GlowHaloWeight * exp(-lateral / halo);
        float aim = facing * facing * (0.2 + 0.8 * smoothstep(ConeCosines.x - 0.1, ConeCosines.y, facing));
        float visible = smoothstep(lensDistance - LensDepthSlack - 0.4, lensDistance - LensDepthSlack, sceneDistance);
        glow += aim * shape * visible * fogFade(lens);
    }
    return glow;
}

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    vec3 viewPosition = reconstructView(texCoord, depth);
    float distance = min(length(viewPosition), 60000.0);
    vec3 ray = normalize(mat3(InverseView) * viewPosition);
    vec3 haze = upsampleHaze(distance);
    float glow = GlowStrength * (1.0 - exp(-Intensity * lensGlow(ray, distance)));
    fragColor = vec4(haze + HeadlightColor * glow, 0.0);
}
