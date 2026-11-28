#version 150

// Mirage terrain: a baked static mesh of one band's generator output, drawn where that band's
// real chunks do not exist. Position is section-local (0..16); u_MirageOrigin is the section
// origin minus the camera, so every varying is camera-relative world space and shared with the
// rift mask below.

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;
in vec3 Normal;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform vec3 u_MirageOrigin;
uniform int FogShape;

out vec4 v_Color;
out vec2 v_TexCoord;
out vec3 v_WorldRel;
flat out vec3 v_SectionCenter;
out float v_FragDistance;
out vec2 v_Light;

void main() {
    vec3 worldRel = Position + u_MirageOrigin;
    v_WorldRel = worldRel;
    // Flat section centre (origin + 8): the same anchor the Sodium chunk shader uses, so a section
    // wall sitting exactly on the seam plane cannot flip side through interpolation jitter.
    v_SectionCenter = u_MirageOrigin + vec3(8.0);
    v_Color = Color;
    v_TexCoord = UV0;
    // Same packing the vanilla BLOCK format shaders use: UV2 is 0..240 in steps of 16, and the
    // +1/32 offset lands on the lightmap texel centre so the bilinear tap reads that exact level.
    v_Light = vec2(UV2) / 256.0 + vec2(0.03125);
    v_FragDistance = FogShape == 1 ? max(length(worldRel.xz), abs(worldRel.y)) : length(worldRel);
    gl_Position = ProjMat * ModelViewMat * vec4(worldRel, 1.0);
}
