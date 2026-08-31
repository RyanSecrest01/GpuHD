/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 */
package net.runelite.client.plugins.gpu;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class VegetationLodTest
{
	@Test
	public void treeLodUsesConfiguredBands()
	{
		assertEquals(0, GpuPlugin.vegetationLod(0.0f, 8, 16, 28));
		assertEquals(0, GpuPlugin.vegetationLod(7.99f, 8, 16, 28));
		assertEquals(1, GpuPlugin.vegetationLod(8.0f, 8, 16, 28));
		assertEquals(2, GpuPlugin.vegetationLod(16.0f, 8, 16, 28));
		assertEquals(3, GpuPlugin.vegetationLod(28.0f, 8, 16, 28));
		assertEquals(3, GpuPlugin.vegetationLod(90.0f, 8, 16, 28));
	}

	@Test
	public void grassDensityFallsSmoothlyAndStopsAtFarBand()
	{
		assertEquals(1.0f,
			GpuPlugin.grassDensityMultiplier(8.0f, 8, 16, 28), 0.0001f);
		assertEquals(0.5f,
			GpuPlugin.grassDensityMultiplier(16.0f, 8, 16, 28), 0.0001f);
		float far = GpuPlugin.grassDensityMultiplier(24.0f, 8, 16, 28);
		assertEquals(0.0556f, far, 0.001f);
		assertEquals(0.0f,
			GpuPlugin.grassDensityMultiplier(28.0f, 8, 16, 28), 0.0001f);
	}
}
