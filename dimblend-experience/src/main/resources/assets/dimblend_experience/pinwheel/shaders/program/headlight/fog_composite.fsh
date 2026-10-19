#version 410 core

in vec2 texCoord;
out vec4 fragColor;
uniform sampler2D FogSampler;
uniform sampler2D DepthSampler;
uniform mat4 InverseProjection;

void main() {
    float depth = texture(DepthSampler, texCoord).r;
    vec4 view = InverseProjection * vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    float distance = min(length(view.xyz / view.w), 60000.0);
    ivec2 size = textureSize(FogSampler, 0);
    vec2 samplePosition = texCoord * vec2(size) - 0.5;
    ivec2 base = ivec2(floor(samplePosition));
    vec2 f = fract(samplePosition);
    float sigma = max(0.1, distance * 0.01);
    vec3 sum = vec3(0.0);
    float weights = 0.0;
    for (int y = 0; y < 2; ++y) {
        for (int x = 0; x < 2; ++x) {
            vec4 fog = texelFetch(FogSampler, clamp(base + ivec2(x, y), ivec2(0), size - 1), 0);
            vec2 spatial = mix(1.0 - f, f, vec2(x, y));
            float weight = spatial.x * spatial.y * exp(-abs(fog.a - distance) / sigma);
            sum += fog.rgb * weight;
            weights += weight;
        }
    }
    // With no matching low-res depth, use zero rather than leaking a background beam over a silhouette.
    fragColor = vec4(weights > 1e-5 ? sum / weights : vec3(0.0), 0.0);
}
