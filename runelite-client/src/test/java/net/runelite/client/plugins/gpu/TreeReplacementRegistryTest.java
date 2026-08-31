/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 */
package net.runelite.client.plugins.gpu;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TreeReplacementRegistryTest
{
	@Test
	public void packagedCatalogHasStableEmptyAuthoringSlots() throws Exception
	{
		TreeReplacementRegistry registry = TreeReplacementRegistry.load();
		List<TreeReplacementRegistry.Definition> definitions = registry.getDefinitions();
		assertEquals(5, definitions.size());
		assertEquals("common-tree", definitions.get(0).name);
		assertEquals("common-tree/tree_lod0.glb", definitions.get(0).model);
		assertEquals("/net/runelite/client/plugins/gpu/vegetation/trees/"
			+ "common-tree/tree_lod0.glb", definitions.get(0).resourcePath(0));
		assertEquals("/net/runelite/client/plugins/gpu/vegetation/trees/"
			+ "common-tree/tree_lod1.glb", definitions.get(0).resourcePath(1));
		assertEquals("/net/runelite/client/plugins/gpu/vegetation/trees/"
			+ "oak/oak_lod3.glb", definitions.get(1).resourcePath(3));
		assertEquals("oak", definitions.get(1).name);
		assertEquals("oak/oak_lod0.glb", definitions.get(1).model);
		assertEquals(18.0f, definitions.get(1).scale, 0.0001f);
		assertEquals(1.0f, definitions.get(1).windStrength, 0.0001f);
		assertTrue(!definitions.get(1).randomYaw);
		assertEquals(4, definitions.get(1).auxiliaryRadius);
		assertEquals(4, definitions.get(1).materialOverrides.size());
		assertEquals("oak_leaf.png", definitions.get(1).materialOverrides
			.get("leaf-removebg-preview").texture);
		assertEquals(0.45f, definitions.get(1).materialOverrides
			.get("leaf-removebg-preview").alphaCutoff, 0.0001f);
		assertEquals(1.0f, definitions.get(1).materialOverrides
			.get("leaf-removebg-preview").windResponse, 0.0001f);
		assertEquals(0.08f, definitions.get(1).materialOverrides
			.get("texturehaven/Rough_Wood/1k__JPG__2.8_MB").windResponse, 0.0001f);
		assertEquals(0.42f, definitions.get(1).materialOverrides
			.get("twig").windResponse, 0.0001f);
		assertEquals("dead/dead-tree.glb", definitions.get(4).model);
		assertTrue(definitions.stream().allMatch(definition -> definition.scale > 0.0f));
		assertNull(registry.resolve(1276, 3220, 3218, 0));
		assertNull(registry.resolve(10820, 3218, 3205, 0));
		assertNull(registry.resolve(1278, 3220, 3218, 0));
		assertNull(registry.resolveAuxiliary(4735));
		registry.setActive(definitions.get(1), true);
		assertEquals(definitions.get(1), registry.resolve(10820, 3218, 3205, 0));
		assertEquals(definitions.get(1), registry.resolve(1278, 3220, 3218, 0));
		assertEquals(definitions.get(1), registry.resolveAuxiliary(4735));
		assertEquals(definitions.get(1), registry.resolveAuxiliary(4736));
	}
}
