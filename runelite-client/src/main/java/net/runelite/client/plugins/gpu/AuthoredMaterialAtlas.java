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

/**
 * Owns the authored albedo, normal, and packed-property
 * texture arrays used by the experimental material system.
 */
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

	/*
	 * White albedo is mathematically neutral if we later
	 * use VANILLA_MULTIPLY.
	 */
	static final int NEUTRAL_ALBEDO_R = 255;
	static final int NEUTRAL_ALBEDO_G = 255;
	static final int NEUTRAL_ALBEDO_B = 255;
	static final int NEUTRAL_ALBEDO_A = 255;

	private int albedoTextureArrayId;
	private int normalTextureArrayId;
	private int propertyTextureArrayId;

	void initialize()
	{
		shutdown();

		AuthoredMaterialCatalog catalog =
				AuthoredMaterialCatalog.bundled();

		int previousActiveTexture =
				glGetInteger(GL_ACTIVE_TEXTURE);

		int previousArrayBinding =
				glGetInteger(GL_TEXTURE_BINDING_2D_ARRAY);

		int previousUnpackAlignment =
				glGetInteger(GL_UNPACK_ALIGNMENT);

		try
		{
			glPixelStorei(
					GL_UNPACK_ALIGNMENT,
					1
			);

			/*
			 * Color/albedo gets its own higher-resolution array.
			 */
			albedoTextureArrayId =
					createAlbedoArray(catalog);

			/*
			 * Existing normal and property arrays stay at their
			 * original authored material resolution.
			 */
			normalTextureArrayId =
					createMaterialDataArray(
							catalog,
							true
					);

			propertyTextureArrayId =
					createMaterialDataArray(
							catalog,
							false
					);

			log.info(
					"Initialized authored material atlas: "
							+ "{}px material data, {}px albedo, "
							+ "{} albedo layer(s), "
							+ "{} normal layer(s), "
							+ "{} property layer(s)",
					catalog.getResolution(),
					catalog.getAlbedoResolution(),
					catalog.getAlbedoLayerCount(),
					catalog.getNormalLayerCount(),
					catalog.getPropertyLayerCount()
			);
		}
		catch (RuntimeException ex)
		{
			shutdown();
			throw ex;
		}
		finally
		{
			glPixelStorei(
					GL_UNPACK_ALIGNMENT,
					previousUnpackAlignment
			);

			glBindTexture(
					GL_TEXTURE_2D_ARRAY,
					previousArrayBinding
			);

			glActiveTexture(
					previousActiveTexture
			);
		}
	}

	void shutdown()
	{
		if (albedoTextureArrayId != 0)
		{
			glDeleteTextures(
					albedoTextureArrayId
			);

			albedoTextureArrayId = 0;
		}

		if (normalTextureArrayId != 0)
		{
			glDeleteTextures(
					normalTextureArrayId
			);

			normalTextureArrayId = 0;
		}

		if (propertyTextureArrayId != 0)
		{
			glDeleteTextures(
					propertyTextureArrayId
			);

			propertyTextureArrayId = 0;
		}
	}

	int getAlbedoTextureArrayId()
	{
		return albedoTextureArrayId;
	}

	int getNormalTextureArrayId()
	{
		return normalTextureArrayId;
	}

	int getPropertyTextureArrayId()
	{
		return propertyTextureArrayId;
	}

	/**
	 * Creates the high-resolution authored color texture array.
	 *
	 * Albedo images are stored as sRGB so OpenGL converts
	 * sampled color values into linear space automatically.
	 */
	private static int createAlbedoArray(
			AuthoredMaterialCatalog catalog)
	{
		int resolution =
				catalog.getAlbedoResolution();

		int layerCount =
				catalog.getAlbedoLayerCount();

		int mipLevels =
				Integer.numberOfTrailingZeros(
						Integer.highestOneBit(
								resolution
						)
				) + 1;

		int texture =
				glGenTextures();

		glBindTexture(
				GL_TEXTURE_2D_ARRAY,
				texture
		);

		if (GL.getCapabilities().glTexStorage3D != 0)
		{
			glTexStorage3D(
					GL_TEXTURE_2D_ARRAY,
					mipLevels,
					GL_SRGB8_ALPHA8,
					resolution,
					resolution,
					layerCount
			);
		}
		else
		{
			int mipSize =
					resolution;

			for (int mip = 0;
				 mip < mipLevels;
				 ++mip)
			{
				glTexImage3D(
						GL_TEXTURE_2D_ARRAY,
						mip,
						GL_RGBA8,
						mipSize,
						mipSize,
						layerCount,
						0,
						GL_RGBA,
						GL_UNSIGNED_BYTE,
						0
				);

				mipSize =
						Math.max(
								1,
								mipSize >> 1
						);
			}
		}

		configureArraySampling();

		for (int layer = 0;
			 layer < layerCount;
			 ++layer)
		{
			AuthoredMaterialCatalog.Layer definition =
					catalog.getAlbedoLayer(
							layer
					);

			ByteBuffer pixels;

			if (AuthoredMaterialCatalog.NEUTRAL_SOURCE.equals(
					definition.getSource()))
			{
				pixels =
						neutralAlbedoPixels(
								resolution
						);
			}
			else
			{
				pixels =
						loadColorPixels(
								definition.getSource(),
								resolution
						);
			}

			glTexSubImage3D(
					GL_TEXTURE_2D_ARRAY,
					0,
					0,
					0,
					layer,
					resolution,
					resolution,
					1,
					GL_RGBA,
					GL_UNSIGNED_BYTE,
					pixels
			);
		}

		glGenerateMipmap(
				GL_TEXTURE_2D_ARRAY
		);

		return texture;
	}

	/**
	 * Creates the existing lower-resolution normal/property arrays.
	 */
	private static int createMaterialDataArray(
			AuthoredMaterialCatalog catalog,
			boolean normal)
	{
		int resolution =
				catalog.getResolution();

		int layerCount =
				normal
						? catalog.getNormalLayerCount()
						: catalog.getPropertyLayerCount();

		int mipLevels =
				Integer.numberOfTrailingZeros(
						Integer.highestOneBit(
								resolution
						)
				) + 1;

		int texture =
				glGenTextures();

		glBindTexture(
				GL_TEXTURE_2D_ARRAY,
				texture
		);

		if (GL.getCapabilities().glTexStorage3D != 0)
		{
			glTexStorage3D(
					GL_TEXTURE_2D_ARRAY,
					mipLevels,
					GL_RGBA8,
					resolution,
					resolution,
					layerCount
			);
		}
		else
		{
			int mipSize =
					resolution;

			for (int mip = 0;
				 mip < mipLevels;
				 ++mip)
			{
				glTexImage3D(
						GL_TEXTURE_2D_ARRAY,
						mip,
						GL_RGBA8,
						mipSize,
						mipSize,
						layerCount,
						0,
						GL_RGBA,
						GL_UNSIGNED_BYTE,
						0
				);

				mipSize =
						Math.max(
								1,
								mipSize >> 1
						);
			}
		}

		configureArraySampling();

		for (int layer = 0;
			 layer < layerCount;
			 ++layer)
		{
			AuthoredMaterialCatalog.Layer definition =
					normal
							? catalog.getNormalLayer(layer)
							: catalog.getPropertyLayer(layer);

			ByteBuffer pixels;

			try
			{
				pixels =
						loadMaterialDataPixels(
								definition.getSource(),
								resolution,
								normal
						);
			}
			catch (RuntimeException ex)
			{
				glDeleteTextures(
						texture
				);

				throw ex;
			}

			glTexSubImage3D(
					GL_TEXTURE_2D_ARRAY,
					0,
					0,
					0,
					layer,
					resolution,
					resolution,
					1,
					GL_RGBA,
					GL_UNSIGNED_BYTE,
					pixels
			);
		}

		glGenerateMipmap(
				GL_TEXTURE_2D_ARRAY
		);

		return texture;
	}

	/**
	 * Shared filtering/wrapping for authored arrays.
	 */
	private static void configureArraySampling()
	{
		glTexParameteri(
				GL_TEXTURE_2D_ARRAY,
				GL_TEXTURE_MIN_FILTER,
				GL_LINEAR_MIPMAP_LINEAR
		);

		glTexParameteri(
				GL_TEXTURE_2D_ARRAY,
				GL_TEXTURE_MAG_FILTER,
				GL_LINEAR
		);

		glTexParameteri(
				GL_TEXTURE_2D_ARRAY,
				GL_TEXTURE_WRAP_S,
				GL_REPEAT
		);

		glTexParameteri(
				GL_TEXTURE_2D_ARRAY,
				GL_TEXTURE_WRAP_T,
				GL_REPEAT
		);
	}

	/**
	 * Loads normal/property images.
	 */
	static ByteBuffer loadMaterialDataPixels(
			String source,
			int resolution,
			boolean normal)
	{
		if (AuthoredMaterialCatalog.NEUTRAL_SOURCE.equals(
				source))
		{
			return neutralMaterialDataPixels(
					resolution,
					normal
			);
		}

		return loadImagePixels(
			source,
			resolution
		);
	}

	static ByteBuffer loadPixels(String source, int resolution, boolean normal)
	{
		return loadMaterialDataPixels(source, resolution, normal);
	}

	/**
	 * Loads authored color/albedo images.
	 */
	private static ByteBuffer loadColorPixels(
			String source,
			int resolution)
	{
		return loadImagePixels(
				source,
				resolution
		);
	}

	/**
	 * Shared PNG reader for authored image assets.
	 */
	private static ByteBuffer loadImagePixels(
			String source,
			int resolution)
	{
		try (InputStream stream =
					 AuthoredMaterialAtlas.class
							 .getResourceAsStream(source))
		{
			if (stream == null)
			{
				throw new IllegalArgumentException(
						"Missing authored material asset: "
								+ source
				);
			}

			BufferedImage image =
					ImageIO.read(
							stream
					);

			if (image == null
					|| image.getWidth() != resolution
					|| image.getHeight() != resolution)
			{
				throw new IllegalArgumentException(
						"Authored material asset "
								+ source
								+ " must be "
								+ resolution
								+ 'x'
								+ resolution
								+ " pixels"
				);
			}

			ByteBuffer pixels =
					ByteBuffer.allocateDirect(
							resolution
									* resolution
									* 4
					);

			/*
			 * BufferedImage ARGB -> OpenGL RGBA.
			 */
			for (int y = 0;
				 y < resolution;
				 ++y)
			{
				for (int x = 0;
					 x < resolution;
					 ++x)
				{
					int argb =
							image.getRGB(
									x,
									y
							);

					pixels.put(
							(byte) (
									argb >> 16
											& 0xff
							)
					);

					pixels.put(
							(byte) (
									argb >> 8
											& 0xff
							)
					);

					pixels.put(
							(byte) (
									argb
											& 0xff
							)
					);

					pixels.put(
							(byte) (
									argb >>> 24
							)
					);
				}
			}

			return pixels.flip();
		}
		catch (IOException ex)
		{
			throw new IllegalArgumentException(
					"Unable to read authored material asset: "
							+ source,
					ex
			);
		}
	}

	/**
	 * Neutral fallback for authored normal/property arrays.
	 */
	static ByteBuffer neutralMaterialDataPixels(
			int resolution,
			boolean normal)
	{
		ByteBuffer pixels =
				ByteBuffer.allocateDirect(
						resolution
								* resolution
								* 4
				);

		int red =
				normal
						? NEUTRAL_NORMAL_R
						: NEUTRAL_ROUGHNESS;

		int green =
				normal
						? NEUTRAL_NORMAL_G
						: NEUTRAL_METALLIC;

		int blue =
				normal
						? NEUTRAL_NORMAL_B
						: NEUTRAL_AO;

		int alpha =
				normal
						? NEUTRAL_NORMAL_A
						: NEUTRAL_HEIGHT;

		for (int pixel = 0;
			 pixel < resolution * resolution;
			 ++pixel)
		{
			pixels
					.put((byte) red)
					.put((byte) green)
					.put((byte) blue)
					.put((byte) alpha);
		}

		return pixels.flip();
	}

	static ByteBuffer neutralPixels(int resolution, boolean normal)
	{
		return neutralMaterialDataPixels(resolution, normal);
	}

	/**
	 * Neutral fallback for authored color.
	 *
	 * White preserves the original surface in multiplicative
	 * workflows and gives us a harmless layer-zero fallback.
	 */
	static ByteBuffer neutralAlbedoPixels(
			int resolution)
	{
		ByteBuffer pixels =
				ByteBuffer.allocateDirect(
						resolution
								* resolution
								* 4
				);

		for (int pixel = 0;
			 pixel < resolution * resolution;
			 ++pixel)
		{
			pixels
					.put((byte) NEUTRAL_ALBEDO_R)
					.put((byte) NEUTRAL_ALBEDO_G)
					.put((byte) NEUTRAL_ALBEDO_B)
					.put((byte) NEUTRAL_ALBEDO_A);
		}

		return pixels.flip();
	}
}
