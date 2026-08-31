/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 */
package net.runelite.client.plugins.gpu;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TreeOcclusionTest
{
	@Test
	public void cameraPitchBlendsContinuouslyBetweenCorridorAndBubble()
	{
		assertEquals(0.0f, GpuPlugin.treeTopDownFactor(128), 0.0001f);
		assertEquals(1.0f, GpuPlugin.treeTopDownFactor(383), 0.0001f);
		float low = GpuPlugin.treeTopDownFactor(192);
		float middle = GpuPlugin.treeTopDownFactor(256);
		float high = GpuPlugin.treeTopDownFactor(320);
		assertTrue(low > 0.0f);
		assertTrue(low < middle);
		assertTrue(middle < high);
		assertTrue(high < 1.0f);
	}

	@Test
	public void presetsIncreaseCoverageAndFadeStrength()
	{
		assertTrue(TreeOcclusionMode.BALANCED.bubbleRadius
			< TreeOcclusionMode.STRONG.bubbleRadius);
		assertTrue(TreeOcclusionMode.STRONG.bubbleRadius
			< TreeOcclusionMode.PLAYER_PRIORITY.bubbleRadius);
		assertTrue(TreeOcclusionMode.BALANCED.sightConeWidth
			< TreeOcclusionMode.STRONG.sightConeWidth);
		assertTrue(TreeOcclusionMode.STRONG.sightConeWidth
			< TreeOcclusionMode.PLAYER_PRIORITY.sightConeWidth);
		assertTrue(TreeOcclusionMode.BALANCED.maximumFade
			< TreeOcclusionMode.STRONG.maximumFade);
		assertEquals(1.0f, TreeOcclusionMode.PLAYER_PRIORITY.maximumFade,
			0.0001f);
	}
}
