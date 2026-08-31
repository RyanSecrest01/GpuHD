#version 330

layout(location = 0) in vec3 position;
layout(location = 1) in vec3 normal;
layout(location = 2) in vec2 uv;
layout(location = 4) in vec4 anchorSeed;
layout(location = 5) in vec2 anchorDetail;

uniform mat4 projection;
uniform vec3 cameraPosition;
uniform vec3 focusPosition;
uniform vec2 worldOffset;
uniform float time;
uniform vec4 drawRadius;
uniform float heightScale;
uniform float windStrength;
uniform int weatherMode;
uniform int debugMode;

out vec2 fDetailUv;
out vec3 fWorldPos;
out vec3 fDetailNormal;
out vec3 fDetailTangent;
flat out float fSeed;
flat out float fDistanceFade;
flat out float fGroundHsl;
flat out float fDetailType;

float hash(float value)
{
	return fract(sin(value * 127.17 + 19.73) * 43758.5453);
}

void main()
{
	int detailType = int(floor(anchorDetail.y + 0.5));
	if (detailType != 0)
	{
		gl_Position = vec4(2.0);
		return;
	}
	float seed = anchorSeed.w;
	float yaw = debugMode != 0 ? 0.0 : hash(seed * 19.3) * 6.2831853;
	float scale = debugMode != 0 ? 1.0
		: max(heightScale, 0.05) * mix(0.82, 1.18, hash(seed * 31.7));
	// GlbGrassMesh owns node transforms, root normalization, and the one axis
	// conversion. Instances stay globally upright: scale, yaw around world-up,
	// then add the exact CPU-sampled terrain position once.
	vec2 rotated = mat2(cos(yaw), -sin(yaw), sin(yaw), cos(yaw))
		* position.xz * scale;
	float height = max(-position.y, 0.0) * scale;
	float gust = sin(dot(anchorSeed.xz + worldOffset, vec2(0.0041, 0.0033))
		- time * 0.85 + seed * 6.2831);
	float bend = debugMode != 0 ? 0.0
		: gust * clamp(abs(windStrength), 0.0, 1.8) * height * 0.075;
	vec3 world = anchorSeed.xyz + vec3(rotated.x + bend,
		position.y * scale, rotated.y);
	vec3 transformedNormal = normalize(vec3(
		normal.x * cos(yaw) - normal.z * sin(yaw),
		normal.y,
		normal.x * sin(yaw) + normal.z * cos(yaw)));
	gl_Position = projection * vec4(world, 1.0);
	fDetailUv = uv;
	fWorldPos = world;
	fDetailNormal = transformedNormal;
	fDetailTangent = normalize(vec3(sin(yaw), -0.35, cos(yaw)));
	fSeed = hash(seed * 53.7);
	fGroundHsl = anchorDetail.x;
	fDetailType = 0.0;
	float radius = max(drawRadius.x, 1.0);
	fDistanceFade = 1.0 - smoothstep(radius * 0.72, radius,
		length(anchorSeed.xz - focusPosition.xz));
}
