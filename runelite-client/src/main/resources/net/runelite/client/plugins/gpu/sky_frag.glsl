#version 330

in vec3 skyDirection;

out vec4 fragColor;

uniform samplerCube skyTexture;
uniform vec3 sunDirection;
uniform vec3 rayColor;
uniform float rayStrength;
uniform float nightFactor;
uniform vec3 moonDirection;
uniform float celestialVisibility;

void main()
{
    vec3 direction =
        normalize(skyDirection);

    vec3 skyColor =
        texture(skyTexture, direction).rgb;

	vec3 sunDir = normalize(sunDirection);
	vec3 moonDir = normalize(moonDirection);
	float sunAlignment = dot(direction, sunDir);
	float moonAlignment = dot(direction, moonDir);
	float sunDisc = smoothstep(0.9960, 0.9982, sunAlignment);
	float sunCore = smoothstep(0.9985, 0.9996, sunAlignment);
	float sunGlow = pow(max(sunAlignment, 0.0), 68.0);
	float moonDisc = smoothstep(0.9968, 0.9987, moonAlignment);
	float moonCore = smoothstep(0.9989, 0.99965, moonAlignment);
	float moonGlow = pow(max(moonAlignment, 0.0), 95.0);
	// Keep the sky contribution compact. Long shafts are generated later from
	// resolved scene depth, where world geometry can actually occlude them.
	float sunHalo = pow(max(sunAlignment, 0.0), 42.0) * 0.21
		+ pow(max(sunAlignment, 0.0), 9.0) * 0.032;
	float moonHalo = pow(max(moonAlignment, 0.0), 58.0) * 0.11
		+ pow(max(moonAlignment, 0.0), 20.0) * 0.018;
	vec3 sunBody = vec3(1.0, 0.76, 0.30) * sunDisc
		+ vec3(1.0, 0.96, 0.78) * sunCore + rayColor * sunGlow * 0.42;
	// A radial core avoids the unstable, faceted wedge produced by normalizing
	// an almost-zero tangent vector at the center of the moon disc.
	vec3 moonBody = vec3(0.58, 0.67, 0.84) * moonDisc * (0.66 + moonCore * 0.20)
		+ vec3(0.38, 0.50, 0.76) * moonGlow * 0.18;
	float strength = clamp(rayStrength, 0.0, 2.0);
	skyColor += mix(sunBody, moonBody, nightFactor) * celestialVisibility;
	skyColor += mix(
		vec3(1.0, 0.72, 0.34) * sunHalo,
		vec3(0.38, 0.50, 0.78) * moonHalo,
		nightFactor) * strength * celestialVisibility;
	fragColor = vec4(skyColor, 1.0);
}
