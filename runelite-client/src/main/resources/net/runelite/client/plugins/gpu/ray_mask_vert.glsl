#version 330

layout(location = 0) in vec3 vertf;

uniform mat4 cameraProj;
uniform ivec3 base;

void main()
{
	gl_Position = cameraProj * vec4(vertf + base, 1.0);
}
