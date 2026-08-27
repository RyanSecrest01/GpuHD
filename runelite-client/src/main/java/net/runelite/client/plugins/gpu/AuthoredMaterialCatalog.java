/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 * All rights reserved.
 */
package net.runelite.client.plugins.gpu;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Immutable contract joining semantic materials and authored atlas layers.
 * Layer zero in both arrays is always a generated neutral fallback.
 */
final class AuthoredMaterialCatalog
{
	static final String NORMAL_ENCODING = "TANGENT_XYZ_UNSIGNED_RGBA8";
	static final String PROPERTY_ENCODING = "R_ROUGHNESS_G_METALLIC_B_AO_A_HEIGHT";
	static final String VANILLA_BASE_COLOR = "VANILLA_MULTIPLY";
	static final String NEUTRAL_SOURCE = "$neutral";
	private static final String RESOURCE = "authored_materials.json";
	private static final int SUPPORTED_VERSION = 1;
	private static final AuthoredMaterialCatalog BUNDLED = loadBundled();

	private final int resolution;
	private final Layer[] normalLayers;
	private final Layer[] propertyLayers;
	private final MaterialDefinition[][] definitions;

	private AuthoredMaterialCatalog(int resolution, Layer[] normalLayers,
		Layer[] propertyLayers, MaterialDefinition[][] definitions)
	{
		this.resolution = resolution;
		this.normalLayers = normalLayers;
		this.propertyLayers = propertyLayers;
		this.definitions = definitions;
	}

	static AuthoredMaterialCatalog bundled()
	{
		return BUNDLED;
	}

	int getResolution()
	{
		return resolution;
	}

	int getNormalLayerCount()
	{
		return normalLayers.length;
	}

	int getPropertyLayerCount()
	{
		return propertyLayers.length;
	}

	Layer getNormalLayer(int index)
	{
		return normalLayers[index];
	}

	Layer getPropertyLayer(int index)
	{
		return propertyLayers[index];
	}

	MaterialDefinition resolve(SurfaceMaterial material, int variant)
	{
		int safeVariant = variant & SurfaceMaterial.VARIANT_MASK;
		MaterialDefinition definition = definitions[material.ordinal()][safeVariant];
		return definition != null ? definition : definitions[material.ordinal()][0];
	}

	static AuthoredMaterialCatalog load(Reader reader)
	{
		CatalogDto dto = new Gson().fromJson(reader, CatalogDto.class);
		if (dto == null || dto.atlas == null || dto.layers == null
			|| dto.materials == null)
		{
			throw new IllegalArgumentException("Authored material catalog is incomplete");
		}
		if (dto.version != SUPPORTED_VERSION)
		{
			throw new IllegalArgumentException(
				"Unsupported authored material catalog version: " + dto.version);
		}
		validateAtlas(dto.atlas);
		Layer[] normalLayers = validateLayers(dto.layers.normal, "normal");
		Layer[] propertyLayers = validateLayers(dto.layers.properties, "property");
		if (!NEUTRAL_SOURCE.equals(normalLayers[0].source)
			|| !NEUTRAL_SOURCE.equals(propertyLayers[0].source))
		{
			throw new IllegalArgumentException(
				"Atlas layer zero must be the generated neutral fallback");
		}

		MaterialDefinition[][] definitions = new MaterialDefinition[
			SurfaceMaterial.values().length][SurfaceMaterial.VARIANT_MASK + 1];
		for (MaterialDto materialDto : dto.materials)
		{
			MaterialDefinition definition = validateMaterial(materialDto,
				normalLayers.length, propertyLayers.length);
			int materialIndex = definition.material.ordinal();
			if (definitions[materialIndex][definition.variant] != null)
			{
				throw new IllegalArgumentException("Duplicate authored material definition: "
					+ definition.material + '/' + definition.variant);
			}
			definitions[materialIndex][definition.variant] = definition;
		}
		for (SurfaceMaterial material : SurfaceMaterial.values())
		{
			if (definitions[material.ordinal()][0] == null)
			{
				throw new IllegalArgumentException(
					"Missing fallback authored definition for " + material);
			}
		}
		return new AuthoredMaterialCatalog(dto.atlas.resolution,
			normalLayers, propertyLayers, definitions);
	}

	private static void validateAtlas(AtlasDto atlas)
	{
		if (atlas.resolution < 4 || atlas.resolution > 1024
			|| (atlas.resolution & atlas.resolution - 1) != 0)
		{
			throw new IllegalArgumentException(
				"Authored atlas resolution must be a power of two from 4 to 1024");
		}
		if (!NORMAL_ENCODING.equals(atlas.normalEncoding)
			|| !PROPERTY_ENCODING.equals(atlas.propertyEncoding))
		{
			throw new IllegalArgumentException("Unsupported authored atlas encoding");
		}
	}

	private static Layer[] validateLayers(LayerDto[] layers, String kind)
	{
		if (layers == null || layers.length == 0)
		{
			throw new IllegalArgumentException("Authored atlas has no " + kind + " layers");
		}
		Layer[] result = new Layer[layers.length];
		for (int i = 0; i < layers.length; ++i)
		{
			LayerDto layer = layers[i];
			if (layer == null || layer.name == null || layer.name.isBlank()
				|| layer.source == null || layer.source.isBlank())
			{
				throw new IllegalArgumentException(kind + " layer " + i + " is incomplete");
			}
			result[i] = new Layer(layer.name, layer.source);
		}
		return result;
	}

