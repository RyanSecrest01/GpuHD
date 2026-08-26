#version 330

// Opaque scene buffers resolved immediately before this pass. Water performs
// its own composition and consequently writes an opaque result; fixed-function
// alpha blending is deliberately unnecessary.
uniform sampler2D sceneColor;
uniform sampler2D sceneDepth;
uniform samplerCube skyTexture;
uniform sampler2D shadowMap;

uniform mat4 worldProjection;
uniform mat4 shadowLightProj;
uniform vec4 sceneUvTransform;
uniform vec2 sceneTargetSize;
uniform vec3 cameraPosition;
uniform vec3 lightDirection;
uniform vec3 fogColor;
uniform float time;
uniform float waterStrength;
uniform float waterOpacity;
uniform float drawDistance;
uniform float nightFactor;
uniform float lightningFlash;
uniform float weatherDensity;
uniform int weatherMode;
uniform int shadowMapValid;
uniform int zeroToOneDepth;
uniform int skyReflectionEnabled;
uniform int materialDebugMode;

in vec3 fWorldPos;
flat in int fWaterTextureId;

out vec4 FragColor;

const float DEPTH_EPSILON = 0.00001;

float clipDepth(float rawDepth)
{
	return zeroToOneDepth != 0 ? rawDepth : rawDepth * 2.0 - 1.0;
}

vec2 sceneUv(vec2 viewportUv)
{
	return sceneUvTransform.xy + viewportUv * sceneUvTransform.zw;
}

vec2 viewportUv(vec2 resolvedUv)
{
	return (resolvedUv - sceneUvTransform.xy)
		/ max(sceneUvTransform.zw, vec2(0.000001));
}

bool insideViewport(vec2 uv)
{
	return uv.x >= 0.0 && uv.x <= 1.0
		&& uv.y >= 0.0 && uv.y <= 1.0;
}

vec2 projectWorldToViewport(vec3 worldPosition)
{
	vec4 clipPosition = worldProjection * vec4(worldPosition, 1.0);
	if (clipPosition.w <= 0.00001)
	{
		return vec2(-2.0);
	}
	return clipPosition.xy / clipPosition.w * 0.5 + 0.5;
}

bool validOpaqueDepth(float rawDepth, float waterClipDepth)
{
	if (rawDepth <= DEPTH_EPSILON)
	{
		return false;
	}
	float opaqueClipDepth = clipDepth(rawDepth);
	// Reversed depth: a smaller positive reciprocal depth is farther away. A
	// refracted sample that is closer than the water belongs to a bank/object and
	// must not be smeared across the surface.
	return opaqueClipDepth > DEPTH_EPSILON
		&& opaqueClipDepth < waterClipDepth - 0.00002;
}

// Generated shoreline substrate is tagged in the opaque resolve with a
// half-alpha marker. Match the nearest color texel to the nearest-filtered depth
// lookup instead of classifying linearly filtered alpha from a neighboring rock.
float generatedBedMarker(vec2 resolvedUv)
{
	ivec2 size = textureSize(sceneColor, 0);
	ivec2 texel = clamp(
		ivec2(resolvedUv * vec2(size)), ivec2(0), size - ivec2(1));
	float alpha = texelFetch(sceneColor, texel, 0).a;
	// The interior marker resolves to 127/255. The broad upper shoulder also
	// retains partially covered MSAA edge pixels, while normal geometry is 1.0.
	return 1.0 - smoothstep(0.72, 0.97, alpha);
}

