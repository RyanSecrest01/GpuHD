/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 * All rights reserved.
 */
package net.runelite.client.plugins.gpu;

public enum MaterialDebugMode
{
	OFF(0),
	MATERIALS(1),
	DETAIL_ELIGIBILITY(2);

	private final int id;

	MaterialDebugMode(int id)
	{
		this.id = id;
	}

	int getId()
	{
		return id;
	}

	@Override
	public String toString()
	{
		switch (this)
		{
			case MATERIALS:
				return "Material classes";
			case DETAIL_ELIGIBILITY:
				return "3D detail material candidates";
			default:
				return "Off";
		}
	}
}
