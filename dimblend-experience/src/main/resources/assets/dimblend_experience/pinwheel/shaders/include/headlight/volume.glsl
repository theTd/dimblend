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

// Fog-only radial falloff: exponential along the beam with a soft end at Range, plus a boost near the lens so
// the shaft visibly leaves the lamp and thins out along its length (from the driver's seat the beam reads as
// going forward, not as a disc floating ahead). The surface pass keeps its own flatter attenuation; haze needs
// the stronger falloff so the far cone does not read as a wall.
const float FalloffRate = 2.0;
const float NearBoost = 2.0;
const float NearBoostRadius = 1.25;

float beamFalloff(float distance) {
    float near = distance / NearBoostRadius;
    return Intensity * exp(-FalloffRate * distance / Range) * (1.0 + NearBoost / (1.0 + near * near))
        * (1.0 - smoothstep(Range * 0.85, Range, distance));
}

// Fades in-scatter within this many blocks of the camera while the camera stands in (or near) a beam. Inside a
// beam this nearby, direction-independent segment would otherwise veil the whole screen (including sky) in grey.
// Behind or beside the lamp (the driver's seat) there is no veil to hide, and fading there would cut the first
// blocks of the shaft so the beam would start as a floating disc instead of leaving the lens.
const float NearFade = 4.0;

// How much the camera stands inside the lamp's forward cone (slightly widened), from the lens-to-camera cosine.
float cameraInside(float cosine) { return smoothstep(ConeCosines.x - 0.15, ConeCosines.x + 0.05, cosine); }

float nearFade(float t, float inside) { return mix(1.0, smoothstep(0.0, NearFade, t), inside); }

// Fog cross-section: feathered across the whole cone radius (the surface pass keeps a crisp edge), with extra
// weight towards the axis (cubic) so the shaft reads as a defined core in soft light, also from the side.
const float CoreFloor = 0.15;

float fogCone(float cosine) {
    float t = clamp((cosine - ConeCosines.x) / max(1.0 - ConeCosines.x, 1e-4), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t) * (CoreFloor + (1.0 - CoreFloor) * t * t * t);
}

// Floor for the lens-to-ray distance in equi-angular sampling (rays through the lens itself).
const float MinAxisDistance = 0.05;

// Exposure and soft cap of the scattered haze: a Reinhard-style knee keeps gradation in long, bright paths
// (looking down the beam) where an exponential knee flattened everything near the cap into a uniform disc.
const float HazeExposure = 0.65;
const float HazeCap = 0.5;

float haze(float density, float scatter) {
    float x = HazeExposure * density * scatter / HazeCap;
    return HazeCap * x / (1.0 + x);
}

// In-scatter behind a translucent surface (water, stained glass) is seen through it: kept, but dimmed, since
// its colour and opacity are unknown here.
const float BehindTranslucent = 0.6;

float translucentDimming(float t, float frontDistance) { return t > frontDistance ? BehindTranslucent : 1.0; }

// Next milestone: sample the world-space voxel visibility volume here.
float visibility(vec3 position) { return 1.0; }
