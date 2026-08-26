/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 * All rights reserved.
 */
package net.runelite.client.plugins.gpu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SurfaceMaterialClassifierTest
{
	@Test
	public void packedTexturePreservesAllTextureCodesAndMaterialIds()
	{
		assertTrue(TextureManager.TEXTURE_COUNT <= SurfaceMaterial.TEXTURE_MASK);
		for (SurfaceMaterial material : SurfaceMaterial.values())
		{
			assertTrue(material.getId() <= SurfaceMaterial.MATERIAL_MASK);
			for (int textureCode = 0; textureCode <= 256; ++textureCode)
			{
				int packed = material.packTextureCode(textureCode);
				assertTrue(packed >= 0 && packed <= Short.MAX_VALUE);
				assertEquals(textureCode, packed & SurfaceMaterial.TEXTURE_MASK);
				assertEquals(material.getId(),
					packed >> SurfaceMaterial.TEXTURE_BITS & SurfaceMaterial.MATERIAL_MASK);
			}
		}
	}

	@Test
	public void paletteFlagsStayOutsideShorelineBitsAndSignedShortRange()
	{
		int flags = SurfaceMaterial.TERRAIN_FLAG | SurfaceMaterial.WORLD_SCENERY_FLAG;
		assertEquals(0, flags & 0xff);
		assertEquals(0, SurfaceMaterial.TERRAIN_FLAG & SurfaceMaterial.WORLD_SCENERY_FLAG);
		assertTrue(flags <= Short.MAX_VALUE);
	}

	@Test
	public void exactTextureClassesRemainConservative()
	{
		assertEquals(SurfaceMaterial.WATER,
			SurfaceMaterialClassifier.classifyTexture(130));
		assertEquals(SurfaceMaterial.WATER,
			SurfaceMaterialClassifier.classifyTexture(189));
		assertEquals(SurfaceMaterial.GRASS,
			SurfaceMaterialClassifier.classifyTexture(129));
		assertEquals(SurfaceMaterial.FOLIAGE,
			SurfaceMaterialClassifier.classifyTexture(190));
		assertEquals(SurfaceMaterial.WOOD,
			SurfaceMaterialClassifier.classifyTexture(16));
		assertEquals(SurfaceMaterial.STONE,
			SurfaceMaterialClassifier.classifyTexture(11));
		assertEquals(SurfaceMaterial.METAL,
			SurfaceMaterialClassifier.classifyTexture(12));
		assertEquals(SurfaceMaterial.UNKNOWN,
			SurfaceMaterialClassifier.classifyTexture(95));
		assertEquals(SurfaceMaterial.UNKNOWN,
			SurfaceMaterialClassifier.classifyTexture(57));
	}

	@Test
	public void waterTextureBoundariesMatchTheLiveCache()
	{
		for (int texture : new int[]{1, 24, 25, 130, 189, 208})
		{
			assertTrue(SurfaceMaterialClassifier.isWaterTexture(texture));
		}
		assertFalse(SurfaceMaterialClassifier.isWaterTexture(129));
		assertFalse(SurfaceMaterialClassifier.isWaterTexture(190));
	}

	@Test
	public void hslFallbackClassificationIsBoundedAndConservative()
	{
		assertEquals(SurfaceMaterial.GRASS,
			SurfaceMaterialClassifier.classifyPackedHsl(hsl(13, 2, 12)));
		assertEquals(SurfaceMaterial.GRASS,
			SurfaceMaterialClassifier.classifyPackedHsl(hsl(28, 7, 116)));
		assertEquals(SurfaceMaterial.STONE,
			SurfaceMaterialClassifier.classifyPackedHsl(hsl(40, 1, 20)));
		assertEquals(SurfaceMaterial.SAND,
			SurfaceMaterialClassifier.classifyPackedHsl(hsl(4, 2, 52)));
		assertEquals(SurfaceMaterial.DIRT,
			SurfaceMaterialClassifier.classifyPackedHsl(hsl(2, 2, 10)));
		assertEquals(SurfaceMaterial.UNKNOWN,
			SurfaceMaterialClassifier.classifyPackedHsl(hsl(40, 2, 60)));
		assertEquals(SurfaceMaterial.UNKNOWN,
			SurfaceMaterialClassifier.classifyPackedHsl(-1));
		assertEquals(SurfaceMaterial.UNKNOWN,
			SurfaceMaterialClassifier.classifyPackedHsl(0x10000));
		assertEquals(SurfaceMaterial.UNKNOWN,
			SurfaceMaterialClassifier.classifyPackedHsl(
				SurfaceMaterialClassifier.INVISIBLE_HSL));

		int grass = hsl(18, 4, 60);
		int stone = hsl(40, 1, 60);
		assertEquals(SurfaceMaterial.GRASS,
			SurfaceMaterialClassifier.classifyConsensus(
				new int[]{grass, grass, stone}, 2));
		assertEquals(SurfaceMaterial.UNKNOWN,
			SurfaceMaterialClassifier.classifyConsensus(
				new int[]{grass, stone}, 2));
	}

	@Test
	public void everyShapedTileFaceHasTheExpectedLayer()
	{
		String[] layers = {
			"1111", "01", "001", "001", "011", "011",
			"0011", "0001", "0111", "000111", "111000", "110000"
		};
		for (int shape = 1; shape <= layers.length; ++shape)
		{
			String expected = layers[shape - 1];
			for (int face = 0; face < expected.length(); ++face)
			{
				assertEquals(expected.charAt(face) - '0',
					SurfaceMaterialClassifier.terrainLayerForFace(shape, face));
			}
			assertEquals(-1, SurfaceMaterialClassifier.terrainLayerForFace(
				shape, expected.length()));
		}
		assertEquals(-1, SurfaceMaterialClassifier.terrainLayerForFace(0, 0));
		assertEquals(-1, SurfaceMaterialClassifier.terrainLayerForFace(13, 0));
	}

	private static int hsl(int hue, int saturation, int luminance)
	{
		return hue << 10 | saturation << 7 | luminance;
	}
}
