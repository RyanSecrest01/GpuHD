#version 330

uniform int snow;
uniform int storm;
uniform int mist;
uniform float intensity;
uniform float lightningFlash;
uniform sampler2D shadowMap;
uniform mat4 shadowLightProj;
in float fFade;
in float fSeed;
in vec3 fWorldPos;
in vec2 fMistUv;
out vec4 FragColor;

void main()
{
	if (mist != 0)
	{
		vec2 point = fMistUv * 2.0 - vec2(1.0);
		float radiusSquared = dot(point, point);
		if (radiusSquared >= 1.0)
		{
			discard;
		}

		float softEdge = 1.0 - smoothstep(0.08, 1.0, radiusSquared);
		float radialDistance = sqrt(radiusSquared);
		// Radial, world-seeded variation keeps each puff visually spherical. The
		// billboard can face the camera without its texture appearing to rotate.
		float cloudNoise = 0.78
			+ sin(radialDistance * 11.0 + fSeed * 31.0) * 0.08
			+ sin(radialDistance * 19.0 - fSeed * 17.0) * 0.05;
		float lightVisibility = 1.0;
		vec4 lightSpacePosition = shadowLightProj * vec4(fWorldPos, 1.0);
		vec3 shadowCoord = lightSpacePosition.xyz / lightSpacePosition.w;
		shadowCoord = shadowCoord * 0.5 + 0.5;
		if (shadowCoord.x >= 0.0 && shadowCoord.x <= 1.0
			&& shadowCoord.y >= 0.0 && shadowCoord.y <= 1.0
			&& shadowCoord.z >= 0.0 && shadowCoord.z <= 1.0)
		{
			float closestDepth = texture(shadowMap, shadowCoord.xy).r;
			lightVisibility = shadowCoord.z - 0.002 > closestDepth ? 0.08 : 1.0;
		}

		float shaftPattern = pow(0.5 + 0.5 * sin(
			fWorldPos.x * 0.0062 + fWorldPos.z * 0.0037 + fSeed * 8.0), 7.0);
		float lightningShaft = lightningFlash * lightVisibility
			* (0.22 + shaftPattern * 0.78);
		vec3 mistColor = mix(
			vec3(0.075, 0.105, 0.145),
			vec3(0.52, 0.68, 0.96),
			lightningShaft);
		float mistAlpha = softEdge * cloudNoise * fFade
			* (0.075 + intensity * 0.070 + lightningShaft * 0.095);
		FragColor = vec4(mistColor, mistAlpha);
		return;
	}

	vec3 color = snow != 0
		? vec3(0.92, 0.96, 1.0)
		: (storm != 0 ? vec3(0.70, 0.71, 0.72) : vec3(0.64, 0.76, 0.84));
	FragColor = vec4(color, fFade * intensity);
}
