#version 330

// The dedicated water pass reuses the static scene VAO. Keep these attribute
// declarations byte-for-byte compatible with vert.glsl/Zone.
layout(location = 0) in vec3 vertf;
layout(location = 1) in int abhsl;
layout(location = 2) in ivec4 tex;

uniform mat4 projection;
uniform ivec3 base;

out vec3 fWorldPos;
out vec2 fTileUv;
out vec3 fBarycentric;
flat out int fWaterTextureId;
flat out int fShoreEdges;

void main()
{
	vec4 worldPosition = vec4(vertf + base, 1.0);
	vec4 screenPosition = projection * worldPosition;

	// Match the bias used by the normal scene vertex shader. This preserves the
	// same reversed-depth ordering at bridges and other layered terrain.
	int bias = (abhsl >> 16) & 0xff;
	screenPosition.z += float(bias) / 128.0;
	gl_Position = screenPosition;

	fWorldPos = worldPosition.xyz;
	fTileUv = vec2(float(tex.y), float(tex.z)) / 256.0;
	int triangleVertex = gl_VertexID % 3;
	fBarycentric = triangleVertex == 0 ? vec3(1.0, 0.0, 0.0)
		: triangleVertex == 1 ? vec3(0.0, 1.0, 0.0)
			: vec3(0.0, 0.0, 1.0);
	fWaterTextureId = (tex.x & 0x1ff) - 1;
	fShoreEdges = tex.w;
}
