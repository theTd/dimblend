#version 410 core

// Per-lamp distance map: one voxel DDA per direction texel through the world clipmap and the vehicle grids.
// Mirrored by HeadlightOcclusionMath.trace.
#include dimblend_experience:headlight/occlusion

out vec4 fragColor;

// World clipmap: R8, world block w at texel (w mod size); the window starts at WorldGridOffset blocks behind the
// camera, WorldGridWrap = window origin mod size.
uniform sampler3D WorldGrid;
uniform vec3 WorldGridOffset;
uniform ivec3 WorldGridWrap;
const ivec3 WorldGridSize = ivec3(128, 64, 128);

// Vehicle grids: 64^3 slots stacked along z; transform maps camera-relative world to slot cells.
uniform sampler3D VehicleGrid;
uniform int VehicleCount;
uniform mat4 VehicleTransform[4];
uniform ivec3 VehicleSize[4];
uniform int VehicleSlot[4];
const int VehicleSlotSize = 64;

const int MaxTraceSteps = 192;
const float NoHit = -1.0;

// slotZ < 0 selects the world clipmap, otherwise the vehicle slot starting at that z texel. (No array index here:
// after inlining a constant -1 grid index would fail to compile even on the untaken branch.)
bool occupied(ivec3 cell, int slotZ) {
    if (slotZ < 0) return texelFetch(WorldGrid, (cell + WorldGridWrap) % WorldGridSize, 0).r > 0.5;
    return texelFetch(VehicleGrid, cell + ivec3(0, 0, slotZ), 0).r > 0.5;
}

// Fraction along a->b at which the first occupied cell is entered; a's own cell is skipped when a is inside.
float traceGrid(vec3 a, vec3 b, ivec3 size, int slotZ) {
    vec3 d = b - a;
    vec3 fsize = vec3(size);
    float t0 = 0.0;
    float t1 = 1.0;
    for (int k = 0; k < 3; ++k) {
        if (abs(d[k]) < 1e-8) {
            if (a[k] < 0.0 || a[k] >= fsize[k]) return NoHit;
        } else {
            float ta = -a[k] / d[k];
            float tb = (fsize[k] - a[k]) / d[k];
            t0 = max(t0, min(ta, tb));
            t1 = min(t1, max(ta, tb));
        }
    }
    if (t0 >= t1) return NoHit;
    ivec3 cell;
    ivec3 stepDir;
    vec3 tMax;
    vec3 tDelta;
    for (int k = 0; k < 3; ++k) {
        cell[k] = clamp(int(floor(a[k] + d[k] * t0)), 0, size[k] - 1);
        if (d[k] > 1e-8) {
            stepDir[k] = 1;
            tMax[k] = (float(cell[k] + 1) - a[k]) / d[k];
            tDelta[k] = 1.0 / d[k];
        } else if (d[k] < -1e-8) {
            stepDir[k] = -1;
            tMax[k] = (float(cell[k]) - a[k]) / d[k];
            tDelta[k] = -1.0 / d[k];
        } else {
            stepDir[k] = 0;
            tMax[k] = 1e30;
            tDelta[k] = 1e30;
        }
    }
    bool skipStart = t0 <= 0.0;
    float entry = t0;
    for (int i = 0; i < MaxTraceSteps; ++i) {
        if (!(skipStart && i == 0) && occupied(cell, slotZ)) return entry;
        int k = tMax.x < tMax.y && tMax.x < tMax.z ? 0 : tMax.y < tMax.z ? 1 : 2;
        entry = tMax[k];
        if (entry > t1) return NoHit;
        cell[k] += stepDir[k];
        if (cell[k] < 0 || cell[k] >= size[k]) return NoHit;
        tMax[k] += tDelta[k];
    }
    return NoHit;
}

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    int lamp = texel.y / OcclusionResolution;
    vec2 plane = (vec2(texel.x, texel.y - lamp * OcclusionResolution) + 0.5) / float(OcclusionResolution) * 2.0 - 1.0;
    vec3 axis = LampDirection[lamp];
    vec3 tangent, bitangent;
    lampBasis(axis, tangent, bitangent);
    vec3 ray = normalize(axis + (plane.x * tangent + plane.y * bitangent) * OcclusionTanHalf);
    vec3 lens = LampPosition[lamp];
    float traceLength = Range + 1.0;
    vec3 end = lens + ray * traceLength;
    float best = 1e4;
    float t = traceGrid(lens + WorldGridOffset, end + WorldGridOffset, WorldGridSize, -1);
    if (t >= 0.0) best = t * traceLength;
    for (int v = 0; v < VehicleCount; ++v) {
        vec3 a = (VehicleTransform[v] * vec4(lens, 1.0)).xyz;
        vec3 b = (VehicleTransform[v] * vec4(end, 1.0)).xyz;
        float tv = traceGrid(a, b, VehicleSize[v], VehicleSlot[v] * VehicleSlotSize);
        if (tv >= 0.0) best = min(best, tv * traceLength);
    }
    fragColor = vec4(best, 0.0, 0.0, 1.0);
}
