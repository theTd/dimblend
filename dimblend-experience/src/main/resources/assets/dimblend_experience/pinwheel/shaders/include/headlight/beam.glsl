// Shared photometry for the opaque surface and volumetric passes.
uniform mat4 InverseProjection;
uniform mat4 InverseView;
uniform vec3 LampPosition[8];
uniform vec3 LampDirection[8];
uniform int LampCount;
uniform float Range;
uniform float Intensity;
uniform vec2 ConeCosines;
uniform vec2 FogRange;
uniform int FogCylinder;

const vec3 HeadlightColor = vec3(1.0, 0.95, 0.85);

vec3 reconstructView(vec2 uv, float depth) {
    vec4 position = InverseProjection * vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    return position.xyz / position.w;
}

float attenuation(float distance) {
    float scaled = distance / (Range * 0.5);
    float linear = Intensity * exp(-distance / Range) / (1.0 + scaled * scaled);
    return (1.0 - exp(-linear)) * (1.0 - smoothstep(Range * 0.8, Range, distance));
}

float coneFactor(float cosine) {
    return smoothstep(ConeCosines.x, ConeCosines.y, cosine)
        * (0.85 + 0.15 * pow(max(cosine, 0.0), 16.0));
}

float fogFade(vec3 position) {
    float distance = FogCylinder != 0 ? max(length(position.xz), abs(position.y)) : length(position);
    // No-fog start may be Float.MAX_VALUE; avoid subtraction or division in that case.
    return distance <= FogRange.x ? 1.0 : distance >= FogRange.y ? 0.0
        : 1.0 - smoothstep(FogRange.x, FogRange.y, distance);
}
