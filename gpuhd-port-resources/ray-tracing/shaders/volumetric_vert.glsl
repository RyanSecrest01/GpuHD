#version 330

uniform mat4 worldProjection;
uniform vec3 cameraPosition;

out vec2 fUv;
out vec3 fNearRay;

void main()
{
	vec2 position;
	if (gl_VertexID == 0)
	{
		position = vec2(-1.0, -1.0);
	}
	else if (gl_VertexID == 1)
	{
		position = vec2(3.0, -1.0);
	}
	else
	{
		position = vec2(-1.0, 3.0);
	}

	fUv = position * 0.5 + 0.5;
	// RuneLite stores a reciprocal camera-space distance in reversed depth.
	// The depth-1 point is the corresponding 100-unit world ray; dividing it
	// by sampled clip depth reconstructs the opaque surface in the fragment pass.
	vec4 nearWorld = inverse(worldProjection) * vec4(position, 1.0, 1.0);
	nearWorld /= nearWorld.w;
	fNearRay = nearWorld.xyz - cameraPosition;
	gl_Position = vec4(position, 0.0, 1.0);
}
