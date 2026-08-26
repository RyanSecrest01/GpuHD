#version 330

uniform mat4 projection;
uniform vec3 cameraPosition;
uniform float time;
uniform float radius;
uniform float fallSpeed;
uniform float wind;
uniform float streakLength;
uniform int snow;
uniform int severe;

out float fFade;
out float fSeed;
out float fGust;

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
	float cycleHeight = snow != 0
		? (severe != 0 ? 1325.0 : 1500.0)
		: 1900.0;
	vec2 flowDirection = normalize(vec2(0.92, 0.38));
	float gustClock = time * 0.14
		+ sin(time * 0.55) * 0.16
		+ sin(time * 1.37) * 0.035;
	vec2 windTravel = snow != 0 && severe != 0
		? flowDirection * wind * gustClock
		: vec2(wind * time * 0.018, 0.0);
	vec2 worldSeed = vec2(seedX, seedZ) + windTravel;
	vec2 wrappedOffset = mod(
		worldSeed - cameraPosition.xz + vec2(radius),
		vec2(radius * 2.0)) - vec2(radius);
	float fallVariation = snow != 0 ? mix(0.76, 1.24, hash(id + 59.0)) : 1.0;
	float worldY = ry * cycleHeight + time * fallSpeed * fallVariation;
	float wrappedY = mod(
		worldY - cameraPosition.y + cycleHeight * 0.5,
		cycleHeight) - cycleHeight * 0.5;
	float depthFade = 1.0 - smoothstep(radius * 0.45, radius, length(wrappedOffset));
	vec3 position = vec3(
		cameraPosition.x + wrappedOffset.x,
		cameraPosition.y + wrappedY,
		cameraPosition.z + wrappedOffset.y);
	float gustBand = snow != 0 && severe != 0
		? 0.5 + 0.5 * sin(
			dot(worldSeed, flowDirection) * 0.0055
			- time * 2.15
			+ sin(worldY * 0.008 + time * 0.73))
		: 0.5;
	if (snow != 0)
	{
		vec2 crossWind = vec2(-flowDirection.y, flowDirection.x);
		position.xz += crossWind
			* sin(id * 0.71 + time * (severe != 0 ? 2.7 : 1.7))
			* (severe != 0 ? 34.0 : 20.0);
		position.xz += flowDirection
			* cos(id * 0.53 + time * (severe != 0 ? 2.1 : 1.2))
			* (severe != 0 ? 46.0 : 15.0);
	}
	else if (endpoint > 0.5)
	{
		position += vec3(-wind * 0.13, -streakLength, 0.0);
	}

	gl_Position = projection * vec4(position, 1.0);
	float particleDistance = length(position - cameraPosition);
	float pointDistanceScale = mix(
		1.50,
		0.72,
		smoothstep(radius * 0.08, radius, particleDistance));
	float flakeSize = mix(3.4, 6.8, hash(id + 101.0));
	gl_PointSize = snow != 0
		? flakeSize * pointDistanceScale * (severe != 0 ? 1.32 : 1.0)
			* (severe != 0 ? mix(0.90, 1.16, gustBand) : 1.0)
		: 1.0;
	fFade = clamp(
		depthFade * (0.42 + hash(id + 73.0) * 0.58)
			* (severe != 0 && snow != 0 ? mix(0.70, 1.24, gustBand) : 1.0),
		0.0,
		1.0);
	fSeed = hash(id + 101.0);
	fGust = gustBand;
}
