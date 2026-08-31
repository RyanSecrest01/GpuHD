/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.gpu;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL42C.glTexStorage3D;

@Singleton
@Slf4j
class TextureManager
{
	static final int TEXTURE_COUNT = 256;
	private static final int TEXTURE_SIZE = 512;

	int initTextureArray(TextureProvider textureProvider)
	{
		if (!allTexturesLoaded(textureProvider))
		{
			return -1;
		}

		Texture[] textures = textureProvider.getTextures();
		HdTextureRegistry registry = HdTextureRegistry.get();
		int layers = registry.getLayerCount();

		int textureArrayId = glGenTextures();
		glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);
		if (GL.getCapabilities().glTexStorage3D != 0)
		{
			glTexStorage3D(GL_TEXTURE_2D_ARRAY, 10, GL_RGBA8, TEXTURE_SIZE, TEXTURE_SIZE, layers);
		}
		else
		{
			int size = TEXTURE_SIZE;
			for (int i = 0; i < 10; i++)
			{
				glTexImage3D(GL_TEXTURE_2D_ARRAY, i, GL_RGBA8, size, size, layers, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
				size = Math.max(1, size / 2);
			}
		}

		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);

		// Set brightness to 1.0d to upload unmodified textures to GPU
		double save = textureProvider.getBrightness();
		textureProvider.setBrightness(1.0d);

		updateTextures(textureProvider, textureArrayId);

		textureProvider.setBrightness(save);

