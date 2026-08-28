/* Copyright (c) 2026, RuneLite GPU Experimental Renderer */
package net.runelite.client.plugins.gpu;

import java.awt.Color;
import java.util.Locale;

/** Stable colors used to correlate exact source IDs with screenshots and exports. */
final class SurfaceIdDebugColors
{
	private SurfaceIdDebugColors()
	{
	}

	static Color colorForId(int id)
	{
		int hash = id * 0x45d9f3b;
		hash ^= hash >>> 16;
		return new Color(55 + (hash & 0x7f),
			55 + ((hash >>> 8) & 0x7f),
			55 + ((hash >>> 16) & 0x7f));
	}

	static String hexForId(int id)
	{
		Color color = colorForId(id);
		return String.format(Locale.ROOT, "#%02X%02X%02X",
			color.getRed(), color.getGreen(), color.getBlue());
	}
}
