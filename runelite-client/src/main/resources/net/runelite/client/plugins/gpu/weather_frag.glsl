#version 330

uniform int snow;
uniform int storm;
uniform int severe;
uniform float intensity;
in float fFade;
in float fSeed;
in float fGust;
out vec4 FragColor;

void main()
{
	float particleAlpha = fFade * intensity;
	if (snow != 0 && severe != 0)
	{
		particleAlpha *= mix(0.86, 1.12, fGust);
	}
	float flakeProfile = 1.0;
	if (snow != 0)
	{
		vec2 point = gl_PointCoord * 2.0 - 1.0;
		float angle = fSeed * 6.2831853;
		float sine = sin(angle);
		float cosine = cos(angle);
		point = mat2(cosine, -sine, sine, cosine) * point;
		point.x *= mix(0.78, 1.18, fSeed);
		float radius = length(point);
		float softFlake = 1.0 - smoothstep(0.54, 1.0, radius);
		float crystalline = 0.84 + 0.16 * cos(atan(point.y, point.x) * 6.0);
		flakeProfile = softFlake * crystalline;
		particleAlpha *= flakeProfile;
		if (particleAlpha < 0.015)
		{
			discard;
		}
	}

	vec3 color = snow != 0
		? mix(vec3(0.72, 0.77, 0.82), vec3(0.96, 0.98, 1.0),
			clamp(flakeProfile * 1.30, 0.0, 1.0))
		: (storm != 0 ? vec3(0.70, 0.71, 0.72) : vec3(0.64, 0.76, 0.84));
	FragColor = vec4(color, particleAlpha);
}
