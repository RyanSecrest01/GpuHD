#version 330

layout(location = 0) out float visibility;

void main()
{
	// The mask is cleared to one, so every opaque silhouette removes light.
	visibility = 0.0;
}
