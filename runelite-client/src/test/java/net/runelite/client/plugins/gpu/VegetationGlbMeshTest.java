/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 */
package net.runelite.client.plugins.gpu;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VegetationGlbMeshTest
{
	private static final String GRASS_ASSET =
		"/net/runelite/client/plugins/gpu/glb/grass green by Steve B - 8q6D0D_SuBE.glb";
	private static final String OAK_ASSET =
		"/net/runelite/client/plugins/gpu/vegetation/trees/oak/oak_lod0.glb";

	@Test
	public void sharedLoaderPreservesRootedIndexedGeometryAndMaterials() throws Exception
	{
		assertRootedMesh(VegetationGlbMesh.load(GRASS_ASSET));
	}

	@Test
	public void productionOakLoadsAsRootedMaterialBatches() throws Exception
	{
		TreeReplacementRegistry registry = TreeReplacementRegistry.load();
		TreeReplacementRegistry.Definition oak = registry.getDefinitions().get(1);
		VegetationGlbMesh mesh = VegetationGlbMesh.load(OAK_ASSET,
			oak.materialOverrides);
		assertRootedMesh(mesh);
		assertEquals(1502, mesh.sourceNodeCount);
		assertEquals(3003, mesh.sourcePrimitiveCount);
		assertEquals(3, mesh.primitives.length);
		assertTrue(mesh.materials.length > 1);
		VegetationGlbMesh.Material leaf = material(mesh, "leaf-removebg-preview");
		VegetationGlbMesh.Material bark = material(mesh,
			"texturehaven/Rough_Wood/1k__JPG__2.8_MB");
		VegetationGlbMesh.Material twig = material(mesh, "twig");
		assertEquals(0.45f, leaf.alphaCutoff, 0.0001f);
		assertEquals(1.0f, leaf.windResponse, 0.0001f);
		assertTrue(hasTransparentPixel(leaf.image));
		assertEquals(0.0f, bark.alphaCutoff, 0.0001f);
		assertEquals(0.08f, bark.windResponse, 0.0001f);
		assertEquals(0.0f, twig.alphaCutoff, 0.0001f);
		assertEquals(0.42f, twig.windResponse, 0.0001f);
		assertTrue(bark.image != null);
		assertTrue(twig.image != null);
		assertTrue(mesh.materials[0].image != null);
		assertEquals(0.08f, mesh.materials[0].windResponse, 0.0001f);
		for (VegetationGlbMesh.Material material : new VegetationGlbMesh.Material[]{
			leaf, bark, twig})
		{
			assertEquals(1, primitiveCount(mesh, material));
		}
	}

	private static int primitiveCount(VegetationGlbMesh mesh,
		VegetationGlbMesh.Material material)
	{
		int materialIndex = -1;
		for (int index = 0; index < mesh.materials.length; ++index)
		{
			if (mesh.materials[index] == material)
			{
				materialIndex = index;
				break;
			}
		}
		int count = 0;
		for (VegetationGlbMesh.Primitive primitive : mesh.primitives)
		{
			if (primitive.material == materialIndex)
			{
				++count;
			}
		}
		return count;
	}

	private static VegetationGlbMesh.Material material(VegetationGlbMesh mesh,
		String name)
	{
		for (VegetationGlbMesh.Material material : mesh.materials)
		{
			if (name.equals(material.name))
			{
				return material;
			}
		}
		throw new AssertionError("Missing material: " + name);
	}

	private static boolean hasTransparentPixel(VegetationGlbMesh.Image image)
	{
		for (int offset = 3; offset < image.rgba.limit(); offset += 4)
		{
			if ((image.rgba.get(offset) & 0xff) < 255)
			{
				return true;
			}
		}
		return false;
	}

	private static void assertRootedMesh(VegetationGlbMesh mesh)
	{
		assertTrue(mesh.vertices.length > 0);
		assertTrue(mesh.indices.length > 0);
		assertTrue(mesh.primitives.length > 0);
		assertTrue(mesh.materials.length > 0);
		assertEquals(0.0f, mesh.normalizedMax[1], 0.0001f);
		assertEquals(-64.0f, mesh.normalizedMin[1], 0.0001f);
		int primitiveIndices = 0;
		for (VegetationGlbMesh.Primitive primitive : mesh.primitives)
		{
			primitiveIndices += primitive.indexCount;
		}
		assertEquals(mesh.indices.length, primitiveIndices);
	}
}
