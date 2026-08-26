#version 330

uniform sampler2D sceneColor;
uniform sampler2D sceneDepth;
uniform sampler2D rayTexture;
uniform vec4 sceneUvTransform;
uniform vec4 rayUvTransform;
uniform vec2 rayTexelSize;

in vec2 fUv;
out vec4 FragColor;

vec2 sceneUv(vec2 viewportUv)
{
	return sceneUvTransform.xy + viewportUv * sceneUvTransform.zw;
}

float depthWeight(float centerDepth, float sampleDepth)
{
	bool centerSky = centerDepth <= 0.000015;
	bool sampleSky = sampleDepth <= 0.000015;
	if (centerSky != sampleSky)
	{
		return 0.002;
	}
	if (centerSky)
	{
		return 1.0;
	}

	// Reversed depth is reciprocal-distance-like. A relative comparison remains
	// useful at both near walls and distant terrain, unlike one absolute epsilon.
	float relativeDifference = abs(centerDepth - sampleDepth)
		/ max(max(centerDepth, sampleDepth), 0.000015);
	return exp(-relativeDifference * 18.0);
}

void main()
{
	vec2 resolvedUv = sceneUv(fUv);
	vec3 scene = texture(sceneColor, resolvedUv).rgb;
	float centerDepth = texture(sceneDepth, resolvedUv).r;
	vec2 rayUv = rayUvTransform.xy + fUv * rayUvTransform.zw;
	vec2 rayMin = rayUvTransform.xy + rayTexelSize * 0.5;
	vec2 rayMax = rayUvTransform.xy + rayUvTransform.zw
		- rayTexelSize * 0.5;

	vec3 deltaSum = vec3(0.0);
	float weightSum = 0.0;
	for (int y = -1; y <= 1; ++y)
	{
		for (int x = -1; x <= 1; ++x)
		{
			vec2 offset = vec2(float(x), float(y));
			vec2 sampleUv = clamp(
				rayUv + offset * rayTexelSize, rayMin, rayMax);
			vec4 raySample = texture(rayTexture, sampleUv);
			float spatialWeight = 1.0 / (1.0 + dot(offset, offset) * 0.72);
			float bilateralWeight = spatialWeight
				* depthWeight(centerDepth, raySample.a);
			deltaSum += raySample.rgb * bilateralWeight;
			weightSum += bilateralWeight;
		}
	}

	vec3 delta = deltaSum / max(weightSum, 0.0001);
	vec3 positiveLight = max(delta, vec3(0.0));
	vec3 attenuation = min(delta, vec3(0.0));
	// Screen-like addition keeps stone, timber and foliage texture visible beneath
	// a strong shaft instead of replacing those surfaces with a flat yellow wash.
	positiveLight *= 1.0 - scene * 0.34;
	vec3 composed = scene + attenuation + positiveLight;
	FragColor = vec4(clamp(composed, 0.0, 1.0), 1.0);
}
