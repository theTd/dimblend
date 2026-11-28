#version 150

// Band-crossing screen distortion. Reads the frame the level just rendered (Sampler0, a straight
// colour copy) and adds the burst vocabulary: horizontal scanline shear, RGB separation, 2x2 pixel
// block flicker and a soft cold-white flash. The flash is what hides RotatingDimensionEffects'
// hard skyType / constantAmbientLight switch, which cannot be interpolated.

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;
uniform vec2 ScreenSize;
uniform float GameTime;
uniform float Intensity;
uniform float Flash;

in vec2 texCoord0;

out vec4 fragColor;

float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

void main() {
    float intensity = clamp(Intensity, 0.0, 1.0);
    float flash = clamp(Flash, 0.0, 1.0);
    // RenderSystem.setShaderGameTime normalises (ticks % 24000) / 24000 (1.0 = 1200 s).
    float seconds = GameTime * 1200.0;
    vec2 uv = texCoord0;

    // Horizontal scanline shear: half-resolution lines, re-rolled every 1/32 s.
    float line = floor(uv.y * max(ScreenSize.y, 1.0) * 0.5);
    float jitter = hash21(vec2(line, floor(seconds * 32.0))) - 0.5;
    uv.x += jitter * 0.016 * intensity;

    // 2x2 pixel block flicker with its own sideways shove.
    vec2 blocks = floor(gl_FragCoord.xy * 0.5);
    float blockRoll = hash21(blocks + floor(seconds * 20.0));
    float flicker = step(blockRoll, 0.06 * intensity);
    uv.x += (hash21(blocks + 3.7) - 0.5) * 0.03 * intensity * flicker;

    // Chromatic separation grows with the burst and again with the flash.
    float split = 0.0035 * intensity + 0.0018 * flash;
    vec3 color;
    color.r = texture(Sampler0, clamp(uv + vec2(split, 0.0), 0.0, 1.0)).r;
    color.g = texture(Sampler0, clamp(uv, 0.0, 1.0)).g;
    color.b = texture(Sampler0, clamp(uv - vec2(split, 0.0), 0.0, 1.0)).b;

    // Soft cold-white / pale-cyan crossing flash, brightest in the middle of the view.
    vec2 centered = uv - 0.5;
    float falloff = 1.0 - smoothstep(0.15, 0.72, length(centered * vec2(1.0, 0.9)));
    vec3 cold = vec3(0.78, 0.92, 1.0);
    color = mix(color, cold, clamp(flash * (0.42 + 0.30 * falloff), 0.0, 0.70));
    color = mix(color, vec3(0.86, 0.96, 1.0), flicker * 0.35 * intensity);

    // Faint scanline darkening so the burst still reads on a bright sky.
    color *= 1.0 - 0.05 * intensity * step(0.5, fract(uv.y * max(ScreenSize.y, 1.0) * 0.25));

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0) * ColorModulator;
}
