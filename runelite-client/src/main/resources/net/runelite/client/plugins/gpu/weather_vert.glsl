#version 330

uniform mat4 projection;
uniform vec3 cameraPosition;
uniform float time;
uniform float radius;
uniform float fallSpeed;
uniform float wind;
uniform float streakLength;
uniform int snow;

out float fFade;

float hash(float n)
{
	return fract(sin(n * 91.3458 + 17.123) * 47453.5453);
}

void main()
{
	int particle = snow != 0 ? gl_VertexID : gl_VertexID / 2;
	float endpoint = float(gl_VertexID & 1);
	float id = float(particle);
	float seedX = hash(id + 1.0) * radius * 2.0;
	float seedZ = hash(id + 19.0) * radius * 2.0;
	float ry = hash(id + 41.0);
	float cycleHeight = snow != 0 ? 1500.0 : 1900.0;
	float drift = wind * time * 0.018;
	vec2 worldSeed = vec2(seedX + drift, seedZ);
	vec2 wrappedOffset = mod(
		worldSeed - cameraPosition.xz + vec2(radius),
		vec2(radius * 2.0)) - vec2(radius);
	float worldY = ry * cycleHeight + time * fallSpeed;
	float wrappedY = mod(
		worldY - cameraPosition.y + cycleHeight * 0.5,
		cycleHeight) - cycleHeight * 0.5;
	float depthFade = 1.0 - smoothstep(radius * 0.45, radius, length(wrappedOffset));
	vec3 position = vec3(
		cameraPosition.x + wrappedOffset.x,
		cameraPosition.y + wrappedY,
		cameraPosition.z + wrappedOffset.y);
	if (snow != 0)
	{
		position.x += sin(id * 0.71 + time * 1.7) * 18.0;
		position.z += cos(id * 0.53 + time * 1.2) * 14.0;
	}
	else if (endpoint > 0.5)
	{
		position += vec3(-wind * 0.13, -streakLength, 0.0);
	}

	gl_Position = projection * vec4(position, 1.0);
	gl_PointSize = snow != 0 ? 2.4 : 1.0;
	fFade = depthFade * (0.42 + hash(id + 73.0) * 0.58);
}
