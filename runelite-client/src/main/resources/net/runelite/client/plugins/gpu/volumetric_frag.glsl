#version 330

uniform sampler2D sceneColor;
uniform sampler2D sceneDepth;
uniform sampler2DShadow shadowMap;
uniform vec4 sceneUvTransform;
uniform mat4 shadowLightProj;
uniform vec3 cameraPosition;
uniform vec3 lightDirection;
uniform vec3 rayColor;
// x is the effective sun strength; y is the effective moon strength. Keeping
// these independent prevents a useful daytime setting from exaggerating the
// much subtler moon shafts.
uniform vec2 celestialRayStrength;
// The active celestial source, independent of sky darkness. Dawn and sunset
// can be dark while the shadow map is still cast by the sun.
uniform float moonProfile;
uniform int weatherMode;
uniform int shadowMapValid;
uniform int zeroToOneDepth;

in vec2 fUv;
in vec3 fNearRay;
out vec4 FragColor;

const int SAMPLE_COUNT = 40;

vec2 sceneUv(vec2 viewportUv)
{
	return sceneUvTransform.xy + viewportUv * sceneUvTransform.zw;
}

float sceneLuminance()
{
	vec2 centerUv = sceneUv(fUv);
	vec2 texel = 1.0 / vec2(textureSize(sceneColor, 0));
	vec2 uvMin = sceneUvTransform.xy + texel * 0.5;
	vec2 uvMax = sceneUvTransform.xy + sceneUvTransform.zw
		- texel * 0.5;
	vec2 contextOffset = texel * 10.0;
	const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

	float center = dot(texture(sceneColor, centerUv).rgb, LUMA);
	float neighborhood = center;
	neighborhood += dot(texture(sceneColor,
		clamp(centerUv + vec2(contextOffset.x, 0.0), uvMin, uvMax)).rgb, LUMA);
	neighborhood += dot(texture(sceneColor,
		clamp(centerUv - vec2(contextOffset.x, 0.0), uvMin, uvMax)).rgb, LUMA);
	neighborhood += dot(texture(sceneColor,
		clamp(centerUv + vec2(0.0, contextOffset.y), uvMin, uvMax)).rgb, LUMA);
	neighborhood += dot(texture(sceneColor,
		clamp(centerUv - vec2(0.0, contextOffset.y), uvMin, uvMax)).rgb, LUMA);
	neighborhood *= 0.2;

	// A small aperture can itself be bright while its surroundings are dark.
	// Bias toward the neighborhood without allowing one black texture texel to
	// classify an otherwise bright outdoor view as an interior portal.
	return mix(center, neighborhood, 0.68);
}

float sceneGeometry(float reversedDepth)
{
	return smoothstep(0.000015, 0.00045, reversedDepth);
}

float weatherTransmission()
{
	if (weatherMode == 4)
	{
		return 0.10;
	}
	if (weatherMode == 2)
	{
		return 0.20;
	}
	if (weatherMode == 1)
	{
		return 0.65;
	}
	if (weatherMode == 3)
	{
		return 0.85;
	}
	return 1.0;
}

float macroVisibility(vec3 worldPosition, float moonBlend, out float valid)
{
	valid = 0.0;
	vec4 lightClip = shadowLightProj * vec4(worldPosition, 1.0);
	if (abs(lightClip.w) < 0.00001)
	{
		return 0.0;
	}

	vec3 shadowCoord = lightClip.xyz / lightClip.w;
	shadowCoord = shadowCoord * 0.5 + 0.5;
	float border = min(
		min(shadowCoord.x, 1.0 - shadowCoord.x),
		min(shadowCoord.y, 1.0 - shadowCoord.y));
	float depthValid = step(0.0, shadowCoord.z)
		* step(shadowCoord.z, 1.0);
	valid = depthValid * smoothstep(0.0, 0.018, border);
	if (valid <= 0.0001)
	{
		return 0.0;
	}

	float referenceDepth = shadowCoord.z - mix(0.00055, 0.00060, moonBlend);
	float visibility = texture(
		shadowMap,
		vec3(clamp(shadowCoord.xy, 0.0, 1.0), referenceDepth));
	return smoothstep(0.10, 0.90, visibility);
}

