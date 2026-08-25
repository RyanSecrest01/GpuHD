#version 330

layout(location = 0) in vec3 position;

out vec2 fUv;

void main()
{
	// Derive bottom-left texture coordinates from clip position. RuneLite's UI
	// texture coordinate attribute is top-left oriented and would flip FBO data.
	fUv = position.xy * 0.5 + 0.5;
	gl_Position = vec4(position.xy, 0.0, 1.0);
}
