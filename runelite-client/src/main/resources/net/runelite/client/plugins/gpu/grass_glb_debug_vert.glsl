#version 330

// Test 1 vertex path: the imported GLB is already node-transformed and its
// vertical minimum is normalized to zero by GlbGrassMesh. The only conversion
// here is glTF Y-up -> RuneLite's downward world-Y axis.
layout(location = 0) in vec3 position;

uniform mat4 projection;
uniform vec3 baseCenter;
uniform float modelScale;

void main()
{
	vec3 worldPosition = baseCenter + vec3(
		position.x * modelScale,
		-position.y * modelScale,
		position.z * modelScale);
	gl_Position = projection * vec4(worldPosition, 1.0);
}
