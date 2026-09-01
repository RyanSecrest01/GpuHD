#version 330

out vec2 fUv;

void main()
{
	vec2 position;
	if (gl_VertexID == 0)
	{
		position = vec2(-1.0, -1.0);
	}
	else if (gl_VertexID == 1)
	{
		position = vec2(3.0, -1.0);
	}
	else
	{
		position = vec2(-1.0, 3.0);
	}

	fUv = position * 0.5 + 0.5;
	gl_Position = vec4(position, 0.0, 1.0);
}
