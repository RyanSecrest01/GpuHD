#version 330

in vec3 skyDirection;

out vec4 fragColor;

uniform samplerCube skyTexture;
uniform float celestialVisibility;
uniform vec3 sunDirection;
uniform vec3 rayColor;
uniform float rayStrength;
uniform float nightFactor;
uniform vec3 moonDirection;

float ellipseMask(vec2 point, vec2 center, vec2 radii)
{
	float ellipseDistance = length((point - center) / radii) - 1.0;
	float antialiasWidth = max(fwidth(ellipseDistance) * 1.25, 0.012);
	return 1.0 - smoothstep(
		-antialiasWidth, antialiasWidth, ellipseDistance);
}

void main()
{
	vec3 direction = normalize(skyDirection);
	vec3 skyColor = texture(skyTexture, direction).rgb;

	vec3 sunDir = normalize(sunDirection);
	vec3 moonDir = normalize(moonDirection);
	float sunAlignment = dot(direction, sunDir);
	float moonAlignment = dot(direction, moonDir);
	float sunRadius = sqrt(max(
		2.0 * (1.0 - clamp(sunAlignment, -1.0, 1.0)), 0.0));
	float moonRadius = sqrt(max(
		2.0 * (1.0 - clamp(moonAlignment, -1.0, 1.0)), 0.0));
	float strength = clamp(rayStrength, 0.0, 1.45);
	float dayVisibility = (1.0 - nightFactor) * celestialVisibility;

	// Screen-blended Gaussian glare stays smooth and visible even when celestial
	// rays are disabled. It is radial only: no repeated spokes or angular cookie.
	float sunDisc = 1.0 - smoothstep(0.066, 0.076, sunRadius);
	float sunLimb = smoothstep(0.043, 0.070, sunRadius);
	float tightGlare = exp2(-sunRadius * sunRadius * 180.0);
	float wideGlare = exp2(-sunRadius * sunRadius * 18.0);
	float glareResponse = 0.72 + strength * 0.22;
	float glareEnergy = (tightGlare * 0.26 + wideGlare * 0.075)
		* glareResponse * dayVisibility;
	vec3 sunGlareColor = mix(
		vec3(1.0, 0.72, 0.28), rayColor, 0.25);
	skyColor += clamp(vec3(1.0) - skyColor, 0.0, 1.0)
		* sunGlareColor * glareEnergy;

	// A tiny, world-upright RuneScape sun face gives the body an identity while
	// remaining stable under camera rotation. Use sky-up through the full orbit;
	// only an exact zenith direction needs the alternate reference.
	vec3 skyUp = vec3(0.0, -1.0, 0.0);
	vec3 projectedFaceUp = skyUp - sunDir * dot(skyUp, sunDir);
	if (dot(projectedFaceUp, projectedFaceUp) < 1e-6)
	{
		vec3 zenithReference = vec3(0.0, 0.0, 1.0);
		projectedFaceUp = zenithReference
			- sunDir * dot(zenithReference, sunDir);
	}
	vec3 faceUp = normalize(projectedFaceUp);
	vec3 faceRight = normalize(cross(faceUp, sunDir));
	vec2 faceUv = vec2(
		dot(direction, faceRight), dot(direction, faceUp)) / 0.068;
	float sunEyes = max(
		ellipseMask(faceUv, vec2(-0.27, 0.18), vec2(0.072, 0.105)),
		ellipseMask(faceUv, vec2(0.27, 0.18), vec2(0.072, 0.105)));
	float smileCurve = -0.19 + 0.60 * faceUv.x * faceUv.x;
	float smileStroke = 1.0 - smoothstep(
		0.035, 0.060, abs(faceUv.y - smileCurve));
	float smileWidth = 1.0 - smoothstep(0.34, 0.43, abs(faceUv.x));
	float sunSmile = smileStroke * smileWidth;

	vec3 sunBodyColor = mix(
		vec3(1.0, 0.94, 0.62),
		vec3(1.0, 0.72, 0.23),
		sunLimb * 0.58);
	float sunBodyOpacity = sunDisc * 0.97 * dayVisibility;
	skyColor = mix(skyColor, sunBodyColor, sunBodyOpacity);

	float moonDisc = 1.0 - smoothstep(0.052, 0.082, moonRadius);
	float moonShade = smoothstep(-0.35, 0.65,
		dot(normalize(direction - moonDir * 0.992),
			normalize(vec3(-0.5, 0.7, 0.3))));
	vec3 moonBodyColor = mix(
		vec3(0.48, 0.58, 0.78),
		vec3(0.88, 0.92, 1.0),
		moonShade);
	float moonBodyOpacity = moonDisc * (0.62 + moonShade * 0.28)
		* nightFactor * celestialVisibility;
	skyColor = mix(skyColor, moonBodyColor, moonBodyOpacity);

	float moonInnerAura = exp(-moonRadius * 30.0);
	float moonOuterAura = exp(-moonRadius * 10.0);
	vec3 moonBloom = vec3(0.38, 0.50, 0.78)
		* (moonInnerAura * 0.15 + moonOuterAura * 0.045)
		* strength * nightFactor * celestialVisibility;
	skyColor += moonBloom * clamp(
		vec3(1.0) - skyColor * 0.55, 0.16, 1.0);

	// Composite the expression last so the glare does not wash it out. The ink
	// exists only inside the daytime sun body.
	float sunFace = max(sunEyes, sunSmile) * sunDisc * dayVisibility;
	skyColor = mix(
		skyColor, vec3(0.43, 0.16, 0.025), sunFace * 0.78);

	fragColor = vec4(clamp(skyColor, 0.0, 1.0), 1.0);
}
