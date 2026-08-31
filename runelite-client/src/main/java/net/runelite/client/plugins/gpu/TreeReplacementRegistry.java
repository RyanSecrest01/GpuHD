/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 */
package net.runelite.client.plugins.gpu;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exact object-ID registry for opt-in visual-only tree replacements. */
final class TreeReplacementRegistry
{
	static final String RESOURCE_ROOT = "/net/runelite/client/plugins/gpu/vegetation/trees/";
	private static final String CATALOG = RESOURCE_ROOT + "tree-replacements.json";

	private final List<Definition> definitions;
	private final Map<Integer, Definition> objects;
	private final Map<Integer, Definition> auxiliaryObjects;
	private final Map<CoordinateKey, Definition> coordinates;
	private final Set<Integer> activeDefinitions = new HashSet<>();

	private TreeReplacementRegistry(List<Definition> definitions,
		Map<Integer, Definition> objects, Map<Integer, Definition> auxiliaryObjects,
		Map<CoordinateKey, Definition> coordinates)
	{
		this.definitions = Collections.unmodifiableList(definitions);
		this.objects = objects;
		this.auxiliaryObjects = auxiliaryObjects;
		this.coordinates = coordinates;
	}

	static TreeReplacementRegistry load() throws IOException
	{
		try (InputStream input = TreeReplacementRegistry.class.getResourceAsStream(CATALOG))
		{
			if (input == null)
			{
				throw new IOException("Missing tree replacement catalog: " + CATALOG);
			}
			Catalog catalog = new Gson().fromJson(new InputStreamReader(input,
				StandardCharsets.UTF_8), Catalog.class);
			if (catalog == null || catalog.trees == null)
			{
				throw new IOException("Tree replacement catalog has no trees object");
			}
			List<Definition> definitions = new ArrayList<>();
			Map<String, Definition> names = new LinkedHashMap<>();
			Map<Integer, Definition> objects = new HashMap<>();
			Map<Integer, Definition> auxiliaryObjects = new HashMap<>();
			for (Map.Entry<String, Entry> item : catalog.trees.entrySet())
			{
				String name = item.getKey();
				Entry entry = item.getValue();
				if (name == null || name.isBlank() || entry == null)
				{
					throw new IOException("Invalid tree definition: " + name);
				}
				String[] models = buildLodModels(name, entry);
				Map<String, MaterialOverride> materialOverrides =
					buildMaterialOverrides(name, entry.materialOverrides);
				Definition definition = new Definition(definitions.size(), name, models,
					entry.scale == null ? 8.0f : entry.scale,
					entry.rotationOffset == null ? 0.0f : entry.rotationOffset,
					entry.randomYaw == null || entry.randomYaw,
					entry.windStrength == null ? 0.0f : entry.windStrength,
					entry.foliageTransmission == null ? 0.0f : entry.foliageTransmission,
					entry.objectIds == null ? new int[0] : entry.objectIds,
					entry.auxiliaryRadius == null ? 0 : entry.auxiliaryRadius,
					materialOverrides);
				if (!(definition.scale > 0.0f) || !Float.isFinite(definition.scale))
				{
					throw new IOException("Tree definition has invalid scale: " + name);
				}
				definitions.add(definition);
				names.put(name, definition);
				if (entry.objectIds != null)
				{
					for (int objectId : entry.objectIds)
					{
						Definition previous = objects.putIfAbsent(objectId, definition);
						if (previous != null)
						{
							throw new IOException("Tree object ID " + objectId
								+ " is assigned to both " + previous.name + " and " + name);
						}
					}
				}
				if (definition.auxiliaryRadius < 0 || definition.auxiliaryRadius > 16)
				{
					throw new IOException("Tree definition has invalid auxiliary radius: " + name);
				}
				if (entry.auxiliaryObjectIds != null)
				{
					for (int objectId : entry.auxiliaryObjectIds)
					{
						Definition previous = auxiliaryObjects.putIfAbsent(objectId, definition);
						if (previous != null)
						{
							throw new IOException("Tree auxiliary object ID " + objectId
								+ " is assigned to both " + previous.name + " and " + name);
						}
					}
				}
			}

			Map<CoordinateKey, Definition> coordinates = new HashMap<>();
			if (catalog.coordinateOverrides != null)
			{
				for (CoordinateOverride override : catalog.coordinateOverrides)
				{
					Definition definition = names.get(override.tree);
					if (definition == null)
					{
						throw new IOException("Coordinate override references unknown tree: "
							+ override.tree);
					}
					CoordinateKey key = new CoordinateKey(override.objectId,
						override.worldX, override.worldY, override.plane);
					if (coordinates.putIfAbsent(key, definition) != null)
					{
						throw new IOException("Duplicate tree coordinate override at "
							+ override.worldX + "," + override.worldY + "," + override.plane);
					}
				}
			}
			return new TreeReplacementRegistry(definitions, objects,
				auxiliaryObjects, coordinates);
		}
	}

