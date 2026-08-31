#version 330

uniform sampler2D baseColorTexture;
uniform sampler2D shadowMap;
uniform mat4 shadowLightProj;
uniform vec4 baseColorFactor;
uniform vec3 lightDirection;
uniform float directionalLightingStrength;
uniform float environmentFillStrength;
uniform float nightFactor;
uniform float shadowStrength;
uniform float alphaCutoff;
uniform float foliageTransmission;
uniform int hasBaseColorTexture;
uniform int shadowsEnabled;
uniform int directionalLightingEnabled;
uniform int foliageMaterial;
uniform vec3 cameraPosition;
uniform vec3 playerPosition;
uniform int treeOcclusionMode;
uniform float treeBubbleRadius;
uniform float treeSightConeWidth;
uniform float treeMaximumFade;
uniform float cameraTopDownFactor;

in vec2 fUv;
in vec3 fWorldPos;
in vec3 fNormal;
flat in float fSeed;

out vec4 FragColor;

float receiveShadow()
{
	if (shadowsEnabled == 0) return 0.0;
	vec4 p = shadowLightProj * vec4(fWorldPos, 1.0);
	if (abs(p.w) < 0.00001) return 0.0;
	vec3 c = p.xyz / p.w * 0.5 + 0.5;
	if (any(lessThan(c, vec3(0.0))) || any(greaterThan(c, vec3(1.0)))) return 0.0;
	float blocked = c.z - 0.00055 > texture(shadowMap, c.xy).r ? 1.0 : 0.0;
	return blocked * clamp(shadowStrength, 0.0, 0.85);
}

float foliageVisibility()
{
	if (treeOcclusionMode == 0 || foliageMaterial == 0) return 1.0;

	float horizontalRadius = max(treeBubbleRadius, 1.0);
	float aboveRadius = horizontalRadius * 4.5;
	float belowRadius = horizontalRadius * 1.3;
	vec3 playerDelta = fWorldPos - playerPosition;
	float horizontal = length(playerDelta.xz) / horizontalRadius;
	// RuneLite height rises toward negative Y. Keep a taller canopy volume above
	// the player while avoiding an unnecessarily large underground bubble.
	float vertical = playerDelta.y < 0.0
		? -playerDelta.y / aboveRadius : playerDelta.y / belowRadius;
	float bubbleDistance = length(vec2(horizontal, vertical));
	float bubble = 1.0 - smoothstep(0.52, 1.0, bubbleDistance);

	vec3 cameraToPlayer = playerPosition - cameraPosition;
	float sightLength = length(cameraToPlayer);
	vec3 sightDirection = sightLength > 0.001
		? cameraToPlayer / sightLength : vec3(0.0, 0.0, 1.0);
	vec3 sightEnd = playerPosition + sightDirection * 128.0;
	vec3 sightAxis = sightEnd - cameraPosition;
	float sightLengthSquared = max(dot(sightAxis, sightAxis), 0.0001);
	float along = clamp(dot(fWorldPos - cameraPosition, sightAxis)
		/ sightLengthSquared, 0.0, 1.0);
	vec3 closest = cameraPosition + sightAxis * along;
	// Keep the camera end broad enough that a canopy immediately in front of
	// the lens cannot escape the corridor merely because it is near its apex.
	float coneRadius = mix(max(treeSightConeWidth, 1.0) * 0.65,
		max(treeSightConeWidth, 1.0), along);
	float corridorDistance = length(fWorldPos - closest) / coneRadius;
	float corridor = 1.0 - smoothstep(0.32, 1.0, corridorDistance);

	// A separate camera guard handles foliage cards which surround or cross the
	// camera itself. This is deliberately foliage-only: trunks and branches are
	// never discarded by the visibility system.
	float cameraGuardRadius = max(treeSightConeWidth, 1.0) * 0.90;
	float cameraGuardDistance = length(fWorldPos - cameraPosition)
		/ cameraGuardRadius;
	float cameraGuard = 1.0 - smoothstep(0.30, 1.0, cameraGuardDistance);

	float topDown = clamp(cameraTopDownFactor, 0.0, 1.0);
	float bubbleWeight = mix(0.15, 1.0, topDown);
	float corridorWeight = 1.0 - smoothstep(0.55, 1.0, topDown);
	float cameraGuardWeight = treeOcclusionMode >= 3 ? 1.0
		: (treeOcclusionMode == 2 ? 0.90 : 0.72);
	float obstruction = max(max(bubble * bubbleWeight,
		corridor * corridorWeight), cameraGuard * cameraGuardWeight);
	// Player Priority is allowed to dither fully obstructing foliage away.
	// The gentler presets retain the minimum coverage encoded by their preset.
	return 1.0 - obstruction * clamp(treeMaximumFade, 0.0, 1.0);
}

