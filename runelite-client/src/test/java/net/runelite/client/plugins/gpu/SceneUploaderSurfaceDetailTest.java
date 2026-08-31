/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 * All rights reserved.
 */
package net.runelite.client.plugins.gpu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Constants;
import org.junit.Test;

public class SceneUploaderSurfaceDetailTest
{
	@Test
	public void mapLoaderCoordinatesDoNotRequireAnAttachedWorldView()
	{
		// Runtime regression: the map-loader tile was extended coordinate 1
		// with scene base 1456 and the top-level extended-scene offset 40.
		assertEquals(1417, SceneUploader.sceneToWorldTile(1456, 1, 40));
		assertEquals(3321, SceneUploader.sceneToWorldTile(3360, 1, 40));
		assertEquals(1457, SceneUploader.sceneToWorldTile(1456, 1, 0));
	}

	@Test
	public void detailMaterialsUseTheStableFourTypeContract()
	{
		assertEquals(0, SurfaceMaterial.GRASS.getDetailType());
		assertEquals(1, SurfaceMaterial.STONE.getDetailType());
		assertEquals(2, SurfaceMaterial.SAND.getDetailType());
		assertEquals(3, SurfaceMaterial.DIRT.getDetailType());
		assertTrue(SurfaceMaterial.GRASS.supportsSurfaceDetails());
		assertTrue(SurfaceMaterial.STONE.supportsSurfaceDetails());
		assertTrue(SurfaceMaterial.SAND.supportsSurfaceDetails());
		assertTrue(SurfaceMaterial.DIRT.supportsSurfaceDetails());
		assertFalse(SurfaceMaterial.UNKNOWN.supportsSurfaceDetails());
		assertFalse(SurfaceMaterial.WOOD.supportsSurfaceDetails());
		assertFalse(SurfaceMaterial.METAL.supportsSurfaceDetails());
		assertFalse(SurfaceMaterial.FOLIAGE.supportsSurfaceDetails());
		assertFalse(SurfaceMaterial.WATER.supportsSurfaceDetails());
	}

	@Test
	public void candidateCountsStayInsideThePerTileAnchorBudget()
	{
		int minimumGrass = Integer.MAX_VALUE;
		int maximumGrass = Integer.MIN_VALUE;
		int minimumStone = Integer.MAX_VALUE;
		int maximumStone = Integer.MIN_VALUE;
		for (int tileX = -16; tileX <= 16; ++tileX)
		{
			for (int tileZ = -16; tileZ <= 16; ++tileZ)
			{
				int grass = SceneUploader.surfaceAnchorCount(
					SurfaceMaterial.GRASS.getDetailType(), tileX, tileZ);
				int stone = SceneUploader.surfaceAnchorCount(
					SurfaceMaterial.STONE.getDetailType(), tileX, tileZ);
				int sand = SceneUploader.surfaceAnchorCount(
					SurfaceMaterial.SAND.getDetailType(), tileX, tileZ);
				int dirt = SceneUploader.surfaceAnchorCount(
					SurfaceMaterial.DIRT.getDetailType(), tileX, tileZ);
				minimumGrass = Math.min(minimumGrass, grass);
				maximumGrass = Math.max(maximumGrass, grass);
				minimumStone = Math.min(minimumStone, stone);
				maximumStone = Math.max(maximumStone, stone);
				assertTrue(grass + stone + sand + dirt <= 64);
			}
		}
		assertEquals(36, minimumGrass);
		assertEquals(48, maximumGrass);
		assertEquals(4, minimumStone);
		assertEquals(5, maximumStone);
		assertEquals(2, SceneUploader.surfaceAnchorCount(
			SurfaceMaterial.SAND.getDetailType(), 0, 0));
		assertEquals(2, SceneUploader.surfaceAnchorCount(
			SurfaceMaterial.DIRT.getDetailType(), 0, 0));
	}

	@Test
	public void stratifiedSamplesAreDeterministicUniqueAndRespectAnchorMargins()
	{
		for (SurfaceMaterial material : new SurfaceMaterial[]{
			SurfaceMaterial.GRASS, SurfaceMaterial.STONE,
			SurfaceMaterial.SAND, SurfaceMaterial.DIRT})
		{
			int type = material.getDetailType();
			int count = SceneUploader.surfaceAnchorCount(type, 3200, 3201);
			float margin = material == SurfaceMaterial.GRASS ? 0.07f : 0.11f;
			Set<Long> samples = new HashSet<>();
			for (int anchor = 0; anchor < count; ++anchor)
			{
				float x = SceneUploader.surfaceSampleCoordinate(
					type, anchor, true, 3200, 3201, 0);
				float z = SceneUploader.surfaceSampleCoordinate(
					type, anchor, false, 3200, 3201, 0);
				assertEquals(x, SceneUploader.surfaceSampleCoordinate(
					type, anchor, true, 3200, 3201, 0), 0.0f);
				assertEquals(z, SceneUploader.surfaceSampleCoordinate(
					type, anchor, false, 3200, 3201, 0), 0.0f);
				assertTrue(x >= margin && x <= 1.0f - margin);
				assertTrue(z >= margin && z <= 1.0f - margin);
				long sample = (long) Float.floatToRawIntBits(x) << 32
					| Float.floatToRawIntBits(z) & 0xffffffffL;
				assertTrue(samples.add(sample));
			}
		}
	}