		glActiveTexture(GL_TEXTURE1);
		glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);
		glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
		glActiveTexture(GL_TEXTURE0);

		return textureArrayId;
	}

	void setAnisotropicFilteringLevel(int textureArrayId, int level)
	{
		glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);

		//level = 0 means no mipmaps and no anisotropic filtering
		if (level == 0)
		{
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		}
		//level = 1 means with mipmaps but without anisotropic filtering GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT defaults to 1.0 which is off
		//level > 1 enables anisotropic filtering. It's up to the vendor what the values mean
		//Even if anisotropic filtering isn't supported, mipmaps will be enabled with any level >= 1
		else
		{
			// Set on GL_NEAREST_MIPMAP_LINEAR (bilinear filtering with mipmaps) since the pixel nature of the game means that nearest filtering
			// looks best for objects up close but allows linear filtering to resolve possible aliasing and noise with mipmaps from far away objects.
			glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_NEAREST_MIPMAP_LINEAR);
		}

		if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic)
		{
			final float maxSamples = glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
			//Clamp from 1 to max GL says it supports.
			final float anisoLevel = Math.max(1, Math.min(maxSamples, level));
			glTexParameterf(GL_TEXTURE_2D_ARRAY, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT, anisoLevel);
		}
	}

	void freeTextureArray(int textureArrayId)
	{
		glDeleteTextures(textureArrayId);
	}

	/**
	 * Check if all textures have been loaded and cached yet.
	 *
	 * @param textureProvider
	 * @return
	 */
	private boolean allTexturesLoaded(TextureProvider textureProvider)
	{
		Texture[] textures = textureProvider.getTextures();
		if (textures == null || textures.length == 0)
		{
			return false;
		}

		for (int textureId = 0; textureId < textures.length; textureId++)
		{
			Texture texture = textures[textureId];
			if (texture != null)
			{
				int[] pixels = textureProvider.load(textureId);
				if (pixels == null)
				{
					return false;
				}
			}
		}

		return true;
	}

	private void updateTextures(TextureProvider textureProvider, int textureArrayId)
	{
		Texture[] textures = textureProvider.getTextures();
		HdTextureRegistry registry = HdTextureRegistry.get();

		glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);

		int cnt = 0;
		List<Integer> alphaMismatches = new ArrayList<>();
		ByteBuffer pixelBuffer = ByteBuffer.allocateDirect(TEXTURE_SIZE * TEXTURE_SIZE * 4);
		for (int textureId = 0; textureId < textures.length; textureId++)
		{
			Texture texture = textures[textureId];
			if (texture != null)
			{
				int[] originalPixels = textureProvider.load(textureId);
				int originalSize = originalPixels == null ? 0 : (int) Math.sqrt(originalPixels.length);
				boolean[] alphaMismatch = new boolean[1];
				byte[] authored = loadAsset(registry.getVanilla(textureId), originalPixels,
					originalSize, alphaMismatch);
				if (alphaMismatch[0])
				{
					alphaMismatches.add(textureId);
				}
				int[] srcPixels = authored == null ? originalPixels : null;
				if (srcPixels == null)
				{
					if (authored == null)
					{
						log.warn("No pixels for texture {}!", textureId);
						continue;
					}
				}

				++cnt;

				int sourceSize = srcPixels == null ? 0 : (int) Math.sqrt(srcPixels.length);
				byte[] pixels = authored != null ? authored : convertPixels(srcPixels, sourceSize, sourceSize, TEXTURE_SIZE, TEXTURE_SIZE);
				pixelBuffer.clear();
				pixelBuffer.put(pixels);
				pixelBuffer.flip();
				glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, textureId, TEXTURE_SIZE, TEXTURE_SIZE,
					1, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuffer);
			}
		}
		for (HdTextureRegistry.Asset asset : registry.getSyntheticAssets())
		{
			byte[] pixels = loadAsset(asset, null, 0, null);
			pixelBuffer.clear();
			pixelBuffer.put(pixels);
			pixelBuffer.flip();
			glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, asset.getLayer(), TEXTURE_SIZE, TEXTURE_SIZE,
				1, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuffer);
		}

		log.debug("Uploaded textures {}", cnt);
		if (!alphaMismatches.isEmpty())
		{
			log.warn("Restored original alpha masks for {} remastered textures whose PNG alpha differed: {}",
				alphaMismatches.size(), alphaMismatches);
		}
	}

	private static byte[] loadAsset(HdTextureRegistry.Asset asset, int[] original,
		int originalSize, boolean[] alphaMismatch)
	{
		if (asset == null)
		{
			return null;
		}
		try (InputStream input = TextureManager.class.getClassLoader().getResourceAsStream(asset.getResource()))
		{
			if (input == null)
			{
				throw new IllegalArgumentException("Missing HD texture asset " + asset.getResource());
			}
			BufferedImage source = ImageIO.read(input);
			if (source == null)
			{
				throw new IllegalArgumentException("Unreadable HD texture asset " + asset.getResource());
			}
			BufferedImage image = new BufferedImage(TEXTURE_SIZE, TEXTURE_SIZE, BufferedImage.TYPE_INT_ARGB);
			Graphics2D graphics = image.createGraphics();
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			graphics.drawImage(source, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE, null); graphics.dispose();
			byte[] rgba = new byte[TEXTURE_SIZE * TEXTURE_SIZE * 4]; int p = 0;
			for (int y = 0; y < TEXTURE_SIZE; y++) for (int x = 0; x < TEXTURE_SIZE; x++)
			{
				int argb = image.getRGB(x, y); rgba[p++] = (byte) (argb >> 16); rgba[p++] = (byte) (argb >> 8);
				rgba[p++] = (byte) argb;
				int alpha = argb >>> 24;
				if (original != null && originalSize > 0)
				{
					int sourcePixel = original[(y * originalSize / TEXTURE_SIZE) * originalSize
						+ x * originalSize / TEXTURE_SIZE];
					int originalAlpha = sourcePixel == 0 ? 0 : 255;
					if (alphaMismatch != null && alpha != originalAlpha)
					{
						alphaMismatch[0] = true;
					}
					alpha = originalAlpha;
				}
				rgba[p++] = (byte) alpha;
			}
			return rgba;
		}
		catch (Exception ex)
		{
			throw new IllegalArgumentException("Unable to load " + asset.getResource(), ex);
		}
	}

	private static byte[] convertPixels(int[] srcPixels, int width, int height, int textureWidth, int textureHeight)
	{
		byte[] pixels = new byte[textureWidth * textureHeight * 4];
		int pixelIdx = 0;
		for (int y = 0; y < textureHeight; y++)
		{
			int sy = y * height / textureHeight;
			for (int x = 0; x < textureWidth; x++)
			{
				int rgb = srcPixels[sy * width + x * width / textureWidth];
				if (rgb != 0)
				{
					pixels[pixelIdx++] = (byte) (rgb >> 16);
					pixels[pixelIdx++] = (byte) (rgb >> 8);
					pixels[pixelIdx++] = (byte) rgb;
					pixels[pixelIdx++] = (byte) -1;
				}
				else
				{
					pixelIdx += 4;
				}
			}
		}
		return pixels;
	}

	float[] computeTextureAnimations(TextureProvider textureProvider)
	{
		Texture[] textures = textureProvider.getTextures();

		if (textures.length > TEXTURE_COUNT)
		{
			log.warn("texture limit exceeded: {} > {}", textures.length, TEXTURE_COUNT);
		}

		float[] anims = new float[TEXTURE_COUNT * 2];
		for (int i = 0; i < Math.min(TEXTURE_COUNT, textures.length); ++i)
		{
			Texture texture = textures[i];
			if (texture == null)
			{
				continue;
			}

			float u = 0f, v = 0f;
			switch (texture.getAnimationDirection())
			{
				case 1:
					v = -1f;
					break;
				case 3:
					v = 1f;
					break;
				case 2:
					u = -1f;
					break;
				case 4:
					u = 1f;
					break;
			}

			int speed = texture.getAnimationSpeed();
			u *= speed;
			v *= speed;

			anims[i * 2] = u;
			anims[i * 2 + 1] = v;
		}
		return anims;
	}
}
