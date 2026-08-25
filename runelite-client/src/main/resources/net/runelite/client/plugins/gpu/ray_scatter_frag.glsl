#version 330

uniform sampler2D visibilityMask;
uniform vec2 lightUv;

in vec2 fUv;

layout(location = 0) out float rayIntensity;

void main()
{
	const int SAMPLE_COUNT = 12;

	vec2 maskSize = vec2(textureSize(visibilityMask, 0));
	float aspect = maskSize.x / max(maskSize.y, 1.0);
	vec2 metric = vec2(aspect, 1.0);
	vec2 toLight = lightUv - fUv;

	float accumulated = 0.0;
	float normalization = 0.0;

	for (int i = 0; i < SAMPLE_COUNT; ++i)
	{
		float t = (float(i) + 0.5) / float(SAMPLE_COUNT);

		// Concentrate samples near the celestial source, where adjacent blocker
		// outlines diverge into visible shafts.
		t = 1.0 - (1.0 - t) * (1.0 - t);

		vec2 sampleUv = mix(fUv, lightUv, t);
		// The virtual source stays only a few physical pixels beyond the viewport,
		// so extending the filtered edge mask is stable. A dark edge remains a real
		// blocker, while open sky at that edge can feed rays into the frame.
		float openSky = texture(
			visibilityMask, clamp(sampleUv, 0.0, 1.0)).r;

		float sampleDistance = length((sampleUv - lightUv) * metric);
		float emitter = exp2(-sampleDistance * sampleDistance * 3.2);
		float weight = emitter * mix(0.58, 1.0, t);

		accumulated += openSky * weight;
		normalization += weight;
	}

	float scattered = accumulated / max(normalization, 0.0001);
	// Only mixed open/blocked paths form shafts. Fully open air and fully blocked
	// silhouettes emit nothing, preventing a blanket glow over the playfield.
	float structuredLight = clamp(
		4.0 * scattered * (1.0 - scattered), 0.0, 1.0);
	structuredLight = smoothstep(0.06, 0.86, structuredLight);

	float sourceDistance = length(toLight * metric);
	float nearFade = smoothstep(0.025, 0.10, sourceDistance);
	float farFade = 1.0 - smoothstep(0.82, 1.55, sourceDistance);

	float intensity = structuredLight
		* nearFade
		* farFade;

	// Store a normalized field so the R8 target uses its full precision. Exposure
	// and art color are applied only during the full-resolution scene composite.
	rayIntensity = clamp(intensity, 0.0, 1.0);
}
