#version 330

uniform float brightness;
uniform int enhancedColors;
uniform float saturation;
uniform float contrast;
uniform vec4 fogColor;

uniform vec3 cameraPosition;
uniform vec3 lightDirection;
uniform float lightIntensity;
uniform float ambientLight;
uniform float lightningFlash;
uniform int weatherMode;
uniform float nightFactor;

uniform sampler2D shadowMap;
uniform mat4 shadowLightProj;
uniform int shadowsEnabled;
uniform float shadowStrength;
uniform int materialDebugMode;
uniform int wetSurfacesEnabled;
uniform float wetSurfaceStrength;

in vec2 fDetailUv;
in vec3 fWorldPos;
in vec3 fDetailNormal;
in vec3 fDetailTangent;
flat in float fSeed;
flat in float fDistanceFade;
flat in float fGroundHsl;
flat in float fDetailType;

out vec4 FragColor;

#include "hsl_to_rgb.glsl"

float stableHash(float value)
{
	return fract(sin(value * 91.3458 + 17.123) * 47453.5453);
}

vec3 safeHalfDirection(vec3 lightDir, vec3 viewDir)
{
	vec3 sum = lightDir + viewDir;
	float lengthSquared = dot(sum, sum);
	return lengthSquared > 0.000001
		? sum * inversesqrt(lengthSquared)
		: lightDir;
}

vec3 unpackGroundHsl()
{
	int packedHsl = max(int(floor(fGroundHsl + 0.5)), 0);
	return vec3(
		float(packedHsl >> 10 & 63),
		float(packedHsl >> 7 & 7),
		float(packedHsl & 127));
}

vec3 terrainMatchedGrass()
{
	vec3 groundHsl = unpackGroundHsl();
	// Keep the local terrain palette, then bias it just enough to read as a
	// living leaf rather than importing one synthetic green into every biome.
	vec3 bladeHsl = groundHsl;
	bladeHsl.x = mod(bladeHsl.x + mix(-0.45, 0.70, fSeed) + 64.0, 64.0);
	bladeHsl.y = clamp(bladeHsl.y + mix(0.30, 0.95, fSeed), 0.0, 7.0);
	bladeHsl.z = clamp(
		bladeHsl.z * mix(0.83, 0.95, fSeed) + mix(-1.0, 2.5, fSeed),
		8.0, 120.0);
	return hslToRgb(bladeHsl);
}

vec3 terrainMatchedStone()
{
	vec3 groundHsl = unpackGroundHsl();
	vec3 stoneHsl = groundHsl;
	// Pebbles inherit their port/path/rock material, but reduced chroma and a
	// small face-stable light shift make them distinct physical pieces.
	stoneHsl.x = mod(stoneHsl.x + mix(-0.65, 0.65, fSeed) + 64.0, 64.0);
	stoneHsl.y = clamp(stoneHsl.y * mix(0.32, 0.58, fSeed), 0.0, 5.2);
	stoneHsl.z = clamp(
		stoneHsl.z * mix(0.88, 1.03, fSeed) + mix(-3.5, 3.0, fSeed),
		9.0, 116.0);
	vec3 color = hslToRgb(stoneHsl);
	float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
	return mix(color, vec3(luminance), 0.16);
}

float detailEnvironmentEnergy()
{
	// These generated objects do not carry RuneLite's baked vertex brightness,
	// so give them a restrained ambient baseline. Direct sun remains a separate
	// material response below instead of becoming a blanket brightness control.
	float ambientEnergy = clamp(
		0.82 + clamp(ambientLight, 0.0, 1.5) * 0.30,
		0.80, 1.07);
	return ambientEnergy * mix(
		1.0, 0.86, clamp(nightFactor, 0.0, 1.0));
}

float receiveShadow()
{
	if (shadowsEnabled == 0)
	{
		return 0.0;
	}

	vec4 lightSpacePosition = shadowLightProj * vec4(fWorldPos, 1.0);
	if (abs(lightSpacePosition.w) < 0.000001)
	{
		return 0.0;
	}

	vec3 shadowCoord = lightSpacePosition.xyz / lightSpacePosition.w;
	shadowCoord = shadowCoord * 0.5 + 0.5;
	if (shadowCoord.x < 0.0 || shadowCoord.x > 1.0
		|| shadowCoord.y < 0.0 || shadowCoord.y > 1.0
		|| shadowCoord.z < 0.0 || shadowCoord.z > 1.0)
	{
		return 0.0;
	}

	// One stable lookup is sufficient at detail scale. The distance-aware bias
	// suppresses crawling acne on the tiny faceted stones.
	float receiverDistance = length(cameraPosition - fWorldPos);
	float bias = mix(0.00042, 0.00060,
		smoothstep(768.0, 4096.0, receiverDistance));
	float closestDepth = texture(shadowMap, shadowCoord.xy).r;
	float occluded = shadowCoord.z - bias > closestDepth ? 1.0 : 0.0;
	vec2 edgeDistance = min(shadowCoord.xy, vec2(1.0) - shadowCoord.xy);
	float mapConfidence = smoothstep(
		0.0, 0.035, min(edgeDistance.x, edgeDistance.y));
	return occluded * mapConfidence * clamp(shadowStrength, 0.0, 0.80);
}