vec2 integrateShafts(
	vec3 viewDirection,
	float marchEnd,
	float moonBlend,
	out float coverage,
	out float portalEvidence)
{
	float brightIntegral = 0.0;
	float darkIntegral = 0.0;
	float validWeight = 0.0;
	float attemptedWeight = 0.0;
	float nearShadowSum = 0.0;
	float nearWeightSum = 0.0;
	float farLightSum = 0.0;
	float farWeightSum = 0.0;
	float pathVariation = 0.0;
	float endpointBrightGain = mix(0.12, 0.05, moonBlend);
	float endpointDarkGain = mix(0.07, 0.0, moonBlend);
	float previousDistance = 48.0;
	float previousValid;
	float previousVisibility = macroVisibility(
		cameraPosition + viewDirection * previousDistance,
		moonBlend,
		previousValid);
	float previousTransition = 4.0
		* previousVisibility * (1.0 - previousVisibility);

	for (int sampleIndex = 0; sampleIndex < SAMPLE_COUNT; ++sampleIndex)
	{
		float u = float(sampleIndex + 1) / float(SAMPLE_COUNT);
		float currentDistance = mix(48.0, marchEnd, u * u);
		float segmentLength = max(currentDistance - previousDistance, 0.0);
		float sampleDistance = 0.5 * (previousDistance + currentDistance);
		float distanceAttenuation = smoothstep(48.0, 260.0, sampleDistance)
			* exp(-sampleDistance / 4200.0);
		float distanceWeight = distanceAttenuation
			* segmentLength / 1024.0;
		attemptedWeight += distanceWeight;

		vec3 sampleWorld = cameraPosition
			+ viewDirection * currentDistance;
		float currentValid;
		float currentVisibility = macroVisibility(
			sampleWorld, moonBlend, currentValid);
		float pairValid = min(previousValid, currentValid);
		if (pairValid > 0.0001)
		{
			float currentTransition = 4.0
				* currentVisibility * (1.0 - currentVisibility);
			float localTransition = 0.5
				* (previousTransition + currentTransition);
			float averageVisibility = 0.5
				* (previousVisibility + currentVisibility);
			float litSide = smoothstep(0.20, 0.80, averageVisibility);
			float sampleWeight = distanceWeight * pairValid;
			float nearWeight = (1.0 - smoothstep(0.18, 0.42, u))
				* pairValid;
			float farWeight = smoothstep(0.54, 0.80, u)
				* pairValid;
			nearShadowSum += (1.0 - averageVisibility) * nearWeight;
			nearWeightSum += nearWeight;
			farLightSum += averageVisibility * farWeight;
			farWeightSum += farWeight;
			// Limit the local penumbra contribution per segment. A grazing view may
			// remain beside one boundary for a long distance, but it should not turn
			// that outline into a solid luminous sheet.
			float localWeight = min(sampleWeight, 0.058);
			brightIntegral += localTransition * litSide * localWeight;
			darkIntegral += localTransition * (1.0 - litSide) * localWeight;

			// If a broad silhouette boundary falls between two stations, endpoint
			// visibility still records the crossing. This phase-independent fallback
			// prevents gaps without adding camera noise, spheres, or filled-path haze.
			float visibilityDelta = currentVisibility - previousVisibility;
			pathVariation += abs(visibilityDelta) * pairValid;
			float missedBoundary = abs(visibilityDelta)
				* (1.0 - max(previousTransition, currentTransition));
			brightIntegral += missedBoundary * step(0.0, visibilityDelta)
				* distanceAttenuation * pairValid * endpointBrightGain;
			darkIntegral += missedBoundary * step(visibilityDelta, 0.0)
				* distanceAttenuation * pairValid * endpointDarkGain;
			validWeight += sampleWeight;
			previousTransition = currentTransition;
		}
		else
		{
			previousTransition = 4.0
				* currentVisibility * (1.0 - currentVisibility);
		}
		previousDistance = currentDistance;
		previousVisibility = currentVisibility;
		previousValid = currentValid;
	}

	coverage = validWeight / max(attemptedWeight, 0.0001);
	float nearShadow = nearShadowSum / max(nearWeightSum, 0.0001);
	float farLight = farLightSum / max(farWeightSum, 0.0001);
	float opening = max(farLight - (1.0 - nearShadow), 0.0);
	float dominance = opening / max(pathVariation, 0.0001);

	// A window or doorway is a sustained dark-to-light route with one dominant
	// transition. Repeated foliage transitions accumulate pathVariation, so a
	// top-down forest does not masquerade as dozens of portals.
	portalEvidence = smoothstep(0.72, 0.94, nearShadow)
		* smoothstep(0.68, 0.92, farLight)
		* smoothstep(0.42, 0.76, opening)
		* smoothstep(0.62, 0.90, dominance);
	return vec2(brightIntegral, darkIntegral);
}

