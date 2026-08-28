/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 */
package net.runelite.client.plugins.gpu;

public enum SurfaceIdDebugMode
{
	OFF,
	OBJECT_IDS,
	WALL_IDS,
	ROOFTOP_IDS,
	OVERLAY_IDS,
	UNDERLAY_IDS;

	@Override
	public String toString()
	{
		switch (this)
		{
			case OBJECT_IDS:
				return "Object IDs";
			case WALL_IDS:
				return "Wall IDs";
			case ROOFTOP_IDS:
				return "Rooftop IDs";
			case OVERLAY_IDS:
				return "Ground overlay IDs";
			case UNDERLAY_IDS:
				return "Ground underlay IDs";
			default:
				return "Off";
		}
	}
}
