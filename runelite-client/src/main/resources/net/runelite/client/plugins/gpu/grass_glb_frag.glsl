#version 330

uniform vec3 lightDirection;
uniform float lightIntensity;
uniform float ambientLight;
uniform vec4 fogColor;
uniform vec3 cameraPosition;
uniform float nightFactor;
uniform sampler2D shadowMap;
uniform mat4 shadowLightProj;
uniform int shadowsEnabled;
uniform float shadowStrength;
uniform int materialLightingEnabled;
uniform float materialLightingStrength;
uniform int debugMode;

in vec2 fDetailUv;
in vec3 fWorldPos;
in vec3 fDetailNormal;
flat in float fSeed;
flat in float fDistanceFade;

out vec4 FragColor;

float receiveShadow()
{
	if (shadowsEnabled == 0) return 0.0;
	vec4 p = shadowLightProj * vec4(fWorldPos, 1.0);
	if (abs(p.w) < 0.00001) return 0.0;
	vec3 c = p.xyz / p.w * 0.5 + 0.5;
	if (any(lessThan(c, vec3(0.0))) || any(greaterThan(c, vec3(1.0)))) return 0.0;
	float blocked = c.z - 0.00055 > texture(shadowMap, c.xy).r ? 1.0 : 0.0;
	return blocked * clamp(shadowStrength, 0.0, 0.8);
}

void main()
{
	if (debugMode != 0)
	{
		FragColor = vec4(1.0, 0.08, 0.85, 1.0);
		return;
	}
	// The imported GLB already contains complete blade silhouettes. Its UVs are
	// not the procedural ribbon coordinates used by the old generated blades.
	if (fDistanceFade < 0.02) discard;
	vec3 n = normalize(fDetailNormal);
	vec3 l = normalize(lightDirection);
	float direct = max(dot(n, l), 0.0);
	float response = materialLightingEnabled != 0
		? clamp(materialLightingStrength, 0.0, 1.0) : 0.0;
	float shadow = receiveShadow();
	vec3 base = mix(vec3(0.16, 0.38, 0.09), vec3(0.24, 0.54, 0.13),
		fract(fSeed * 7.31));
	float energy = mix(0.78, 1.04, clamp(ambientLight, 0.0, 1.0));
	energy *= mix(1.0, 0.82, clamp(nightFactor, 0.0, 1.0));
	energy *= mix(1.0, 0.90 + direct * 0.70 * clamp(lightIntensity, 0.0, 1.0), response);
	energy *= 1.0 - shadow;
	vec3 color = base * energy;
	float fog = 1.0 - smoothstep(0.10, 0.78, fDistanceFade);
	FragColor = vec4(mix(color, fogColor.rgb, fog * 0.80), 1.0);
}