float grassCoverage()
{
	vec2 blade = fDetailUv * vec2(2.0, 1.0) - vec2(1.0, 0.0);
	float edgeNoise = sin(blade.y * 27.0 + fSeed * 37.0) * 0.026
		+ sin(blade.y * 51.0 + fSeed * 19.0) * 0.011;
	float sideLimit = 0.94 + edgeNoise;
	float sideAa = max(fwidth(blade.x) * 1.12, 0.018);
	float sideCoverage = 1.0 - smoothstep(
		sideLimit - sideAa,
		sideLimit + sideAa,
		abs(blade.x));
	float tipDistance = length(vec2(
		blade.x * 0.66,
		(blade.y - 0.90) * 4.25));
	float tipAa = max(fwidth(tipDistance) * 1.15, 0.018);
	float tipCoverage = blade.y < 0.83
		? 1.0
		: 1.0 - smoothstep(0.45 - tipAa, 0.71 + tipAa, tipDistance);
	return sideCoverage * tipCoverage;
}

vec3 shadeGrass(
	vec3 normal,
	vec3 tangent,
	vec3 lightDir,
	vec3 viewDir,
	float shadowAmount)
{
	vec3 color = terrainMatchedGrass();
	float heightTone = mix(
		0.78, 1.11, smoothstep(0.02, 0.96, fDetailUv.y));
	color *= detailEnvironmentEnergy()
		* heightTone * mix(0.95, 1.045, stableHash(fSeed * 41.0));

	// The leaf owns this narrow response; existing terrain is not relit. A
	// wrapped diffuse keeps thin ribbons legible while the anisotropic term and
	// back-light transmission create the sun-catching Unity-like edge shimmer.
	float normalLight = dot(normal, lightDir);
	float wrappedDiffuse = smoothstep(0.05, 0.94, normalLight * 0.5 + 0.5);
	color *= mix(0.91, 1.075, wrappedDiffuse);

	vec3 halfDirection = safeHalfDirection(lightDir, viewDir);
	float tangentHalf = clamp(abs(dot(normalize(tangent), halfDirection)), 0.0, 1.0);
	float anisotropic = pow(max(1.0 - tangentHalf * tangentHalf, 0.0), 14.0);
	anisotropic *= smoothstep(-0.12, 0.72, normalLight);
	float grazingView = pow(1.0 - abs(dot(normal, viewDir)), 2.0);
	float transmission = pow(clamp(-normalLight, 0.0, 1.0), 1.55)
		* mix(0.48, 1.0, grazingView);
	float overheadSun = clamp(
		dot(vec3(0.0, -1.0, 0.0), lightDir), 0.0, 1.0);
	float tipSheen = smoothstep(0.48, 0.98, fDetailUv.y)
		* overheadSun * mix(0.52, 1.0, grazingView);
	float sunEnergy = clamp(lightIntensity * 1.85, 0.0, 1.20)
		* mix(1.0, 0.18, clamp(nightFactor, 0.0, 1.0))
		* (1.0 - shadowAmount);
	color += vec3(0.13, 0.16, 0.075) * anisotropic * sunEnergy;
	color += vec3(0.075, 0.095, 0.038) * tipSheen * sunEnergy;
	color += color * vec3(0.13, 0.16, 0.075) * transmission * sunEnergy;
	return color;
}

vec3 shadeStone(
	vec3 normal,
	vec3 lightDir,
	vec3 viewDir,
	float shadowAmount)
{
	vec3 color = terrainMatchedStone();
	vec3 worldUp = vec3(0.0, -1.0, 0.0);
	float upFacing = dot(normal, worldUp) * 0.5 + 0.5;
	color *= detailEnvironmentEnergy()
		* mix(0.86, 1.055, smoothstep(0.12, 0.92, upFacing));

	float normalLight = dot(normal, lightDir);
	float facetedDiffuse = smoothstep(-0.18, 0.92, normalLight);
	color *= mix(0.89, 1.075, facetedDiffuse);
	vec3 halfDirection = safeHalfDirection(lightDir, viewDir);
	float facetGlint = pow(clamp(dot(normal, halfDirection), 0.0, 1.0), 52.0);
	float sunEnergy = clamp(lightIntensity * 1.75, 0.0, 1.10)
		* mix(1.0, 0.12, clamp(nightFactor, 0.0, 1.0))
		* (1.0 - shadowAmount);
	color += vec3(0.075, 0.071, 0.062) * facetGlint * sunEnergy;
	return color;
}

