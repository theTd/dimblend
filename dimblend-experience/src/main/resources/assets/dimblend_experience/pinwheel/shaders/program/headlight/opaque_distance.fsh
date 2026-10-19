#version 410 core

// Captures the opaque scene distance at haze resolution before translucent terrain writes depth.
in vec2 texCoord;
out vec4 fragColor;
uniform sampler2D DepthSampler;
uniform mat4 InverseProjection;

void main() {
    vec4 view = InverseProjection * vec4(texCoord * 2.0 - 1.0, texture(DepthSampler, texCoord).r * 2.0 - 1.0, 1.0);
    fragColor = vec4(min(length(view.xyz / view.w), 60000.0), 0.0, 0.0, 1.0);
}
