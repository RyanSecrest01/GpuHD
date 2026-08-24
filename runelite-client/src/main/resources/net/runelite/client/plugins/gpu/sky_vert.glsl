#version 330

layout(location = 0) in vec3 position;

out vec3 skyDirection;

uniform mat4 skyProj;

void main()
{
    skyDirection = position;

    vec3 skyPosition =
        position * 10000.0;

    gl_Position =
        skyProj *
        vec4(skyPosition, 1.0);
}