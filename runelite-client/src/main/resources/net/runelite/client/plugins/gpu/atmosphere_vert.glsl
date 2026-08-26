#version 330

uniform mat4 projection;
uniform vec3 cameraPosition;
uniform vec3 anchorPosition;
uniform float time;
uniform float radius;
uniform float wind;
uniform float density;
uniform int blizzard;
uniform vec3 lightDirection;
uniform float nightFactor;

out vec2 fUv;
out float fOpacity;
out float fSeed;
out float fScattering;

float hash(float value)
{
	return fract(sin(value * 127.17 + 19.73) * 43758.31);
}

vec2 cornerForVertex(int vertex)
{
	if (vertex == 0) return vec2(-1.0, -1.0);
	if (vertex == 1) return vec2( 1.0, -1.0);
	if (vertex == 2) return vec2( 1.0,  1.0);
	if (vertex == 3) return vec2(-1.0, -1.0);
	if (vertex == 4) return vec2( 1.0,  1.0);
	return vec2(-1.0, 1.0);
}

void main()
{
	int puff = gl_VertexID / 6;
	int vertex = gl_VertexID - puff * 6;
	float id = float(puff);
	float cluster = floor(id / 4.0);
	float lobe = mod(id, 4.0);
	float seed = hash(id + 3.0);
	float domain = radius * 2.0;

	// Centers live in a repeating world-space domain around the camera focal
	// point. Orbiting the camera changes only the view, never the fog layout.
	vec2 baseCenter = vec2(hash(cluster + 17.0), hash(cluster + 61.0)) * domain;
	float lobeAngle = hash(id + 79.0) * 6.2831853;
	float lobeRadius = lobe < 0.5 ? 0.0 : mix(55.0, 190.0, hash(id + 83.0));
	baseCenter += vec2(cos(lobeAngle), sin(lobeAngle)) * lobeRadius;
	vec2 flowDirection = normalize(vec2(0.88, 0.34 + hash(cluster + 89.0) * 0.24));
	float signedWind = wind * (blizzard != 0 ? 0.58 : 0.42);
	vec2 drift = flowDirection
		* (signedWind + (blizzard != 0 ? 12.0 : 5.5)) * time;
	vec2 offset = mod(
		baseCenter + drift - anchorPosition.xz + vec2(radius),
		vec2(domain)) - vec2(radius);

	vec3 center = vec3(
		anchorPosition.x + offset.x,
		anchorPosition.y + mix(-210.0, 150.0, hash(id + 107.0)),
		anchorPosition.z + offset.y);
	center.y += sin(
		center.x * 0.0021 + center.z * 0.0014 + time * 0.22 + seed * 6.2831)
		* (blizzard != 0 ? 42.0 : 58.0);

	vec3 toCamera = cameraPosition - center;
	float cameraDistance = max(length(toCamera), 1.0);
	vec3 forward = toCamera / cameraDistance;
	vec3 right = cross(vec3(0.0, 1.0, 0.0), forward);
	if (dot(right, right) < 0.0001)
	{
		right = vec3(1.0, 0.0, 0.0);
	}
	else
	{
		right = normalize(right);
	}
	vec3 up = normalize(cross(forward, right));

	float halfWidth = mix(105.0, 235.0, hash(id + 131.0))
		* (blizzard != 0 ? 1.34 : 1.0);
	float halfHeight = halfWidth * mix(0.30, 0.56, hash(id + 149.0))
		* (blizzard != 0 ? 0.68 : 1.0);
	vec2 corner = cornerForVertex(vertex);
	vec3 position = center
		+ right * corner.x * halfWidth
		+ up * corner.y * halfHeight;

	gl_Position = projection * vec4(position, 1.0);
	fUv = corner * 0.5 + 0.5;
	fSeed = seed;

	float nearFade = smoothstep(190.0, 520.0, cameraDistance);
	float farFade = 1.0 - smoothstep(radius * 0.62, radius, length(offset));
	float layerVariation = mix(0.48, 1.0, hash(id + 173.0));
	float baseOpacity = blizzard != 0 ? 0.034 : 0.030;
	fOpacity = baseOpacity * nearFade * farFade * layerVariation
		* sqrt(clamp(density, 0.0, 2.0));
	float lightAlignment = max(dot(normalize(-lightDirection), forward), 0.0);
	fScattering = mix(
		pow(lightAlignment, 5.0) * 0.44,
		pow(lightAlignment, 9.0) * 0.82,
		clamp(nightFactor, 0.0, 1.0));
}