	private static String[] buildLodModels(String tree, Entry entry) throws IOException
	{
		String[] source = {entry.lod0 != null ? entry.lod0 : entry.model,
			entry.lod1, entry.lod2, entry.lod3};
		if (source[0] == null || source[0].isBlank())
		{
			throw new IOException("Tree definition has no LOD0 model: " + tree);
		}
		String[] models = new String[4];
		for (int lod = 0; lod < models.length; ++lod)
		{
			if (source[lod] != null && !source[lod].isBlank())
			{
				models[lod] = normalizeModelPath(source[lod]);
			}
		}
		return models;
	}

	private static String normalizeModelPath(String model) throws IOException
	{
		String path = model.replace('\\', '/');
		if (path.startsWith("/") || path.contains("..") || !path.endsWith(".glb"))
		{
			throw new IOException("Unsafe or unsupported tree model path: " + model);
		}
		return path;
	}

	private static Map<String, MaterialOverride> buildMaterialOverrides(String tree,
		Map<String, MaterialOverrideEntry> entries) throws IOException
	{
		if (entries == null || entries.isEmpty())
		{
			return Collections.emptyMap();
		}
		Map<String, MaterialOverride> overrides = new LinkedHashMap<>();
		for (Map.Entry<String, MaterialOverrideEntry> item : entries.entrySet())
		{
			String material = item.getKey();
			MaterialOverrideEntry entry = item.getValue();
			if (material == null || material.isBlank() || entry == null
				|| entry.texture == null || entry.texture.isBlank())
			{
				throw new IOException("Invalid material override for tree: " + tree);
			}
			String texture = entry.texture.replace('\\', '/');
			if (texture.startsWith("/") || texture.contains("..")
				|| !texture.toLowerCase().endsWith(".png"))
			{
				throw new IOException("Unsafe tree material texture: " + texture);
			}
			if (entry.alphaCutoff != null && (!(entry.alphaCutoff >= 0.0f)
				|| entry.alphaCutoff > 1.0f || !Float.isFinite(entry.alphaCutoff)))
			{
				throw new IOException("Invalid alpha cutoff for tree material: " + material);
			}
			if (entry.windResponse != null && (!(entry.windResponse >= 0.0f)
				|| entry.windResponse > 2.0f || !Float.isFinite(entry.windResponse)))
			{
				throw new IOException("Invalid wind response for tree material: " + material);
			}
			overrides.put(material, new MaterialOverride(texture,
				entry.alphaCutoff, entry.doubleSided, entry.windResponse));
		}
		return Collections.unmodifiableMap(overrides);
	}

	List<Definition> getDefinitions()
	{
		return definitions;
	}

	void setActive(Definition definition, boolean active)
	{
		if (active)
		{
			activeDefinitions.add(definition.index);
		}
		else
		{
			activeDefinitions.remove(definition.index);
		}
	}

	Definition resolve(int objectId, int worldX, int worldY, int plane)
	{
		Definition definition = coordinates.get(new CoordinateKey(objectId,
			worldX, worldY, plane));
		if (definition == null)
		{
			definition = objects.get(objectId);
		}
		return definition != null && activeDefinitions.contains(definition.index)
			? definition : null;
	}

