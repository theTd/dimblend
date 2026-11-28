#version 150

// Far-band sky: a full-screen quad at the far plane. The fragment reconstructs the world-space
// view ray, evaluates the rift shell at 512 blocks and only keeps pixels the shell claims for the
// other universe; everything else stays exactly as the near band's sky left it (discard).

in vec3 Position;

out vec2 v_Ndc;

void main() {
    v_Ndc = Position.xy;
    // 0.999999 in clip space: passes GL_LEQUAL against the cleared depth of 1.0 without ever
    // occluding near terrain, and writes no depth (depthMask is off for this pass).
    gl_Position = vec4(Position.xy, 0.999999, 1.0);
}
