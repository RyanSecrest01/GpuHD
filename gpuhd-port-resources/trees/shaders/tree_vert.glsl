#version 330

layout(location = 0) in vec3 position;
layout(location = 1) in vec3 normal;
layout(location = 2) in vec2 uv;
layout(location = 4) in vec4 anchorYaw;
layout(location = 5) in vec2 scaleSeed;

uniform mat4 projection;
uniform float time;
uniform vec2 windDirection;
uniform float windStrength;
uniform float materialWindResponse;

out vec2 fUv;
out vec3 fWorldPos;
out vec3 fNormal;
flat out float fSeed;

vec2 treeWindOffset(vec3 localPosition)
{
	float height01 = clamp(-position.y / 64.0, 0.0, 1.0);
	float bendProfile = pow(height01, 1.65);
	float phase = dot(anchorYaw.xz, vec2(0.0021, 0.0017))
		- time * 0.72 + scaleSeed.y * 6.2831853;
	float gust = sin(phase) * 0.72 + sin(phase * 0.43 + 1.7) * 0.28;
	vec2 direction = normalize(windDirection);
	vec2 crossWind = vec2(-direction.y, direction.x);
	float broad = scaleSeed.x * 1.55 * windStrength
		* materialWindResponse * bendProfile * gust;
	float flutter = sin(time * 2.35 + dot(localPosition.xz, vec2(0.085, 0.063))
		+ scaleSeed.y * 11.7) * scaleSeed.x * 0.24
		* materialWindResponse * bendProfile;
	return direction * broad + crossWind * flutter;
}

void main()
{
	float yaw = anchorYaw.w;
	float c = cos(yaw);
	float s = sin(yaw);
	vec3 local = position * scaleSeed.x;
	vec2 windOffset = treeWindOffset(local);
	vec3 world = anchorYaw.xyz + vec3(
		local.x * c - local.z * s + windOffset.x,
		local.y,
		local.x * s + local.z * c + windOffset.y);
	fNormal = normalize(vec3(
		normal.x * c - normal.z * s,
		normal.y,
		normal.x * s + normal.z * c));
	fUv = uv;
	fWorldPos = world;
	fSeed = scaleSeed.y;
	gl_Position = projection * vec4(world, 1.0);
}
