#version 330

layout(location = 0) in vec4 anchorSeed;
layout(location = 1) in vec2 anchorDetail;

uniform mat4 projection;
uniform float markerSize;

void main()
{
	if (int(floor(anchorDetail.y + 0.5)) != 0)
	{
		gl_Position = vec4(2.0);
		return;
	}
	int axis = gl_VertexID / 2;
	float direction = (gl_VertexID & 1) == 0 ? -1.0 : 1.0;
	vec3 offset = axis == 0
		? vec3(direction * markerSize, 0.0, 0.0)
		: axis == 1
			? vec3(0.0, direction * markerSize, 0.0)
			: vec3(0.0, 0.0, direction * markerSize);
	gl_Position = projection * vec4(anchorSeed.xyz + offset, 1.0);
}
