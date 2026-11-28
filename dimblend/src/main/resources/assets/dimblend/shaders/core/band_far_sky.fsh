#version 150

// Far-band sky fragment stage. u_InvViewProj is the inverse of projection * (rotation-only)
// model-view, so unprojecting NDC (xy, 1, 1) gives a camera-relative world direction. The same
// rift block as mirage_terrain.fsh decides which pixels belong to the far universe; the rest are
// discarded so the near sky shows through untouched.

uniform mat4 u_InvViewProj;
uniform vec3 u_Zenith;
uniform vec3 u_Horizon;
uniform float u_Stars;

in vec2 v_Ndc;

out vec4 fragColor;

// ---- dimblend:rift-mask v1 (shared block, do not edit one copy alone) ----------------------
uniform int u_RiftMode;
uniform vec4 u_RiftPlane;   // xyz = near->far unit normal, w = (slabX - cameraX) * normal.x
uniform vec4 u_RiftCenter;  // xyz = aperture centre, camera relative; w = openFactor
uniform vec4 u_RiftRadii;   // x = fixed epsilon depth; yz = aperture radii; w = random hole density
uniform vec4 u_RiftStyle;   // xyz = far-lane glow colour; w = real seconds
uniform vec4 u_RiftCam;     // xyz = mod(cameraPos, 64); w = rim width in blocks

float dimblendRiftHash(vec3 p) {
    p = fract(p * 0.1031);
    float spread = dot(p, p.yzx + 33.33);
    p += spread;
    return fract((p.x + p.y) * p.z);
}

float dimblendRiftNoise(vec3 p) {
    vec3 cell = floor(p);
    vec3 f = p - cell;
    vec3 u = f * f * (3.0 - 2.0 * f);
    float n000 = dimblendRiftHash(cell);
    float n100 = dimblendRiftHash(cell + vec3(1.0, 0.0, 0.0));
    float n010 = dimblendRiftHash(cell + vec3(0.0, 1.0, 0.0));
    float n110 = dimblendRiftHash(cell + vec3(1.0, 1.0, 0.0));
    float n001 = dimblendRiftHash(cell + vec3(0.0, 0.0, 1.0));
    float n101 = dimblendRiftHash(cell + vec3(1.0, 0.0, 1.0));
    float n011 = dimblendRiftHash(cell + vec3(0.0, 1.0, 1.0));
    float n111 = dimblendRiftHash(cell + vec3(1.0, 1.0, 1.0));
    float nx00 = mix(n000, n100, u.x);
    float nx10 = mix(n010, n110, u.x);
    float nx01 = mix(n001, n101, u.x);
    float nx11 = mix(n011, n111, u.x);
    return mix(mix(nx00, nx10, u.y), mix(nx01, nx11, u.y), u.z);
}

// Two-octave world-locked voxel fBM for the fracture edge; hitW is on the 1/8-block world grid.
float dimblendRiftWobble(vec3 hitW, float seconds) {
    return 0.68 * dimblendRiftNoise(hitW * 0.20 + vec3(0.0, seconds * 0.18, 0.0))
         + 0.32 * dimblendRiftNoise(hitW * 0.55 + vec3(0.0, seconds * 0.31, 0.0));
}

