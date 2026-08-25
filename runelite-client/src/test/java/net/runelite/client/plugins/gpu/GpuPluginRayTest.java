/*
 * Copyright (c) 2026
 * All rights reserved.
 */
package net.runelite.client.plugins.gpu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GpuPluginRayTest
{
	private static final float[] TEST_PROJECTION = {
		1, 0, 0, 0,
		0, 1, 0, 0,
		0, 0, 0, 1,
		0, 0, 0, 0,
	};
	private static final int VIEWPORT_WIDTH = 1920;
	private static final int VIEWPORT_HEIGHT = 1080;
	private static final float EPSILON = 1e-5f;

	@Test
	public void capsWideViewportWithoutChangingAspect()
	{
		assertEquals(512, GpuPlugin.rayBufferDimension(1920, 1920));
		assertEquals(288, GpuPlugin.rayBufferDimension(1080, 1920));
	}

	@Test
	public void keepsSmallViewportAtNativeResolution()
	{
		assertEquals(480, GpuPlugin.rayBufferDimension(480, 480));
		assertEquals(320, GpuPlugin.rayBufferDimension(320, 480));
	}

	@Test
	public void preservesPortraitAndUltrawideAspect()
	{
		assertEquals(288, GpuPlugin.rayBufferDimension(1080, 1920));
		assertEquals(512, GpuPlugin.rayBufferDimension(1920, 1920));
		assertEquals(512, GpuPlugin.rayBufferDimension(3440, 3440));
		assertEquals(214, GpuPlugin.rayBufferDimension(1440, 3440));
	}

	@Test
	public void clampsInvalidViewportToOnePixel()
	{
		assertEquals(1, GpuPlugin.rayBufferDimension(0, 0));
	}

	@Test
	public void preservesExactOnscreenCelestialPosition()
	{
		float[] uv = new float[2];
		assertTrue(GpuPlugin.projectCelestialRaySource(
			TEST_PROJECTION,
			new float[]{0.5f, 0.25f, 1.0f},
			VIEWPORT_WIDTH,
			VIEWPORT_HEIGHT,
			uv));
		assertEquals(0.75f, uv[0], EPSILON);
		assertEquals(0.625f, uv[1], EPSILON);
	}

	@Test
	public void pinsFrontAndBehindSourcesToSameDirectionalEdge()
	{
		float expectedRightEdge = 1.0f + 27.0f / VIEWPORT_WIDTH;
		float[] frontUv = new float[2];
		float[] behindUv = new float[2];
		assertTrue(GpuPlugin.projectCelestialRaySource(
			TEST_PROJECTION,
			new float[]{100.0f, 0.0f, 1.0f},
			VIEWPORT_WIDTH,
			VIEWPORT_HEIGHT,
			frontUv));
		assertTrue(GpuPlugin.projectCelestialRaySource(
			TEST_PROJECTION,
			new float[]{1.0f, 0.0f, -1.0f},
			VIEWPORT_WIDTH,
			VIEWPORT_HEIGHT,
			behindUv));
		assertEquals(expectedRightEdge, frontUv[0], 1e-4f);
		assertEquals(expectedRightEdge, behindUv[0], EPSILON);
		assertEquals(0.5f, frontUv[1], EPSILON);
		assertEquals(0.5f, behindUv[1], EPSILON);
	}

	@Test
	public void givesDirectlyBehindSourceAStableTopEdgeFallback()
	{
		float[] uv = new float[2];
		assertTrue(GpuPlugin.projectCelestialRaySource(
			TEST_PROJECTION,
			new float[]{0.0f, 0.0f, -1.0f},
			VIEWPORT_WIDTH,
			VIEWPORT_HEIGHT,
			uv));
		assertEquals(0.5f, uv[0], EPSILON);
		assertEquals(1.0f + 27.0f / VIEWPORT_HEIGHT, uv[1], EPSILON);
	}

	@Test
	public void crossesViewportEdgeWithoutJumping()
	{
		float[] insideUv = new float[2];
		float[] outsideUv = new float[2];
		assertTrue(GpuPlugin.projectCelestialRaySource(
			TEST_PROJECTION,
			new float[]{0.9999f, 0.0f, 1.0f},
			VIEWPORT_WIDTH,
			VIEWPORT_HEIGHT,
			insideUv));
		assertTrue(GpuPlugin.projectCelestialRaySource(
			TEST_PROJECTION,
			new float[]{1.0001f, 0.0f, 1.0f},
			VIEWPORT_WIDTH,
			VIEWPORT_HEIGHT,
			outsideUv));
		assertTrue(Math.abs(outsideUv[0] - insideUv[0]) < 0.001f);
	}

	@Test
	public void keepsPhysicalMarginEqualAcrossAxes()
	{
		float[] rightUv = new float[2];
		float[] topUv = new float[2];
		assertTrue(GpuPlugin.projectCelestialRaySource(
			TEST_PROJECTION,
			new float[]{1.0f, 0.0f, -1.0f},
			VIEWPORT_WIDTH,
			VIEWPORT_HEIGHT,
			rightUv));
		assertTrue(GpuPlugin.projectCelestialRaySource(
			TEST_PROJECTION,
			new float[]{0.0f, 1.0f, -1.0f},
			VIEWPORT_WIDTH,
			VIEWPORT_HEIGHT,
			topUv));
		assertEquals(27.0f, (rightUv[0] - 1.0f) * VIEWPORT_WIDTH, 0.001f);
		assertEquals(27.0f, (topUv[1] - 1.0f) * VIEWPORT_HEIGHT, 0.001f);
	}

	@Test
	public void ignoresProjectionTranslationForDirectionalSource()
	{
		float[] translatedProjection = TEST_PROJECTION.clone();
		translatedProjection[12] = 4000.0f;
		translatedProjection[13] = -9000.0f;
		translatedProjection[14] = 1200.0f;
		float[] baseUv = new float[2];
		float[] translatedUv = new float[2];
		float[] direction = {0.3f, -0.2f, 1.0f};
		assertTrue(GpuPlugin.projectCelestialRaySource(
			TEST_PROJECTION,
			direction,
			VIEWPORT_WIDTH,
			VIEWPORT_HEIGHT,
			baseUv));
		assertTrue(GpuPlugin.projectCelestialRaySource(
			translatedProjection,
			direction,
			VIEWPORT_WIDTH,
			VIEWPORT_HEIGHT,
			translatedUv));
		assertEquals(baseUv[0], translatedUv[0], EPSILON);
		assertEquals(baseUv[1], translatedUv[1], EPSILON);
	}

	@Test
	public void rejectsOnlyDegenerateDirection()
	{
		assertFalse(GpuPlugin.projectCelestialRaySource(
			TEST_PROJECTION,
			new float[]{0.0f, 0.0f, 0.0f},
			VIEWPORT_WIDTH,
			VIEWPORT_HEIGHT,
			new float[2]));
	}
}
