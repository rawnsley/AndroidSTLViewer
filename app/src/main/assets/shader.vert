#version 310 es

layout (location = 0) in vec4 vPosition;
layout (location = 1) in vec3 vNormal;
layout (location = 2) in vec3 vColor;
layout (location = 3) in vec2 vTexCoord;

uniform mat4 mvpMatrix;

out vec3 fNormal;
out vec3 fColor;
out vec2 fTexCoord;

void main()
{
    gl_Position = mvpMatrix * vPosition;
    fColor = vColor;
    fTexCoord = vTexCoord;
    fNormal = vNormal;
}