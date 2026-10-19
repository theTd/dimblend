// Mirrored by HeadlightVolumeMath. Sphere bounds match the surface pass's radial range.
// Veil prepends every #include to the top of the including file, so two sibling includes end up in
// reverse order. Pull beam in here (nested, deduplicated) and have programs include only this file.
#include dimblend_experience:headlight/beam
// Below this the quadratic is treated as linear; its far root lies beyond any range sphere.
const float ConeLinearEpsilon = 1e-4;

bool clipBeam(vec3 ray, int lamp, float maxDistance, out vec2 segment) {
    vec3 p = -LampPosition[lamp];
    float b = dot(p, ray);
    float discriminant = b * b - dot(p, p) + Range * Range;
    if (discriminant <= 0.0) return false;
    float root = sqrt(discriminant);
    float lo = max(0.0, -b - root);
    float hi = min(maxDistance, -b + root);
    float z = dot(p, LampDirection[lamp]);
    float dz = dot(ray, LampDirection[lamp]);
    if (abs(dz) < 1e-6) {
        if (z < 0.0) return false;
    } else if (dz > 0.0) lo = max(lo, -z / dz);
    else hi = min(hi, -z / dz);
    if (hi <= lo) return false;

    float cos2 = ConeCosines.x * ConeCosines.x;
    float a = cos2 - dz * dz;
    float qb = cos2 * b - z * dz;
    float c = cos2 * dot(p, p) - z * z;
    if (abs(a) < ConeLinearEpsilon) {
        if (abs(qb) < 1e-6) {
            // f(t) ~ a t^2 + c: from (almost) the apex, a decides; otherwise the origin side does.
            if (c > 0.0 || (c > -1e-6 && a > 0.0)) return false;
        } else if (qb > 0.0) hi = min(hi, -c / (2.0 * qb));
        else lo = max(lo, -c / (2.0 * qb));
    } else {
        float d = qb * qb - a * c;
        if (d < 0.0) {
            if (a > 0.0) return false;
        } else {
            // Plain roots, not the q = -(b + sign(b) sqrt(d)) form: that form collapsed both roots to ~0
            // on the GPU for rays through the apex (d -> 0), cutting the march short = black lamp spot.
            // |a| >= ConeLinearEpsilon keeps the plain form well conditioned (abs error ~ eps * |b| / |a|).
            float h = sqrt(d);
            float t0 = (-qb - h) / a;
            float t1 = (-qb + h) / a;
            float lower = min(t0, t1);
            float upper = max(t0, t1);
            if (a > 0.0) {
                lo = max(lo, lower);
                hi = min(hi, upper);
            // The sign selects the forward cone, including rays crossing exactly at the apex.
            } else if (dz > 0.0) lo = max(lo, upper);
            else hi = min(hi, lower);
        }
    }
    segment = vec2(lo, hi);
    return hi > lo;
}

// Haze model, mirrored by HeadlightVolumeMath (tuning rationale in its javadoc).
// Henyey-Greenstein, normalized over the sphere.
float phase(float cosine, float g) {
    float d = 1.0 + g * g - 2.0 * g * clamp(cosine, -1.0, 1.0);
    return (1.0 - g * g) / (12.5663706144 * d * sqrt(d));
}

const float PhaseG = 0.85;
const float PhaseForward = 0.3;

// Isotropic floor plus a tight forward lobe, relative to isotropic (= 1): side views keep a visible
// shaft while looking into the lamp still gives a concentrated glow rather than a lit screen.
float scatterPhase(float cosine) {
    return (1.0 - PhaseForward) + PhaseForward * 12.5663706144 * phase(cosine, PhaseG);
}

// Fog-only radial falloff: exponential along the beam with a soft end at Range. The surface pass keeps
// its own flatter attenuation; haze needs the stronger falloff so the far cone does not read as a wall.
const float FalloffRate = 2.0;

float beamFalloff(float distance) {
    return Intensity * exp(-FalloffRate * distance / Range) * (1.0 - smoothstep(Range * 0.85, Range, distance));
}

// Fades in-scatter within this many blocks of the camera. Inside a beam this nearby, direction-independent
// segment would otherwise veil the whole screen (including sky) in uniform grey.
const float NearFade = 4.0;

float nearFade(float t) { return smoothstep(0.0, NearFade, t); }

// Fog cross-section: feathered across the whole cone radius (the surface pass keeps a crisp edge) so the
// shaft reads as soft light with a brighter core instead of a hard-edged solid.
float fogCone(float cosine) {
    float t = clamp((cosine - ConeCosines.x) / max(1.0 - ConeCosines.x, 1e-4), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

// Floor for the lens-to-ray distance in equi-angular sampling (rays through the lens itself).
const float MinAxisDistance = 0.05;

// Exposure and soft cap of the scattered haze.
const float HazeExposure = 0.4;
const float HazeCap = 0.5;

float haze(float density, float scatter) {
    return HazeCap * (1.0 - exp(-HazeExposure * density * scatter / HazeCap));
}

// Next milestone: sample the world-space voxel visibility volume here.
float visibility(vec3 position) { return 1.0; }
