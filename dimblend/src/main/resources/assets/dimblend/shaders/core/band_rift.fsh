#version 150

// Band-rift membrane fragment stage.
//
// RiftOpen = (openFactor, solidRadius, glitchDensity, curtainAlpha)
// RiftFx   = (chunkReady, burst, seed, bulge)
// RiftTint = lane-tinted energy halo colour
//
// Everything is evaluated on a 1/16-block grid so the tear edge stays in Minecraft's pixel
// language. Torn fragments are discarded rather than blended to zero: the next band's corridor is
// already in the depth and colour buffers behind this plane, so a discard is a real hole with no
// overdraw and no sorting problem.

uniform vec4 ColorModulator;
uniform float GameTime;
uniform vec2 RiftExtent;
uniform vec4 RiftOpen;
uniform vec4 RiftFx;
uniform vec4 RiftTint;

in vec2 vBlock;

out vec4 fragColor;

float hash21(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

float valueNoise(vec2 p) {
    vec2 cell = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    float a = hash21(cell);
    float b = hash21(cell + vec2(1.0, 0.0));
    float c = hash21(cell + vec2(0.0, 1.0));
    float d = hash21(cell + vec2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm2(vec2 p) {
    float sum = 0.55 * valueNoise(p);
    p = mat2(0.80, -0.60, 0.60, 0.80) * p * 2.03 + 11.7;
    return sum + 0.28 * valueNoise(p);
}

float fbm3(vec2 p) {
    float sum = 0.0;
    float amp = 0.55;
    mat2 turn = mat2(0.80, -0.60, 0.60, 0.80);
    for (int i = 0; i < 3; i++) {
        sum += amp * valueNoise(p);
        p = turn * p * 2.03 + 11.7;
        amp *= 0.5;
    }
    return sum;
}

void main() {
    float open = clamp(RiftOpen.x, 0.0, 1.0);
    float solid = clamp(RiftOpen.y, 0.0, 1.4);
    float glitch = clamp(RiftOpen.z, 0.0, 1.0);
    float curtain = clamp(RiftOpen.w, 0.0, 1.0);
    float ready = RiftFx.x;
    float burst = clamp(RiftFx.y, 0.0, 1.0);
    float seed = RiftFx.z;
    // RenderSystem.setShaderGameTime normalises (ticks % 24000) / 24000 (1.0 = 1200 s).
    float seconds = GameTime * 1200.0;

    vec2 pixel = floor(vBlock * 16.0 + 0.5) * 0.0625;
    vec2 n = pixel / max(RiftExtent, vec2(0.001));
    float radius = length(n);

    // Seamless Cartesian fBM (no atan -pi/+pi seam) animated in real seconds.
    float wobble = fbm2(n * 2.8 + vec2(seed * 3.1, -seconds * 0.55));
    float tear = radius * (0.78 + 0.55 * wobble);

    float glitchTick = floor(seconds * 12.0);
    vec2 cell = floor(vBlock * 4.0);
    vec2 cellSeed = cell + vec2(seed * 31.0 + glitchTick * 7.13, seed * 17.0 + glitchTick * 19.37);
    float cellHash = hash21(cellSeed);

    if (ready > 0.5 && (tear < solid || (glitch > 0.01 && cellHash < glitch))) {
        discard;
    }

    float coreRim = (ready > 0.5 && solid > 0.005) ? 1.0 - smoothstep(0.0, 0.11, abs(tear - solid)) : 0.0;
    float cellRim = 0.0;
    if (ready > 0.5 && glitch > 0.01) {
        // Only the outer 1/16-block ring of a punched 1/4-block cell glows. Outlining the whole
        // cell (the pre-P4 formula) turns a field of holes into a white QR code at distance.
        vec2 sub = floor(vBlock * 16.0 + 0.5) - cell * 4.0;
        bool onEdge = sub.x < 0.5 || sub.x > 2.5 || sub.y < 0.5 || sub.y > 2.5;
        if (onEdge) {
            float right = step(hash21(cellSeed + vec2(1.0, 0.0)), glitch);
            float left = step(hash21(cellSeed - vec2(1.0, 0.0)), glitch);
            float up = step(hash21(cellSeed + vec2(0.0, 1.0)), glitch);
            float down = step(hash21(cellSeed - vec2(0.0, 1.0)), glitch);
            cellRim = max(max(right, left), max(up, down));
        }
    }

    vec2 starField = pixel * 2.2 + n * (0.55 + 0.35 * open);
    float nearStars = pow(hash21(floor(starField * 8.0)), 26.0);
    float farStars = pow(hash21(floor(starField * 3.5) + 7.31), 18.0) * 0.6;
    float star = clamp(nearStars + farStars, 0.0, 1.0);
    float nebula = fbm3(pixel * 0.28 + n * 0.5 + vec2(seed, seconds * 0.06));
    vec3 starColor = mix(vec3(0.03, 0.04, 0.11), vec3(0.58, 0.74, 1.0), star);
    starColor += vec3(0.14, 0.09, 0.28) * nebula * nebula;

    float energy = clamp(coreRim + cellRim * 0.9 + burst * (0.20 + 0.80 * exp(-abs(tear - solid) * 4.0)), 0.0, 1.0);
    float spark = step(0.86, hash21(floor(pixel * 16.0) + floor(seconds * 14.0))) * energy;
    vec3 hot = mix(RiftTint.rgb, vec3(1.0, 0.98, 0.94), smoothstep(0.25, 1.0, energy));
    vec3 color = mix(starColor, hot * (1.55 + 0.9 * burst), energy);
    color += vec3(spark) * 0.8;

    // Unopened membrane stays fully opaque (1.0) to cleanly mask far-side unloaded chunks;
    // see-through happens via discard above, and curtain fades smoothly only on carriage overlap.
    float alpha = curtain;
    fragColor = vec4(color, alpha) * ColorModulator;
}