vec3 waveNormal(float strength, float weatherRoughness)
{
	// Analytic, world-anchored wave slopes. Slight phase warping keeps the calm
	// surface organic instead of exposing straight, repeating sine bands. Broad
	// undulation is useful at distance, where it breaks up a flat horizon, but it
	// reads as inflated rubber when inspected beside the player. Fade only that
	// broad component nearby and replace it with much smaller capillary ripples.
	// RuneLite world up is negative Y.
	vec2 directionA = normalize(vec2(0.91, 0.42));
	vec2 directionB = normalize(vec2(-0.31, 0.95));
	vec2 directionC = normalize(vec2(0.62, -0.78));
	float phaseWarp = sin(
		dot(fWorldPos.xz, vec2(0.019, -0.023)) - time * 0.17);
	float phaseA = dot(fWorldPos.xz, directionA) * 0.046
		+ time * 0.54 + phaseWarp * 0.52;
	float phaseB = dot(fWorldPos.xz, directionB) * 0.073
		- time * 0.41 - phaseWarp * 0.31;
	float phaseC = dot(fWorldPos.xz, directionC) * 0.027
		+ time * 0.19 + sin(phaseB * 0.47) * 0.36;
	vec2 broadSlope = directionA * cos(phaseA) * 0.0125
		+ directionB * cos(phaseB) * 0.0075
		+ directionC * cos(phaseC) * 0.0042;
	float horizontalDistance = length(cameraPosition.xz - fWorldPos.xz);
	float nearSurface = 1.0 - smoothstep(420.0, 1150.0,
		horizontalDistance);
	vec2 capillaryDirectionA = normalize(vec2(0.48, 0.88));
	vec2 capillaryDirectionB = normalize(vec2(-0.86, 0.51));
	float capillaryPhaseA = dot(fWorldPos.xz, capillaryDirectionA) * 0.224
		- time * 0.72 + sin(phaseA * 0.61) * 0.24;
	float capillaryPhaseB = dot(fWorldPos.xz, capillaryDirectionB) * 0.307
		+ time * 0.56 - sin(phaseB * 0.53) * 0.19;
	vec2 capillarySlope = capillaryDirectionA * cos(capillaryPhaseA) * 0.0018
		+ capillaryDirectionB * cos(capillaryPhaseB) * 0.00125;
	vec2 slope = broadSlope * mix(1.0, 0.34, nearSurface)
		+ capillarySlope * nearSurface;

	// Rain makes the surface rough rather than making the underlying color pulse.
	// The fine response is still world-space and deliberately below foam scale.
	float rainPhaseA = dot(fWorldPos.xz, vec2(0.73, -0.68)) * 0.061
		- time * 2.65;
	float rainPhaseB = dot(fWorldPos.xz, vec2(0.38, 0.92)) * 0.083
		+ time * 2.12;
	vec2 rainSlope = vec2(0.73, -0.68) * cos(rainPhaseA)
		+ vec2(0.38, 0.92) * cos(rainPhaseB);
	slope += rainSlope * 0.0055 * weatherRoughness;
	slope *= mix(0.54, 0.96, clamp(strength, 0.0, 2.0) * 0.5);

	vec3 geometricNormal = normalize(cross(dFdx(fWorldPos), dFdy(fWorldPos)));
	if (geometricNormal.y > 0.0)
	{
		geometricNormal = -geometricNormal;
	}
	// Water should not inherit the deliberately faceted normals of shaped terrain
	// tiles. Retain only a trace of their broad slope so adjoining triangles do not
	// become separate reflective wedges, then layer the world-space waves over it.
	vec2 terrainSlope = clamp(
		geometricNormal.xz * 0.005, vec2(-0.004), vec2(0.004));
	return normalize(vec3(
		terrainSlope.x + slope.x, -1.0, terrainSlope.y + slope.y));
}

float shadowVisibility(vec3 worldPosition, vec3 normal, vec3 sourceDirection)
{
	if (shadowMapValid == 0)
	{
		return 1.0;
	}

	vec4 lightPosition = shadowLightProj * vec4(worldPosition, 1.0);
	if (abs(lightPosition.w) < 0.00001)
	{
		return 1.0;
	}
	vec3 shadowCoord = lightPosition.xyz / lightPosition.w;
	shadowCoord = shadowCoord * 0.5 + 0.5;
	if (shadowCoord.x < 0.0 || shadowCoord.x > 1.0
		|| shadowCoord.y < 0.0 || shadowCoord.y > 1.0
		|| shadowCoord.z < 0.0 || shadowCoord.z > 1.0)
	{
		return 1.0;
	}

	vec2 texel = 1.0 / vec2(textureSize(shadowMap, 0));
	float facing = max(dot(normal, sourceDirection), 0.0);
	float bias = max(0.0018 * (1.0 - facing), 0.00045);
	float occlusion = 0.0;
	for (int x = -1; x <= 1; ++x)
	{
		for (int y = -1; y <= 1; ++y)
		{
			float storedDepth = texture(
				shadowMap, shadowCoord.xy + vec2(float(x), float(y)) * texel).r;
			occlusion += shadowCoord.z - bias > storedDepth ? 1.0 : 0.0;
		}
	}
	return 1.0 - occlusion / 9.0 * 0.86;
}

