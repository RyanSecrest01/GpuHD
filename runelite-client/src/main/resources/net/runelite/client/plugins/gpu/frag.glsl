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
uniform sampler2DArray smoothTextures;
uniform float brightness;
uniform float smoothBanding;
uniform vec4 fogColor;
uniform float textureLightMode;

// ========================================================
// Enhanced colors
// ========================================================

uniform int enhancedColors;
uniform float saturation;
uniform float contrast;
uniform int materialPalette;
uniform int materialDebug;
uniform float stoneWallCleanup;

// ========================================================
// Selective celestial effects
// ========================================================

uniform vec3 lightDirection;
uniform vec3 cameraPosition;
uniform int enhancedWater;
uniform float waterStrength;
uniform float waterOpacity;
uniform samplerCube environmentMap;
uniform float lightningFlash;
uniform int weatherMode;
uniform float weatherTime;
uniform float celestialNightFactor;
uniform float polygonDefinition;
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

vec3 stableSurfaceNormal()
{
	vec3 geometricNormal = cross(dFdx(fWorldPos), dFdy(fWorldPos));
	float normalLengthSquared = dot(geometricNormal, geometricNormal);
	return normalLengthSquared > 1e-8
		? geometricNormal * inversesqrt(normalLengthSquared)
		: vec3(0.0, 1.0, 0.0);
}

vec3 fitColorToGamut(vec3 color, float luminance)
{
	vec3 chroma = color - vec3(luminance);
	float chromaScale = 1.0;

	if (chroma.r > 1e-5)
		chromaScale = min(chromaScale, (1.0 - luminance) / chroma.r);
	else if (chroma.r < -1e-5)
		chromaScale = min(chromaScale, luminance / -chroma.r);
	if (chroma.g > 1e-5)
		chromaScale = min(chromaScale, (1.0 - luminance) / chroma.g);
	else if (chroma.g < -1e-5)
		chromaScale = min(chromaScale, luminance / -chroma.g);
	if (chroma.b > 1e-5)
		chromaScale = min(chromaScale, (1.0 - luminance) / chroma.b);
	else if (chroma.b < -1e-5)
		chromaScale = min(chromaScale, luminance / -chroma.b);

	return vec3(luminance) + chroma * clamp(chromaScale, 0.0, 1.0);
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
	if (materialId == 8) return vec3(0.10, 0.48, 0.96); // water
	return vec3(0.72, 0.08, 0.62); // unknown
}