	@Test
	public void detailSlopesBecomeStricterForOpaqueGroundScatter()
	{
		assertEquals(72, SceneUploader.surfaceDetailMaxSlope(
			SurfaceMaterial.GRASS.getDetailType()));
		assertEquals(48, SceneUploader.surfaceDetailMaxSlope(
			SurfaceMaterial.STONE.getDetailType()));
		assertEquals(32, SceneUploader.surfaceDetailMaxSlope(
			SurfaceMaterial.SAND.getDetailType()));
		assertEquals(48, SceneUploader.surfaceDetailMaxSlope(
			SurfaceMaterial.DIRT.getDetailType()));
	}

	@Test
	public void geometrySeedRetainsItsFullVariationRange()
	{
		float minimum = 1.0f;
		float maximum = 0.0f;
		for (int tileX = 3180; tileX < 3220; ++tileX)
		{
			for (int tileZ = 3180; tileZ < 3220; ++tileZ)
			{
				for (int type = 0; type < 4; ++type)
				{
					float seed = SceneUploader.surfaceGeometrySeed(
						tileX, tileZ, 0, type, 0);
					assertTrue(seed >= 0.0f && seed < 1.0f);
					minimum = Math.min(minimum, seed);
					maximum = Math.max(maximum, seed);
				}
			}
		}
		assertTrue(minimum < 0.01f);
		assertTrue(maximum > 0.99f);
	}

	@Test
	public void terrainDefinitionIdsDecodeTheScenePlusOneContract()
	{
		assertEquals(-1, SceneUploader.decodeTerrainDefinitionId((short) 0));
		assertEquals(0, SceneUploader.decodeTerrainDefinitionId((short) 1));
		assertEquals(29, SceneUploader.decodeTerrainDefinitionId((short) 30));
		assertEquals(32767,
			SceneUploader.decodeTerrainDefinitionId((short) 0x8000));
	}

	@Test
	public void paintPrefersItsOverlayDefinitionWhenPresent()
	{
		assertEquals(-1, SceneUploader.paintTerrainLayer(-1, -1));
		assertEquals(0, SceneUploader.paintTerrainLayer(7, -1));
		assertEquals(1, SceneUploader.paintTerrainLayer(7, 29));
		assertEquals(1, SceneUploader.paintTerrainLayer(-1, 29));
	}

	@Test
	public void proceduralDetailsRequireUncoveredPlaneZeroGround()
	{
		assertTrue(SceneUploader.surfaceDetailTileEligible(
			0, 0, 0, 0, 0, 0, false));
		assertFalse(SceneUploader.surfaceDetailTileEligible(
			1, 0, 0, 0, 0, 0, false));
		assertFalse(SceneUploader.surfaceDetailTileEligible(
			0, 1, 0, 0, 0, 0, false));
		assertFalse(SceneUploader.surfaceDetailTileEligible(
			0, 0, 1, 0, 0, 0, false));
		assertFalse(SceneUploader.surfaceDetailTileEligible(
			0, 0, 0, Constants.TILE_FLAG_UNDER_ROOF, 0, 0, false));
		assertFalse(SceneUploader.surfaceDetailTileEligible(
			0, 0, 0, 0, Constants.TILE_FLAG_BRIDGE, 0, false));
		assertFalse(SceneUploader.surfaceDetailTileEligible(
			0, 0, 0, 0, 0, 7, false));
		assertFalse(SceneUploader.surfaceDetailTileEligible(
			0, 0, 0, 0, 0, 0, true));
	}

