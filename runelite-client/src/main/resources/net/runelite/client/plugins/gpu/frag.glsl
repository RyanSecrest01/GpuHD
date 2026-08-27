/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 */

#version 330

//#define FRAG_UVS
//#define ZBUF_DEBUG

#include colorblind_mode

// ========================================================
// RuneLite uniforms
// ========================================================

uniform sampler2DArray textures;
// Authored material texture arrays.
// For the first milestone we only consume the normal atlas.
uniform sampler2DArray authoredMaterialNormals;
uniform sampler2DArray authoredMaterialAlbedos;
uniform float brightness;
uniform float smoothBanding;
uniform vec4 fogColor;
uniform float textureLightMode;
uniform int terrainTextureBlending;
uniform float terrainBlendStrength;
uniform int materialDebugMode;
uniform int materialLightingEnabled;
uniform float materialLightingStrength;
uniform int wetSurfacesEnabled;
uniform float wetSurfaceStrength;

// ========================================================
// Enhanced colors
// ========================================================

uniform int enhancedColors;
uniform float saturation;
uniform float contrast;

// ========================================================
// Selective celestial shadows
// ========================================================

uniform vec3 lightDirection;
uniform vec3 cameraPosition;
uniform int enhancedWater;
uniform float waterStrength;
uniform float waterOpacity;
uniform float lightningFlash;
uniform int weatherMode;
uniform float weatherTime;
uniform float weatherDensity;
uniform float celestialNightFactor;
uniform int tick;

uniform sampler2D shadowMap;
uniform mat4 shadowLightProj;
uniform int shadowsEnabled;
uniform float shadowStrength;

// ========================================================
// Inputs
// ========================================================

in vec4 fColor;
noperspective centroid in float fHsl;
flat in int fTextureId;
flat in int fMaterialId;
flat in int fMaterialVariant;
in vec2 fUv;
in vec2 fTileUv;
flat in int fShoreEdges;
in float fFogAmount;
in vec3 fWorldPos;

#ifdef ZBUF_DEBUG
in float fDepth;
#endif

out vec4 FragColor;

#include "hsl_to_rgb.glsl"

#if COLORBLIND_MODE > 0
#include "colorblind.glsl"
#endif

#ifdef ZBUF_DEBUG
float linear_depth(float depth)
{
    float z = 100 / depth;
    return 1 - z / 10000;
}
#endif

bool isWaterTexture(int textureIdx)
{
    return textureIdx == 1
        || textureIdx == 24
        || textureIdx == 25
        || (textureIdx >= 130 && textureIdx <= 189)
        || textureIdx == 208;
}

vec3 materialDebugColor(int materialId)
{
	if (materialId == 1) return vec3(0.16, 0.92, 0.24); // grass
	if (materialId == 2) return vec3(0.48, 0.58, 0.72); // stone
	if (materialId == 3) return vec3(0.95, 0.75, 0.30); // sand
	if (materialId == 4) return vec3(0.55, 0.25, 0.10); // dirt
	if (materialId == 5) return vec3(0.86, 0.43, 0.12); // wood
	if (materialId == 6) return vec3(0.24, 0.86, 0.92); // metal
	if (materialId == 7) return vec3(0.08, 0.48, 0.12); // foliage
	if (materialId == 8) return vec3(0.12, 0.45, 1.00); // water
	return vec3(0.88, 0.08, 0.72); // unknown
}

float weatherHash(vec2 point)
{
    vec3 p = fract(vec3(point.xyx) * 0.1031);
    p += dot(p, p.yzx + 33.33);
    return fract((p.x + p.y) * p.z);
}

vec2 weatherHash2(vec2 point)
{
    float first = weatherHash(point);
    return vec2(first, weatherHash(point + vec2(first + 17.17, first + 43.31)));
}

float rainImpactLayer(
    vec2 worldPosition,
    float cellSize,
    float cycleRate,
    float seed,
    float coverage)
{
    vec2 baseCell = floor(worldPosition / cellSize);
    float response = 0.0;

    // Neighbor evaluation keeps expanding rings continuous across cell edges.
    for (int offsetX = -1; offsetX <= 1; ++offsetX)
    {
        for (int offsetY = -1; offsetY <= 1; ++offsetY)
        {
            vec2 cell = baseCell + vec2(float(offsetX), float(offsetY));
            vec2 randomPair = weatherHash2(cell + vec2(seed, seed * 1.73));
            vec2 center = (cell + vec2(0.14) + randomPair * 0.72) * cellSize;
            vec2 delta = worldPosition - center;

            float angle = weatherHash(cell + vec2(seed * 2.31, 9.17)) * 6.2831853;
            float sine = sin(angle);
            float cosine = cos(angle);
            vec2 elliptical = mat2(cosine, -sine, sine, cosine) * delta;
            elliptical.x *= mix(0.78, 1.24, randomPair.y);

            float phase = fract(
                weatherTime * cycleRate
                + weatherHash(cell + vec2(seed * 4.17, 27.41)));
            float distanceToImpact = length(elliptical);
            float ringRadius = phase * cellSize * 0.39;
            float antialias = 0.85;
            float thickness = mix(1.15, 2.45, phase);
            float ring = 1.0 - smoothstep(
                thickness,
                thickness + antialias,
                abs(distanceToImpact - ringRadius));
            float lifetime = smoothstep(0.025, 0.11, phase)
                * (1.0 - smoothstep(0.62, 0.97, phase));
            float eventStrength = smoothstep(
                1.0 - coverage,
                1.0,
                weatherHash(cell + vec2(seed * 7.13, 51.83)));
            float irregularity = 0.72 + 0.28 * sin(
                atan(elliptical.y, elliptical.x) * 3.0
                + randomPair.x * 11.0);
            response += ring * lifetime * eventStrength * irregularity;
        }
    }

    return clamp(response, 0.0, 1.0);
}

