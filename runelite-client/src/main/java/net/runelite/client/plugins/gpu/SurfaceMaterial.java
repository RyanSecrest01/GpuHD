/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 * All rights reserved.
 */
package net.runelite.client.plugins.gpu;

/**
 * Stable material identities carried by scene vertices.
 *
 * <p>The IDs are part of the GPU vertex contract. Keep existing values stable
 * when adding materials so saved scenes and shader diagnostics remain
 * intelligible while the classifier evolves.</p>
 */
enum SurfaceMaterial
{
	UNKNOWN(0, -1),
	GRASS(1, 0),
	STONE(2, 1),
	SAND(3, 2),
	DIRT(4, 3),
	WOOD(5, -1),
	METAL(6, -1),
	FOLIAGE(7, -1),
	WATER(8, -1);

	static final int TEXTURE_BITS = 9;
	static final int TEXTURE_MASK = (1 << TEXTURE_BITS) - 1;
	static final int MATERIAL_BITS = 4;
	static final int MATERIAL_MASK = (1 << MATERIAL_BITS) - 1;
	static final int AUTHORED_SLOT_BITS = 3;
	static final int AUTHORED_SLOT_SHIFT = TEXTURE_BITS + MATERIAL_BITS;
	static final int AUTHORED_SLOT_MASK = (1 << AUTHORED_SLOT_BITS) - 1;
	// Compatibility aliases for the authored-material catalog during migration.
	static final int VARIANT_BITS = AUTHORED_SLOT_BITS;
	static final int VARIANT_SHIFT = AUTHORED_SLOT_SHIFT;
	static final int VARIANT_MASK = AUTHORED_SLOT_MASK;

	private final int id;
	private final int detailType;

	SurfaceMaterial(int id, int detailType)
	{
		this.id = id;
		this.detailType = detailType;
	}

	int getId()
	{
		return id;
	}

	int getDetailType()
	{
		return detailType;
	}

	boolean supportsSurfaceDetails()
	{
		return detailType >= 0;
	}

	int getDefaultAuthoredVariant()
	{
		if (this == GRASS)
		{
			return 2;
		}
		if (this == DIRT)
		{
			return 3;
		}
		if (this == STONE)
		{
			return 4;
		}
		return this == UNKNOWN || this == WATER ? 0 : 1;
	}

	int packTextureCode(int textureCode)
	{
		return packTextureCode(textureCode, 0);
	}

	int packTextureCode(int textureCode, int authoredSlot)
	{
		return textureCode & TEXTURE_MASK
			| (id & MATERIAL_MASK) << TEXTURE_BITS
			| (authoredSlot & AUTHORED_SLOT_MASK) << AUTHORED_SLOT_SHIFT;
	}
}
