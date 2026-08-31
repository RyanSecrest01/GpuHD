#version 330

uniform sampler2D baseColorTexture;
uniform vec4 baseColorFactor;
uniform float alphaCutoff;
uniform int hasBaseColorTexture;

in vec2 fUv;

void main()
{
	float alpha = baseColorFactor.a;
	if (hasBaseColorTexture != 0)
	{
		alpha *= texture(baseColorTexture, fUv).a;
	}
	if (alpha < alphaCutoff) discard;
}
