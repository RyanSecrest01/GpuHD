#version 330

out vec4 FragColor;
uniform vec3 debugColor;

void main()
{
	FragColor = vec4(debugColor, 1.0);
}
