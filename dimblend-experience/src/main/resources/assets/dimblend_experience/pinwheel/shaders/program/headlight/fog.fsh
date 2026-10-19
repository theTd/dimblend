#version 410 core

// volume includes beam itself; listing both here would reorder them (see volume.glsl).
#include dimblend_experience:headlight/volume

in vec2 texCoord;
out vec4 fragColor;
uniform sampler2D DepthSampler;
// Opaque scene distance captured before translucent terrain (same resolution and texel centres as this pass).
uniform sampler2D OpaqueSampler;
uniform float Density;
uniform int Samples;
uniform float MaxDistance;

void main() {
    // The front-most scene distance (translucent surfaces included) travels in alpha for the joint upsample,
    // which compares it with the same main depth. Clamp far-plane values to finite half-float range,
    // independently of the march bound.
    vec3 viewPosition = reconstructView(texCoord, texture(DepthSampler, texCoord).r);
    float sceneDistance = min(length(viewPosition), 60000.0);
    // The march continues behind water/glass down to the opaque surface, dimmed there (see translucentDimming).
    float opaqueDistance = max(texture(OpaqueSampler, texCoord).r, sceneDistance);
    vec3 ray = normalize(mat3(InverseView) * viewPosition);
    float maxDistance = min(opaqueDistance, MaxDistance);
    // Static per-pixel interleaved gradient noise: there is no temporal accumulation to hide a
    // per-frame pattern, so a varying offset would only shimmer.
    float jitter = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float radiance = 0.0;
    for (int lamp = 0; lamp < LampCount; ++lamp) {
        vec2 segment;
        // Reject bounding-sphere and cone misses before taking a single march sample.
        if (!clipBeam(ray, lamp, maxDistance, segment)) continue;
        // Equi-angular sampling about the ray's closest approach to the lens: samples crowd where the
        // in-scatter peaks, so 8 of them resolve the near-lamp glow without radial banding.
        vec3 lens = LampPosition[lamp];
        float inside = cameraInside(-dot(lens, LampDirection[lamp]) / max(length(lens), 1e-4));
        float closest = dot(lens, ray);
        float axisDistance = max(length(lens - ray * closest), MinAxisDistance);
        float angleStart = atan((segment.x - closest) / axisDistance);
        float angleRange = atan((segment.y - closest) / axisDistance) - angleStart;
        for (int s = 0; s < Samples; ++s) {
            float t = closest + axisDistance * tan(angleStart + (float(s) + jitter) / float(Samples) * angleRange);
            vec3 position = ray * t;
            vec3 offset = position - lens;
            float distance2 = dot(offset, offset);
            float distance = sqrt(distance2);
            vec3 direction = offset / max(distance, 0.0001);
            float cone = fogCone(dot(direction, LampDirection[lamp]));
            if (cone <= 0.0) continue;
            // 1 / pdf of the equi-angular sample, divided by the sample count.
            float along = t - closest;
            float weight = angleRange * (axisDistance * axisDistance + along * along) / (axisDistance * float(Samples));
            // Positive HG g emphasizes light travelling toward the viewer (looking into a beam).
            radiance += cone * beamFalloff(distance) * scatterPhase(dot(direction, -ray)) * nearFade(t, inside)
                * visibility(position) * fogFade(position) * translucentDimming(t, sceneDistance) * weight;
        }
    }
    // A non-finite texel would poison the 2x2 upsample and turn black after additive blending.
    if (isnan(radiance) || isinf(radiance)) radiance = 0.0;
    // Soft cap bounds total energy across all lamps (looking straight into a beam saturates there).
    fragColor = vec4(HeadlightColor * haze(Density, radiance), sceneDistance);
}
