#version 330

uniform sampler2D rayIntensity;
uniform vec3 rayColor;
uniform float applyStrength;

in vec2 fUv;

out vec4 fragColor;

void main()
{
	float amount = clamp(
		texture(rayIntensity, fUv).r * applyStrength * 0.13,
		0.0,
		0.28);

	// The destination pass uses ONE_MINUS_DST_COLOR, ONE for a true screen
	// blend. Alpha is preserved independently by the fixed-function blend state.
	fragColor = vec4(rayColor * amount, 0.0);
}
