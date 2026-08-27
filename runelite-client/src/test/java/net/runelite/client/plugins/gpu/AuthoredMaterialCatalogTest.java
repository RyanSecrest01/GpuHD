/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 * All rights reserved.
 */
package net.runelite.client.plugins.gpu;

import java.io.StringReader;
import java.nio.ByteBuffer;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Test;

public class AuthoredMaterialCatalogTest
{
	@Test
	public void bundledCatalogProvidesNeutralFallbackForEverySemanticMaterial()
	{
		AuthoredMaterialCatalog catalog = AuthoredMaterialCatalog.bundled();
		assertEquals(128, catalog.getResolution());
		assertEquals(11, catalog.getNormalLayerCount());
		assertEquals(11, catalog.getPropertyLayerCount());
		assertEquals(AuthoredMaterialCatalog.NEUTRAL_SOURCE,
			catalog.getNormalLayer(0).getSource());
		assertEquals(AuthoredMaterialCatalog.NEUTRAL_SOURCE,
			catalog.getPropertyLayer(0).getSource());

		for (SurfaceMaterial material : SurfaceMaterial.values())
		{
			AuthoredMaterialCatalog.MaterialDefinition fallback =
				catalog.resolve(material, 0);
			assertEquals(material, fallback.getMaterial());
			assertEquals(0, fallback.getVariant());
			assertEquals(0, fallback.getNormalLayer());
			assertEquals(0, fallback.getPropertyLayer());
			assertEquals(0.0f, fallback.getNormalStrength(), 0.0f);
			assertEquals(0.0f, fallback.getHeightScale(), 0.0f);
			assertEquals(fallback, catalog.resolve(material, 7));
		}
	}

	@Test
	public void firstPackResolvesEveryAuthoredSurface()
	{
		AuthoredMaterialCatalog catalog = AuthoredMaterialCatalog.bundled();
		assertDefinition(catalog, SurfaceMaterial.GRASS, 1, 1);
		assertDefinition(catalog, SurfaceMaterial.DIRT, 1, 2);
		assertDefinition(catalog, SurfaceMaterial.SAND, 1, 3);
		assertDefinition(catalog, SurfaceMaterial.STONE, 1, 5);
		assertDefinition(catalog, SurfaceMaterial.STONE, 2, 4);
		assertDefinition(catalog, SurfaceMaterial.WOOD, 1, 6);
		assertDefinition(catalog, SurfaceMaterial.WOOD, 2, 7);
		assertDefinition(catalog, SurfaceMaterial.STONE, 3, 8);
		assertDefinition(catalog, SurfaceMaterial.METAL, 1, 9);
		assertDefinition(catalog, SurfaceMaterial.FOLIAGE, 1, 10);
	}

	@Test
	public void everyAuthoredAssetLoadsAtAtlasResolution()
	{
		AuthoredMaterialCatalog catalog = AuthoredMaterialCatalog.bundled();
		int byteCount = catalog.getResolution() * catalog.getResolution() * 4;
		for (int layer = 1; layer < catalog.getNormalLayerCount(); ++layer)
		{
			assertEquals(byteCount, AuthoredMaterialAtlas.loadPixels(
				catalog.getNormalLayer(layer).getSource(),
				catalog.getResolution(), true).remaining());
			assertEquals(byteCount, AuthoredMaterialAtlas.loadPixels(
				catalog.getPropertyLayer(layer).getSource(),
				catalog.getResolution(), false).remaining());
		}
	}

	@Test
	public void neutralLayerTexelsHaveStableChannelSemantics()
	{
		ByteBuffer normal = AuthoredMaterialAtlas.neutralPixels(1, true);
		assertEquals(128, normal.get() & 0xff);
		assertEquals(128, normal.get() & 0xff);
		assertEquals(255, normal.get() & 0xff);
		assertEquals(255, normal.get() & 0xff);

		ByteBuffer properties = AuthoredMaterialAtlas.neutralPixels(1, false);
		assertEquals(255, properties.get() & 0xff);
		assertEquals(0, properties.get() & 0xff);
		assertEquals(255, properties.get() & 0xff);
		assertEquals(128, properties.get() & 0xff);
	}

	@Test
	public void existingShortLaneCarriesTextureMaterialAndVariantWithoutWidening()
	{
		int packed = SurfaceMaterial.WOOD.packTextureCode(256, 7);
		short gpuLane = (short) packed;
		int shaderValue = gpuLane;
		assertEquals(256, shaderValue & SurfaceMaterial.TEXTURE_MASK);
		assertEquals(SurfaceMaterial.WOOD.getId(),
			shaderValue >> SurfaceMaterial.TEXTURE_BITS & SurfaceMaterial.MATERIAL_MASK);
		assertEquals(7,
			shaderValue >> SurfaceMaterial.VARIANT_SHIFT & SurfaceMaterial.VARIANT_MASK);
		assertTrue(gpuLane < 0);
	}

	@Test
	public void rejectsInvalidAtlasContracts()
	{
		assertInvalid(validCatalog().replace("\"resolution\":128", "\"resolution\":127"));
		assertInvalid(validCatalog().replaceFirst("\"normalLayer\":0", "\"normalLayer\":2"));
		assertInvalid(validCatalog().replaceFirst("\"variant\":0", "\"variant\":8"));
		assertInvalid(validCatalog().replaceFirst("\"source\":\"\\$neutral\"",
			"\"source\":\"normal.png\""));
	}

	private static String validCatalog()
	{
		StringBuilder json = new StringBuilder("{\"version\":1,\"atlas\":{"
			+ "\"resolution\":128,"
			+ "\"normalEncoding\":\"TANGENT_XYZ_UNSIGNED_RGBA8\","
			+ "\"propertyEncoding\":\"R_ROUGHNESS_G_METALLIC_B_AO_A_HEIGHT\"},"
			+ "\"layers\":{\"normal\":[{\"name\":\"neutral\","
			+ "\"source\":\"$neutral\"}],\"properties\":[{\"name\":\"neutral\","
			+ "\"source\":\"$neutral\"}]},\"materials\":[");
		for (int i = 0; i < SurfaceMaterial.values().length; ++i)
		{
			if (i > 0)
			{
				json.append(',');
			}
			json.append("{\"material\":\"")
				.append(SurfaceMaterial.values()[i].name())
				.append("\",\"variant\":0,\"normalLayer\":0,"
					+ "\"propertyLayer\":0,\"baseColorMode\":\"VANILLA_MULTIPLY\","
					+ "\"uvScale\":1,\"normalStrength\":0,\"roughnessFactor\":1,"
					+ "\"metallicFactor\":0,\"aoStrength\":0,\"heightScale\":0}");
		}
		return json.append("]}").toString();
	}

	private static void assertDefinition(AuthoredMaterialCatalog catalog,
		SurfaceMaterial material, int variant, int layer)
	{
		AuthoredMaterialCatalog.MaterialDefinition definition =
			catalog.resolve(material, variant);
		assertEquals(material, definition.getMaterial());
		assertEquals(variant, definition.getVariant());
		assertEquals(layer, definition.getNormalLayer());
		assertEquals(layer, definition.getPropertyLayer());
		assertTrue(definition.getNormalStrength() > 0.0f);
	}

	private static void assertInvalid(String json)
	{
		try
		{
			AuthoredMaterialCatalog.load(new StringReader(json));
			fail("Expected authored material catalog validation to fail");
		}
		catch (IllegalArgumentException expected)
		{
			// Expected validation failure.
		}
	}
}
