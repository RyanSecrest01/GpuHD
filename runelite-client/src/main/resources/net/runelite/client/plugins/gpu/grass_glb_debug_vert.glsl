#version 330

// The loader has already applied node transforms, normalized the root to zero,
// and converted glTF Y-up to RuneLite Y-down exactly once.
layout(location = 0) in vec3 position;

uniform mat4 projection;
uniform vec3 baseCenter;
uniform float modelScale;

void main()
{
	vec3 worldPosition = baseCenter + position * modelScale;
	gl_Position = projection * vec4(worldPosition, 1.0);
}
