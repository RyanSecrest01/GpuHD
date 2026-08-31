#version 330

layout(location = 0) in vec3 position;
layout(location = 1) in vec3 normal;
layout(location = 2) in vec2 uv;
layout(location = 4) in vec4 anchorSeed;
layout(location = 5) in vec2 anchorDetail;
layout(location = 6) in vec3 terrainNormal;

uniform mat4 projection;
uniform vec3 cameraPosition;
uniform vec3 focusPosition;
uniform vec2 worldOffset;
uniform float time;
uniform vec4 drawRadius;
uniform float heightScale;
uniform float windStrength;
uniform float slopeFollow;
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
	// Blend upright world-up with the sampled triangle normal. The imported
	// model's local +Y is mapped onto this blended up direction; yaw is applied
	// around that direction so roots remain fixed at the instance anchor.
	vec3 up = normalize(mix(vec3(0.0, -1.0, 0.0),
			terrainNormal, clamp(slopeFollow, 0.0, 1.0)));
	vec3 reference = abs(up.y) < 0.90 ? vec3(0.0, -1.0, 0.0)
		: vec3(0.0, 0.0, 1.0);
	vec3 tangent = normalize(cross(reference, up));
	vec3 bitangent = normalize(cross(up, tangent));
	vec2 rotated = mat2(cos(yaw), -sin(yaw), sin(yaw), cos(yaw))
		* position.xz * 1.55 * scale;
	float height = max(position.y, 0.0) * scale;
	float gust = sin(dot(anchorSeed.xz + worldOffset, vec2(0.0041, 0.0033))
		- time * 0.85 + seed * 6.2831);
	float bend = debugMode != 0 ? 0.0
		: gust * clamp(abs(windStrength), 0.0, 1.8) * height * 0.075;
	// RuneLite elevation grows toward negative Y; bury the imported base by a
	// tiny anti-z-fighting offset so it remains grounded on the sampled triangle.
	vec3 lateral = tangent * (rotated.x + bend) + bitangent * rotated.y;
	vec3 world = anchorSeed.xyz + lateral + up * height;
	vec3 transformedNormal = normalize(
		tangent * (normal.x * cos(yaw) - normal.z * sin(yaw))
		+ up * normal.y
		+ bitangent * (normal.x * sin(yaw) + normal.z * cos(yaw)));
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