vec3 stableSurfaceNormal()
{
	vec3 normal = cross(dFdx(fWorldPos), dFdy(fWorldPos));
	float lengthSquared = dot(normal, normal);
	if (lengthSquared < 0.000001)
	{
		return vec3(0.0, -1.0, 0.0);
	}
	normal *= inversesqrt(lengthSquared);
	vec3 viewVector = cameraPosition - fWorldPos;
	return dot(normal, viewVector) < 0.0 ? -normal : normal;
}

vec3 authoredSurfaceNormal(vec3 geometricNormal)
{
    // =====================================================
    // FIRST VISUAL MATERIAL TEST
    //
    // STONE = material ID 2
    // variant 1 = cobble
    // normal atlas layer 4
    //
    // Everything else keeps the original geometric normal.
    // =====================================================

    if (fMaterialId != 2 || fMaterialVariant != 1)
    {
        return geometricNormal;
    }

    /*
     * Use RuneLite's existing UVs for this first proof.
     *
     * Later we can use authored UV scale and improve
     * world/terrain projection.
     */
    vec2 materialUv = fUv;

    /*
     * Sample cobble normal.
     *
     * authored_materials.json:
     * STONE variant 1 -> normalLayer 4
     */
    vec3 tangentNormal =
        texture(
            authoredMaterialNormals,
            vec3(materialUv, 4.0)
        ).xyz;

    /*
     * Stored unsigned RGB [0,1]
     * becomes tangent normal [-1,1].
     */
    tangentNormal =
        tangentNormal * 2.0 - 1.0;

    /*
     * Make the FIRST TEST deliberately obvious.
     *
     * The catalog currently specifies 0.76.
     * We exaggerate XY slightly so we can clearly prove
     * that normal mapping is active.
     */
    tangentNormal.xy *= 1.25;

    tangentNormal =
        normalize(tangentNormal);

    // =====================================================
    // Reconstruct tangent basis from world position + UV
    // derivatives.
    // =====================================================

    vec3 dp1 = dFdx(fWorldPos);
    vec3 dp2 = dFdy(fWorldPos);

    vec2 duv1 = dFdx(materialUv);
    vec2 duv2 = dFdy(materialUv);

    float determinant =
        duv1.x * duv2.y -
        duv1.y * duv2.x;

    /*
     * Degenerate UV mapping:
     * safely fall back to the normal RuneLite surface.
     */
    if (abs(determinant) < 0.000001)
    {
        return geometricNormal;
    }

    float inverseDeterminant =
        1.0 / determinant;

    vec3 tangent =
        normalize(
            (dp1 * duv2.y -
             dp2 * duv1.y)
            * inverseDeterminant
        );

    vec3 bitangent =
        normalize(
            (-dp1 * duv2.x +
              dp2 * duv1.x)
            * inverseDeterminant
        );

    /*
     * Keep the basis aligned with the geometric normal.
     */
    tangent =
        normalize(
            tangent -
            geometricNormal *
            dot(geometricNormal, tangent)
        );

    bitangent =
        normalize(
            cross(
                geometricNormal,
                tangent
            )
        );

    /*
     * Preserve the UV orientation where possible.
     */
    if (determinant < 0.0)
    {
        bitangent = -bitangent;
    }

    mat3 tbn =
        mat3(
            tangent,
            bitangent,
            geometricNormal
        );

    return normalize(
        tbn * tangentNormal
    );
}

vec3 mainPassLightDirection()
{
	// Java supplies the main pass a logical Y-up direction. fWorldPos and its
	// derivatives use RuneLite scene space where upward is -Y.
	return normalize(vec3(lightDirection.x, -lightDirection.y, lightDirection.z));
}

vec3 safeHalfDirection(vec3 lightDir, vec3 viewDir)
{
	vec3 sum = lightDir + viewDir;
	float lengthSquared = dot(sum, sum);
	return lengthSquared > 0.000001
		? sum * inversesqrt(lengthSquared)
		: lightDir;
}

vec3 addHeadroomLimited(vec3 baseColor, vec3 response)
{
	// Keep RuneLite's authored color authoritative. Material light only occupies
	// part of the remaining display headroom, so pale sand and white objects do
	// not clip into flat glowing patches.
	vec3 headroom = max(vec3(0.0), vec3(0.985) - baseColor);
	return baseColor + min(max(response, vec3(0.0)), headroom * 0.42);
}