vec3 applyMaterialPalette(vec3 sourceColor, int materialId, int preset)
{
	const vec3 luminanceWeights = vec3(0.2126, 0.7152, 0.0722);
	vec3 gain = vec3(1.0);
	float chromaGain = 1.0;
	float amount = 0.0;

	if (preset == 1) // Natural
	{
		if (materialId == 1) { gain = vec3(0.80, 1.08, 1.00); chromaGain = 1.10; amount = 0.76; }
		else if (materialId == 2) { gain = vec3(0.91, 1.00, 1.10); chromaGain = 0.84; amount = 0.60; }
		else if (materialId == 3) { gain = vec3(1.10, 1.025, 0.83); chromaGain = 1.07; amount = 0.62; }
		else if (materialId == 4) { gain = vec3(1.12, 0.97, 0.82); chromaGain = 1.08; amount = 0.64; }
		else if (materialId == 5) { gain = vec3(1.14, 0.97, 0.78); chromaGain = 1.09; amount = 0.66; }
		else if (materialId == 6) { gain = vec3(0.88, 1.00, 1.15); chromaGain = 0.82; amount = 0.60; }
		else if (materialId == 7) { gain = vec3(0.74, 1.10, 0.95); chromaGain = 1.14; amount = 0.78; }
	}
	else if (preset == 2) // Lush
	{
		if (materialId == 1) { gain = vec3(0.68, 1.13, 0.95); chromaGain = 1.18; amount = 0.90; }
		else if (materialId == 2) { gain = vec3(0.82, 1.00, 1.18); chromaGain = 0.82; amount = 0.78; }
		else if (materialId == 3) { gain = vec3(1.16, 1.035, 0.72); chromaGain = 1.12; amount = 0.78; }
		else if (materialId == 4) { gain = vec3(1.18, 0.94, 0.70); chromaGain = 1.12; amount = 0.78; }
		else if (materialId == 5) { gain = vec3(1.20, 0.95, 0.66); chromaGain = 1.14; amount = 0.80; }
		else if (materialId == 6) { gain = vec3(0.82, 1.00, 1.22); chromaGain = 0.82; amount = 0.75; }
		else if (materialId == 7) { gain = vec3(0.60, 1.16, 0.88); chromaGain = 1.22; amount = 0.92; }
	}

	float sourceLuminance = clamp(dot(sourceColor, luminanceWeights), 0.0, 1.0);
	vec3 targetColor = sourceColor * gain;
	targetColor += vec3(sourceLuminance - dot(targetColor, luminanceWeights));
	targetColor = vec3(sourceLuminance)
		+ (targetColor - vec3(sourceLuminance)) * chromaGain;
	targetColor = fitColorToGamut(targetColor, sourceLuminance);

	float maximumChannel = max(sourceColor.r, max(sourceColor.g, sourceColor.b));
	float minimumChannel = min(sourceColor.r, min(sourceColor.g, sourceColor.b));
	float relativeChroma = (maximumChannel - minimumChannel)
		/ max(maximumChannel, 0.10);
	float tonalGate = smoothstep(0.015, 0.075, sourceLuminance)
		* (1.0 - smoothstep(0.88, 0.99, sourceLuminance));
	float vividGate = 1.0 - 0.50 * smoothstep(0.62, 0.95, relativeChroma);
	return mix(sourceColor, targetColor,
		clamp(amount * tonalGate * vividGate, 0.0, 1.0));
}

float stoneTapWeight(vec4 center, vec4 tap)
{
	const vec3 luminanceWeights = vec3(0.2126, 0.7152, 0.0722);
	vec3 difference = abs(tap.rgb - center.rgb);
	float edgeDelta = max(
		abs(dot(tap.rgb - center.rgb, luminanceWeights)),
		0.55 * max(difference.r, max(difference.g, difference.b)));
	return 1.0 - smoothstep(0.035, 0.12, edgeDelta);
}