	Definition resolveAuxiliary(int objectId)
	{
		Definition definition = auxiliaryObjects.get(objectId);
		return definition != null && activeDefinitions.contains(definition.index)
			? definition : null;
	}

	static final class Definition
	{
		final int index;
		final String name;
		final String model;
		final String[] lodModels;
		final float scale;
		final float rotationOffset;
		final boolean randomYaw;
		final float windStrength;
		final float foliageTransmission;
		final int[] objectIds;
		final int auxiliaryRadius;
		final Map<String, MaterialOverride> materialOverrides;

		private Definition(int index, String name, String[] lodModels, float scale,
			float rotationOffset, boolean randomYaw, float windStrength,
			float foliageTransmission, int[] objectIds, int auxiliaryRadius,
			Map<String, MaterialOverride> materialOverrides)
		{
			this.index = index;
			this.name = name;
			this.lodModels = lodModels.clone();
			this.model = lodModels[0];
			this.scale = scale;
			this.rotationOffset = rotationOffset;
			this.randomYaw = randomYaw;
			this.windStrength = windStrength;
			this.foliageTransmission = foliageTransmission;
			this.objectIds = objectIds.clone();
			this.auxiliaryRadius = auxiliaryRadius;
			this.materialOverrides = materialOverrides;
		}

		String resourcePath()
		{
			return RESOURCE_ROOT + model;
		}

		String resourcePath(int lod)
		{
			String modelPath = lod >= 0 && lod < lodModels.length
				? lodModels[lod] : null;
			return modelPath == null ? null : RESOURCE_ROOT + modelPath;
		}

		boolean matchesPrimaryObject(int objectId)
		{
			for (int candidate : objectIds)
			{
				if (candidate == objectId)
				{
					return true;
				}
			}
			return false;
		}
	}

	static final class MaterialOverride
	{
		final String texture;
		final Float alphaCutoff;
		final Boolean doubleSided;
		final Float windResponse;

		private MaterialOverride(String texture, Float alphaCutoff,
			Boolean doubleSided, Float windResponse)
		{
			this.texture = texture;
			this.alphaCutoff = alphaCutoff;
			this.doubleSided = doubleSided;
			this.windResponse = windResponse;
		}
	}

	private static final class Catalog
	{
		private LinkedHashMap<String, Entry> trees;
		private List<CoordinateOverride> coordinateOverrides;
	}

	private static final class Entry
	{
		private int[] objectIds;
		private int[] auxiliaryObjectIds;
		private Integer auxiliaryRadius;
		private String model;
		private String lod0;
		private String lod1;
		private String lod2;
		private String lod3;
		private Float scale;
		private Float rotationOffset;
		private Boolean randomYaw;
		private Float windStrength;
		private Float foliageTransmission;
		private LinkedHashMap<String, MaterialOverrideEntry> materialOverrides;
	}

	private static final class MaterialOverrideEntry
	{
		private String texture;
		private Float alphaCutoff;
		private Boolean doubleSided;
		private Float windResponse;
	}

	private static final class CoordinateOverride
	{
		private int objectId;
		private int worldX;
		private int worldY;
		private int plane;
		private String tree;
	}

	private static final class CoordinateKey
	{
		private final int objectId;
		private final int worldX;
		private final int worldY;
		private final int plane;

		private CoordinateKey(int objectId, int worldX, int worldY, int plane)
		{
			this.objectId = objectId;
			this.worldX = worldX;
			this.worldY = worldY;
			this.plane = plane;
		}

		@Override
		public boolean equals(Object object)
		{
			if (this == object)
			{
				return true;
			}
			if (!(object instanceof CoordinateKey))
			{
				return false;
			}
			CoordinateKey other = (CoordinateKey) object;
			return objectId == other.objectId && worldX == other.worldX
				&& worldY == other.worldY && plane == other.plane;
		}

		@Override
		public int hashCode()
		{
			int hash = objectId;
			hash = 31 * hash + worldX;
			hash = 31 * hash + worldY;
			return 31 * hash + plane;
		}
	}
}
