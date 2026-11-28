#version 150

// Full-screen pass over an already-rendered level frame. Positions are clip-space: the caller
// passes identity ModelViewMat/ProjMat, so no camera math leaks in here.

in vec3 Position;
in vec2 UV0;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord0 = UV0;
}
