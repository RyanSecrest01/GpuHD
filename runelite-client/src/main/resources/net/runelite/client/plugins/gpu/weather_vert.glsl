#version 330

uniform mat4 projection;
uniform vec3 cameraPosition;
uniform float time;
uniform float radius;
uniform float fallSpeed;
uniform float wind;
uniform float streakLength;
uniform int snow;
uniform int mist;

out float fFade;
out float fSeed;
out vec3 fWorldPos;
out vec2 fMistUv;

float hash(float n)
{
	return fract(sin(n * 91.3458 + 17.123) * 47453.5453);
}

void main()
{
	if (mist != 0)
	{
		int particle = gl_VertexID / 6;
		int corner = gl_VertexID - particle * 6;
		float id = float(particle);
		vec2 quadCorner;
		if (corner == 0) quadCorner = vec2(-1.0, -1.0);
		else if (corner == 1) quadCorner = vec2(1.0, -1.0);
		else if (corner == 2) quadCorner = vec2(1.0, 1.0);
		else if (corner == 3) quadCorner = vec2(-1.0, -1.0);
		else if (corner == 4) quadCorner = vec2(1.0, 1.0);
		else quadCorner = vec2(-1.0, 1.0);
		vec2 seedOffset = vec2(hash(id + 1.0), hash(id + 19.0)) * radius * 2.0;
		vec2 drift = vec2(time * 8.0 + sin(time * 0.11) * 120.0,
			-time * 5.0 + cos(time * 0.09) * 90.0);
		vec2 wrappedOffset = mod(
			seedOffset + drift - cameraPosition.xz + vec2(radius),
			vec2(radius * 2.0)) - vec2(radius);
		float horizontalDistance = length(wrappedOffset);
		// A shallow, hard-edged vertical slab projected a visible boundary into
		// the view as the camera pitched. Use a taller volume and feather both
		// vertical edges so the mist cannot end on a screen-space line.
		float verticalOffset = (hash(id + 41.0) - 0.5) * 2200.0
			+ sin(id * 0.37 + time * 0.21) * 120.0;
		vec3 position = vec3(
			cameraPosition.x + wrappedOffset.x,
			cameraPosition.y + verticalOffset,
			cameraPosition.z + wrappedOffset.y);
		vec4 clipPosition = projection * vec4(position, 1.0);
		// RuneLite's projection stores view depth in w. Keep mist sufficiently
		// beyond the near plane that a puff can never expand across the camera.
		if (clipPosition.w <= 600.0)
		{
			gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
			fFade = 0.0;
			fSeed = hash(id + 113.0);
			fWorldPos = position;
			fMistUv = vec2(0.5);
			return;
		}
		// Derive the camera's horizontal/vertical projection scale from the
		// matrix. Unlike the old constant-screen-size expansion, this makes each
		// puff a fixed world-space size: nearby puffs are larger and distant ones
		// naturally shrink. It also follows RuneLite stretching and Retina scale.
		float projectionScaleX = length(vec3(
			projection[0][0], projection[1][0], projection[2][0]));
		float projectionScaleY = length(vec3(
			projection[0][1], projection[1][1], projection[2][1]));
		float mistWorldSize = mix(110.0, 260.0, hash(id + 73.0));
		clipPosition.xy += quadCorner * mistWorldSize
			* vec2(projectionScaleX, projectionScaleY);
		gl_Position = clipPosition;
		float nearFade = smoothstep(90.0, 360.0, horizontalDistance);
		float farFade = 1.0 - smoothstep(radius * 0.68, radius, horizontalDistance);
		float verticalFade = 1.0 - smoothstep(720.0, 1220.0, abs(verticalOffset));
		// Fade around the camera plane before OpenGL clips a billboard. This
		// conceals near-plane intersections without thinning nearby world fog.
		float cameraPlaneFade = smoothstep(600.0, 1200.0, clipPosition.w);
		fFade = nearFade * farFade * verticalFade * cameraPlaneFade
			* (0.52 + hash(id + 97.0) * 0.48);
		fSeed = hash(id + 113.0);
		fWorldPos = position;
		fMistUv = quadCorner * 0.5 + vec2(0.5);
		return;
	}

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
	fSeed = hash(id + 113.0);
	fWorldPos = position;
	fMistUv = vec2(0.5);
}