vec3 applyDryMaterialResponse(
	vec3 baseColor,
	vec3 normal,
	float directVisibility,
	float worldPattern,
	float microVisibility)
{
	float strength = clamp(materialLightingStrength, 0.0, 1.0);
	float distanceToCamera = length(cameraPosition - fWorldPos);
	float detailFade = 1.0 - smoothstep(1152.0, 4300.0, distanceToCamera);
	if (strength <= 0.0 || detailFade <= 0.0)
	{
		return baseColor;
	}

	vec3 lightDir = mainPassLightDirection();
	vec3 viewDir = normalize(cameraPosition - fWorldPos);
	vec3 halfDir = safeHalfDirection(lightDir, viewDir);
	float normalLight = dot(normal, lightDir);
	float facingLight = clamp(normalLight, 0.0, 1.0);
	float halfLight = clamp(dot(normal, halfDir), 0.0, 1.0);
	float night = clamp(celestialNightFactor, 0.0, 1.0);
	float weatherTransmission = 1.0;
	if (weatherMode == 1) weatherTransmission = 0.58; // rain
	if (weatherMode == 2) weatherTransmission = 0.22; // storm
	if (weatherMode == 3) weatherTransmission = 0.65; // snow
	if (weatherMode == 4) weatherTransmission = 0.30; // blizzard
	float celestialEnergy = mix(1.0, 0.11, night)
		* clamp(directVisibility, 0.0, 1.0)
		* weatherTransmission;
	vec3 celestialTint = mix(
		vec3(1.0, 0.84, 0.61),
		vec3(0.48, 0.60, 0.84),
		night);
	float baseLuminance = dot(baseColor, vec3(0.2126, 0.7152, 0.0722));
	float tonalWeight = mix(0.48, 1.0, clamp(baseLuminance, 0.0, 1.0));
	float microPattern = mix(0.82, 1.0, worldPattern * microVisibility);
	vec3 response = vec3(0.0);

	if (fMaterialId == 1) // grass
	{
		float wrappedSun = smoothstep(-0.18, 0.82, normalLight);
		float grazing = pow(1.0 - abs(dot(normal, viewDir)), 2.0);
		response = baseColor * vec3(0.090, 0.125, 0.042)
			* wrappedSun * (0.68 + grazing * 0.24)
			* microPattern;
	}
	else if (fMaterialId == 7) // foliage
	{
		// Foliage cards are two-sided. Back-facing light is transmission, not a
		// reversed glossy normal, which avoids view-flipping white card artifacts.
		float leafDiffuse = smoothstep(-0.10, 0.82, abs(normalLight));
		float transmission = pow(clamp(-normalLight, 0.0, 1.0), 1.6);
		float grazing = pow(1.0 - abs(dot(normal, viewDir)), 2.0);
		response = baseColor * vec3(0.095, 0.135, 0.040)
			* (leafDiffuse * 0.58 + transmission * (0.48 + grazing * 0.32));
	}
	else if (fMaterialId == 2) // stone
	{
		float roughDiffuse = smoothstep(0.02, 0.86, facingLight);
		response = celestialTint * roughDiffuse * (0.018 + microPattern * 0.026)
			* microPattern * tonalWeight;
	}
	else if (fMaterialId == 3) // sand
	{
		float roughDiffuse = smoothstep(-0.02, 0.84, facingLight);
		float sparseGrain = pow(worldPattern * microVisibility, 5.0);
		response = celestialTint * roughDiffuse * (0.030 + sparseGrain * 0.012)
			* tonalWeight;
	}
	else if (fMaterialId == 4) // dirt
	{
		response = celestialTint * smoothstep(0.08, 0.88, facingLight)
			* 0.016 * microPattern * tonalWeight;
	}
	else if (fMaterialId == 5) // wood
	{
		float satin = pow(halfLight, 30.0);
		vec3 woodLight = mix(
			vec3(1.0, 0.69, 0.39),
			vec3(0.48, 0.60, 0.82),
			night);
		response = woodLight
			* (smoothstep(0.12, 0.88, facingLight) * 0.021 + satin * 0.034)
			* tonalWeight;
	}
	else if (fMaterialId == 6) // metal
	{
		float closeFade = 1.0 - smoothstep(900.0, 2800.0, distanceToCamera);
		float narrowSpecular = pow(halfLight, 86.0) * closeFade;
		response = celestialTint * narrowSpecular * 0.145;
	}

	response *= celestialEnergy * detailFade * strength;
	return addHeadroomLimited(baseColor, response);
}


