/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 * All rights reserved.
 */
package net.runelite.client.plugins.gpu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import org.junit.Test;

public class HdTextureRegistryTest
{
	@Test
	public void discoversFilenameMappingsAndOptionalObjectMetadata()
	{
		HdTextureRegistry registry = HdTextureRegistry.get();
		assertNotNull(registry.getVanilla(2));
		assertNotNull(registry.getUnderlay(96));
		assertNotNull(registry.getOverlay(7));
		HdTextureRegistry.ObjectOverride wall = registry.getObject(20084);
		assertNotNull(wall);
		assertEquals(HdTextureRegistry.UvMode.PLANAR, wall.getUvMode());
		assertEquals(1f, wall.getUvScale(), 0f);
	}
}
