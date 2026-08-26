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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GpuPluginLightMatrixTest
{
	private static final float EPSILON = 1e-5f;

	@Test
	public void configuredCelestialDirectionsProduceOrthonormalLightViews()
	{
		assertLightView(0.65f, -0.55f, -0.52f); // Morning scene direction
		assertLightView(0.035f, -1.0f, -0.025f); // Noon scene direction
		assertLightView(-0.65f, -0.48f, 0.52f); // Evening scene direction
	}

	@Test
	public void lightDepthAxisPointsTowardConfiguredDirection()
	{
		float lightX = 0.35f;
		float lightY = -0.82f;
		float lightZ = 0.19f;
		float length = length(lightX, lightY, lightZ);
		float[] matrix = GpuPlugin.makeLightViewRotation(lightX, lightY, lightZ);

		assertEquals(lightX / length, matrix[2], EPSILON);
		assertEquals(lightY / length, matrix[6], EPSILON);
		assertEquals(lightZ / length, matrix[10], EPSILON);
	}

	@Test
	public void verticalDirectionsUseStableOrthonormalFallback()
	{
		assertLightView(0.0f, 1.0f, 0.0f);
		assertLightView(0.0f, -1.0f, 0.0f);

		float[] overhead = GpuPlugin.makeLightViewRotation(0.0f, -1.0f, 0.0f);
		for (float component : overhead)
		{
			assertTrue(Float.isFinite(component));
		}
	}

	@Test
	public void degenerateDirectionReturnsIdentity()
	{
		assertArrayEquals(new float[]
		{
			1.0f, 0.0f, 0.0f, 0.0f,
			0.0f, 1.0f, 0.0f, 0.0f,
			0.0f, 0.0f, 1.0f, 0.0f,
			0.0f, 0.0f, 0.0f, 1.0f
		}, GpuPlugin.makeLightViewRotation(0.0f, 0.0f, 0.0f), 0.0f);
	}

	@Test
	public void atmosphereFocalPointSnappingIsStableInLightSpace()
	{
		float[] matrix = GpuPlugin.makeLightViewRotation(0.65f, -0.55f, -0.52f);
		float[] right = {matrix[0], matrix[4], matrix[8]};
		float[] up = {matrix[1], matrix[5], matrix[9]};
		float[] light = {matrix[2], matrix[6], matrix[10]};
		float shadowRadius = 58.0f * 128.0f;
		float shadowTexelSize = 2.0f * shadowRadius / 1024.0f;

		// Both focal points remain inside the same right/up shadow texels, even
		// though their world position and light-space depth are different.
		float[] firstFocalPoint = pointInBasis(
			right, 12.20f * shadowTexelSize,
			up, -8.20f * shadowTexelSize,
			light, 300.0f);
		float[] secondFocalPoint = pointInBasis(
			right, 12.42f * shadowTexelSize,
			up, -8.42f * shadowTexelSize,
			light, 900.0f);
		float[] firstSnapped = snapAtmosphereCenter(
			firstFocalPoint, right, up, shadowTexelSize);
		float[] secondSnapped = snapAtmosphereCenter(
			secondFocalPoint, right, up, shadowTexelSize);

		assertEquals(dot(right, firstSnapped), dot(right, secondSnapped), 1e-3f);
		assertEquals(dot(up, firstSnapped), dot(up, secondSnapped), 1e-3f);
		assertEquals(dot(light, firstFocalPoint), dot(light, firstSnapped), 1e-3f);
		assertEquals(dot(light, secondFocalPoint), dot(light, secondSnapped), 1e-3f);

		// Crossing a right-axis texel boundary advances exactly one texel and
		// does not disturb the independently snapped up coordinate.
		float[] nextTexelFocalPoint = pointInBasis(
			right, 12.55f * shadowTexelSize,
			up, -8.42f * shadowTexelSize,
			light, 900.0f);
		float[] nextTexelSnapped = snapAtmosphereCenter(
			nextTexelFocalPoint, right, up, shadowTexelSize);
		assertEquals(
			shadowTexelSize,
			dot(right, nextTexelSnapped) - dot(right, secondSnapped),
			1e-3f);
		assertEquals(dot(up, secondSnapped), dot(up, nextTexelSnapped), 1e-3f);
	}

	@Test
	public void halfResolutionViewportPreservesNegativePaddingCoverage()
	{
		assertEquals(-1, GpuPlugin.halfResolutionViewportOrigin(-1));
		assertEquals(961, GpuPlugin.halfResolutionViewportExtent(-1, 1920));
		assertEquals(-1, GpuPlugin.halfResolutionViewportOrigin(-2));
		assertEquals(960, GpuPlugin.halfResolutionViewportExtent(-2, 1920));

		assertEquals(0, GpuPlugin.halfResolutionViewportOrigin(0));
		assertEquals(960, GpuPlugin.halfResolutionViewportExtent(0, 1920));
		assertEquals(960, GpuPlugin.halfResolutionViewportExtent(0, 1919));
	}

	@Test
	public void halfResolutionViewportAlwaysHasDrawableExtent()
	{
		assertEquals(1, GpuPlugin.halfResolutionViewportExtent(0, 0));
		assertEquals(1, GpuPlugin.halfResolutionViewportExtent(-1, 0));
	}

	@Test
	public void activeRayProfileFollowsTheCelestialSourceRatherThanSkyBrightness()
	{
		assertFalse(GpuPlugin.isMoonEnvironment(SkyMode.OFF));
		assertFalse(GpuPlugin.isMoonEnvironment(SkyMode.DAY));
		assertFalse(GpuPlugin.isMoonEnvironment(SkyMode.SUNSET));
		assertTrue(GpuPlugin.isMoonEnvironment(SkyMode.NIGHT));
		assertTrue(GpuPlugin.isMoonEnvironment(SkyMode.COSMIC));
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

		float inputLength = length(lightX, lightY, lightZ);
		assertEquals(lightX / inputLength, light[0], EPSILON);
		assertEquals(lightY / inputLength, light[1], EPSILON);
		assertEquals(lightZ / inputLength, light[2], EPSILON);

		// Moving toward the configured celestial source must change only
		// light-space depth, never the two shadow-map texel coordinates.
		float[] point = {256.0f, -128.0f, -64.0f};
		float distance = 192.0f;
		float[] towardLight =
		{
			point[0] + light[0] * distance,
			point[1] + light[1] * distance,
			point[2] + light[2] * distance
		};
		assertEquals(dot(right, point), dot(right, towardLight), 1e-3f);
		assertEquals(dot(up, point), dot(up, towardLight), 1e-3f);
		assertEquals(distance, dot(light, towardLight) - dot(light, point), 1e-3f);
	}

	private static float length(float x, float y, float z)
	{
		return (float) Math.sqrt(x * x + y * y + z * z);
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

	private static float[] pointInBasis(
		float[] right,
		float rightCoordinate,
		float[] up,
		float upCoordinate,
		float[] light,
		float lightCoordinate)
	{
		return new float[]
		{
			right[0] * rightCoordinate + up[0] * upCoordinate + light[0] * lightCoordinate,
			right[1] * rightCoordinate + up[1] * upCoordinate + light[1] * lightCoordinate,
			right[2] * rightCoordinate + up[2] * upCoordinate + light[2] * lightCoordinate
		};
	}

	private static float[] snapAtmosphereCenter(
		float[] focalPoint,
		float[] right,
		float[] up,
		float shadowTexelSize)
	{
		float centerRight = dot(right, focalPoint);
		float centerUp = dot(up, focalPoint);
		float rightDelta = Math.round(centerRight / shadowTexelSize)
			* shadowTexelSize - centerRight;
		float upDelta = Math.round(centerUp / shadowTexelSize)
			* shadowTexelSize - centerUp;

		return new float[]
		{
			focalPoint[0] + right[0] * rightDelta + up[0] * upDelta,
			focalPoint[1] + right[1] * rightDelta + up[1] * upDelta,
			focalPoint[2] + right[2] * rightDelta + up[2] * upDelta
		};
	}
}
