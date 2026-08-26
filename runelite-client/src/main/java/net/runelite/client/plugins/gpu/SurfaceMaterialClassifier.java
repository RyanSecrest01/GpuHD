/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 * All rights reserved.
 */
package net.runelite.client.plugins.gpu;

import net.runelite.api.SceneTilePaint;
import net.runelite.api.SceneTileModel;

/**
 * CPU-side material classifier. The classifier runs while terrain is uploaded,
 * and its result is packed into each primitive. Shaders consume that explicit
 * tag instead of repeatedly guessing a material from the final, lit RGB value.
 */
final class SurfaceMaterialClassifier
{
	static final int INVISIBLE_HSL = 12345678;
	private static final boolean[][] SHAPE_OVERLAY_FACES = {
		{true, true, true, true},
		{false, true},
		{false, false, true},
		{false, false, true},
		{false, true, true},
		{false, true, true},
		{false, false, true, true},
		{false, false, false, true},
		{false, true, true, true},
		{false, false, false, true, true, true},
		{true, true, true, false, false, false},
		{true, true, false, false, false, false}
	};

	private SurfaceMaterialClassifier()
	{
	}

	static SurfaceMaterial classifyPaint(SceneTilePaint paint)
	{
		if (isWaterTexture(paint.getTexture()))
		{
			return SurfaceMaterial.WATER;
		}

		int[] colors = {
			paint.getSwColor(), paint.getSeColor(),
			paint.getNeColor(), paint.getNwColor()
		};
		for (int color : colors)
		{
			if (!isValidPackedHsl(color))
			{
				return SurfaceMaterial.UNKNOWN;
			}
		}

		// Textured paint corners contain lightness rather than authored HSL. The
		// paint RGB is the stable material-color hint available at upload time.
		if (paint.getTexture() >= 0)
		{
			SurfaceMaterial textureMaterial = classifyTexture(paint.getTexture());
			if (textureMaterial != SurfaceMaterial.UNKNOWN)
			{
				return textureMaterial;
			}
			int rgb = paint.getRBG();
			return rgb >= 0 && rgb <= 0xffffff
				? classifyPackedHsl(rgbToPackedHsl(rgb))
				: SurfaceMaterial.UNKNOWN;
		}

		return classifyConsensus(colors, 3);
	}

	static SurfaceMaterial classifyFace(int texture, int colorA, int colorB, int colorC)
	{
		if (texture >= 0)
		{
			// Texture identity is authoritative when it is in the explicit map.
			// Unknown shaped textures stay unknown because their triangle colors
			// contain brightness rather than material HSL.
			return classifyTexture(texture);
		}
		return classifyConsensus(new int[]{colorA, colorB, colorC}, 2);
	}

	static SurfaceMaterial classifyTerrainFace(SceneTileModel model, int face,
		int texture, int colorA, int colorB, int colorC)
	{
		SurfaceMaterial textureMaterial = classifyTexture(texture);
		if (textureMaterial != SurfaceMaterial.UNKNOWN)
		{
			return textureMaterial;
		}
		if (texture < 0)
		{
			return classifyFace(texture, colorA, colorB, colorC);
		}

		int layer = terrainLayerForFace(model.getShape(), face);
		if (layer < 0)
		{
			return SurfaceMaterial.UNKNOWN;
		}
		int rgb = layer == 1
			? model.getModelOverlay() : model.getModelUnderlay();
		return rgb >= 0 && rgb <= 0xffffff
			? classifyPackedHsl(rgbToPackedHsl(rgb))
			: SurfaceMaterial.UNKNOWN;
	}

	/** @return 0 for underlay, 1 for overlay, or -1 when the face is unknown. */
	static int terrainLayerForFace(int shape, int face)
	{
		int shapeIndex = shape - 1;
		if (shapeIndex < 0 || shapeIndex >= SHAPE_OVERLAY_FACES.length
			|| face < 0 || face >= SHAPE_OVERLAY_FACES[shapeIndex].length)
		{
			return -1;
		}
		return SHAPE_OVERLAY_FACES[shapeIndex][face] ? 1 : 0;
	}

	static SurfaceMaterial classifyConsensus(int[] colors, int requiredMatches)
	{
		SurfaceMaterial best = SurfaceMaterial.UNKNOWN;
		int bestMatches = 0;
		for (SurfaceMaterial candidate : SurfaceMaterial.values())
		{
			if (candidate == SurfaceMaterial.UNKNOWN || candidate == SurfaceMaterial.WATER)
			{
				continue;
			}
			int matches = 0;
			for (int color : colors)
			{
				matches += classifyPackedHsl(color) == candidate ? 1 : 0;
			}
			if (matches > bestMatches)
			{
				best = candidate;
				bestMatches = matches;
			}
		}
		return bestMatches >= requiredMatches ? best : SurfaceMaterial.UNKNOWN;
	}