// (x = 1 when this fragment is reached through the portal, y = edge glow weight)
vec2 dimblendEvalRift(vec3 p) {
    float open = u_RiftCenter.w;
    if (open <= 0.002) {
        return vec2(0.0);
    }
    float distP = length(p);
    vec3 dir = p / max(distP, 1e-4);
    float denom = dot(dir, u_RiftPlane.xyz);
    if (denom <= 1e-4) {
        return vec2(0.0);
    }
    float tPlane = u_RiftPlane.w / denom;
    if (tPlane < 0.0 || distP < tPlane) {
        return vec2(0.0);
    }
    vec3 hitRel = dir * tPlane;
    vec2 radiiYZ = max(u_RiftRadii.yz, vec2(1e-3));
    vec2 deltaYZ = (hitRel.yz - u_RiftCenter.yz) / radiiYZ;
    float radial = length(deltaYZ);
    if (radial >= 1.15) {
        return vec2(0.0);
    }
    // World-locked 1/8-block voxel point right on the stationary seam plane X = slabX.
    vec3 hitW = floor((hitRel + u_RiftCam.xyz) * 8.0) * 0.125;
    float wobble = dimblendRiftWobble(hitW, u_RiftStyle.w);
    float solidScale = smoothstep(0.20, 0.95, open);
    float solidEdge = solidScale * (0.74 + 0.38 * wobble);
    float glitch = clamp(u_RiftRadii.w, 0.0, 0.35);
    bool hole = false;
    float border = 1.0;
    float fringe = min(1.0, solidEdge + 0.14 * max(solidScale, 0.25));
    if (radial < fringe && glitch > 0.001) {
        vec3 cell = floor(hitW * 12.0);
        float tick = floor(u_RiftStyle.w * 10.0);
        float cellHash = dimblendRiftHash(cell + vec3(tick * 7.13, tick * 3.71, tick * 11.17));
        hole = cellHash < glitch;
        if (hole) {
            border = 0.055;
        }
    }
    float spark = step(0.985, dimblendRiftHash(floor(hitW * 16.0) + vec3(0.0, 0.0, floor(u_RiftStyle.w * 7.0))));
    bool hit = false;
    float edgeMetric = 1.0;
    if (radial < solidEdge) {
        hit = true;
        edgeMetric = abs(radial - solidEdge);
    } else if (hole) {
        hit = true;
        edgeMetric = border;
    }
    if (!hit) {
        return vec2(0.0);
    }
    float surfRim = 1.0 - smoothstep(0.0, max(u_RiftCam.w, 0.05), distP - tPlane);
    float silRim = 1.0 - smoothstep(0.0, 0.09, edgeMetric);
    float rim = max(surfRim * 0.85, silRim);
    // World-locked 1/16-block pinpricks: the fracture rim twinkles instead of glowing flat.
    float rimSpark = spark * (silRim + surfRim * 0.35);
    return vec2(1.0, min(1.0, rim + rimSpark * 0.35));
}

bool dimblendRiftKeeps(int mode, bool nearSide, bool other) {
    if (mode == 1) {
        return nearSide && !other;
    }
    if (mode == 2) {
        return !nearSide && other;
    }
    if (mode == 3) {
        return !nearSide && !other;
    }
    if (mode == 4) {
        return nearSide && other;
    }
    return true;
}
// ---- end dimblend:rift-mask v1 -------------------------------------------------------------

void main() {
    vec4 farPoint = u_InvViewProj * vec4(v_Ndc, 1.0, 1.0);
    if (abs(farPoint.w) < 1e-6) {
        discard;
    }
    vec3 dir = normalize(farPoint.xyz / farPoint.w);
    vec2 rift = dimblendEvalRift(dir * 512.0);
    if (rift.x < 0.5) {
        discard;
    }

    float up = clamp(dir.y * 0.5 + 0.5, 0.0, 1.0);
    vec3 color = mix(u_Horizon, u_Zenith, up * up);
    if (u_Stars > 0.001) {
        // World-locked 1/220-radian star bins: rotating the camera never makes them slide.
        vec3 cell = floor(dir * 220.0);
        float star = pow(dimblendRiftHash(cell), 26.0);
        float twinkle = 0.65 + 0.35 * sin(u_RiftStyle.w * 1.7 + cell.x * 0.7 + cell.z * 1.3);
        color += vec3(0.85, 0.90, 1.0) * star * u_Stars * 2.2 * twinkle;
    }
    if (rift.y > 0.01) {
        // Saturated far-lane colour on the outer ring; only the tight fracture core goes white.
        vec3 hot = mix(u_RiftStyle.rgb, vec3(1.0, 0.97, 0.92), smoothstep(0.75, 1.0, rift.y) * 0.6) * 1.30;
        color = mix(color, hot, rift.y * 0.85);
    }
    fragColor = vec4(color, 1.0);
}
