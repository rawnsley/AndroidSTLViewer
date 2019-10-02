#version 310 es

precision highp float;

in vec3 fColor;
in vec2 fTexCoord;

layout(location = 0) out vec4 fragColor;

uniform sampler2D myTextureSampler;

void main(){
    fragColor = vec4(fColor, 1.0f);
}