void main()
{
    vec4 c;
	bool waterSurface = false;
	bool swampWater = false;
	bool terrainSurface = (fShoreEdges & 256) != 0;

    // ====================================================
    // Normal RuneLite texture rendering
    // ====================================================

    if (fTextureId > 0)
    {
        int textureIdx = fTextureId - 1;
        waterSurface = isWaterTexture(textureIdx);
        swampWater = textureIdx == 25;

        vec2 sampleUv = fUv;
        if (enhancedWater != 0 && waterSurface)
        {
            float time = float(tick) * 0.0125;
            float waveA = sin(fWorldPos.x * 0.018 + time);
            float waveB = cos(fWorldPos.z * 0.014 - time * 0.73);
            sampleUv += vec2(waveA, waveB) * 0.006 * clamp(waterStrength, 0.0, 2.0);
        }

        vec4 textureColor =
            texture(
                textures,
                vec3(sampleUv, float(textureIdx))
            );

        vec4 textureColor0 =
            textureLod(
                textures,
                vec3(sampleUv, float(textureIdx)),
                0.f
            );

        if (textureColor0.a < 1.f)
        {
            discard;
        }

		// Terrain tiles map their texture coordinates from 0..1 independently,
		// which exposes opposite clamped texture edges at every tile boundary.
		// Near a land-tile edge, converge onto a world-anchored mirrored sample.
		// Adjacent tiles then read the same texels at their shared edge while the
		// center of each tile retains the stock texture detail and orientation.
		if (terrainTextureBlending != 0 && terrainSurface && !waterSurface)
		{
			float strength = clamp(terrainBlendStrength, 0.0, 1.0);
			float featherWidth = mix(0.035, 0.115, strength);
			float westBlend = (fShoreEdges & 512) != 0
				? 1.0 - smoothstep(0.012, featherWidth, fTileUv.x) : 0.0;
			float eastBlend = (fShoreEdges & 1024) != 0
				? 1.0 - smoothstep(0.012, featherWidth, 1.0 - fTileUv.x) : 0.0;
			float southBlend = (fShoreEdges & 2048) != 0
				? 1.0 - smoothstep(0.012, featherWidth, fTileUv.y) : 0.0;
			float northBlend = (fShoreEdges & 4096) != 0
				? 1.0 - smoothstep(0.012, featherWidth, 1.0 - fTileUv.y) : 0.0;
			float horizontalBlend = max(westBlend, eastBlend);
			float verticalBlend = max(southBlend, northBlend);
			float edgeBlend = max(horizontalBlend, verticalBlend) * strength;

			if (edgeBlend > 0.0001)
			{
				vec2 worldTile = fWorldPos.xz / 128.0;
				vec2 worldMirror = vec2(1.0)
					- abs(mod(worldTile, 2.0) - vec2(1.0));
				vec2 animationOffset = fUv - fTileUv;
				vec2 seamUv = fUv;
				seamUv.x = mix(seamUv.x,
					worldMirror.x + animationOffset.x,
					step(0.0001, horizontalBlend));
				seamUv.y = mix(seamUv.y,
					worldMirror.y + animationOffset.y,
					step(0.0001, verticalBlend));

				vec4 seamSample = texture(
					textures,
					vec3(seamUv, float(textureIdx)));
				float seamAlpha = textureLod(
					textures,
					vec3(seamUv, float(textureIdx)),
					0.f).a;
				if (seamAlpha >= 1.f)
				{
					textureColor.rgb = mix(
						textureColor.rgb,
						seamSample.rgb,
						edgeBlend);
				}
			}
		}

        textureColor =
            vec4(
                textureColor.rgb,
                1.f
            );

        textureColor =
            pow(
                textureColor,
                vec4(
                    brightness,
                    brightness,
                    brightness,
                    1.f
                )
            );

        float light =
            fHsl / 127.f;

        vec3 mul =
            (1.f - textureLightMode) * vec3(light)
            + textureLightMode * fColor.rgb;

        c =
            textureColor *
            vec4(
                mul,
                fColor.a
            );
    }
    else
    {
        // =================================================
        // Normal RuneLite untextured rendering
        // =================================================

        vec3 hsl =
            vec3(
                int(fHsl) >> 10 & 63,
                int(fHsl) >> 7 & 7,
                int(fHsl) & 127
            );

        vec3 rgb =
            mix(
                fColor.rgb,
                hslToRgb(hsl),
                smoothBanding
            );

        c =
            vec4(
                rgb,
                fColor.a
            );
    }

#if COLORBLIND_MODE > 0
    c.rgb = colorblind(c.rgb);
#endif

	// ====================================================
	// FIRST AUTHORED ALBEDO TEST
	// STONE variant 2 = masonry
	// albedo layer 1 = masonry_albedo.png
	// ====================================================

	if (fMaterialId == 2 && fMaterialVariant == 2)
	{
		vec2 authoredUv = vec2(
				fUv.y,
				1.0 - fUv.x
		);

		vec3 authoredColor = texture(
				authoredMaterialAlbedos,
				vec3(authoredUv, 1.0)
		).rgb;

		// TEMP TEST: brighten authored albedo before world lighting.
		authoredColor *= 1.45;

		c.rgb = clamp(authoredColor, 0.0, 1.0);
	}

    // ====================================================
    // Enhanced water
    // ====================================================

    if (enhancedWater != 0 && waterSurface)
    {
        float strength = clamp(waterStrength, 0.0, 2.0);
        float time = float(tick) * 0.0125;
        float waveX =
            cos(fWorldPos.x * 0.018 + time)
            + cos((fWorldPos.x + fWorldPos.z) * 0.010 - time * 0.61) * 0.55;
        float waveZ =
            sin(fWorldPos.z * 0.014 - time * 0.73)
            + sin((fWorldPos.x - fWorldPos.z) * 0.012 + time * 0.47) * 0.55;
		float softWaveX = sin(fWorldPos.x * 0.0048 - time * 0.52)
			+ sin((fWorldPos.x + fWorldPos.z) * 0.0031 + time * 0.37) * 0.6;
		float softWaveZ = cos(fWorldPos.z * 0.0042 + time * 0.44)
			+ cos((fWorldPos.x - fWorldPos.z) * 0.0027 - time * 0.31) * 0.6;
        vec3 waterNormal = normalize(vec3(
			waveX * 0.075 + softWaveX * 0.035,
			1.0,
			waveZ * 0.075 + softWaveZ * 0.035));
        vec3 viewDirection = normalize(cameraPosition - fWorldPos);
        vec3 sunDirection = normalize(lightDirection);
        float fresnel =
            pow(1.0 - max(dot(waterNormal, viewDirection), 0.0), 3.0);
        float sparkle =
            pow(max(dot(reflect(-sunDirection, waterNormal), viewDirection), 0.0), 96.0);
        float broadHighlight =
            pow(max(dot(reflect(-sunDirection, waterNormal), viewDirection), 0.0), 12.0);
        vec3 waterTint =
            swampWater ? vec3(0.13, 0.19, 0.10) : vec3(0.34, 0.62, 0.67);
        float tintAmount =
            (swampWater ? 0.10 : 0.045)
            + fresnel * (swampWater ? 0.08 : 0.11);
		float clarity = 1.0 - clamp(waterOpacity, 0.4, 1.0);
		float depthVariation =
			0.5 + 0.5 * sin(fWorldPos.x * 0.0041 + sin(fWorldPos.z * 0.0023));
		float shoreDistance = 2.0;
		if ((fShoreEdges & 1) != 0) shoreDistance = min(shoreDistance, fTileUv.x);
		if ((fShoreEdges & 2) != 0) shoreDistance = min(shoreDistance, 1.0 - fTileUv.x);
		if ((fShoreEdges & 4) != 0) shoreDistance = min(shoreDistance, fTileUv.y);
		if ((fShoreEdges & 8) != 0) shoreDistance = min(shoreDistance, 1.0 - fTileUv.y);
		if ((fShoreEdges & 16) != 0) shoreDistance = min(shoreDistance, length(fTileUv));
		if ((fShoreEdges & 32) != 0) shoreDistance = min(shoreDistance, length(fTileUv - vec2(1.0, 0.0)));
		if ((fShoreEdges & 64) != 0) shoreDistance = min(shoreDistance, length(fTileUv - vec2(1.0)));
		if ((fShoreEdges & 128) != 0) shoreDistance = min(shoreDistance, length(fTileUv - vec2(0.0, 1.0)));
		float organicWarp =
			sin(fWorldPos.x * 0.027 + time * 0.31)
			+ sin(fWorldPos.z * 0.019 - time * 0.23)
			+ sin((fWorldPos.x - fWorldPos.z) * 0.011 + time * 0.17);
		shoreDistance += organicWarp * 0.008;
		float shore = 1.0 - smoothstep(0.025, 0.34, shoreDistance);
		// A narrow visual shelf makes shoreline water read as shallow and clear,
		// then rapidly returns to opaque water before missing underwater geometry
		// can become visible.
		float shallowShelf = 1.0 - smoothstep(0.035, 0.30, shoreDistance);
		shallowShelf *= shallowShelf;
		float ripplePhase = shoreDistance * 58.0 - time * 2.35
			+ organicWarp * 0.72
			+ sin((fWorldPos.x + fWorldPos.z) * 0.031) * 0.42;
		float shoreRipple = pow(0.5 + 0.5 * sin(ripplePhase), 15.0)
			* shore * smoothstep(0.015, 0.07, shoreDistance);
		vec2 sunFlow = normalize(sunDirection.xz + vec2(0.0001));
		vec2 sunCross = vec2(-sunFlow.y, sunFlow.x);
		float lightTravel = dot(fWorldPos.xz, sunFlow);
		float lightAcross = dot(fWorldPos.xz, sunCross);
		float causticA = sin(lightTravel * 0.086 - time * 1.28 + sin(lightAcross * 0.032));
		float causticB = sin(lightAcross * 0.069 + time * 0.72 + sin(lightTravel * 0.025));
		float caustic = pow(max(causticA * causticB, 0.0), 3.0);
		vec3 shallowBed = swampWater
			? vec3(0.18, 0.21, 0.10)
			: vec3(0.43, 0.49, 0.35);

        // Preserve the vanilla water character instead of laying down a blue wash.
		// Apparent clarity is simulated on this one surface. A real transparent
		// bed would require a separate scene-color/depth pass and exposes gaps in
		// RuneLite's non-watertight tile geometry.
		c.rgb = mix(c.rgb, shallowBed,
			clarity * (0.36 + depthVariation * 0.24 + shore * 0.16));
		vec3 bankShelf = swampWater ? vec3(0.23, 0.25, 0.12) : vec3(0.49, 0.48, 0.34);
		c.rgb = mix(c.rgb, bankShelf,
			shallowShelf * (0.34 + clarity * 0.46));
        c.rgb = mix(c.rgb, waterTint, tintAmount * min(strength, 1.0));
		c.rgb += vec3(0.58, 0.78, 0.70)
			* caustic * shore * 0.055 * strength;
		c.rgb += vec3(0.78, 0.94, 0.91) * shoreRipple * 0.13 * strength;
        c.rgb *= 1.0 + 0.035 * min(strength, 1.0);
		c.rgb +=
			vec3(0.78, 0.94, 1.0)
			* (sparkle * 0.72 + broadHighlight * 0.10)
			* strength;
		c.a = 1.0;
        c.rgb = clamp(c.rgb, 0.0, 1.0);
    }


    // ====================================================
    // Selective cast shadows
    // ====================================================

	// RuneLite's texture and vertex color remain authoritative. The shadow map
	// only removes a restrained celestial contribution from occluded fragments;
	// it never derives a second diffuse lighting model from low-poly normals.
	vec3 stockSurface = c.rgb;
	float materialDirectVisibility = 1.0;
	if (shadowsEnabled != 0)
	{
		vec4 lightSpacePos = shadowLightProj * vec4(fWorldPos, 1.0);
		vec3 shadowCoord = lightSpacePos.xyz / lightSpacePos.w;
		shadowCoord = shadowCoord * 0.5 + 0.5;

		if (
			shadowCoord.x >= 0.0 && shadowCoord.x <= 1.0 &&
			shadowCoord.y >= 0.0 && shadowCoord.y <= 1.0 &&
			shadowCoord.z >= 0.0 && shadowCoord.z <= 1.0)
		{
			vec2 texelSize = 1.0 / vec2(textureSize(shadowMap, 0));
			// The conventional 40,000-unit light-depth span makes this a
			// restrained 16-world-unit receiver offset. It suppresses acne without
			// erasing the much larger separation of genuine blockers.
			float bias = 0.00040;
			float occlusion = 0.0;
			float receiverDistance = length(cameraPosition - fWorldPos);
			float filterRadius = mix(
				0.65,
				0.95,
				smoothstep(1152.0, 6144.0, receiverDistance));

			for (int x = 0; x < 3; ++x)
			{
				for (int y = 0; y < 3; ++y)
				{
					vec2 filterOffset =
						(vec2(float(x), float(y)) - vec2(1.0))
						* texelSize * filterRadius;
					float closestDepth = texture(
						shadowMap, shadowCoord.xy + filterOffset).r;
					occlusion += shadowCoord.z - bias > closestDepth
						? 1.0 : 0.0;
				}
			}

			occlusion = smoothstep(0.10, 0.90, occlusion / 9.0);
			// Fade the final few percent of the orthographic map so its finite
			// coverage cannot paint a rectangular border into the world.
			vec2 shadowEdgeDistance = min(
				shadowCoord.xy, vec2(1.0) - shadowCoord.xy);
			float shadowMapConfidence = smoothstep(
				0.0, 0.035,
				min(shadowEdgeDistance.x, shadowEdgeDistance.y));
			float configuredOcclusion = occlusion
				* shadowMapConfidence
				* clamp(shadowStrength, 0.0, 0.80);
			materialDirectVisibility = 1.0 - configuredOcclusion;

			vec3 dayShadowTransmission = vec3(0.44, 0.48, 0.55);
			vec3 nightShadowTransmission = vec3(0.60, 0.65, 0.76);
			vec3 shadowTransmission = mix(
				dayShadowTransmission,
				nightShadowTransmission,
				clamp(celestialNightFactor, 0.0, 1.0));
			vec3 shadowMultiplier = mix(
				vec3(1.0), shadowTransmission, configuredOcclusion);
			c.rgb = stockSurface * shadowMultiplier;
		}
	}

	// The explicit CPU tag is the authority for material effects. Generated
	// shoreline substrate deliberately carries a half-alpha marker for the later
	// water composite; excluding it keeps underwater sand from catching dry sun
	// or rain highlights. UNKNOWN and water retain the established shader path.
	bool generatedWaterBed = terrainSurface
		&& fMaterialId == 3
		&& fColor.a < 0.75;
	bool taggedMaterialSurface = fMaterialId >= 1
		&& fMaterialId <= 7
		&& !waterSurface
		&& !generatedWaterBed;
	bool dryMaterialActive = materialLightingEnabled != 0
		&& materialLightingStrength > 0.0;
	bool rainWeatherActive = weatherMode == 1 || weatherMode == 2;
	bool wetMaterialActive = wetSurfacesEnabled != 0
		&& wetSurfaceStrength > 0.0
		&& rainWeatherActive;
	vec3 materialNormal = vec3(0.0, -1.0, 0.0);
	float materialWorldPattern = 0.5;
	float materialMicroVisibility = 0.0;

    // These derivative operations sit behind uniform feature branches, never a
    // per-material branch. Neighboring fragments therefore agree on derivative
    // evaluation even at primitive/material boundaries.
    if (dryMaterialActive || rainWeatherActive)
    {
        // RuneLite's original polygon/geometry normal.
        materialNormal = stableSurfaceNormal();

        // Apply our authored normal map on supported material variants.
        // For the first milestone this only affects STONE variant 1 / cobble.
        materialNormal = authoredSurfaceNormal(
            materialNormal
        );
    }
	if (dryMaterialActive)
	{
		float materialPatternPhase = fWorldPos.x * 0.19
			+ sin(fWorldPos.z * 0.17 + 1.7) * 1.6;
		materialWorldPattern = 0.5 + 0.5 * sin(materialPatternPhase);
		float patternFootprint = fwidth(materialPatternPhase);
		materialMicroVisibility = 1.0
			- smoothstep(0.42, 1.15, patternFootprint);
	}
	if (dryMaterialActive && taggedMaterialSurface)
	{
		c.rgb = applyDryMaterialResponse(
			c.rgb,
			materialNormal,
			materialDirectVisibility,
			materialWorldPattern,
			materialMicroVisibility);
	}

    // ====================================================
    // Tagged rain response on exposed-looking surfaces
    // ====================================================

    if (wetMaterialActive && taggedMaterialSurface)
    {
		vec3 rainNormal = materialNormal;
		float upwardSurface = smoothstep(
			0.54, 0.94, dot(rainNormal, vec3(0.0, -1.0, 0.0)));
        if (upwardSurface > 0.0)
        {
            float stormAmount = weatherMode == 2 ? 1.0 : 0.58;
			float profileAmount = 1.0;
			float darkenAmount = 0.16;
			float glossExponent = 48.0;
			if (fMaterialId == 1) // grass
			{
				profileAmount = 0.48;
				darkenAmount = 0.09;
				glossExponent = 72.0;
			}
			else if (fMaterialId == 2) // stone
			{
				profileAmount = 1.0;
				darkenAmount = 0.19;
				glossExponent = 54.0;
			}
			else if (fMaterialId == 3) // sand
			{
				profileAmount = 0.68;
				darkenAmount = 0.14;
				glossExponent = 66.0;
			}
			else if (fMaterialId == 4) // dirt
			{
				profileAmount = 0.86;
				darkenAmount = 0.22;
				glossExponent = 38.0;
			}
			else if (fMaterialId == 5) // wood
			{
				profileAmount = 0.78;
				darkenAmount = 0.17;
				glossExponent = 62.0;
			}
			else if (fMaterialId == 6) // metal
			{
				profileAmount = 0.72;
				darkenAmount = 0.08;
				glossExponent = 92.0;
			}
			else if (fMaterialId == 7) // foliage
			{
				profileAmount = 0.52;
				darkenAmount = 0.10;
				glossExponent = 76.0;
			}

			float configuredWetness = clamp(wetSurfaceStrength, 0.0, 1.0);
			float rainAmount = stormAmount
				* clamp(weatherDensity, 0.0, 1.0)
				* configuredWetness
				* profileAmount;
            float surfaceVariation = 0.82 + 0.18 * weatherHash(
                floor(fWorldPos.xz / 96.0));
            float wetness = upwardSurface * rainAmount * surfaceVariation;

			// Retain material color under a thin neutral film. Dirt/stone absorb more;
			// metal and living leaves retain their authored brightness.
			c.rgb = mix(
				c.rgb,
				c.rgb * vec3(0.86, 0.88, 0.90),
				wetness * darkenAmount);

			vec3 rainLightDirection = mainPassLightDirection();
            vec3 rainViewDirection = normalize(cameraPosition - fWorldPos);
            float wetHighlight = pow(max(dot(
                reflect(-rainLightDirection, rainNormal),
				rainViewDirection), 0.0), glossExponent);
			float night = clamp(celestialNightFactor, 0.0, 1.0);
			vec3 wetLight = mix(
				vec3(0.66, 0.72, 0.74),
				vec3(0.46, 0.57, 0.76),
				night);
			if (weatherMode == 2)
			{
				wetLight *= vec3(0.88, 0.90, 0.92);
			}
			vec3 wetResponse = wetLight
				* wetHighlight * 0.060 * materialDirectVisibility
				* wetness
				* (1.0 + lightningFlash * 1.65);
			c.rgb = addHeadroomLimited(c.rgb, wetResponse);
            c.rgb = clamp(c.rgb, 0.0, 1.0);
        }
    }

	// Impacts are falling-rain weather, not a glossy material layer, so they
	// remain visible when Wet surfaces is disabled. Limit them to horizontal
	// terrain that plausibly holds a ripple: unknown ground, stone, sand, and
	// dirt. Grass, wood docks, metal, foliage, models, water, and generated
	// underwater substrate never receive the rings.
	bool rainImpactEligible = rainWeatherActive
		&& terrainSurface
		&& !waterSurface
		&& !generatedWaterBed
		&& (fMaterialId == 0
			|| fMaterialId == 2
			|| fMaterialId == 3
			|| fMaterialId == 4);
	float rainImpactDistance = length(cameraPosition - fWorldPos);
	if (rainImpactEligible && rainImpactDistance < 4300.0)
	{
		float impactUpward = smoothstep(
			0.54, 0.94, dot(materialNormal, vec3(0.0, -1.0, 0.0)));
		float stormAmount = weatherMode == 2 ? 1.0 : 0.58;
		float rainAmount = stormAmount * clamp(weatherDensity, 0.1, 1.0);
		float surfaceVariation = 0.82 + 0.18 * weatherHash(
			floor(fWorldPos.xz / 96.0));
		float impactWetness = impactUpward * rainAmount * surfaceVariation;
		float coverage = clamp(
			weatherDensity * (weatherMode == 2 ? 0.62 : 0.38),
			0.08,
			0.68);
		float fineImpacts = rainImpactLayer(
			fWorldPos.xz, 76.0, 0.78, 4.7, coverage);
		float broadImpacts = rainImpactLayer(
			fWorldPos.xz + vec2(31.0, -19.0),
			124.0,
			0.46,
			13.9,
			coverage * 0.74);
		float impactRings = clamp(
			fineImpacts + broadImpacts * 0.72, 0.0, 1.0);
		float impactFade = 1.0 - smoothstep(
			1450.0, 4300.0, rainImpactDistance);
		float night = clamp(celestialNightFactor, 0.0, 1.0);
		vec3 impactLight = mix(
			vec3(0.66, 0.72, 0.74),
			vec3(0.46, 0.57, 0.76),
			night);
		if (weatherMode == 2)
		{
			impactLight *= vec3(0.88, 0.90, 0.92);
		}
		vec3 impactResponse = impactLight
			* impactRings * 0.072 * impactFade * impactWetness
			* (1.0 + lightningFlash * 1.65);
		c.rgb = addHeadroomLimited(c.rgb, impactResponse);
		c.rgb = clamp(c.rgb, 0.0, 1.0);
	}
    // ====================================================
    // CUSTOM: Enhanced colors
    // ====================================================

    if (enhancedColors != 0)
    {
        float luminance =
            dot(
                c.rgb,
                vec3(
                    0.2126,
                    0.7152,
                    0.0722
                )
            );

        c.rgb =
            mix(
                vec3(luminance),
                c.rgb,
                saturation
            );

        c.rgb =
            (c.rgb - vec3(0.5))
            * contrast
            + vec3(0.5);

        c.rgb =
            clamp(
                c.rgb,
                0.0,
                1.0
        );
    }

    // ====================================================
    // Fog
    // ====================================================

    vec3 mixedColor =
        mix(
            c.rgb,
            fogColor.rgb,
            fFogAmount
        );
	if (weatherMode == 3 || weatherMode == 4)
	{
		vec3 snowNormal = normalize(cross(dFdx(fWorldPos), dFdy(fWorldPos)));
		if (snowNormal.y < 0.0)
		{
			snowNormal = -snowNormal;
		}

		float macroSnow = 0.93
			+ 0.04 * sin(fWorldPos.x * 0.0023 + sin(fWorldPos.z * 0.0019))
			+ 0.03 * sin((fWorldPos.x - fWorldPos.z) * 0.0041 + 1.7);

		// Terrain gets one continuous world-space blanket. Objects retain a
		// stricter upward-facing test so roofs and foliage catch snow without
		// whitening walls, trunks, characters, and every low-poly face.
		float terrainSlope = smoothstep(0.12, 0.52, snowNormal.y);
		float terrainCoverage = (weatherMode == 4 ? 0.82 : 0.62)
			* mix(0.42, 1.0, terrainSlope);
		float objectCatch = smoothstep(0.52, 0.92, snowNormal.y);
		float objectCoverage = objectCatch * (weatherMode == 4 ? 0.44 : 0.27);
		float accumulation = macroSnow
			* (terrainSurface ? terrainCoverage : objectCoverage);

		// Falling snow does not paint animated water into bright polygon tiles.
		if (waterSurface)
		{
			accumulation = 0.0;
		}
		mixedColor = mix(
			mixedColor,
			weatherMode == 4 ? vec3(0.78, 0.83, 0.87) : vec3(0.76, 0.82, 0.86),
			clamp(accumulation, 0.0, 0.86));
	}
	float weatherDistance = length(cameraPosition - fWorldPos);
	float weatherMist = 0.0;
	vec3 weatherMistColor = vec3(0.48, 0.55, 0.60);
	if (weatherMode == 1) weatherMist = 0.06;
	if (weatherMode == 2) { weatherMist = 0.28; weatherMistColor = vec3(0.32, 0.35, 0.38); }
	if (weatherMode == 3) { weatherMist = 0.08; weatherMistColor = vec3(0.68, 0.75, 0.82); }
	if (weatherMode == 4) { weatherMist = 0.18; weatherMistColor = vec3(0.62, 0.68, 0.74); }
	float weatherHaze = smoothstep(
		weatherMode == 2 ? 620.0 : 700.0,
		weatherMode == 2 ? 3400.0 : 4100.0,
		weatherDistance) * weatherMist;
	float hazeVariation = 0.84 + 0.16 * sin(
		fWorldPos.x * 0.0017 + fWorldPos.z * 0.0011 + weatherTime * 0.055);
	weatherHaze *= hazeVariation;
	mixedColor = mix(mixedColor, weatherMistColor, weatherHaze);
	mixedColor += vec3(0.68, 0.78, 1.0) * lightningFlash
		* (0.32 + 0.68 * (1.0 - fFogAmount));

	// Material debug is deliberately the final world-color operation. It makes
	// CPU classification errors obvious without allowing fog, grading, weather,
	// or shadows to disguise the primitive's stable tag.
	if (materialDebugMode != 0)
	{
		if (materialDebugMode == 2)
		{
			mixedColor = terrainSurface && fMaterialId == 1
				? vec3(0.12, 1.0, 0.18)
				: terrainSurface && fMaterialId == 2
					? vec3(0.36, 0.72, 1.0)
					: terrainSurface && fMaterialId == 3
						? vec3(1.0, 0.72, 0.18)
						: terrainSurface && fMaterialId == 4
							? vec3(0.62, 0.29, 0.10)
							: vec3(0.12, 0.03, 0.04);
		}
		else if (terrainSurface || fMaterialId != 0)
		{
			mixedColor = materialDebugColor(fMaterialId);
		}
		else
		{
			mixedColor *= 0.08;
		}
	}

    FragColor =
        vec4(
            mixedColor,
            c.a
        );


#ifdef FRAG_UVS

    if (fTextureId > 0)
    {
        FragColor =
            vec4(
                fUv.x,
                0,
                fUv.y,
                1
            );
    }

#endif


#ifdef ZBUF_DEBUG

    float dc =
        linear_depth(
            fDepth
        );

    if (dc > 1.0)
    {
        FragColor =
            vec4(1, 0, 0, 1);
    }
    else if (dc < -1.0)
    {
        FragColor =
            vec4(0, 0, 1, 1);
    }
    else if (dc < 0.0)
    {
        FragColor =
            vec4(0, 1, 0, 1);
    }
    else
    {
        FragColor =
            vec4(
                dc,
                dc,
                dc,
                1
            );
    }

#endif
}
