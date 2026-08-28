/* Copyright (c) 2026, RuneLite GPU Experimental Renderer */
package net.runelite.client.plugins.gpu;

import com.google.gson.Gson;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.imageio.ImageIO;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.GL;

import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL42C.glTexStorage3D;

/** Loads sparse, explicit RuneScape-ID texture replacements. */
@Singleton
@Slf4j
final class AuthoredTextureOverrideAtlas
{
	private static final int SIZE = 256;
	private int texture;
	private int[] textureLayers = emptyLayers();
	private int[] underlayLayers = emptyLayers();
	private int[] overlayLayers = emptyLayers();

	void initialize()
	{
		shutdown();
		try (InputStream stream = getClass().getResourceAsStream("authored_texture_overrides.json"))
		{
			if (stream == null) return;
			Definition definition = new Gson().fromJson(
				new InputStreamReader(stream, StandardCharsets.UTF_8), Definition.class);
			if (definition == null || definition.entries == null || definition.entries.length == 0) return;

			texture = glGenTextures();
			glBindTexture(GL_TEXTURE_2D_ARRAY, texture);
			int layerCount = definition.entries.length;
			if (GL.getCapabilities().glTexStorage3D != 0)
				glTexStorage3D(GL_TEXTURE_2D_ARRAY, 9, GL_SRGB8_ALPHA8, SIZE, SIZE, layerCount);
			else
				for (int level = 0, size = SIZE; level < 9; ++level, size = Math.max(1, size >> 1))
					glTexImage3D(GL_TEXTURE_2D_ARRAY, level, GL_RGBA8, size, size, layerCount,
						0, GL_RGBA, GL_UNSIGNED_BYTE, 0);

			textureLayers = emptyLayers();
			underlayLayers = emptyLayers();
			overlayLayers = emptyLayers();
			for (int layer = 0; layer < layerCount; ++layer)
			{
				Entry entry = definition.entries[layer];
				if (entry == null || entry.source == null) continue;
				try (InputStream imageStream = getClass().getResourceAsStream(entry.source))
				{
					BufferedImage image = imageStream == null ? null : ImageIO.read(imageStream);
					if (image == null)
					{
						log.warn("Missing authored texture resource: {}", entry.source);
						continue;
					}
					BufferedImage scaled = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
					Graphics2D graphics = scaled.createGraphics();
					graphics.drawImage(image, 0, 0, SIZE, SIZE, null);
					graphics.dispose();
					int[] pixels = scaled.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
					ByteBuffer buffer = ByteBuffer.allocateDirect(SIZE * SIZE * 4);
					for (int pixel : pixels)
						buffer.put((byte) (pixel >> 16)).put((byte) (pixel >> 8))
							.put((byte) pixel).put((byte) (pixel >> 24));
					buffer.flip();
					glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer, SIZE, SIZE, 1,
						GL_RGBA, GL_UNSIGNED_BYTE, buffer);
					assign(entry.textureId, textureLayers, layer);
					assign(entry.underlayId, underlayLayers, layer);
					assign(entry.overlayId, overlayLayers, layer);
				}
			}
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
			glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
			log.info("Loaded authored texture overrides: {} layer(s)", layerCount);
		}
		catch (Exception ex)
		{
			log.warn("Unable to load authored texture overrides", ex);
			shutdown();
		}
	}

	int textureId() { return texture; }
	int[] textureLayers() { return textureLayers; }
	int[] underlayLayers() { return underlayLayers; }
	int[] overlayLayers() { return overlayLayers; }

	void shutdown()
	{
		if (texture != 0) glDeleteTextures(texture);
		texture = 0;
	}

	private static int[] emptyLayers()
	{
		int[] layers = new int[256];
		Arrays.fill(layers, -1);
		return layers;
	}

	private static void assign(Integer id, int[] layers, int layer)
	{
		if (id != null && id >= 0 && id < layers.length)
		{
			if (layers[id] >= 0) throw new IllegalArgumentException("Duplicate authored ID: " + id);
			layers[id] = layer;
		}
	}

	private static final class Definition { Entry[] entries; }
	private static final class Entry
	{
		Integer textureId;
		Integer underlayId;
		Integer overlayId;
		String source;
	}
}