vec3 roughSkyReflection(vec3 reflectionDirection, float roughness)
{
	if (skyReflectionEnabled == 0)
	{
		return fogColor;
	}

	vec3 helperAxis = abs(reflectionDirection.y) < 0.88
		? vec3(0.0, -1.0, 0.0)
		: vec3(1.0, 0.0, 0.0);
	vec3 tangent = normalize(cross(helperAxis, reflectionDirection));
	vec3 bitangent = normalize(cross(reflectionDirection, tangent));
	float spread = roughness * 0.055;
	vec3 center = texture(skyTexture, reflectionDirection).rgb;
	vec3 sideA = texture(skyTexture, normalize(
		reflectionDirection + tangent * spread + bitangent * spread * 0.45)).rgb;
	vec3 sideB = texture(skyTexture, normalize(
		reflectionDirection - tangent * spread + bitangent * spread * 0.31)).rgb;
	return center * 0.54 + (sideA + sideB) * 0.23;
}

void main()
{
	if (materialDebugMode != 0)
	{
		FragColor = vec4(
			materialDebugMode == 1 ? vec3(0.12, 0.45, 1.0) : vec3(0.02),
			1.0);
		return;
	}
	float strength = clamp(waterStrength, 0.0, 2.0);
	float configuredOpacity = clamp(waterOpacity, 0.0, 1.0);
	bool swampWater = fWaterTextureId == 25;
	float rainAmount = weatherMode == 2 ? 1.0
		: weatherMode == 1 ? 0.62 : 0.0;
	float snowWeather = weatherMode == 4 ? 0.62
		: weatherMode == 3 ? 0.32 : 0.0;
	float weatherRoughness = clamp(
		(rainAmount + snowWeather) * max(weatherDensity, 0.18), 0.0, 1.0);

	vec3 normal = waveNormal(strength, weatherRoughness);
	vec3 viewDirection = normalize(cameraPosition - fWorldPos);
	float viewFacing = clamp(dot(normal, viewDirection), 0.0, 1.0);
	// Keep reflection concentrated near grazing angles. The stable blue volume is
	// responsible for the water body; the cubemap is only its clean surface sheen.
	float fresnel = 0.022 + 0.978 * pow(1.0 - viewFacing, 5.0);

	vec2 baseResolvedUv = gl_FragCoord.xy
		/ max(sceneTargetSize, vec2(1.0));
	vec2 baseViewportUv = viewportUv(baseResolvedUv);
	float waterRawDepth = gl_FragCoord.z;
	float waterClipDepth = clipDepth(waterRawDepth);
	float waterDistance = length(cameraPosition - fWorldPos);
	float nearSurface = 1.0 - smoothstep(420.0, 1150.0,
		length(cameraPosition.xz - fWorldPos.xz));

	// Convert a small horizontal normal displacement through the real world
	// projection. This makes refraction turn with the camera instead of behaving
	// like a screen-space sticker.
	vec2 projectedBase = projectWorldToViewport(fWorldPos);
	vec3 refractedWorld = fWorldPos
		+ vec3(normal.x, 0.0, normal.z) * mix(28.0, 54.0, strength * 0.5);
	vec2 projectedRefracted = projectWorldToViewport(refractedWorld);
	vec2 refractedOffset = (projectedRefracted - projectedBase)
		* mix(0.10, 0.21, strength * 0.5)
		* (1.0 - fresnel * 0.72);
	refractedOffset *= mix(1.0, 1.14, weatherRoughness);
	// Large refraction offsets are attractive on the distant ocean but make the
	// foreground surface look gelatinous as its contents slide beneath the camera.
	refractedOffset *= 1.0 - nearSurface * 0.55;
	float offsetLength = length(refractedOffset);
	float maximumOffset = mix(0.00075, 0.00155, strength * 0.5);
	if (offsetLength > maximumOffset)
	{
		refractedOffset *= maximumOffset / offsetLength;
	}
	vec2 refractedViewportUv = baseViewportUv + refractedOffset;
	if (!insideViewport(refractedViewportUv))
	{
		refractedViewportUv = clamp(baseViewportUv, 0.0, 1.0);
	}

	vec2 refractedResolvedUv = sceneUv(refractedViewportUv);
	float refractedRawDepth = texture(sceneDepth, refractedResolvedUv).r;
	bool hasOpaqueBed = validOpaqueDepth(refractedRawDepth, waterClipDepth);
	if (!hasOpaqueBed)
	{
		float baseRawDepth = texture(sceneDepth, baseResolvedUv).r;
		if (validOpaqueDepth(baseRawDepth, waterClipDepth))
		{
			refractedResolvedUv = baseResolvedUv;
			refractedRawDepth = baseRawDepth;
			hasOpaqueBed = true;
		}
	}

	// Missing-bed pixels retain the stable deep-water body. Shallow color is
	// derived only from resolved geometry beneath the water surface.
	float night = clamp(nightFactor, 0.0, 1.0);
	vec3 clearDeep = mix(
		vec3(0.195, 0.475, 0.620), vec3(0.045, 0.120, 0.205), night);
	vec3 swampDeep = mix(
		vec3(0.105, 0.205, 0.105), vec3(0.035, 0.075, 0.045), night);
	vec3 fallbackWaterColor = swampWater ? swampDeep : clearDeep;
	if (weatherMode == 2)
	{
		fallbackWaterColor = mix(fallbackWaterColor, fogColor, 0.14);
	}
	else if (weatherMode == 4)
	{
		fallbackWaterColor = mix(fallbackWaterColor, fogColor, 0.07);
	}

	// Feather only the binary scene-depth coverage across a compact resolved-pixel
	// kernel. A valid primary sample remains authoritative for color and thickness,
	// preserving the real substrate texture instead of blurring it across taps.
	vec2 bedProbeUv = hasOpaqueBed ? refractedResolvedUv : baseResolvedUv;
	vec2 depthTexel = 1.0 / max(sceneTargetSize, vec2(1.0));
	vec2 sceneBoundsA = sceneUv(vec2(0.0)) + depthTexel * 0.5;
	vec2 sceneBoundsB = sceneUv(vec2(1.0)) - depthTexel * 0.5;
	vec2 sceneMinimum = min(sceneBoundsA, sceneBoundsB);
	vec2 sceneMaximum = max(sceneBoundsA, sceneBoundsB);
	vec4 primaryBedSample = hasOpaqueBed
		? texture(sceneColor, refractedResolvedUv)
		: vec4(fallbackWaterColor, 1.0);
	vec3 primaryBedColor = primaryBedSample.rgb;
	float primaryGeneratedBed = hasOpaqueBed
		? generatedBedMarker(refractedResolvedUv) : 0.0;
	float primaryThickness = 3.0;
	if (hasOpaqueBed)
	{
		float primaryClipDepth = clipDepth(refractedRawDepth);
		float primaryDistance = waterDistance * waterClipDepth
			/ max(primaryClipDepth, DEPTH_EPSILON);
		primaryThickness = clamp(
			primaryDistance - waterDistance, 3.0, 560.0);
	}
	vec3 realBedColorSum = vec3(0.0);
	float realThicknessSum = 0.0;
	float generatedBedWeight = 0.0;
	float validBedWeight = 0.0;
	float totalBedWeight = 0.0;
	for (int x = -1; x <= 1; ++x)
	{
		for (int y = -1; y <= 1; ++y)
		{
			if (x != 0 && y != 0)
			{
				continue;
			}
			float kernelWeight = x == 0 && y == 0 ? 4.0
				: 2.0;
			totalBedWeight += kernelWeight;
			vec2 sampleUv = clamp(bedProbeUv
				+ vec2(float(x), float(y)) * depthTexel * 2.5,
				sceneMinimum, sceneMaximum);
			float sampleDepth = texture(sceneDepth, sampleUv).r;
			if (validOpaqueDepth(sampleDepth, waterClipDepth))
			{
				validBedWeight += kernelWeight;
				if (!hasOpaqueBed)
				{
					vec4 sampleColor = texture(sceneColor, sampleUv);
					float sampleClipDepth = clipDepth(sampleDepth);
					float sampleDistance = waterDistance * waterClipDepth
						/ max(sampleClipDepth, DEPTH_EPSILON);
					realBedColorSum += sampleColor.rgb
						* kernelWeight;
					realThicknessSum += clamp(
						sampleDistance - waterDistance, 3.0, 560.0)
						* kernelWeight;
					generatedBedWeight += generatedBedMarker(sampleUv)
						* kernelWeight;
				}
			}
		}
	}
	float realBedCoverage = validBedWeight / max(totalBedWeight, 0.0001);
	// A valid center sample is the resolved object itself, even when a thin rock
	// occupies only one kernel texel. Feather coverage only when refraction missed
	// the center and the neighboring depth taps provide the fallback.
	float realBedBlend = hasOpaqueBed ? 1.0
		: smoothstep(0.08, 0.78, realBedCoverage);
	vec3 realBedColor = hasOpaqueBed ? primaryBedColor
		: validBedWeight > 0.0001
			? realBedColorSum / validBedWeight : fallbackWaterColor;
	float realThickness = hasOpaqueBed ? primaryThickness
		: validBedWeight > 0.0001
			? realThicknessSum / validBedWeight : 3.0;
	float generatedBedInfluence = hasOpaqueBed ? primaryGeneratedBed
		: validBedWeight > 0.0001
			? generatedBedWeight / validBedWeight : 0.0;

	// The generated coast is deliberately identifiable in the resolved scene, so
	// it can carry a clearer water surface without changing open water or native
	// submerged geometry. Add two small, world-anchored capillary waves here: they
	// break up both the refracted bed and the reflected sky, but remain far below
	// the broad amplitudes which previously made foreground water look rubbery.
	float generatedWaterInfluence = generatedBedInfluence
		* realBedBlend
		* (1.0 - smoothstep(150.0, 224.0, realThickness));
	if (generatedWaterInfluence > 0.001)
	{
		// Keep the additional trigonometric work inside the tagged coast branch;
		// open and distant water retain both their previous result and cost.
		float coastPhaseA = dot(fWorldPos.xz, vec2(0.173, 0.091))
			- time * 0.78;
		float coastPhaseB = dot(fWorldPos.xz, vec2(-0.118, 0.207))
			+ time * 0.61;
		vec2 coastRippleSlope = vec2(
			cos(coastPhaseA) * 0.0058 + cos(coastPhaseB) * 0.0034,
			cos(coastPhaseA * 0.73 + 1.4) * 0.0030
				- cos(coastPhaseB * 1.16) * 0.0050);
		normal = normalize(vec3(
			normal.x + coastRippleSlope.x * generatedWaterInfluence,
			-1.0,
			normal.z + coastRippleSlope.y * generatedWaterInfluence));
		viewFacing = clamp(dot(normal, viewDirection), 0.0, 1.0);
		fresnel = 0.022 + 0.978 * pow(1.0 - viewFacing, 5.0);

		// Reproject the disturbed world-space surface rather than sliding a fixed
		// screen-space texture. Accept the stronger sample only while it remains on
		// tagged substrate, preventing sand from leaking across banks or rocks.
		vec3 coastRefractedWorld = fWorldPos
			+ vec3(normal.x, 0.0, normal.z)
				* mix(92.0, 138.0, strength * 0.5);
		vec2 coastRefractedOffset =
			(projectWorldToViewport(coastRefractedWorld) - projectedBase)
			* mix(0.30, 0.42, strength * 0.5)
			* (1.0 - fresnel * 0.58);
		float coastOffsetLength = length(coastRefractedOffset);
		float coastMaximumOffset = mix(0.00085, 0.00155,
			strength * 0.5);
		if (coastOffsetLength > coastMaximumOffset)
		{
			coastRefractedOffset *= coastMaximumOffset
				/ coastOffsetLength;
		}
		vec2 coastViewportUv = baseViewportUv + coastRefractedOffset;
		if (insideViewport(coastViewportUv))
		{
			vec2 coastResolvedUv = sceneUv(coastViewportUv);
			float coastRawDepth = texture(sceneDepth, coastResolvedUv).r;
			float coastMarker = generatedBedMarker(coastResolvedUv);
			if (validOpaqueDepth(coastRawDepth, waterClipDepth)
				&& coastMarker > 0.001)
			{
				vec3 coastBedColor = texture(
					sceneColor, coastResolvedUv).rgb;
				float coastRefractionWeight = generatedWaterInfluence
					* coastMarker
					* mix(0.54, 0.78, strength * 0.5);
				realBedColor = mix(realBedColor, coastBedColor,
					coastRefractionWeight);
			}
		}
	}

	// Terrain textures around many RuneLite coasts are grass-biased. They supply
	// useful fine detail, but their hue should not survive as a green seabed. Map
	// only tagged substrate luminance into a compact wet-sand ramp. This keeps the
	// original texture's light/dark structure while producing the pale beach bed
	// seen through the water film. Native rocks retain their original RGB exactly.
	float substrateLuminance = dot(
		realBedColor, vec3(0.2126, 0.7152, 0.0722));
	float sandTone = smoothstep(0.08, 0.78, substrateLuminance);
	vec3 dayWetSand = mix(
		vec3(0.240, 0.205, 0.135),
		vec3(0.680, 0.585, 0.380),
		sandTone);
	vec3 nightWetSand = mix(
		vec3(0.035, 0.055, 0.065),
		vec3(0.130, 0.160, 0.170),
		sandTone);
	vec3 gradedWetSand = mix(dayWetSand, nightWetSand, night);
	float sandGradeWeight = clamp(
		generatedBedInfluence * realBedBlend * 0.88, 0.0, 0.88);
	realBedColor = mix(realBedColor, gradedWetSand, sandGradeWeight);

	// Only actual shallow geometry changes clarity and brightness. Missing floors,
	// deep beds, and distant open water retain the existing water response.
	float shallowBedInfluence = swampWater ? 0.0
		: realBedBlend
			* (1.0 - smoothstep(96.0, 224.0, realThickness));
	float effectiveOpacity = mix(
		configuredOpacity, min(configuredOpacity, 0.45), shallowBedInfluence);

	// Apply real Beer-Lambert transmission only where the opaque resolve contains
	// actual geometry beneath the surface. Missing-floor pixels retain the stable
	// blue body above rather than exposing a fake textured bottom.
	float densityScale = swampWater
		? mix(0.26, 0.78, configuredOpacity)
		: mix(0.050, 0.300, effectiveOpacity);
	densityScale *= mix(0.86, 1.0, clamp(strength, 0.0, 1.0));
	vec3 absorption = swampWater
		? vec3(0.46, 0.22, 0.50)
		: vec3(0.44, 0.135, 0.055);
	float opticalDepth = realThickness / 180.0 * densityScale;
	vec3 transmission = exp(-absorption * opticalDepth);
	vec3 scatterColor = swampWater
		? vec3(0.060, 0.100, 0.042)
		: vec3(0.075, 0.315, 0.420);
	scatterColor *= mix(1.0, 0.50, night);
	vec3 realUnderwaterColor = realBedColor * transmission
		+ scatterColor * (vec3(1.0) - transmission)
			* (swampWater ? 0.30 : 0.48);
	float shallowLight = shallowBedInfluence * (1.0 - night * 0.65);
	realUnderwaterColor *= 1.0 + shallowLight * 0.075;
	realUnderwaterColor += vec3(0.006, 0.020, 0.024)
		* shallowLight;

	// The copied coast material is the seabed, not the final surface color. Lay a
	// restrained tropical-blue water film over generated shallow substrate so the
	// sand remains legible through water rather than appearing to replace it.
	// Real rocks and other native opaque geometry are intentionally not tagged and
	// therefore retain their existing transmission response at any depth.
	float generatedShallowInfluence = generatedWaterInfluence;
	float generatedFilmDepth = smoothstep(8.0, 128.0, realThickness);
	vec3 tropicalSurfaceFilm = mix(
		vec3(0.075, 0.500, 0.680),
		vec3(0.025, 0.135, 0.225),
		night);
	float surfaceFilmWeight = generatedShallowInfluence
		* mix(0.26, 0.44, generatedFilmDepth)
		* mix(0.86, 1.08, configuredOpacity);
	surfaceFilmWeight = min(surfaceFilmWeight, 0.50);
	realUnderwaterColor = mix(
		realUnderwaterColor, tropicalSurfaceFilm, surfaceFilmWeight);

	// Generated coast beds reach a deliberately deep final ring. Dissolve that
	// last ring into the stable water body before its geometry ends. This terminal
	// fade must never apply to native scene geometry: doing so erased the resolved
	// rocks and removed their deep-water transparency.
	float generatedTerminalFade = 1.0
		- smoothstep(144.0, 224.0, realThickness);
	float bedBoundaryFade = mix(
		1.0, generatedTerminalFade, generatedBedInfluence);
	float resolvedBedVisibility = realBedBlend * bedBoundaryFade;
	vec3 underwaterColor = mix(
		fallbackWaterColor, realUnderwaterColor, resolvedBedVisibility);

	// Fine, low-energy interference adds movement without broad frosted bands.
	float ripplePhase = dot(fWorldPos.xz, vec2(0.071, -0.053))
		+ sin(dot(fWorldPos.xz, vec2(0.043, 0.067)) - time * 0.42)
		+ time * 0.58;
	float fineRipple = sin(ripplePhase) * 0.5
		+ sin(ripplePhase * 1.71 - time * 0.31) * 0.5;
	underwaterColor += vec3(0.045, 0.085, 0.095)
		* fineRipple
		* mix(0.014, 0.026, generatedShallowInfluence)
		* strength;

	vec3 reflectionDirection = normalize(reflect(-viewDirection, normal));
	float surfaceRoughness = 0.060 + weatherRoughness * 0.42;
	vec3 reflectedSky = roughSkyReflection(reflectionDirection, surfaceRoughness);
	// A tiny water tint keeps extremely bright cubemap faces from reading as a
	// chrome mirror, while retaining the environment's real day/night colors.
	vec3 reflectionTint = swampWater
		? vec3(0.72, 0.80, 0.63)
		: vec3(0.94, 1.00, 1.035);
	reflectedSky *= reflectionTint;
	float reflectionWeight = clamp(
		fresnel * mix(0.82, 1.08, strength * 0.5), 0.0, 0.62);
	// Keep distant/deep reflection intact while resolved shallow geometry receives
	// a clearer, lighter transmission-dominant surface.
	reflectionWeight *= 1.0 - nearSurface * 0.14;
	reflectionWeight *= mix(1.0, 0.78, shallowBedInfluence);
	reflectionWeight += generatedShallowInfluence
		* mix(0.038, 0.095, 1.0 - viewFacing)
		* (0.70 + 0.30 * strength);
	reflectionWeight *= 1.0
		+ fineRipple * 0.055 * 0.35
		+ fineRipple * 0.12 * generatedShallowInfluence;
	reflectionWeight = clamp(reflectionWeight, 0.0, 0.62);
	vec3 color = mix(underwaterColor, reflectedSky, reflectionWeight);

	// Java uploads the logical celestial direction here (positive Y means above).
	// RuneLite scene elevation grows upward along negative Y, so convert once at
	// this shader boundary before evaluating the water normal and shadow bias.
	vec3 sourceDirection = normalize(vec3(
		lightDirection.x, -lightDirection.y, lightDirection.z));
	float directFacing = max(dot(normal, sourceDirection), 0.0);
	vec3 halfDirection = normalize(sourceDirection + viewDirection);
	float normalHalf = max(dot(normal, halfDirection), 0.0);
	float specularPower = mix(165.0, 52.0, surfaceRoughness);
	float sharpSpecular = pow(normalHalf, specularPower);
	float broadSpecular = pow(normalHalf, mix(38.0, 18.0, surfaceRoughness));
	float directShadow = shadowVisibility(
		fWorldPos, normal, sourceDirection);
	vec3 sunColor = vec3(1.0, 0.84, 0.58);
	float sunPresence = 1.0 - smoothstep(0.30, 0.78, night);
	float broadSpecularWeight = mix(0.052, 0.024, nearSurface);
	float specular = (sharpSpecular * 0.52
		+ broadSpecular * broadSpecularWeight)
		* directFacing * directShadow * sunPresence
		* (1.0 - weatherRoughness * 0.38) * strength;
	color += sunColor * specular;

	// Lightning illuminates the water volume and its rough reflective surface,
	// but does not turn the river into a solid white overlay.
	color += vec3(0.64, 0.74, 0.94) * lightningFlash
		* (0.08 + fresnel * 0.18 + weatherRoughness * 0.05);

	// The opaque scene already contains normal world fog. Apply only the missing
	// distance response of the newly composited reflection/surface so water joins
	// the horizon instead of cutting a bright strip through it.
	float safeDrawDistance = max(drawDistance, 512.0);
	float distanceFog = smoothstep(
		safeDrawDistance * 0.72,
		safeDrawDistance,
		waterDistance);
	float weatherFog = weatherMode == 2 ? 0.18
		: weatherMode == 4 ? 0.12 : 0.0;
	color = mix(color, fogColor,
		clamp(distanceFog * 0.42 + distanceFog * weatherFog, 0.0, 0.68));

	// Water's scene contribution was omitted from the opaque resolve. The result
	// here is therefore a complete manually composited pixel, not an alpha source.
	FragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
