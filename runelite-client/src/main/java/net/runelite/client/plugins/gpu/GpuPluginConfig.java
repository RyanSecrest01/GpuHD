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
			keyName = "dynamicLighting",
			name = "Dynamic lighting",
			description = "Adds lightweight directional lighting to the world.",
			position = 18
	)
	default boolean dynamicLighting()
	{
		return true;
	}

	@Range(
			min = 0,
			max = 200
	)
	@ConfigItem(
			keyName = "lightIntensity",
			name = "Light intensity",
			description = "Strength of directional lighting.",
			position = 19
	)
	default int lightIntensity()
	{
		return 55;
	}

	@Range(
			min = 0,
			max = 150
	)
	@ConfigItem(
			keyName = "ambientLight",
			name = "Ambient light",
			description = "Base brightness applied to all surfaces.",
			position = 20
	)
	default int ambientLight()
	{
		return 80;
	}

	@ConfigItem(
			keyName = "dynamicShadows",
			name = "Dynamic shadows",
			description = "Experimental soft directional shadows. Requires dynamic lighting.",
			position = 21
	)
	default boolean dynamicShadows()
	{
		return false;
	}

	@Range(
			min = 0,
			max = 60
	)
	@ConfigItem(
			keyName = "shadowStrength",
			name = "Shadow strength",
			description = "Controls how much direct sunlight is removed inside shadows.",
			position = 22
	)
	default int shadowStrength()
	{
		return 35;
	}

	@ConfigItem(
			keyName = "godRays",
			name = "Celestial rays",
			description = "Adds sun glare by day and a soft dusty moonlight cone at night.",
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
			name = "Celestial ray strength",
			description = "Controls sun glare and moonlight atmosphere intensity.",
			position = 24
	)
	default int godRaysStrength()
	{
		return 120;
	}

	@ConfigItem(
			keyName = "shadowDebug",
			name = "Show shadow map",
			description = "Displays the shadow depth texture for projection diagnostics.",
			position = 25
	)
	default boolean shadowDebug()
	{
		return false;
	}

	@ConfigItem(
			keyName = "enhancedWater",
			name = "Enhanced water",
			description = "Adds layered motion, environment tint, Fresnel highlights, and sunlight sparkle to water.",
			position = 26
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
			position = 27
	)
	default int waterStrength()
	{
		return 100;
	}

	@Range(
			min = 40,
			max = 100
	)
	@ConfigItem(
			keyName = "waterOpacity",
			name = "Water opacity",
			description = "Controls water transparency where underlying scene geometry is available.",
			position = 28
	)
	default int waterOpacity()
	{
		return 82;
	}

	@ConfigItem(keyName = "weatherMode", name = "Weather", description = "Selects world-space precipitation and its matching environment.", position = 29)
	default WeatherMode weatherMode()
	{
		return WeatherMode.CLEAR;
	}

	@Range(min = 10, max = 100)
	@ConfigItem(keyName = "weatherDensity", name = "Weather density", description = "Controls precipitation density. Storm modes intentionally exceed normal plugin density.", position = 30)
	default int weatherDensity()
	{
		return 80;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(keyName = "stormFogDensity", name = "Storm fog density", description = "Controls storm view-distance fog and volumetric mist density.", position = 31)
	default int stormFogDensity()
	{
		return 78;
	}

	@ConfigItem(keyName = "stormSkyMode", name = "Storm sky", description = "Selects a brighter daytime storm or a dark nighttime storm.", position = 32)
	default StormSkyMode stormSkyMode()
	{
		return StormSkyMode.DAY;
	}

	@Range(min = -100, max = 100)
	@ConfigItem(keyName = "weatherWind", name = "Weather wind", description = "Controls horizontal precipitation drift.", position = 33)
	default int weatherWind()
	{
		return 25;
	}

	@ConfigItem(keyName = "weatherSounds", name = "Weather sounds", description = "Plays looping rain and synchronized thunder for weather modes.", position = 34)
	default boolean weatherSounds()
	{
		return true;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(keyName = "weatherVolume", name = "Weather volume", description = "Controls rain and thunder volume.", position = 35)
	default int weatherVolume()
	{
		return 55;
	}

	@ConfigItem(keyName = "sunPosition", name = "Sun position", description = "Selects morning, overhead noon, or opposite evening sunlight. The day/night cycle overrides this.", position = 36)
	default SunPosition sunPosition()
	{
		return SunPosition.MORNING;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(keyName = "nightDirectLight", name = "Night direct light", description = "Moonlight strength during night and cosmic stages.", position = 37)
	default int nightDirectLight()
	{
		return 28;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(keyName = "nightAmbientLight", name = "Night ambient light", description = "Ambient world brightness during night and cosmic stages.", position = 38)
	default int nightAmbientLight()
	{
		return 42;
	}

	@ConfigItem(keyName = "moonPosition", name = "Moon position", description = "Selects the nighttime moon and moonlight direction.", position = 39)
	default MoonPosition moonPosition()
	{
		return MoonPosition.SOUTHEAST;
	}
}
