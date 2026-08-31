/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 * All rights reserved.
 */
package net.runelite.client.plugins.gpu;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class ModelUploaderPlanarUvTest
{
	@Test
	public void horizontalFacesUseXZAtOneTileDensity()
	{
		float[] u = new float[3], v = new float[3];
		ModelUploader.computePlanarFaceUvs(0, 0, 0, 128, 0, 0, 0, 0, 128, 1, u, v);
		assertArrayEquals(new float[]{0, 1, 0}, u, 1e-6f);
		assertArrayEquals(new float[]{0, 0, -1}, v, 1e-6f);
	}

	@Test
	public void xFacingWallsUseZYWithoutTriangleLocalRestart()
	{
		float[] u = new float[3], v = new float[3];
		ModelUploader.computePlanarFaceUvs(64, 0, 128, 64, -128, 128, 64, 0, 256, 1, u, v);
		assertEquals(1, Math.abs(u[0]), 1e-6f);
		assertEquals(2, Math.abs(u[2]), 1e-6f);
		assertEquals(1, v[1], 1e-6f);
	}

	@Test
	public void zFacingWallsUseXYAndHonorScale()
	{
		float[] u = new float[3], v = new float[3];
		ModelUploader.computePlanarFaceUvs(128, 0, 64, 256, 0, 64, 128, -128, 64, .5f, u, v);
		assertEquals(.5f, Math.abs(u[0]), 1e-6f);
		assertEquals(1f, Math.abs(u[1]), 1e-6f);
		assertEquals(.5f, v[2], 1e-6f);
	}
}
