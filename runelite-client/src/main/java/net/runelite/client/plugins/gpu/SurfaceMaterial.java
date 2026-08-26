/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 * All rights reserved.
 */
package net.runelite.client.plugins.gpu;

/**
 * Stable material identities carried in the unused high bits of the stock
 * texture-code vertex field.
 */
enum SurfaceMaterial
{
	UNKNOWN(0),
	GRASS(1),
	STONE(2),
	SAND(3),
	DIRT(4),
	WOOD(5),
	METAL(6),
	FOLIAGE(7),
	WATER(8);

	static final int TEXTURE_BITS = 9;
	static final int TEXTURE_MASK = (1 << TEXTURE_BITS) - 1;
	static final int MATERIAL_BITS = 4;
	static final int MATERIAL_MASK = (1 << MATERIAL_BITS) - 1;
	static final int TERRAIN_FLAG = 1 << 8;
	static final int WORLD_SCENERY_FLAG = 1 << 9;

	private final int id;

	SurfaceMaterial(int id)
	{
		this.id = id;
	}

	int getId()
	{
		return id;
	}

	int packTextureCode(int textureCode)
	{
		return textureCode & TEXTURE_MASK
			| (id & MATERIAL_MASK) << TEXTURE_BITS;
	}
}