void main()
{
    vec4 c;
    bool waterSurface = false;
    bool swampWater = false;
	bool paletteEligible = (fShoreEdges & 0x300) != 0;
	float stoneWallAmount = 0.0;
	if (stoneWallCleanup > 0.0 && fMaterialId == 2
		&& fTextureId > 0 && (fShoreEdges & 0x200) != 0)
	{
		vec3 stoneNormal = stableSurfaceNormal();
		float verticalStone = 1.0 - smoothstep(
			0.20, 0.46, abs(stoneNormal.y));
		stoneWallAmount = clamp(stoneWallCleanup, 0.0, 1.0)
			* verticalStone;
	}
	// Texture smoothing stays gradual, while rough masonry reaches its matte
	// response sooner so the default cannot read as partly polished stone.
	float stoneMatteAmount = clamp(stoneWallAmount * 1.45, 0.0, 1.0);

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
		vec2 textureGradientX = dFdx(sampleUv);
		vec2 textureGradientY = dFdy(sampleUv);

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

		if (stoneWallAmount > 0.0)
		{
			vec3 textureLayer = vec3(sampleUv, float(textureIdx));
			vec4 smoothCenter = textureGrad(
				smoothTextures,
				textureLayer,
				textureGradientX,
				textureGradientY);
			vec3 cleanStoneColor = smoothCenter.rgb;
			bool safeAlpha = smoothCenter.a >= 0.995;

			vec2 textureDimensions = vec2(textureSize(smoothTextures, 0).xy);
			vec2 texel = 1.0 / textureDimensions;
			float footprint = max(
				length(textureGradientX * textureDimensions),
				length(textureGradientY * textureDimensions));
			float crossBlend = 1.0 - smoothstep(1.0, 3.25, footprint);

			if (crossBlend > 0.01)
			{
				vec4 tapLeft = textureGrad(smoothTextures,
					vec3(sampleUv - vec2(texel.x, 0.0), float(textureIdx)),
					textureGradientX, textureGradientY);
				vec4 tapRight = textureGrad(smoothTextures,
					vec3(sampleUv + vec2(texel.x, 0.0), float(textureIdx)),
					textureGradientX, textureGradientY);
				vec4 tapDown = textureGrad(smoothTextures,
					vec3(sampleUv - vec2(0.0, texel.y), float(textureIdx)),
					textureGradientX, textureGradientY);
				vec4 tapUp = textureGrad(smoothTextures,
					vec3(sampleUv + vec2(0.0, texel.y), float(textureIdx)),
					textureGradientX, textureGradientY);

				safeAlpha = safeAlpha
					&& tapLeft.a >= 0.995 && tapRight.a >= 0.995
					&& tapDown.a >= 0.995 && tapUp.a >= 0.995;
				float weightLeft = stoneTapWeight(smoothCenter, tapLeft);
				float weightRight = stoneTapWeight(smoothCenter, tapRight);
				float weightDown = stoneTapWeight(smoothCenter, tapDown);
				float weightUp = stoneTapWeight(smoothCenter, tapUp);
				float totalWeight = 2.0 + weightLeft + weightRight
					+ weightDown + weightUp;
				vec3 bilateralStone = (
					smoothCenter.rgb * 2.0
					+ tapLeft.rgb * weightLeft + tapRight.rgb * weightRight
					+ tapDown.rgb * weightDown + tapUp.rgb * weightUp)
					/ totalWeight;
				cleanStoneColor = mix(
					smoothCenter.rgb,
					bilateralStone,
					crossBlend);
			}

			if (!safeAlpha)
			{
				cleanStoneColor = textureColor.rgb;
			}
			textureColor.rgb = mix(
				textureColor.rgb,
				cleanStoneColor,
				stoneWallAmount);
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

	// Material palettes reshape only the chroma of classified world surfaces.
	// Classic, unknown materials, actors, water, and UI retain the stock path.
	if (materialPalette != 0 && paletteEligible
		&& fMaterialId >= 1 && fMaterialId <= 7)
	{
		c.rgb = applyMaterialPalette(c.rgb, fMaterialId, materialPalette);
	}


#if COLORBLIND_MODE > 0
    c.rgb = colorblind(c.rgb);
#endif

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
        // Horizontal RuneLite geometry is reoriented into the plugin's virtual
        // +Y-up shading space, so its view vector must use that same convention.
        vec3 viewDirection = normalize(fWorldPos - cameraPosition);
        vec3 sunDirection = normalize(vec3(
            -lightDirection.x,
             lightDirection.y,
            -lightDirection.z));
        float fresnel =
            pow(1.0 - max(dot(waterNormal, viewDirection), 0.0), 3.0);
        float sparkle =
            pow(max(dot(reflect(-sunDirection, waterNormal), viewDirection), 0.0), 96.0);
        float broadHighlight =
            pow(max(dot(reflect(-sunDirection, waterNormal), viewDirection), 0.0), 12.0);
		vec3 waterReflectionShading = reflect(-viewDirection, waterNormal);
		vec3 reflectedSky = texture(
			environmentMap, -waterReflectionShading).rgb;
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
		float skyReflectionAmount = (swampWater ? 0.65 : 1.0)
			* (0.055 + fresnel * 0.26) * min(strength, 1.4);
		c.rgb = mix(
			c.rgb,
			reflectedSky,
			clamp(skyReflectionAmount, 0.0, 0.42));
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
	// Selective shadows and material highlights
	// ====================================================

	// RuneLite's stock texture/vertex color is authoritative. The shadow map is
	// the only effect allowed to darken it, so flat terrain normals can no longer
	// expose tile and triangle boundaries across the whole scene.
	vec3 stockSurface = c.rgb;
	float worldShadowVisibility = 1.0;
	float worldShadowOcclusion = 0.0;
	float configuredShadowOcclusion = 0.0;

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
			// Keep the receiver clear of its own neighboring PCF samples. The
			// conventional light-depth span is 40,000 world units, so this is a
			// restrained 16-unit floor: enough to remove OSRS terrain/roof acne
			// without erasing the much deeper separation of real blockers.
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
			vec2 shadowEdgeDistance = min(
				shadowCoord.xy, vec2(1.0) - shadowCoord.xy);
			float shadowMapConfidence = smoothstep(
				0.0, 0.035,
				min(shadowEdgeDistance.x, shadowEdgeDistance.y));
			worldShadowOcclusion = occlusion * shadowMapConfidence;
			configuredShadowOcclusion = worldShadowOcclusion
				* clamp(shadowStrength, 0.0, 0.80);

			// Preserve the useful part of the mainline lighting model: a shadow
			// removes only the direct celestial contribution while ambient texture
			// detail survives. The mask supplies all diffuse directionality, so no
			// per-triangle normal can relight the stock RuneLite surface.
			vec3 dayShadowTransmission = vec3(0.44, 0.48, 0.55);
			vec3 nightShadowTransmission = vec3(0.60, 0.65, 0.76);
			vec3 shadowTransmission = mix(
				dayShadowTransmission,
				nightShadowTransmission,
				celestialNightFactor);
			vec3 shadowMultiplier = mix(
				vec3(1.0), shadowTransmission, configuredShadowOcclusion);
			c.rgb = stockSurface * shadowMultiplier;
			worldShadowVisibility = dot(
				shadowMultiplier, vec3(0.299, 0.587, 0.114));
		}
	}

	// Reflections are additive and material-local. Their normals never multiply
	// the base scene color, and broad horizontal reflection normals are smoothed
	// toward the sky to keep low-poly ground and roofs visually cohesive.
	if (!waterSurface)
	{
		vec3 normal = stableSurfaceNormal();
		if (abs(normal.y) < 0.10)
		{
			normal = normalize(vec3(normal.x, 0.0, normal.z));
		}
		else if (normal.y < -0.10)
		{
			normal = -normal;
		}

		vec3 actualViewDir = normalize(cameraPosition - fWorldPos);
		bool virtualShadingSpace = abs(normal.y) >= 0.10;
		vec3 viewDir = virtualShadingSpace
			? -actualViewDir : actualViewDir;
		vec3 materialNormal = normal;
		if (dot(materialNormal, viewDir) < 0.0)
		{
			materialNormal = -materialNormal;
		}

		float horizontalSurface = smoothstep(
			0.08, 0.86, abs(materialNormal.y));
		vec3 planarNormal = vec3(
			0.0, materialNormal.y >= 0.0 ? 1.0 : -1.0, 0.0);
		vec3 broadNormal = normalize(mix(
			materialNormal, planarNormal, horizontalSurface * 0.80));
		float hardSurface = fTextureId > 0
			? mix(0.16, 0.82, horizontalSurface)
			: mix(0.05, 0.22, horizontalSurface);
		float NdotV = max(dot(broadNormal, viewDir), 0.0);
		float surfaceFresnel = pow(1.0 - NdotV, 4.0);
		vec3 reflectionShading = reflect(-viewDir, broadNormal);
		vec3 reflectionDirection = virtualShadingSpace
			? -reflectionShading : reflectionShading;
		vec3 reflectedEnvironment = texture(
			environmentMap, reflectionDirection).rgb;

		float surfaceMaximum = max(
			stockSurface.r, max(stockSurface.g, stockSurface.b));
		float surfaceMinimum = min(
			stockSurface.r, min(stockSurface.g, stockSurface.b));
		float neutralMaterial = 1.0 - smoothstep(
			0.12, 0.42, surfaceMaximum - surfaceMinimum);
		float materialGloss = mix(0.38, 1.0, neutralMaterial);
		float environmentMaterial = hardSurface * materialGloss;
		float environmentAmount = environmentMaterial
			* (0.016 + horizontalSurface * 0.065
				+ surfaceFresnel * 0.14);
		// Masonry is a rough diffuse material. Neutral gray must not be inferred
		// as polished merely because it has little chroma.
		environmentAmount *= mix(1.0, 0.16, stoneMatteAmount);
		vec3 environmentHeadroom = clamp(
			vec3(1.0) - c.rgb * 0.70, 0.12, 1.0);
		c.rgb += reflectedEnvironment
			* environmentAmount * environmentHeadroom;

		vec3 materialLightDir = virtualShadingSpace
			? normalize(vec3(
				-lightDirection.x, lightDirection.y, -lightDirection.z))
			: normalize(vec3(
				lightDirection.x, -lightDirection.y, lightDirection.z));
		vec3 halfVectorSum = materialLightDir + viewDir;
		float halfVectorLength = dot(halfVectorSum, halfVectorSum);
		vec3 halfVector = halfVectorLength > 1e-6
			? halfVectorSum * inversesqrt(halfVectorLength)
			: vec3(0.0);
		float broadAlignment = max(dot(broadNormal, halfVector), 0.0);
		float sharpAlignment = max(dot(materialNormal, halfVector), 0.0);
		float broadSpecular = pow(broadAlignment, 18.0);
		float sharpSpecular = pow(sharpAlignment, 72.0);
		float materialNdotL = max(dot(broadNormal, materialLightDir), 0.0);

		// Optional low-poly definition inspired by 117 HD's flat-shading mode.
		// The derivative normal is constant across a triangle, but this effect only
		// adds a bounded lift to light-facing geometry. It never darkens stock color,
		// draws outlines, or changes the texture/vertex-color interpolation.
		float definitionAmount = clamp(polygonDefinition, 0.0, 1.0);
		definitionAmount *= 1.0 - stoneMatteAmount;
		if (definitionAmount > 0.0)
		{
			float faceNdotL = max(dot(materialNormal, materialLightDir), 0.0);
			float faceResponse = smoothstep(0.10, 0.90, faceNdotL);
			float blockerVisibility = 1.0 - worldShadowOcclusion;
			float environmentStrength = mix(
				1.0, 0.45, celestialNightFactor);
			float opaqueGate = smoothstep(0.92, 1.0, c.a);
			float desiredGain = 0.14 * definitionAmount
				* faceResponse * blockerVisibility
				* environmentStrength * opaqueGate;
			float maximumChannel = max(c.r, max(c.g, c.b));
			float headroomGain = (1.0 - maximumChannel)
				/ max(maximumChannel, 1e-4);
			c.rgb *= 1.0 + min(desiredGain, max(headroomGain, 0.0));
		}

		// Shadow strength is an artistic control for diffuse transmission; direct
		// celestial glints still disappear behind a real geometric blocker.
		float directVisibility = (1.0 - worldShadowOcclusion)
			* smoothstep(0.04, 0.28, materialNdotL);
		vec3 directReflectionColor = mix(
			vec3(1.0, 0.88, 0.62),
			vec3(0.52, 0.66, 1.0),
			celestialNightFactor);
		float celestialSpecularStrength = mix(
			1.0, 0.32, celestialNightFactor);
		float directReflection =
			(broadSpecular * 0.080 + sharpSpecular * 0.34)
			* hardSurface * materialGloss
			* directVisibility * celestialSpecularStrength;
		directReflection *= mix(1.0, 0.12, stoneMatteAmount);
		vec3 reflectionHeadroom = clamp(
			vec3(1.0) - c.rgb * 0.45, 0.35, 1.0);
		c.rgb += directReflectionColor
			* directReflection * reflectionHeadroom;
	}

	c.rgb = clamp(c.rgb, 0.0, 1.0);

    // ====================================================
    // Rain-wet surfaces and lightweight ground impacts
    // ====================================================

	if ((weatherMode == 1 || weatherMode == 2) && !waterSurface)
	{
		vec3 wetNormal = stableSurfaceNormal();
		if (wetNormal.y < 0.0)
		{
			wetNormal = -wetNormal;
		}

		float upwardSurface = smoothstep(0.18, 0.72, wetNormal.y);
		float stormWetness = weatherMode == 2 ? 0.94 : 0.68;
		float wetAmount = upwardSurface * stormWetness;
		vec3 wetViewDirection = normalize(fWorldPos - cameraPosition);
		float wetFresnel = pow(
			1.0 - max(dot(wetNormal, wetViewDirection), 0.0),
			1.7);
		vec3 wetLightDirection = normalize(vec3(
			-lightDirection.x,
			 lightDirection.y,
			-lightDirection.z));
		float wetHighlight = pow(max(dot(
			reflect(-wetLightDirection, wetNormal),
			wetViewDirection), 0.0), 12.0);
		vec3 reflectionDirection = reflect(-wetViewDirection, wetNormal);
		vec3 reflectedStormColor = texture(
			environmentMap, -reflectionDirection).rgb;
		reflectedStormColor *= weatherMode == 2 ? 0.82 : 0.92;
		reflectedStormColor += vec3(0.28, 0.38, 0.52) * lightningFlash * 0.65;

		// Wet materials become slightly darker and more reflective without
		// losing their original hue or turning into a gray overlay.
		c.rgb *= 1.0 - wetAmount * 0.14;
		float environmentReflection = wetAmount * (0.10 + wetFresnel * 0.24);
		c.rgb = mix(c.rgb, reflectedStormColor, environmentReflection);
		c.rgb += vec3(0.50, 0.64, 0.78)
			* wetHighlight * wetAmount * (0.14 + lightningFlash * 0.34);

		// Surface-local rings imply raindrops hitting terrain. They are derived
		// from existing fragments, so they follow slopes without impact geometry.
		vec2 impactCell = floor(fWorldPos.xz / 92.0);
		vec2 impactUv = fract(fWorldPos.xz / 92.0) - vec2(0.5);
		float impactSeed = fract(sin(dot(
			impactCell,
			vec2(12.9898, 78.233))) * 43758.5453);
		vec2 impactCenter = vec2(
			fract(impactSeed * 17.13),
			fract(impactSeed * 43.71)) - vec2(0.5);
		float impactPhase = fract(weatherTime * 1.35 + impactSeed);
		float impactRadius = impactPhase * 0.34;
		float impactRing = 1.0 - smoothstep(
			0.018,
			0.050,
			abs(length(impactUv - impactCenter) - impactRadius));
		impactRing *= smoothstep(0.04, 0.15, impactPhase)
			* (1.0 - smoothstep(0.58, 1.0, impactPhase));
		c.rgb += vec3(0.48, 0.63, 0.72)
			* impactRing
			* wetAmount
			* (weatherMode == 2 ? 0.16 : 0.10);
		c.rgb = clamp(c.rgb, 0.0, 1.0);
	}

    // ====================================================
    // CUSTOM: Enhanced colors
    // ====================================================

    if (enhancedColors != 0)
    {
        const vec3 luminanceWeights = vec3(0.2126, 0.7152, 0.0722);
        float sourceLuminance = clamp(
            dot(c.rgb, luminanceWeights), 0.0, 1.0);
        float maximumChannel = max(c.r, max(c.g, c.b));
        float minimumChannel = min(c.r, min(c.g, c.b));
        float channelSpread = maximumChannel - minimumChannel;
        float relativeChroma = channelSpread / max(maximumChannel, 0.10);

        // Raise muted OSRS materials more than colors that are already vivid.
        // This produces "pop" without turning grass, fire, and markers neon.
        float requestedSaturation = clamp(saturation, 0.0, 2.0);
        float vibranceMask = 1.0 - smoothstep(0.18, 0.78, relativeChroma);
        float effectiveSaturation = requestedSaturation <= 1.0
            ? requestedSaturation
            : 1.0 + (requestedSaturation - 1.0)
                * mix(0.15, 1.0, vibranceMask);
        vec3 saturatedColor = vec3(sourceLuminance)
            + (c.rgb - vec3(sourceLuminance)) * effectiveSaturation;

        // Contrast operates on luminance through a smooth toe and shoulder,
        // rather than clipping each RGB channel independently.
        float requestedContrast = clamp(contrast, 0.0, 2.0);
        float targetLuminance;
        float chromaContrastScale = 1.0;
        if (requestedContrast < 1.0)
        {
            targetLuminance = mix(
                0.5, sourceLuminance, requestedContrast);
            chromaContrastScale = requestedContrast;
        }
        else
        {
            float luminanceSquared = sourceLuminance * sourceLuminance;
            float inverseLuminance = 1.0 - sourceLuminance;
            float inverseSquared = inverseLuminance * inverseLuminance;
            float luminanceCurve = luminanceSquared
                / max(luminanceSquared + inverseSquared, 1e-5);
            targetLuminance = mix(
                sourceLuminance,
                luminanceCurve,
                requestedContrast - 1.0);
        }

        vec3 gradedColor = vec3(targetLuminance)
            + (saturatedColor - vec3(sourceLuminance))
                * chromaContrastScale;
        c.rgb = clamp(
            fitColorToGamut(gradedColor, targetLuminance),
            0.0,
            1.0);
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
		vec3 snowNormal = stableSurfaceNormal();
		float upward = smoothstep(0.46, 0.86, abs(snowNormal.y));
		float snowNoise = 0.88 + 0.12 * sin(fWorldPos.x * 0.031 + sin(fWorldPos.z * 0.019));
		float accumulation = upward * snowNoise * (weatherMode == 4 ? 0.82 : 0.58);
		mixedColor = mix(mixedColor, vec3(0.80, 0.87, 0.92), accumulation);
	}
	float weatherDistance = length(cameraPosition - fWorldPos);
	if (weatherMode == 2)
	{
		float stormPocket = 0.5
			+ sin(fWorldPos.x * 0.0052 + weatherTime * 0.17) * 0.24
			+ sin(fWorldPos.z * 0.0061 - weatherTime * 0.13) * 0.18
			+ sin((fWorldPos.x + fWorldPos.z) * 0.0027 + weatherTime * 0.09) * 0.12;
		stormPocket = smoothstep(0.36, 0.72, stormPocket);
		float nearMistBand = smoothstep(64.0, 300.0, weatherDistance)
			* (1.0 - smoothstep(2200.0, 3800.0, weatherDistance));
		float localStormMist = stormPocket * nearMistBand * 0.42;
		float distantStormHaze = smoothstep(900.0, 4600.0, weatherDistance) * 0.32;
		float stormAtmosphere = clamp(localStormMist + distantStormHaze, 0.0, 0.58);
		vec3 stormScattering = mixedColor * vec3(0.68, 0.76, 0.86)
			+ vec3(0.018, 0.025, 0.036);
		mixedColor = mix(mixedColor, stormScattering, stormAtmosphere);
	}
	else
	{
		float weatherMist = 0.0;
		vec3 weatherMistColor = vec3(0.48, 0.55, 0.60);
		if (weatherMode == 1) weatherMist = 0.10;
		if (weatherMode == 3) { weatherMist = 0.14; weatherMistColor = vec3(0.68, 0.75, 0.82); }
		if (weatherMode == 4) { weatherMist = 0.40; weatherMistColor = vec3(0.68, 0.73, 0.78); }
		float weatherHaze = smoothstep(350.0, 3300.0, weatherDistance) * weatherMist;
		mixedColor = mix(mixedColor, weatherMistColor, weatherHaze);
	}

	float lightningVisibility = mix(0.16, 1.0, worldShadowVisibility);
	mixedColor += vec3(0.68, 0.78, 1.0) * lightningFlash
		* lightningVisibility * (0.32 + 0.68 * (1.0 - fFogAmount));

	// Keep this last so classification mistakes are obvious and are not hidden
	// by fog, shadows, weather, or the independent Enhanced Colors controls.
	if (materialDebug != 0)
	{
		mixedColor = paletteEligible
			? materialDebugColor(fMaterialId)
			: mixedColor * 0.08;
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