	@Test
	public void detailEligibilitySeparatesNaturalGroundFromConstructedSurfaces()
	{
		int grass = SurfaceMaterial.GRASS.getDetailType();
		int pebble = SurfaceMaterial.STONE.getDetailType();
		int sand = SurfaceMaterial.SAND.getDetailType();
		int dirt = SurfaceMaterial.DIRT.getDetailType();

		assertEquals(grass, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.GRASS, -1, 0, 46, -1));
		assertEquals(grass, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.GRASS, -1, 0, 64, -1));
		assertEquals(-1, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.GRASS, -1, 0, 45, -1));
		assertEquals(-1, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.GRASS, -1, 1, 7, 29));
		assertEquals(-1, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.GRASS, 129, 1, 7, 10));
		assertEquals(-1, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.GRASS, 129, -1, -1, -1));
		assertEquals(-1, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.GRASS, 129, 1, 7, 13));
		assertEquals(-1, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.GRASS, -1, 1, 7, 163));

		assertEquals(pebble, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.STONE, -1, 0, 64, -1));
		assertEquals(-1, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.STONE, 11, 0, 64, -1));
		assertEquals(-1, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.STONE, -1, 1, 64, 10));

		assertEquals(sand, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.SAND, 35, 0, 129, -1));
		assertEquals(sand, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.SAND, -1, 1, 129, 25));
		assertEquals(sand, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.SAND, -1, 1, 129, 26));
		assertEquals(sand, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.SAND, -1, 1, 129, 76));
		assertEquals(-1, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.SAND, -1, 1, 129, 10));

		assertEquals(dirt, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.DIRT, -1, 0, 63, -1));
		assertEquals(-1, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.DIRT, 22, 0, 63, -1));
		assertEquals(-1, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.DIRT, -1, 1, 63, 14));
		assertEquals(-1, SceneUploader.eligibleSurfaceDetailType(
			SurfaceMaterial.UNKNOWN, -1, 0, 7, -1));
	}

	@Test
	public void shapedDetailsRespectMaterialBoundariesAndFootprintSizes()
	{
		assertEquals(4.0f, SceneUploader.surfaceDetailEdgeClearance(
			SurfaceMaterial.GRASS.getDetailType()), 0.0f);
		assertEquals(14.0f, SceneUploader.surfaceDetailEdgeClearance(
			SurfaceMaterial.STONE.getDetailType()), 0.0f);
		assertEquals(11.0f, SceneUploader.surfaceDetailEdgeClearance(
			SurfaceMaterial.SAND.getDetailType()), 0.0f);
		assertEquals(12.0f, SceneUploader.surfaceDetailEdgeClearance(
			SurfaceMaterial.DIRT.getDetailType()), 0.0f);

		int[] faceX = {0, 1};
		int[] faceY = {1, 2};
		int[] faceZ = {3, 3};
		int[] vertexX = {0, 128, 128, 0};
		int[] vertexZ = {0, 0, 128, 128};
		boolean[] sampleable = {true, true};
		int stone = SurfaceMaterial.STONE.getDetailType();
		int grass = SurfaceMaterial.GRASS.getDetailType();

		assertFalse(SceneUploader.surfaceDetailFootprintClear(
			63.0f, 63.0f, 0, stone,
			new int[]{stone, grass}, sampleable,
			faceX, faceY, faceZ, vertexX, vertexZ));
		assertTrue(SceneUploader.surfaceDetailFootprintClear(
			63.0f, 63.0f, 0, stone,
			new int[]{stone, stone}, sampleable,
			faceX, faceY, faceZ, vertexX, vertexZ));
		assertTrue(SceneUploader.surfaceDetailFootprintClear(
			20.0f, 20.0f, 0, stone,
			new int[]{stone, grass}, sampleable,
			faceX, faceY, faceZ, vertexX, vertexZ));
		assertTrue(SceneUploader.surfaceDetailFootprintClear(
			60.0f, 60.0f, 0, grass,
			new int[]{grass, stone}, sampleable,
			faceX, faceY, faceZ, vertexX, vertexZ));
	}

	@Test
	public void densitySelectionIsStableWithoutTruncatingGeometryVariation()
	{
		float seed = SceneUploader.surfaceGeometrySeed(3200, 3201, 0, 2, 1);
		assertEquals(
			GpuPlugin.surfaceDetailSelection(seed, 2),
			GpuPlugin.surfaceDetailSelection(seed, 2),
			0.0f);

		float acceptedMinimum = 1.0f;
		float acceptedMaximum = 0.0f;
		for (int tileX = 3000; tileX < 3060; ++tileX)
		{
			for (int tileZ = 3000; tileZ < 3060; ++tileZ)
			{
				float geometrySeed = SceneUploader.surfaceGeometrySeed(
					tileX, tileZ, 0, 1, 0);
				float selection = GpuPlugin.surfaceDetailSelection(geometrySeed, 1);
				assertTrue(selection >= 0.0f && selection < 1.0f);
				if (selection <= 0.40f)
				{
					acceptedMinimum = Math.min(acceptedMinimum, geometrySeed);
					acceptedMaximum = Math.max(acceptedMaximum, geometrySeed);
				}
			}
		}
		assertTrue(acceptedMinimum < 0.05f);
		assertTrue(acceptedMaximum > 0.95f);
	}
}