	private static MaterialDefinition validateMaterial(MaterialDto dto,
		int normalLayerCount, int propertyLayerCount)
	{
		if (dto == null || dto.material == null || dto.baseColorMode == null)
		{
			throw new IllegalArgumentException("Authored material definition is incomplete");
		}
		SurfaceMaterial material;
		try
		{
			material = SurfaceMaterial.valueOf(dto.material.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex)
		{
			throw new IllegalArgumentException(
				"Unknown authored material: " + dto.material, ex);
		}
		if (dto.variant < 0 || dto.variant > SurfaceMaterial.VARIANT_MASK
			|| dto.normalLayer < 0 || dto.normalLayer >= normalLayerCount
			|| dto.propertyLayer < 0 || dto.propertyLayer >= propertyLayerCount)
		{
			throw new IllegalArgumentException(
				"Authored material has an invalid variant or layer: " + dto.material);
		}
		if (!VANILLA_BASE_COLOR.equals(dto.baseColorMode)
			|| !inRange(dto.uvScale, 0.0625f, 16.0f)
			|| !inRange(dto.normalStrength, 0.0f, 2.0f)
			|| !inRange(dto.roughnessFactor, 0.0f, 2.0f)
			|| !inRange(dto.metallicFactor, 0.0f, 1.0f)
			|| !inRange(dto.aoStrength, 0.0f, 1.0f)
			|| !inRange(dto.heightScale, 0.0f, 0.1f))
		{
			throw new IllegalArgumentException(
				"Authored material properties are out of range: " + dto.material);
		}
		return new MaterialDefinition(material, dto.variant,
			dto.normalLayer, dto.propertyLayer, dto.uvScale,
			dto.normalStrength, dto.roughnessFactor, dto.metallicFactor,
			dto.aoStrength, dto.heightScale);
	}

	private static boolean inRange(float value, float minimum, float maximum)
	{
		return Float.isFinite(value) && value >= minimum && value <= maximum;
	}

	private static AuthoredMaterialCatalog loadBundled()
	{
		try (InputStream stream = AuthoredMaterialCatalog.class
			.getResourceAsStream(RESOURCE))
		{
			if (stream == null)
			{
				throw new IllegalStateException("Missing authored material catalog: " + RESOURCE);
			}
			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8))
			{
				return load(reader);
			}
		}
		catch (IOException | IllegalArgumentException ex)
		{
			throw new IllegalStateException("Unable to load authored material catalog", ex);
		}
	}

	static final class Layer
	{
		private final String name;
		private final String source;

		private Layer(String name, String source)
		{
			this.name = name;
			this.source = source;
		}

		String getName()
		{
			return name;
		}

		String getSource()
		{
			return source;
		}
	}

	static final class MaterialDefinition
	{
		private final SurfaceMaterial material;
		private final int variant;
		private final int normalLayer;
		private final int propertyLayer;
		private final float uvScale;
		private final float normalStrength;
		private final float roughnessFactor;
		private final float metallicFactor;
		private final float aoStrength;
		private final float heightScale;

		private MaterialDefinition(SurfaceMaterial material, int variant,
			int normalLayer, int propertyLayer, float uvScale,
			float normalStrength, float roughnessFactor, float metallicFactor,
			float aoStrength, float heightScale)
		{
			this.material = material;
			this.variant = variant;
			this.normalLayer = normalLayer;
			this.propertyLayer = propertyLayer;
			this.uvScale = uvScale;
			this.normalStrength = normalStrength;
			this.roughnessFactor = roughnessFactor;
			this.metallicFactor = metallicFactor;
			this.aoStrength = aoStrength;
			this.heightScale = heightScale;
		}

		SurfaceMaterial getMaterial()
		{
			return material;
		}

		int getVariant()
		{
			return variant;
		}

		int getNormalLayer()
		{
			return normalLayer;
		}

		int getPropertyLayer()
		{
			return propertyLayer;
		}

		float getUvScale()
		{
			return uvScale;
		}

		float getNormalStrength()
		{
			return normalStrength;
		}

		float getRoughnessFactor()
		{
			return roughnessFactor;
		}

		float getMetallicFactor()
		{
			return metallicFactor;
		}

		float getAoStrength()
		{
			return aoStrength;
		}

		float getHeightScale()
		{
			return heightScale;
		}
	}

	private static final class CatalogDto
	{
		private int version;
		private AtlasDto atlas;
		private LayersDto layers;
		private MaterialDto[] materials;
	}

	private static final class AtlasDto
	{
		private int resolution;
		private String normalEncoding;
		private String propertyEncoding;
	}

	private static final class LayersDto
	{
		private LayerDto[] normal;
		private LayerDto[] properties;
	}

	private static final class LayerDto
	{
		private String name;
		private String source;
	}

	private static final class MaterialDto
	{
		private String material;
		private int variant;
		private int normalLayer;
		private int propertyLayer;
		private String baseColorMode;
		private float uvScale;
		private float normalStrength;
		private float roughnessFactor;
		private float metallicFactor;
		private float aoStrength;
		private float heightScale;
	}
}
