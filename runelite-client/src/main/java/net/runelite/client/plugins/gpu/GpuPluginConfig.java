/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.gpu;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import static net.runelite.client.plugins.gpu.GpuPlugin.MAX_DISTANCE;
import static net.runelite.client.plugins.gpu.GpuPlugin.MAX_FOG_DEPTH;
import net.runelite.client.plugins.gpu.config.AntiAliasingMode;
import net.runelite.client.plugins.gpu.config.ColorBlindMode;
import net.runelite.client.plugins.gpu.config.UIScalingMode;

@ConfigGroup(GpuPluginConfig.GROUP)
public interface GpuPluginConfig extends Config
{
	String GROUP = "gpu";

	@Range(
		max = MAX_DISTANCE
	)
	@ConfigItem(
		keyName = "drawDistance",
		name = "Draw distance",
		description = "Draw distance.",
		position = 1
	)
	default int drawDistance()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "hideUnrelatedMaps",
		name = "Hide unrelated maps",
		description = "Hide unrelated map areas you shouldn't see.",
		position = 2
	)
	default boolean hideUnrelatedMaps()
	{
		return true;
	}

	@Range(
		max = 5
	)
	@ConfigItem(
		keyName = "expandedMapLoadingChunks",
		name = "Extended map loading",
		description = "Extra map area to load, in 8 tile chunks.",
		position = 1
	)
	default int expandedMapLoadingZones()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "smoothBanding",
		name = "Remove color banding",
		description = "Smooths out the color banding that is present in the CPU renderer.",
		position = 2
	)
	default boolean smoothBanding()
	{
		return true;
	}

	@ConfigItem(
		keyName = "antiAliasingMode",
		name = "Anti aliasing",
		description = "Configures the anti-aliasing mode.",
		position = 3
	)
	default AntiAliasingMode antiAliasingMode()
	{
		return AntiAliasingMode.MSAA_2;
	}

	@ConfigItem(
		keyName = "uiScalingMode",
		name = "UI scaling mode",
		description = "Sampling function to use for the UI in stretched mode.",
		position = 4
	)
	default UIScalingMode uiScalingMode()
	{
		return UIScalingMode.HYBRID;
	}

	@Range(
		max = MAX_FOG_DEPTH
	)
	@ConfigItem(
		keyName = "fogDepth",
		name = "Fog depth",
		description = "Distance from the scene edge the fog starts.",
		position = 5
	)
	default int fogDepth()
	{
		return 0;
	}

	@ConfigItem(
			keyName = "customFog",
			name = "Custom fog",
			description = "Use custom fog settings with custom skies.",
			position = 6
	)
	default boolean customFog()
	{
		return true;
	}

	@Range(
			min = 0,
			max = 100
	)

	@ConfigItem(
			keyName = "customFogBrightness",
			name = "Fog brightness",
			description = "Brightness of the custom fog color.",
			position = 7
	)
	default int customFogBrightness()
	{
		return 90;
	}

	@Range(
			min = 0,
			max = 100
	)

	@ConfigItem(
			keyName = "customFogStrength",
			name = "Fog thickness",
			description = "How aggressively fog hides the distant scene edge.",
			position = 8
	)
	default int customFogStrength()
	{
		return 70;
	}

	@ConfigItem(
			keyName = "skyMode",
			name = "Custom Sky",
			description = "Select the custom skybox to render.",
			position = 6
	)
	default SkyMode skyMode()
	{
		return SkyMode.COSMIC;
	}

	@ConfigItem(
		keyName = "dayNightCycle",
		name = "Day/night cycle",
		description = "Automatically cycles the environment through day, sunset, night, and dawn.",
		position = 7
	)
	default boolean dayNightCycle()
	{
		return false;
	}

	@Range(min = 2, max = 120)
	@ConfigItem(
		keyName = "dayNightCycleMinutes",
		name = "Cycle minutes",
		description = "Length of one complete day and night cycle.",
		position = 8
	)
	default int dayNightCycleMinutes()
	{
		return 20;
	}

	@Range(
		min = 0,
		max = 16
	)
	@ConfigItem(
		keyName = "anisotropicFilteringLevel",
		name = "Anisotropic filtering",
		description = "Configures the anisotropic filtering level.",
		position = 7
	)
	default int anisotropicFilteringLevel()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "colorBlindMode",
		name = "Colorblindness correction",
		description = "Adjusts colors to account for colorblindness.",
		position = 8
	)
	default ColorBlindMode colorBlindMode()
	{
		return ColorBlindMode.NONE;
	}

	@Range(
		min = 0,
		max = 100
	)
	@ConfigItem(
		keyName = "colorBlindIntensity",
		name = "Colorblindness intensity",
		description = "Strength of the colorblindness correction effect.",
		position = 9
	)
	default int colorBlindIntensity()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "brightTextures",
		name = "Bright textures",
		description = "Use old texture lighting method which results in brighter game textures.",
		position = 10
	)
	default boolean brightTextures()
	{
		return false;
	}

	@ConfigItem(
		keyName = "unlockFps",
		name = "Unlock FPS",
		description = "Removes the 50 FPS cap for camera movement.",
		position = 11
	)
	default boolean unlockFps()
	{
		return true;
	}

	enum SyncMode
	{
		OFF,
		ON,
		ADAPTIVE
	}

	@ConfigItem(
		keyName = "vsyncMode",
		name = "Vsync mode",
		description = "Method to synchronize frame rate with refresh rate.",
		position = 12
	)
	default SyncMode syncMode()
	{
		return SyncMode.OFF;
	}

	@ConfigItem(
		keyName = "fpsTarget",
		name = "FPS target",
		description = "Target FPS when 'Unlock FPS' is enabled and 'Vsync mode' is off.",
		position = 13
	)
	@Range(
		min = 1,
		max = 999
	)
	default int fpsTarget()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "removeVertexSnapping",
		name = "Remove vertex snapping",
		description = "Removes vertex snapping from most animations.",
		position = 14
	)
	default boolean removeVertexSnapping()
	{
		return true;
	}

	@ConfigItem(
		keyName = "numThreads",
		name = "Threads",
		description = "Number of render threads to use.",
		position = 20
	)
	@Range(min = 0, max = 15)
	default int numThreads()
	{
		return 3;
	}

	@ConfigItem(
			keyName = "enhancedColors",
			name = "Enhanced colors",
			description = "Adds responsive saturation and contrast to the GPU renderer.",
			position = 15
	)
	default boolean enhancedColors()
	{
		return true;
	}

	@Range(
			min = 50,
			max = 150
	)
	@ConfigItem(
			keyName = "saturation",
			name = "Saturation",
			description = "Controls world color saturation.",
			position = 16
	)
	default int saturation()
	{
		return 112;
	}

	@Range(
			min = 50,
			max = 150
	)
	@ConfigItem(
			keyName = "contrast",
			name = "Contrast",
			description = "Controls world color contrast.",
			position = 17
	)
	default int contrast()
	{
		return 104;
	}

	@ConfigItem(
			keyName = "dynamicShadows",
			name = "Cast shadows",
			description = "Adds selective cast shadows aligned with the visible sun or moon without relighting the whole world.",
			position = 21
	)
	default boolean dynamicShadows()
	{
		return true;
	}

	@Range(
			min = 0,
			max = 80
	)
	@ConfigItem(
			keyName = "shadowStrength",
			name = "Shadow strength",
			description = "Controls the contrast of selective sun and moon cast shadows.",
			position = 22
	)
	default int shadowStrength()
	{
		return 40;
	}

	@ConfigItem(
			keyName = "godRays",
			name = "Sun rays",
			description = "Adds selective depth-occluded sunlight shafts through the playable scene.",
			position = 23
	)
	default boolean godRays()
	{
		return true;
	}

	@Range(
			min = 0,
			max = 200
	)
	@ConfigItem(
			keyName = "godRaysStrength",
			name = "Sun ray strength",
			description = "Controls selective sunlight shaft intensity.",
			position = 24
	)
	default int godRaysStrength()
	{
		return 120;
	}

	@ConfigItem(
		keyName = "moonRays",
		name = "Moon rays",
		description = "Adds a separate, softer moonlight shaft profile at night.",
		position = 25
	)
	default boolean moonRays()
	{
		return false;
	}

	@Range(
		min = 0,
		max = 200
	)
	@ConfigItem(
		keyName = "moonRaysStrength",
		name = "Moon ray strength",
		description = "Controls nighttime moonlight shaft intensity independently from sunlight.",
		position = 26
	)
	default int moonRaysStrength()
	{
		return 45;
	}

	@Range(
		min = 0,
		max = 200
	)
	@ConfigItem(
		keyName = "celestialGlareStrength",
		name = "Celestial glare",
		description = "Controls the compact glow around the visible sun or moon independently from light shafts.",
		position = 27
	)
	default int celestialGlareStrength()
	{
		return 180;
	}

	@ConfigItem(
			keyName = "shadowDebug",
			name = "Show shadow map",
			description = "Displays the shadow depth texture for projection diagnostics.",
			position = 28
	)
	default boolean shadowDebug()
	{
		return false;
	}

	@ConfigItem(
			keyName = "enhancedWater",
			name = "Enhanced water",
			description = "Renders water after the opaque world with transparency, depth absorption, refraction, waves, and sky reflection.",
			position = 29
	)
	default boolean enhancedWater()
	{
		return true;
	}

	@Range(
			min = 0,
			max = 200
	)
	@ConfigItem(
			keyName = "waterStrength",
			name = "Water strength",
			description = "Controls the strength of enhanced water shading.",
			position = 30
	)
	default int waterStrength()
	{
		return 100;
	}

	@Range(
		min = 10,
			max = 100
	)
	@ConfigItem(
			keyName = "waterOpacity",
			name = "Water opacity",
			description = "Controls water transparency where underlying scene geometry is available.",
			position = 31
	)
	default int waterOpacity()
	{
		return 58;
	}

	@ConfigItem(keyName = "weatherMode", name = "Weather", description = "Selects world-space precipitation and its matching environment.", position = 32)
	default WeatherMode weatherMode()
	{
		return WeatherMode.CLEAR;
	}

	@Range(min = 10, max = 100)
	@ConfigItem(keyName = "weatherDensity", name = "Weather density", description = "Controls precipitation density. Storm modes intentionally exceed normal plugin density.", position = 33)
	default int weatherDensity()
	{
		return 80;
	}

	@Range(min = -100, max = 100)
	@ConfigItem(keyName = "weatherWind", name = "Weather wind", description = "Controls horizontal precipitation drift.", position = 34)
	default int weatherWind()
	{
		return 25;
	}

	@Range(min = 0, max = 200)
	@ConfigItem(keyName = "stormAtmosphereDensity", name = "Storm atmosphere", description = "Controls rolling world-space fog volume density during storms and blizzards. Set to 0 to disable it.", position = 35)
	default int stormAtmosphereDensity()
	{
		return 130;
	}

	@ConfigItem(keyName = "weatherSounds", name = "Weather sounds", description = "Plays looping rain and synchronized thunder for weather modes.", position = 36)
	default boolean weatherSounds()
	{
		return true;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(keyName = "weatherVolume", name = "Weather volume", description = "Controls rain and thunder volume.", position = 37)
	default int weatherVolume()
	{
		return 55;
	}

	@ConfigItem(keyName = "sunPosition", name = "Sun position", description = "Selects morning, overhead noon, or opposite evening sunlight. The day/night cycle overrides this.", position = 38)
	default SunPosition sunPosition()
	{
		return SunPosition.MORNING;
	}

	@ConfigItem(keyName = "moonPosition", name = "Moon position", description = "Selects the nighttime moon and moonlight direction.", position = 41)
	default MoonPosition moonPosition()
	{
		return MoonPosition.SOUTHEAST;
	}

	@ConfigItem(
		keyName = "flowingGrass",
		name = "Flowing grass",
		description = "Adds experimental world-space grass clumps with environment-driven wind.",
		position = 42
	)
	default boolean flowingGrass()
	{
		return true;
	}

	@Range(min = 10, max = 100)
	@ConfigItem(
		keyName = "grassDensity",
		name = "Grass density",
		description = "Controls how many eligible terrain grass clumps are rendered.",
		position = 43
	)
	default int grassDensity()
	{
		return 62;
	}

	@Range(min = 0, max = 200)
	@ConfigItem(
		keyName = "grassWindStrength",
		name = "Grass wind",
		description = "Controls grass sway and weather gust response.",
		position = 44
	)
	default int grassWindStrength()
	{
		return 100;
	}

	@Range(min = 6, max = 18)
	@ConfigItem(
		keyName = "grassDistance",
		name = "Grass distance",
		description = "Maximum grass render distance around the player, in tiles.",
		position = 45
	)
	default int grassDistance()
	{
		return 12;
	}

	@ConfigItem(
		keyName = "terrainTextureBlending",
		name = "Terrain texture blending",
		description = "Experimentally feathers compatible ground textures and colors across tile borders.",
		position = 46
	)
	default boolean terrainTextureBlending()
	{
		return true;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "terrainBlendStrength",
		name = "Terrain blend strength",
		description = "Controls how strongly compatible terrain is blended across tile borders.",
		position = 47
	)
	default int terrainBlendStrength()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "terrainDetail",
		name = "3D ground details",
		description = "Adds real, light-reactive pebbles, sand fragments, and dirt clods to eligible ground materials.",
		position = 48
	)
	default boolean terrainDetail()
	{
		return true;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "terrainDetailStrength",
		name = "Ground detail density",
		description = "Controls how many eligible pebble, sand, and dirt detail clusters are rendered.",
		position = 49
	)
	default int terrainDetailStrength()
	{
		return 65;
	}

	@Range(min = 6, max = 18)
	@ConfigItem(
		keyName = "terrainDetailDistance",
		name = "Ground detail distance",
		description = "Maximum 3D ground-detail render distance around the player, in tiles.",
		position = 50
	)
	default int terrainDetailDistance()
	{
		return 12;
	}

	@ConfigItem(
		keyName = "materialDebugMode",
		name = "Material debug",
		description = "Visualizes explicit material tags or grass, stone, sand, and dirt candidates for 3D details.",
		position = 51
	)
	default MaterialDebugMode materialDebugMode()
	{
		return MaterialDebugMode.OFF;
	}

	@ConfigItem(
		keyName = "materialInspector",
		name = "Material inspector",
		description = "Shows the hovered tile's material, source rule, terrain IDs, textures, and object IDs for renderer development.",
		position = 52
	)
	default boolean materialInspector()
	{
		return false;
	}

	@ConfigItem(
		keyName = "materialLighting",
		name = "Material lighting",
		description = "Lets tagged grass, stone, sand, wood, metal, foliage, and dirt respond naturally to the active sun or moon.",
		position = 53
	)
	default boolean materialLighting()
	{
		return true;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "materialLightingStrength",
		name = "Material response",
		description = "Controls the strength of material-specific highlights and surface response without relighting untagged geometry.",
		position = 54
	)
	default int materialLightingStrength()
	{
		return 70;
	}

	@ConfigItem(
		keyName = "wetSurfaces",
		name = "Wet surfaces",
		description = "Lets rain and storms darken and add restrained reflections to eligible ground materials.",
		position = 55
	)
	default boolean wetSurfaces()
	{
		return true;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "wetSurfaceStrength",
		name = "Wet surface strength",
		description = "Controls the rain-driven darkening and reflected-light response of wet surfaces.",
		position = 56
	)
	default int wetSurfaceStrength()
	{
		return 60;
	}
}
