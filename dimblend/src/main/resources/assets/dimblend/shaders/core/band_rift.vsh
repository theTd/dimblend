#version 150

// Band-rift membrane: the cross-section plane at a band boundary column. Vertices arrive in
// gate-local block space (x = 0 on the plane, y/z = cross-section), are pushed out along the
// plane normal into a shallow sphere cap so the tear band reads as a bubble instead of a decal,
// and are then transformed by the camera-relative model-view matrix the caller builds.

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec2 RiftExtent;
uniform vec4 RiftFx;

out vec2 vBlock;

void main() {
    vBlock = Position.yz;

    vec3 pos = Position;
    vec2 n = Position.yz / max(RiftExtent, vec2(0.001));
    float dome = max(0.0, 1.0 - dot(n, n));
    pos.x += RiftFx.w * dome * dome;

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);
}
