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
uniform float brightness;

in vec2 fDetailUv;
in vec3 fWorldPos;
in vec3 fDetailNormal;
flat in float fSeed;
flat in float fDistanceFade;
flat in float fGroundHsl;

out vec4 FragColor;

#include "hsl_to_rgb.glsl"

vec3 rusticGrassColor()
{
	int packedHsl = max(int(floor(fGroundHsl + 0.5)), 0);
	vec3 groundHsl = vec3(
		float(packedHsl >> 10 & 63),
		float(packedHsl >> 7 & 7),
		float(packedHsl & 127));
	vec3 ground = hslToRgb(groundHsl);
	float groundLuma = dot(ground, vec3(0.2126, 0.7152, 0.0722));
	// Preserve the local lawn's identity while replacing the synthetic lime
	// paint with moss, olive, dry tips, and restrained clump variation.
	vec3 olive = mix(vec3(0.095, 0.185, 0.050),
		vec3(0.205, 0.335, 0.095), fSeed);
	vec3 terrainTint = mix(vec3(groundLuma), ground, 0.62);
	vec3 base = mix(olive, terrainTint, 0.38);
	float dry = smoothstep(0.76, 0.98, fract(fSeed * 5.73));
	base = mix(base, vec3(0.255, 0.235, 0.105), dry * 0.24);
	return base * mix(0.86, 1.02, fract(fSeed * 11.17));
}

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
	vec3 base = rusticGrassColor();
	float night = clamp(nightFactor, 0.0, 1.0);
	float energy = mix(0.70, 1.00, clamp(ambientLight, 0.0, 1.0));
	energy *= mix(1.0, 0.38, night);
	energy *= mix(1.0, 0.88 + direct * 0.58
		* clamp(lightIntensity, 0.0, 1.0), response);
	energy *= 1.0 - shadow;
	vec3 color = base * energy;
	color *= mix(vec3(1.0), vec3(0.64, 0.72, 0.82), night * 0.42);
	float fog = 1.0 - smoothstep(0.10, 0.78, fDistanceFade);
	FragColor = vec4(mix(color, fogColor.rgb, fog * 0.80), 1.0);
}