float screenDoorThreshold(vec2 pixel)
{
	// Stable four-by-four Bayer coverage avoids alpha sorting and gives gradual,
	// deterministic coverage changes as the smoothed corridor moves.
	ivec2 cell = ivec2(mod(floor(pixel), 4.0));
	int index = cell.x + cell.y * 4;
	float values[16] = float[16](
		0.0, 8.0, 2.0, 10.0,
		12.0, 4.0, 14.0, 6.0,
		3.0, 11.0, 1.0, 9.0,
		15.0, 7.0, 13.0, 5.0);
	return (values[index] + 0.5) / 16.0;
}

void main()
{
	vec4 albedo = baseColorFactor;
	if (hasBaseColorTexture != 0)
	{
		albedo *= texture(baseColorTexture, fUv);
	}
	if (albedo.a < alphaCutoff) discard;
	float visibility = foliageVisibility();
	if (foliageMaterial != 0
		&& screenDoorThreshold(gl_FragCoord.xy) > visibility) discard;

	// GLB normals have already received node transforms, the one Y-up to
	// RuneLite conversion, and instance yaw. Flip the interpolated normal on a
	// back face so two-sided cards retain a coherent visible-side normal.
	vec3 n = normalize(gl_FrontFacing ? fNormal : -fNormal);
	vec3 l = normalize(lightDirection);
	float normalLight = dot(n, l);
	float facing = max(normalLight, 0.0);
	if (foliageMaterial != 0)
	{
		// Thin foliage has no meaningful dark interior side. Preserve direction
		// while wrapping enough light around each card to avoid black back faces.
		float wrap = 0.34 + clamp(foliageTransmission, 0.0, 1.0) * 0.16;
		facing = mix(wrap, 1.0, abs(normalLight));
	}
	float diffuse = smoothstep(0.025, 0.92, facing);
	float night = clamp(nightFactor, 0.0, 1.0);
	float configuredOcclusion = receiveShadow();
	if (directionalLightingEnabled == 0)
	{
		vec3 shadowTransmission = mix(vec3(0.44, 0.48, 0.55),
			vec3(0.60, 0.65, 0.76), night);
		vec3 color = albedo.rgb * mix(vec3(1.0), shadowTransmission,
			configuredOcclusion);
		FragColor = vec4(color, albedo.a);
		return;
	}
	float sunStrength = clamp(directionalLightingStrength, 0.0, 1.0);
	float fillStrength = clamp(environmentFillStrength, 0.0, 1.0);
	float directVisibility = 1.0 - configuredOcclusion;
	float daylight = mix(1.0, 0.20, night);
	float directEnergy = diffuse * sunStrength * 1.22
		* daylight * directVisibility;
	float fillEnergy = mix(0.17, 0.13, night)
		+ fillStrength * mix(0.36, 0.42, night);
	vec3 sunTint = mix(vec3(1.00, 0.91, 0.76),
		vec3(0.58, 0.70, 0.98), night);
	vec3 skyTint = mix(vec3(0.70, 0.82, 1.00),
		vec3(0.38, 0.48, 0.74), night);
	vec3 illumination = sunTint * directEnergy + skyTint * fillEnergy;
	float illuminationLuma = dot(illumination, vec3(0.2126, 0.7152, 0.0722));
	vec3 illuminationChroma = illumination / max(illuminationLuma, 0.0001);
	float chromaAmount = 0.10 + 0.04 * fillStrength;
	vec3 color = albedo.rgb * illuminationLuma;
	color *= mix(vec3(1.0), illuminationChroma, chromaAmount);
	FragColor = vec4(color, albedo.a);
}
