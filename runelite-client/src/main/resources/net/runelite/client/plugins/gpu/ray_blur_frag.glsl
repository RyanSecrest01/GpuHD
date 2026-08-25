#version 330

uniform sampler2D sourceMask;
uniform vec2 blurAxis;
uniform int finalPass;

in vec2 fUv;

layout(location = 0) out float filteredMask;

void main()
{
	vec2 texel = blurAxis / vec2(textureSize(sourceMask, 0));

	// Five bilinear taps approximate a nine-tap Gaussian. This work stays at
	// mask resolution, so broad blockers survive without a full-resolution blur.
	float value = texture(sourceMask, fUv).r * 0.2270270270;
	value += texture(sourceMask, fUv + texel * 1.3846153846).r
		* 0.3162162162;
	value += texture(sourceMask, fUv - texel * 1.3846153846).r
		* 0.3162162162;
	value += texture(sourceMask, fUv + texel * 3.2307692308).r
		* 0.0702702703;
	value += texture(sourceMask, fUv - texel * 3.2307692308).r
		* 0.0702702703;

	// Apply the silhouette bias only after both axes have been filtered. Small,
	// diluted details disappear while roofs, buildings, and trees stay opaque.
	filteredMask = finalPass != 0
		? smoothstep(0.56, 0.90, value)
		: value;
}
