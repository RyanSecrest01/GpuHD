/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 * All rights reserved.
 */
package net.runelite.client.plugins.gpu;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import javax.imageio.ImageIO;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL42C.glTexStorage3D;

/** Owns the normal and packed-property texture arrays for authored materials. */
@Singleton
@Slf4j
final class AuthoredMaterialAtlas
{
	static final int NEUTRAL_NORMAL_R = 128;
	static final int NEUTRAL_NORMAL_G = 128;
	static final int NEUTRAL_NORMAL_B = 255;
	static final int NEUTRAL_NORMAL_A = 255;
	static final int NEUTRAL_ROUGHNESS = 255;
	static final int NEUTRAL_METALLIC = 0;
	static final int NEUTRAL_AO = 255;
	static final int NEUTRAL_HEIGHT = 128;

	private int normalTextureArrayId;
	private int propertyTextureArrayId;

	void initialize()
	{
		shutdown();
		AuthoredMaterialCatalog catalog = AuthoredMaterialCatalog.bundled();
		int previousActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
		int previousArrayBinding = glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);
		int previousUnpackAlignment = glGetInteger(GL_UNPACK_ALIGNMENT);
		try
		{
			glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
			normalTextureArrayId = createArray(catalog, true);
			propertyTextureArrayId = createArray(catalog, false);
			log.info("Initialized authored material atlas: {}px, {} normal layer(s), "
				+ "{} property layer(s)", catalog.getResolution(),
				catalog.getNormalLayerCount(), catalog.getPropertyLayerCount());
		}
		catch (RuntimeException ex)
		{
			shutdown();
			throw ex;
		}
		finally
		{
			glPixelStorei(GL_UNPACK_ALIGNMENT, previousUnpackAlignment);
			glBindTexture(GL_TEXTURE_2D_ARRAY, previousArrayBinding);
			glActiveTexture(previousActiveTexture);
		}
	}

	void shutdown()
	{
		if (normalTextureArrayId != 0)
		{
			glDeleteTextures(normalTextureArrayId);
			normalTextureArrayId = 0;
		}
		if (propertyTextureArrayId != 0)
		{
			glDeleteTextures(propertyTextureArrayId);
			propertyTextureArrayId = 0;
		}
	}

	int getNormalTextureArrayId()
	{
		return normalTextureArrayId;
	}

	int getPropertyTextureArrayId()
	{
		return propertyTextureArrayId;
	}

	private static int createArray(AuthoredMaterialCatalog catalog, boolean normal)
	{
		int resolution = catalog.getResolution();
		int layerCount = normal
			? catalog.getNormalLayerCount() : catalog.getPropertyLayerCount();
		int mipLevels = Integer.numberOfTrailingZeros(
			Integer.highestOneBit(resolution)) + 1;
		int texture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D_ARRAY, texture);
		if (GL.getCapabilities().glTexStorage3D != 0)
		{
			glTexStorage3D(GL_TEXTURE_2D_ARRAY, mipLevels, GL_RGBA8,
				resolution, resolution, layerCount);
		}
		else
		{
			int mipSize = resolution;
			for (int mip = 0; mip < mipLevels; ++mip)
			{
				glTexImage3D(GL_TEXTURE_2D_ARRAY, mip, GL_RGBA8,
					mipSize, mipSize, layerCount, 0, GL_RGBA,
					GL_UNSIGNED_BYTE, 0);
				mipSize = Math.max(1, mipSize >> 1);
			}
		}

		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER,
			GL_LINEAR_MIPMAP_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);

		for (int layer = 0; layer < layerCount; ++layer)
		{
			AuthoredMaterialCatalog.Layer definition = normal
				? catalog.getNormalLayer(layer) : catalog.getPropertyLayer(layer);
			ByteBuffer pixels;
			try
			{
				pixels = loadPixels(definition.getSource(), resolution, normal);
			}
			catch (RuntimeException ex)
			{
				glDeleteTextures(texture);
				throw ex;
			}
			glTexSubImage3D(GL_TEXTURE_2D_ARRAY, 0, 0, 0, layer,
				resolution, resolution, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
		}
		glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
		return texture;
	}

	static ByteBuffer loadPixels(String source, int resolution, boolean normal)
	{
		if (AuthoredMaterialCatalog.NEUTRAL_SOURCE.equals(source))
		{
			return neutralPixels(resolution, normal);
		}
		try (InputStream stream = AuthoredMaterialAtlas.class
			.getResourceAsStream(source))
		{
			if (stream == null)
			{
				throw new IllegalArgumentException(
					"Missing authored material asset: " + source);
			}
			BufferedImage image = ImageIO.read(stream);
			if (image == null || image.getWidth() != resolution
				|| image.getHeight() != resolution)
			{
				throw new IllegalArgumentException("Authored material asset " + source
					+ " must be " + resolution + 'x' + resolution + " pixels");
			}

			ByteBuffer pixels = ByteBuffer.allocateDirect(resolution * resolution * 4);
			for (int y = 0; y < resolution; ++y)
			{
				for (int x = 0; x < resolution; ++x)
				{
					int argb = image.getRGB(x, y);
					pixels.put((byte) (argb >> 16 & 0xff));
					pixels.put((byte) (argb >> 8 & 0xff));
					pixels.put((byte) (argb & 0xff));
					pixels.put((byte) (argb >>> 24));
				}
			}
			return pixels.flip();
		}
		catch (IOException ex)
		{
			throw new IllegalArgumentException(
				"Unable to read authored material asset: " + source, ex);
		}
	}

	static ByteBuffer neutralPixels(int resolution, boolean normal)
	{
		ByteBuffer pixels = ByteBuffer.allocateDirect(resolution * resolution * 4);
		int red = normal ? NEUTRAL_NORMAL_R : NEUTRAL_ROUGHNESS;
		int green = normal ? NEUTRAL_NORMAL_G : NEUTRAL_METALLIC;
		int blue = normal ? NEUTRAL_NORMAL_B : NEUTRAL_AO;
		int alpha = normal ? NEUTRAL_NORMAL_A : NEUTRAL_HEIGHT;
		for (int pixel = 0; pixel < resolution * resolution; ++pixel)
		{
			pixels.put((byte) red).put((byte) green)
				.put((byte) blue).put((byte) alpha);
		}
		return pixels.flip();
	}
}
