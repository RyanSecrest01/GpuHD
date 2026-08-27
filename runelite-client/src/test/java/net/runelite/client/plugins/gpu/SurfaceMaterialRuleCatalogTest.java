/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 * All rights reserved.
 */
package net.runelite.client.plugins.gpu;

import java.io.StringReader;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SurfaceMaterialRuleCatalogTest
{
	@Test
	public void bundledRulesExposeExactProvenance()
	{
		SurfaceMaterialRuleCatalog.Match turf =
			SurfaceMaterialClassifier.classifyTextureMatch(129);
		assertEquals(SurfaceMaterial.GRASS, turf.getMaterial());
		assertEquals("texture:live-cache-turf", turf.getSource());
		assertEquals(1, turf.getAuthoredVariant());
		assertTrue(turf.isExact());

		SurfaceMaterialRuleCatalog.Match unknown =
			SurfaceMaterialClassifier.classifyTextureMatch(57);
		assertEquals(SurfaceMaterial.UNKNOWN, unknown.getMaterial());
		assertEquals("fallback:unknown", unknown.getSource());
		assertFalse(unknown.isExact());
		assertEquals(0, unknown.getAuthoredVariant());
	}

	@Test
	public void rejectsDuplicateTextureAssignments()
	{
		String json = "{\"version\":1,\"textureRules\":["
			+ "{\"name\":\"first\",\"material\":\"WOOD\",\"textures\":[3]},"
			+ "{\"name\":\"second\",\"material\":\"STONE\",\"textures\":[3]}]}";
		assertInvalid(json);
	}

	@Test
	public void rejectsUnknownMaterialsAndInvalidTextureIds()
	{
		String unknownMaterial = "{\"version\":1,\"textureRules\":["
			+ "{\"name\":\"bad\",\"material\":\"GLASS\",\"textures\":[4]}]}";
		assertInvalid(unknownMaterial);

		String invalidTexture = "{\"version\":1,\"textureRules\":["
			+ "{\"name\":\"bad\",\"material\":\"WOOD\",\"textures\":[256]}]}";
		assertInvalid(invalidTexture);
	}

	@Test
	public void rejectsUnsupportedCatalogVersions()
	{
		String json = "{\"version\":2,\"textureRules\":[]}";
		assertInvalid(json);
	}

	@Test
	public void rulesCarryValidatedAuthoredVariants()
	{
		SurfaceMaterialRuleCatalog.Match stone =
			SurfaceMaterialClassifier.classifyTextureMatch(11);
		assertEquals(SurfaceMaterial.STONE, stone.getMaterial());
		assertEquals(2, stone.getAuthoredVariant());

		String invalid = "{\"version\":1,\"textureRules\":["
			+ "{\"name\":\"bad\",\"material\":\"WOOD\","
			+ "\"authoredVariant\":8,\"textures\":[3]}]}";
		assertInvalid(invalid);
	}

	@Test
	public void bundledTerrainRulesOverrideTextureAndExposeProvenance()
	{
		SurfaceMaterialRuleCatalog.Match carpet =
			SurfaceMaterialRuleCatalog.resolveTerrain(129, 1, 13, 3050, 3250, 0);
		assertEquals(SurfaceMaterial.UNKNOWN, carpet.getMaterial());
		assertEquals("terrain:carpet-overlay-veto", carpet.getSource());
		assertTrue(carpet.isExact());

		SurfaceMaterialRuleCatalog.Match grass =
			SurfaceMaterialRuleCatalog.resolveTerrain(-1, 1, 29, 3050, 3250, 0);
		assertEquals(SurfaceMaterial.GRASS, grass.getMaterial());
		assertEquals("terrain:grass-overlay", grass.getSource());
	}

	@Test
	public void orderedContextRulesRespectAreaPlaneAndObjectTexture()
	{
		String json = "{\"version\":1,"
			+ "\"terrainRules\":["
			+ "{\"name\":\"port-grass\",\"material\":\"GRASS\","
			+ "\"layer\":\"OVERLAY\",\"ids\":[7],"
			+ "\"area\":[3000,3200,3100,3300],\"planes\":[0]},"
			+ "{\"name\":\"fallback-sand\",\"material\":\"SAND\","
			+ "\"layer\":\"OVERLAY\",\"ids\":[7]}],"
			+ "\"objectRules\":[{\"name\":\"port-dock\","
			+ "\"material\":\"WOOD\",\"objectIds\":[100,101],"
			+ "\"textureIds\":[5],\"area\":[3000,3200,3100,3300],"
			+ "\"planes\":[0]}],"
			+ "\"textureRules\":[{\"name\":\"stone-texture\","
			+ "\"material\":\"STONE\",\"textures\":[5]}]}";
		SurfaceMaterialRuleCatalog catalog =
			SurfaceMaterialRuleCatalog.load(new StringReader(json));

		assertEquals("terrain:port-grass",
			catalog.resolveTerrainRule(5, 1, 7, 3050, 3250, 0).getSource());
		assertEquals("terrain:fallback-sand",
			catalog.resolveTerrainRule(5, 1, 7, 3050, 3250, 1).getSource());
		assertEquals("texture:stone-texture",
			catalog.resolveTerrainRule(5, 0, 7, 3050, 3250, 0).getSource());
		assertEquals("object:port-dock",
			catalog.resolveObjectRule(5, 100, 3050, 3250, 0).getSource());
		assertEquals("texture:stone-texture",
			catalog.resolveObjectRule(5, 100, 3200, 3250, 0).getSource());
	}

	@Test
	public void rejectsInvalidContextSelectors()
	{
		String badLayer = "{\"version\":1,\"textureRules\":[],"
			+ "\"terrainRules\":[{\"name\":\"bad\",\"material\":\"GRASS\","
			+ "\"layer\":\"BOTH\",\"ids\":[1]}]}";
		assertInvalid(badLayer);

		String badArea = "{\"version\":1,\"textureRules\":[],"
			+ "\"objectRules\":[{\"name\":\"bad\",\"material\":\"WOOD\","
			+ "\"objectIds\":[1],\"area\":[10,10,5,20]}]}";
		assertInvalid(badArea);
	}

	private static void assertInvalid(String json)
	{
		try
		{
			SurfaceMaterialRuleCatalog.load(new StringReader(json));
			fail("Expected invalid material catalog to be rejected");
		}
		catch (IllegalArgumentException expected)
		{
			// Expected validation failure.
		}
	}
}
