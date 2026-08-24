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

// ========================================================
// Directional lighting
// ========================================================

uniform int dynamicLighting;
uniform float lightIntensity;
uniform float ambientLight;
uniform vec3 lightDirection;
uniform vec3 cameraPosition;
uniform int enhancedWater;
uniform float waterStrength;
uniform float waterOpacity;
uniform float lightningFlash;
uniform int weatherMode;
uniform float celestialRayStrength;
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


void main()
{
    vec4 c;
    bool waterSurface = false;
    bool swampWater = false;

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
    // CUSTOM: Directional surface lighting
    // ====================================================

    if (dynamicLighting != 0)
    {
        /*
         * Derive a flat surface normal from the triangle's
         * world-space position.
         *
         * Unlike our old lighting formula, we will NOT
         * aggressively brighten the texture.
         *
         * The sun-facing side stays close to its normal
         * RuneLite brightness.
         *
         * Surfaces facing away become darker.
         */

        vec3 dx =
            dFdx(fWorldPos);

        vec3 dy =
            dFdy(fWorldPos);

        vec3 normal =
            normalize(
                cross(dx, dy)
            );

        /*
         * Vertical RuneLite walls have normal.y very close to zero. Never use
         * that noisy sign to orient the full normal or adjacent pixels can
         * alternate between opposite lighting directions.
         */
        if (abs(normal.y) < 0.10)
        {
            normal = normalize(vec3(normal.x, 0.0, normal.z));
        }
        else if (normal.y < -0.10)
        {
            normal = -normal;
        }

		// Screen-space derivative normals use the opposite horizontal orientation
		// from the shadow camera's world-space basis. Preserve the upward component
		// while correcting X/Z so visible diffuse light agrees with cast shadows.
		vec3 correctedLightDir = normalize(vec3(
			-lightDirection.x,
			 lightDirection.y,
			-lightDirection.z));
		// Vertical RuneScape wall faces retain the native world-axis convention,
		// while derivative normals on terrain and foliage require corrected X/Z.
		vec3 lightDir = abs(normal.y) < 0.10
			? normalize(lightDirection)
			: correctedLightDir;

        /*
         * How directly this surface faces the sun.
         *
         * 0 = facing away
         * 1 = facing directly toward sun
         */
		float NdotL = max(dot(normal, lightDir), 0.0);

        /*
         * Smooth the transition.
         *
         * This avoids very harsh triangle-to-triangle
         * changes on RuneScape's low-poly terrain.
         */
        float diffuse =
            smoothstep(
                0.05,
                0.95,
                NdotL
            );

        /*
         * Convert our intensity slider into a sane range.
         *
         * 100 = full directional effect
         * 50  = half effect
         * Values above 100 add a stronger sun-facing highlight.
         */
        float strength =
            clamp(
                lightIntensity * 1.75,
                0.0,
                2.0
            );

        /*
         * ambientLight controls the darkest possible face.
         *
         * Example:
         *
         * ambient = 0.65
         *
         * shadow side = 65% brightness
         * sun side    = 100% brightness
         *
         * This preserves texture detail much better than
         * boosting lit surfaces above their original color.
         */
        float shadowVisibility = 1.0;

        if (shadowsEnabled != 0)
        {
            vec4 lightSpacePos =
                shadowLightProj * vec4(fWorldPos, 1.0);
            vec3 shadowCoord =
                lightSpacePos.xyz / lightSpacePos.w;
            shadowCoord = shadowCoord * 0.5 + 0.5;

            if (
                shadowCoord.x >= 0.0 && shadowCoord.x <= 1.0 &&
                shadowCoord.y >= 0.0 && shadowCoord.y <= 1.0 &&
                shadowCoord.z >= 0.0 && shadowCoord.z <= 1.0
            )
            {
                vec2 texelSize =
                    1.0 / vec2(textureSize(shadowMap, 0));
                float bias =
                    max(0.0025 * (1.0 - NdotL), 0.0005);
                float occlusion = 0.0;

				float receiverDistance = length(cameraPosition - fWorldPos);
				float filterRadius = mix(
					0.70,
					1.35,
					smoothstep(1152.0, 6144.0, receiverDistance));

				for (int x = 0; x < 3; ++x)
				{
					for (int y = 0; y < 3; ++y)
					{
						vec2 filterOffset =
							(vec2(float(x), float(y)) - vec2(1.0))
							* texelSize
							* filterRadius;
                        float closestDepth =
                            texture(
                                shadowMap,
                                shadowCoord.xy + filterOffset
                            ).r;
                        occlusion +=
                            shadowCoord.z - bias > closestDepth ? 1.0 : 0.0;
                    }
                }

				occlusion /= 9.0;
                shadowVisibility =
                    1.0 - occlusion * clamp(shadowStrength, 0.0, 0.8);
            }
        }

        /* Shadows remove direct sun only; ambient light remains intact. */
        float directionalShade =
            ambientLight
            + (1.0 - ambientLight) * diffuse * shadowVisibility;

        /*
         * Blend between normal GPU appearance and
         * directional shading.
         */
        float finalShade =
            mix(
                1.0,
                directionalShade,
                min(strength, 1.0)
            )
            + diffuse * shadowVisibility * strength * 0.20;

		c.rgb *=
			finalShade;

		// Lightweight view-dependent reflection. Textured horizontal hard surfaces
		// receive a restrained highlight; steep card-like foliage remains subdued.
		if (!waterSurface)
		{
			vec3 viewDir = normalize(cameraPosition - fWorldPos);
			float reflectedLight = pow(
				max(dot(reflect(-lightDir, normal), viewDir), 0.0), 52.0);
			float hardSurface = fTextureId > 0
				? mix(0.16, 0.62, smoothstep(0.08, 0.82, abs(normal.y)))
				: 0.12;
			c.rgb += vec3(1.0, 0.86, 0.62)
				* reflectedLight * hardSurface * shadowVisibility
				* min(strength, 1.35) * 0.22;
		}

        c.rgb =
            clamp(
                c.rgb,
                0.0,
                1.0
            );
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

    // Camera-centered celestial scattering. Unlike the cubemap glare, this is
    // evaluated on world fragments, so the cone stays aimed into the playable
    // scene from the player's camera instead of sliding across the sky.
	vec3 cameraRay = normalize(fWorldPos - cameraPosition);
	vec3 scatteringDirection = normalize(vec3(
		-lightDirection.x, lightDirection.y, -lightDirection.z));
	float celestialAlignment = max(dot(cameraRay, scatteringDirection), 0.0);
	float celestialDistance = length(fWorldPos - cameraPosition);
	float celestialDepth = smoothstep(180.0, 2400.0, celestialDistance);
	float sunCone = pow(celestialAlignment, 10.0) * celestialDepth;
	float moonCone = pow(celestialAlignment, 18.0) * celestialDepth;
	vec3 celestialColor = mix(vec3(1.0, 0.72, 0.38), vec3(0.38, 0.50, 0.78), celestialNightFactor);
	float celestialCone = mix(sunCone * 0.18, moonCone * 0.11, celestialNightFactor)
		* clamp(celestialRayStrength, 0.0, 2.0);
	c.rgb += celestialColor * celestialCone * (0.35 + 0.65 * (1.0 - fFogAmount));
	c.rgb = clamp(c.rgb, 0.0, 1.0);

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
		float upward = smoothstep(0.46, 0.86, abs(snowNormal.y));
		float snowNoise = 0.88 + 0.12 * sin(fWorldPos.x * 0.031 + sin(fWorldPos.z * 0.019));
		float accumulation = upward * snowNoise * (weatherMode == 4 ? 0.82 : 0.58);
		mixedColor = mix(mixedColor, vec3(0.80, 0.87, 0.92), accumulation);
	}
	float weatherDistance = length(cameraPosition - fWorldPos);
	float weatherMist = 0.0;
	vec3 weatherMistColor = vec3(0.48, 0.55, 0.60);
	if (weatherMode == 1) weatherMist = 0.10;
	if (weatherMode == 2) { weatherMist = 0.82; weatherMistColor = vec3(0.40, 0.42, 0.43); }
	if (weatherMode == 3) { weatherMist = 0.14; weatherMistColor = vec3(0.68, 0.75, 0.82); }
	if (weatherMode == 4) { weatherMist = 0.40; weatherMistColor = vec3(0.68, 0.73, 0.78); }
	float weatherHaze = smoothstep(
		weatherMode == 2 ? 48.0 : 350.0,
		weatherMode == 2 ? 1050.0 : 3300.0,
		weatherDistance) * weatherMist;
	if (weatherMode == 2)
	{
		weatherHaze = clamp(weatherHaze + 0.14, 0.0, 0.94);
	}
	mixedColor = mix(mixedColor, weatherMistColor, weatherHaze);
	mixedColor += vec3(0.68, 0.78, 1.0) * lightningFlash
		* (0.32 + 0.68 * (1.0 - fFogAmount));

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
