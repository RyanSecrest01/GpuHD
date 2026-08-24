#version 330

uniform int snow;
uniform int storm;
uniform float intensity;
in float fFade;
out vec4 FragColor;

void main()
{
	vec3 color = snow != 0
		? vec3(0.92, 0.96, 1.0)
		: (storm != 0 ? vec3(0.70, 0.71, 0.72) : vec3(0.64, 0.76, 0.84));
	FragColor = vec4(color, fFade * intensity);
}
