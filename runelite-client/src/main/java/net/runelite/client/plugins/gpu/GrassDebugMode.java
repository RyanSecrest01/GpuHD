/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 */
package net.runelite.client.plugins.gpu;

public enum GrassDebugMode
{
	OFF,
	MAGENTA_TEST,
	GREEN_BLADE,
	GREEN_CLUMP,
	GREEN_INSTANCED_LINE,
	GREEN_TERRAIN,
	GLB_CLUMP,
	GLB_LINE,
	GLB_TERRAIN,
	GLB_TERRAIN_ALL,
	GLB_CLUMP_NO_DEPTH,
	GLB_ROOT_MARKERS;

	@Override
	public String toString()
	{
		return this == MAGENTA_TEST ? "Magenta test quad"
			: this == GREEN_BLADE ? "Green segmented blade"
				: this == GREEN_CLUMP ? "Green 8-blade clump"
					: this == GREEN_INSTANCED_LINE ? "Green 10-instance line"
					: this == GREEN_TERRAIN ? "Green terrain placement"
					: this == GLB_CLUMP ? "GLB clump proof"
							: this == GLB_LINE ? "GLB 10-clump line"
								: this == GLB_TERRAIN ? "GLB one terrain tile"
									: this == GLB_TERRAIN_ALL ? "GLB all grass terrain"
										: this == GLB_CLUMP_NO_DEPTH ? "GLB clump no depth"
											: this == GLB_ROOT_MARKERS ? "GLB terrain + root markers" : "Off";
	}
}
