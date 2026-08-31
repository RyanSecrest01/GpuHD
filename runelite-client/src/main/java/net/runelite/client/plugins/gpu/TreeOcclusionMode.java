/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 */
package net.runelite.client.plugins.gpu;

public enum TreeOcclusionMode
{
	OFF("Off", 0.0f, 0.0f, 0.0f, 8.0f),
	BALANCED("Balanced", 384.0f, 224.0f, 0.90f, 10.0f),
	STRONG("Strong", 512.0f, 320.0f, 0.97f, 13.0f),
	PLAYER_PRIORITY("Player Priority", 640.0f, 448.0f, 1.0f, 16.0f);

	private final String name;
	final float bubbleRadius;
	final float sightConeWidth;
	final float maximumFade;
	final float fadeSpeed;

	TreeOcclusionMode(String name, float bubbleRadius, float sightConeWidth,
		float maximumFade, float fadeSpeed)
	{
		this.name = name;
		this.bubbleRadius = bubbleRadius;
		this.sightConeWidth = sightConeWidth;
		this.maximumFade = maximumFade;
		this.fadeSpeed = fadeSpeed;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