void main()
{
	bool isGrass = fDetailType < 0.5;
	if (isGrass)
	{
		float coverage = grassCoverage();
		float stableFadeThreshold = mix(0.035, 0.88, stableHash(fSeed * 73.1));
		if (coverage < 0.40 || fDistanceFade < stableFadeThreshold)
		{
			discard;
		}
	}
	else if (fDistanceFade <= 0.025)
	{
		// The final few percent are already fog-colored. Discarding there hides
		// the opaque geometry cutoff without screen-door shimmer on stone facets.
		discard;
	}

	if (materialDebugMode != 0)
	{
		FragColor = vec4(
			isGrass ? vec3(0.16, 0.92, 0.24) : vec3(0.48, 0.58, 0.72),
			1.0);
		return;
	}

	vec3 geometricNormal = normalize(fDetailNormal);
	vec3 normal = isGrass && !gl_FrontFacing
		? -geometricNormal
		: geometricNormal;
	vec3 lightDir = normalize(lightDirection);
	vec3 viewDir = normalize(cameraPosition - fWorldPos);
	float shadowAmount = receiveShadow();
	vec3 color = isGrass
		? shadeGrass(normal, normalize(fDetailTangent), lightDir, viewDir, shadowAmount)
		: shadeStone(normal, lightDir, viewDir, shadowAmount);

	if (weatherMode == 1 || weatherMode == 2)
	{
		if (wetSurfacesEnabled != 0)
		{
			float wetness = (weatherMode == 2 ? 0.32 : 0.20)
				* clamp(wetSurfaceStrength, 0.0, 1.0);
			// Living blades retain more color; rough pebbles absorb a visibly
			// stronger neutral film without becoming black or blue.
			color *= mix(1.0, isGrass ? 0.82 : 0.70, wetness);
			// Wet facets get a tiny extra highlight; it remains local to the pebble
			// and is shadow-aware so it cannot glow beneath an occluder.
			if (!isGrass)
			{
				vec3 halfDirection = safeHalfDirection(lightDir, viewDir);
				float wetGlint = pow(
					clamp(dot(normal, halfDirection), 0.0, 1.0), 72.0);
				color += vec3(0.055, 0.064, 0.070) * wetGlint * wetness
					* (1.0 - shadowAmount);
			}
		}
		// Lightning is an environment event, not a wet-material response.
		color += vec3(0.70, 0.76, 0.82) * lightningFlash * 0.48;
	}
	else if (weatherMode == 3 || weatherMode == 4)
	{
		float snowMask;
		if (isGrass)
		{
			snowMask = smoothstep(0.54, 0.94, fDetailUv.y + fSeed * 0.13);
		}
		else
		{
			snowMask = smoothstep(0.56, 0.88, dot(normal, vec3(0.0, -1.0, 0.0)));
		}
		float snowAmount = weatherMode == 4 ? 0.78 : 0.60;
		vec3 snowColor = weatherMode == 4
			? vec3(0.73, 0.78, 0.82)
			: vec3(0.82, 0.86, 0.88);
		color = mix(color, snowColor, snowMask * snowAmount);
	}

	vec3 dayTransmission = isGrass
		? vec3(0.54, 0.59, 0.62)
		: vec3(0.58, 0.59, 0.61);
	vec3 nightTransmission = isGrass
		? vec3(0.64, 0.70, 0.79)
		: vec3(0.66, 0.69, 0.76);
	vec3 shadowTransmission = mix(
		dayTransmission,
		nightTransmission,
		clamp(nightFactor, 0.0, 1.0));
	color *= mix(vec3(1.0), shadowTransmission, shadowAmount);

	if (enhancedColors != 0)
	{
		float luminance = dot(color, vec3(0.2126, 0.7152, 0.0722));
		color = mix(vec3(luminance), color, saturation);
		color = (color - vec3(0.5)) * contrast + vec3(0.5);
		color = clamp(color, 0.0, 1.0);
	}

	float distanceFog = 1.0
		- smoothstep(0.08, 0.72, fDistanceFade);
	color = mix(color, fogColor.rgb, distanceFog * 0.96);
	FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
