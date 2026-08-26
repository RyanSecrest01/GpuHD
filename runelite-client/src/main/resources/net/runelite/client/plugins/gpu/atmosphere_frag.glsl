#version 330

uniform float time;
uniform int blizzard;
uniform float lightningFlash;

in vec2 fUv;
in float fOpacity;
in float fSeed;
in float fScattering;

out vec4 FragColor;

float hash(vec2 point)
{
	vec3 p = fract(vec3(point.xyx) * 0.1031);
	p += dot(p, p.yzx + 33.33);
	return fract((p.x + p.y) * p.z);
}

float valueNoise(vec2 point)
{
	vec2 cell = floor(point);
	vec2 local = fract(point);
	local = local * local * (3.0 - 2.0 * local);
	float a = hash(cell);
	float b = hash(cell + vec2(1.0, 0.0));
	float c = hash(cell + vec2(0.0, 1.0));
	float d = hash(cell + vec2(1.0));
	return mix(mix(a, b, local.x), mix(c, d, local.x), local.y);
}

float cloudNoise(vec2 point)
{
	float value = 0.0;
	float weight = 0.56;
	for (int octave = 0; octave < 4; ++octave)
	{
		value += valueNoise(point) * weight;
		point = point * 2.03 + vec2(7.1, 3.7);
		weight *= 0.48;
	}
	return value;
}

void main()
{
	vec2 local = fUv * 2.0 - 1.0;
	vec2 atmosphereFlow = blizzard != 0
		? vec2(time * 0.050, -time * 0.019)
		: vec2(time * 0.018, -time * 0.011);
	vec2 flowingUv = local * vec2(blizzard != 0 ? 3.20 : 2.35, 3.15)
		+ atmosphereFlow
		+ vec2(fSeed * 31.0, fSeed * 17.0);
	float detail = cloudNoise(flowingUv);
	float broadShape = 1.0 - length(local * vec2(0.92, 1.16));
	float brokenEdge = broadShape + (detail - 0.50) * 0.72;
	float body = smoothstep(-0.18, 0.22, brokenEdge);
	float core = smoothstep(0.02, 0.68, broadShape);
	float wisps = 0.72 + 0.28 * sin(
		local.x * 8.0 + detail * 5.0 + fSeed * 11.0 + time * 0.07);
	// Treat each soft card as a view through a shallow ellipsoid instead of a
	// flat alpha stamp.  The approximate chord length gives the center more
	// optical depth while keeping wispy edges transparent.  Beer-Lambert
	// conversion also prevents a handful of overlapping cards from turning
	// into the old uniform grey blanket.
	float ellipsoid = dot(local * vec2(0.92, 1.16), local * vec2(0.92, 1.16));
	float chordLength = 2.0 * sqrt(max(1.0 - ellipsoid, 0.0));
	float densityField = body * mix(0.48, 1.0, core)
		* mix(0.72, 1.18, detail) * wisps;
	float opticalDepth = densityField * chordLength * fOpacity * 1.9;
	float alpha = 1.0 - exp(-opticalDepth);

	if (alpha < 0.002)
	{
		discard;
	}

	vec3 stormColor = blizzard != 0
		? vec3(0.66, 0.71, 0.76)
		: vec3(0.31, 0.33, 0.34);
	stormColor *= mix(0.76, 1.08, core + detail * 0.24);
	vec3 scatteredLight = blizzard != 0
		? vec3(0.72, 0.82, 0.96)
		: vec3(0.48, 0.59, 0.82);
	stormColor += scatteredLight * fScattering * (0.28 + detail * 0.72);
	stormColor = mix(
		stormColor,
		blizzard != 0 ? vec3(0.84, 0.90, 0.98) : vec3(0.66, 0.73, 0.86),
		clamp(lightningFlash * (0.38 + core * 0.62), 0.0, 1.0));

	// Premultiplied alpha keeps overlapping low-opacity puffs from developing
	// dark sprite borders while remaining compatible with dense layering.
	FragColor = vec4(stormColor * alpha, alpha);
}
