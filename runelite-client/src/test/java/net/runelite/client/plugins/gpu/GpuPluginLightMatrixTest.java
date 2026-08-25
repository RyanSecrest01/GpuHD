/*
 * Copyright (c) 2026, Ryan Secrest
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.gpu;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class GpuPluginLightMatrixTest
{
	private static final float EPSILON = 1e-5f;

	@Test
	public void testConfiguredSunDirectionsProduceOrthonormalLightViews()
	{
		assertLightView(0.65f, -0.55f, -0.52f); // Morning
		assertLightView(0.035f, -1.0f, -0.025f); // Noon
		assertLightView(-0.65f, -0.48f, 0.52f); // Evening
	}

	private static void assertLightView(float lightX, float lightY, float lightZ)
	{
		float[] matrix = GpuPlugin.makeLightViewRotation(lightX, lightY, lightZ);
		float[] right = {matrix[0], matrix[4], matrix[8]};
		float[] up = {matrix[1], matrix[5], matrix[9]};
		float[] light = {matrix[2], matrix[6], matrix[10]};

		assertEquals(1.0f, dot(right, right), EPSILON);
		assertEquals(1.0f, dot(up, up), EPSILON);
		assertEquals(1.0f, dot(light, light), EPSILON);
		assertEquals(0.0f, dot(right, up), EPSILON);
		assertEquals(0.0f, dot(right, light), EPSILON);
		assertEquals(0.0f, dot(up, light), EPSILON);
		assertEquals(1.0f, dot(cross(right, up), light), EPSILON);

		// Moving toward the configured sun must change only light-space depth.
		float[] point = {256.0f, -128.0f, -64.0f};
		float distance = 192.0f;
		float[] towardLight = {
			point[0] + light[0] * distance,
			point[1] + light[1] * distance,
			point[2] + light[2] * distance
		};
		assertEquals(dot(right, point), dot(right, towardLight), 1e-3f);
		assertEquals(dot(up, point), dot(up, towardLight), 1e-3f);
		assertEquals(distance, dot(light, towardLight) - dot(light, point), 1e-3f);
	}

	private static float dot(float[] a, float[] b)
	{
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	private static float[] cross(float[] a, float[] b)
	{
		return new float[]
		{
			a[1] * b[2] - a[2] * b[1],
			a[2] * b[0] - a[0] * b[2],
			a[0] * b[1] - a[1] * b[0]
		};
	}
}
