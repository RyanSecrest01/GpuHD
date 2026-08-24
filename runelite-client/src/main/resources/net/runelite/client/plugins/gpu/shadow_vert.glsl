#version 330

layout(location = 0) in vec3 vertf;

uniform mat4 lightProj;
uniform ivec3 base;

void main()
{
    vec4 worldPos =
        vec4(vertf + base, 1.0);

    gl_Position =
        lightProj * worldPos;
}