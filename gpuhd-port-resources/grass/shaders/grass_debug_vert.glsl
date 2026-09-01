#version 330

layout(location = 0) in vec3 position;
uniform mat4 projection;
uniform vec3 baseCenter;
uniform float instanceSpacing;

void main()
{
	vec3 worldPosition = position
		+ baseCenter
		+ vec3(float(gl_InstanceID) * instanceSpacing, 0.0, 0.0);
	gl_Position = projection * vec4(worldPosition, 1.0);
}