void main()
{
	float reversedDepth = texture(sceneDepth, sceneUv(fUv)).r;
	float moonBlend = clamp(moonProfile, 0.0, 1.0);
	float strength = max(mix(
		celestialRayStrength.x,
		celestialRayStrength.y,
		moonBlend), 0.0);
	if (strength <= 0.001 || shadowMapValid == 0)
	{
		FragColor = vec4(0.0, 0.0, 0.0, reversedDepth);
		return;
	}

	vec3 viewDirection = normalize(fNearRay);
	vec3 sourceDirection = normalize(lightDirection);
	float alignment = max(dot(viewDirection, sourceDirection), 0.0);
	// A celestial shaft must lead toward its actual source. Keep the broader
	// sunward cone available for a coherent doorway/window path, but reject
	// sideways, away-facing, and top-down pixels before paying for the march.
	float sunFacing = smoothstep(0.574, 0.906, alignment);
	float moonFacing = smoothstep(0.788, 0.951, alignment);
	float portalFacing = smoothstep(0.08, 0.35, alignment);
	bool shaftEligible = !(viewDirection.y >= 0.60
		|| (moonBlend >= 0.5 && moonFacing <= 0.0001)
		|| (moonBlend < 0.5
			&& max(sunFacing, portalFacing) <= 0.0001));
	if (!shaftEligible)
	{
		FragColor = vec4(0.0, 0.0, 0.0, reversedDepth);
		return;
	}

	float clipDepth = zeroToOneDepth != 0
		? reversedDepth
		: reversedDepth * 2.0 - 1.0;
	bool hasGeometry = sceneGeometry(reversedDepth) > 0.20
		&& clipDepth > 0.0001;
	float surfaceDistance = hasGeometry
		? length(fNearRay / clipDepth)
		: 0.0;
	float opaqueLimit = mix(4600.0, 2200.0, moonBlend);
	float skyLimit = mix(3000.0, 1600.0, moonBlend);
	float marchEnd = hasGeometry
		? min(surfaceDistance, opaqueLimit)
		: skyLimit;
	if (marchEnd <= 64.0)
	{
		FragColor = vec4(0.0, 0.0, 0.0, reversedDepth);
		return;
	}

	float coverage;
	float portalEvidence;
	vec2 integrals = vec2(0.0);
	coverage = 0.0;
	portalEvidence = 0.0;
	if (strength > 0.001 && shadowMapValid != 0 && shaftEligible)
	{
		integrals = integrateShafts(
			viewDirection, marchEnd, moonBlend, coverage, portalEvidence);
	}
	// The directional blocker volume is finite. Fade its edge instead of
	// normalizing one surviving sample into a hard fan at the horizon.
	float coverageFade = smoothstep(0.10, 0.38, coverage);
	strength = clamp(strength, 0.0, 2.0);
	float transmission = weatherTransmission();

	float brightCoefficient = mix(0.32, 0.12, moonBlend);
	float darkCoefficient = mix(0.14, 0.0, moonBlend);
	float brightCap = mix(0.17, 0.05, moonBlend);
	float darkCap = mix(0.055, 0.0, moonBlend);
	float bright = min(
		1.0 - exp(-integrals.x * brightCoefficient),
		brightCap);
	float dark = min(
		1.0 - exp(-integrals.y * darkCoefficient),
		darkCap);

	// Sun shafts are an event near the source, not a permanent outline around
	// every caster. Full response is reached near 25 degrees and smoothly ends
	// around 55 degrees. The moon is intentionally tighter and gentler.
	float portalGate = 0.0;
	if (moonBlend < 0.5 && portalFacing > 0.0 && sunFacing < 0.999)
	{
		float darkContext = 1.0
			- smoothstep(0.14, 0.40, sceneLuminance());
		// RuneLite world +Y points downward. Suppress a camera looking down at
		// ordinary tree shadows while preserving an upward view through a window.
		float lowAngleGate = 1.0
			- smoothstep(0.32, 0.60, viewDirection.y);
		portalGate = portalEvidence
			* darkContext * lowAngleGate * portalFacing;
	}
	float sunGate = max(sunFacing, portalGate * 0.88);
	float moonGate = moonFacing;
	float presentationGate = mix(sunGate, moonGate, moonBlend);
	float profileIntensity = mix(1.0, 0.58, moonBlend);

	vec3 scattering = rayColor
		* bright
		* presentationGate
		* profileIntensity
		* strength
		* transmission
		* coverageFade
		* 1.5;
	// Extinction is deliberately tiny and confined to the adjacent dark rim.
	// The main surface shader remains the sole owner of cast-shadow darkening.
	float extinction = dark
		* strength
		* transmission
		* coverageFade
		* 0.35
		* sunGate
		* (1.0 - moonBlend);
	vec3 delta = scattering - vec3(extinction);
	FragColor = vec4(clamp(delta, -0.08, 0.32), reversedDepth);
}
