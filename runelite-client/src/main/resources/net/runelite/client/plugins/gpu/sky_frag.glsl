#version 330

in vec3 skyDirection;

out vec4 fragColor;

uniform samplerCube skyTexture;
uniform vec3 sunDirection;
uniform vec3 rayColor;
uniform float rayStrength;
uniform float nightFactor;
uniform vec3 moonDirection;

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
	float moonShade = smoothstep(-0.35, 0.65,
		dot(normalize(direction - moonDir * 0.992), normalize(vec3(-0.5, 0.7, 0.3))));
	float moonGlow = pow(max(moonAlignment, 0.0), 95.0);
	vec3 sunTangent = normalize(cross(sunDir, vec3(0.0, 1.0, 0.001)));
	vec3 sunBitangent = normalize(cross(sunDir, sunTangent));
	vec2 sunPlane = vec2(dot(direction, sunTangent), dot(direction, sunBitangent));
	float sunRadius = length(sunPlane);
	float sunAngle = atan(sunPlane.y, sunPlane.x);
	float sunSpokes = pow(0.5 + 0.5 * cos(sunAngle * 8.0), 12.0)
		* exp(-sunRadius * 13.0);
	float horizontalFlare = exp(-abs(sunPlane.y) * 75.0)
		* exp(-abs(sunPlane.x) * 5.0);
	float sunDown = max(-sunPlane.y, 0.0);
	float sunTail = exp(-abs(sunPlane.x) * 18.0 / (0.08 + sunDown * 0.42))
		* smoothstep(0.015, 0.12, sunDown)
		* (1.0 - smoothstep(0.22, 0.92, sunDown));
	float sunGlare = exp(-sunRadius * 11.0) * 0.22
		+ sunSpokes * 0.42 + horizontalFlare * 0.10 + sunTail * 0.48;

	vec3 moonTangent = normalize(cross(moonDir, vec3(0.0, 1.0, 0.001)));
	vec3 moonBitangent = normalize(cross(moonDir, moonTangent));
	vec2 moonPlane = vec2(dot(direction, moonTangent), dot(direction, moonBitangent));
	float moonRadius = length(moonPlane);
	float dustNoise = 0.82 + 0.18 * sin(
		direction.x * 91.0 + direction.y * 57.0 + direction.z * 73.0);
	float moonDown = max(-moonPlane.y, 0.0);
	float moonSpotlight = exp(-abs(moonPlane.x) * 14.0 / (0.10 + moonDown * 0.55))
		* smoothstep(0.018, 0.10, moonDown)
		* (1.0 - smoothstep(0.28, 0.88, moonDown)) * dustNoise;
	vec3 sunBody = vec3(1.0, 0.76, 0.30) * sunDisc
		+ vec3(1.0, 0.96, 0.78) * sunCore + rayColor * sunGlow * 0.42;
	vec3 moonBody = vec3(0.64, 0.73, 0.90) * moonDisc * (0.48 + moonShade * 0.52)
		+ vec3(0.42, 0.55, 0.82) * moonGlow * 0.24;
	float strength = clamp(rayStrength, 0.0, 2.0);
	skyColor += mix(sunBody, moonBody, nightFactor);
	skyColor += mix(
		vec3(1.0, 0.72, 0.34) * sunGlare,
		vec3(0.38, 0.48, 0.72) * moonSpotlight * 0.46,
		nightFactor) * strength;
	fragColor = vec4(skyColor, 1.0);
}