	static SurfaceMaterial classifyPackedHsl(int color)
	{
		if (!isValidPackedHsl(color))
		{
			return SurfaceMaterial.UNKNOWN;
		}

		int hue = color >> 10 & 63;
		int saturation = color >> 7 & 7;
		int luminance = color & 127;
		if (hue >= 13 && hue <= 28 && saturation >= 2
			&& luminance >= 12 && luminance <= 116)
		{
			return SurfaceMaterial.GRASS;
		}
		if (saturation <= 1 && luminance >= 20 && luminance <= 105)
		{
			return SurfaceMaterial.STONE;
		}
		if (hue >= 4 && hue <= 12 && saturation >= 2 && luminance >= 52)
		{
			return SurfaceMaterial.SAND;
		}
		if (hue >= 2 && hue <= 12 && saturation >= 2
			&& luminance >= 10 && luminance < 62)
		{
			return SurfaceMaterial.DIRT;
		}
		return SurfaceMaterial.UNKNOWN;
	}

	static boolean isValidPackedHsl(int color)
	{
		return color >= 0 && color <= 0xffff && color != INVISIBLE_HSL;
	}

	static boolean isWaterTexture(int textureId)
	{
		return textureId == 1
			|| textureId == 24
			|| textureId == 25
			|| textureId >= 130 && textureId <= 189
			|| textureId == 208;
	}

	static SurfaceMaterial classifyTexture(int textureId)
	{
		if (isWaterTexture(textureId))
		{
			return SurfaceMaterial.WATER;
		}
		switch (textureId)
		{
			// Conservative identities verified against this fork's live cache.
			// Unlisted textures deliberately remain UNKNOWN until inspected.
			case 0:
			case 3:
			case 5:
			case 7:
			case 9:
			case 10:
			case 16:
			case 20:
			case 22:
			case 32:
			case 51:
				return SurfaceMaterial.WOOD;
			case 2:
			case 6:
			case 11:
			case 15:
			case 23:
			case 35:
			case 42:
			case 43:
			case 44:
			case 45:
			case 46:
			case 50:
			case 55:
			case 120:
			case 121:
			case 122:
				return SurfaceMaterial.STONE;
			case 129:
				return SurfaceMaterial.GRASS;
			case 8:
			case 28:
			case 29:
			case 30:
			case 33:
			case 41:
			case 60:
			case 89:
			case 90:
			case 123:
			case 124:
			case 127:
			case 128:
			case 190:
			case 191:
			case 192:
			case 193:
			case 194:
			case 195:
			case 196:
			case 197:
			case 198:
			case 199:
			case 200:
			case 201:
			case 202:
			case 203:
			case 204:
			case 205:
			case 209:
			case 210:
			case 211:
			case 212:
			case 213:
			case 214:
				return SurfaceMaterial.FOLIAGE;
			case 12:
			case 37:
				return SurfaceMaterial.METAL;
			default:
				return SurfaceMaterial.UNKNOWN;
		}
	}

	static int rgbToPackedHsl(int rgb)
	{
		float red = (rgb >> 16 & 255) / 255.0f;
		float green = (rgb >> 8 & 255) / 255.0f;
		float blue = (rgb & 255) / 255.0f;
		float maximum = Math.max(red, Math.max(green, blue));
		float minimum = Math.min(red, Math.min(green, blue));
		float range = maximum - minimum;
		float lightness = (maximum + minimum) * 0.5f;
		float saturation = range == 0.0f ? 0.0f
			: range / (1.0f - Math.abs(2.0f * lightness - 1.0f));
		float hue;
		if (range == 0.0f)
		{
			hue = 0.0f;
		}
		else if (maximum == red)
		{
			hue = ((green - blue) / range) / 6.0f;
		}
		else if (maximum == green)
		{
			hue = ((blue - red) / range + 2.0f) / 6.0f;
		}
		else
		{
			hue = ((red - green) / range + 4.0f) / 6.0f;
		}
		hue -= (float) Math.floor(hue);
		int packedHue = Math.round(hue * 64.0f) & 63;
		int packedSaturation = Math.max(0, Math.min(7, Math.round(saturation * 7.0f)));
		int packedLightness = Math.max(0, Math.min(127, Math.round(lightness * 127.0f)));
		return packedHue << 10 | packedSaturation << 7 | packedLightness;
	}
}
