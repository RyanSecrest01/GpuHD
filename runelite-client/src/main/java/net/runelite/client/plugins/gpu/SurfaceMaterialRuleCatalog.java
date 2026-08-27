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
 * Versioned material rules for the live texture cache and scene context.
 *
 * <p>Ordered terrain and object rules may narrow matches by layer, definition,
 * object ID, texture, world area, and plane. Verified texture identity follows
 * those contextual overrides; color heuristics remain the final fallback.</p>
 */
final class SurfaceMaterialRuleCatalog
{
	private static final String RESOURCE = "surface_material_rules.json";
	private static final int SUPPORTED_VERSION = 1;
	private static final int MAX_TEXTURE_ID = 255;
	private static final Match UNKNOWN = new Match(
		SurfaceMaterial.UNKNOWN, 0, "fallback:unknown", false);
	private static volatile SurfaceMaterialRuleCatalog bundled = loadBundled();

	private final Match[] textureMatches;
	private final RuntimeRule[] terrainRules;
	private final RuntimeRule[] objectRules;

	private SurfaceMaterialRuleCatalog(Match[] textureMatches,
		RuntimeRule[] terrainRules, RuntimeRule[] objectRules)
	{
		this.textureMatches = textureMatches;
		this.terrainRules = terrainRules;
		this.objectRules = objectRules;
	}

	static Match resolveTexture(int textureId)
	{
		return bundled.resolve(textureId);
	}

	static Match heuristic(SurfaceMaterial material, String source)
	{
		// Authored slots are explicit replacement identities. Heuristic semantic
		// classification must never select an authored object appearance.
		return new Match(material, material.getDefaultAuthoredVariant(), source, false);
	}

	static Match resolveTerrain(int textureId, int layer, int definitionId,
		int worldX, int worldY, int plane)
	{
		return bundled.resolveTerrainRule(textureId, layer, definitionId,
			worldX, worldY, plane);
	}

	Match resolveTerrainRule(int textureId, int layer, int definitionId,
		int worldX, int worldY, int plane)
	{
		for (RuntimeRule rule : terrainRules)
		{
			if (rule.matchesTerrain(layer, definitionId, worldX, worldY, plane))
			{
				return rule.match;
			}
		}
		return resolve(textureId);
	}

	static Match resolveObject(int textureId, int objectId,
		int worldX, int worldY, int plane)
	{
		return bundled.resolveObjectRule(textureId, objectId, worldX, worldY, plane);
	}

	Match resolveObjectRule(int textureId, int objectId,
		int worldX, int worldY, int plane)
	{
		for (RuntimeRule rule : objectRules)
		{
			if (rule.matchesObject(textureId, objectId, worldX, worldY, plane))
			{
				return rule.match;
			}
		}
		return resolve(textureId);
	}

	static void reloadBundled()
	{
		bundled = loadBundled();
	}

	private Match resolve(int textureId)
	{
		return textureId >= 0 && textureId < textureMatches.length
			&& textureMatches[textureId] != null
			? textureMatches[textureId] : UNKNOWN;
	}

	static SurfaceMaterialRuleCatalog load(Reader reader)
	{
		Definition definition = new Gson().fromJson(reader, Definition.class);
		if (definition == null)
		{
			throw new IllegalArgumentException("Material rule catalog is empty");
		}
		if (definition.version != SUPPORTED_VERSION)
		{
			throw new IllegalArgumentException("Unsupported material rule catalog version: "
				+ definition.version);
		}
		if (definition.textureRules == null)
		{
			throw new IllegalArgumentException("Material rule catalog has no textureRules");
		}

		Match[] matches = new Match[MAX_TEXTURE_ID + 1];
		for (TextureRule rule : definition.textureRules)
		{
			if (rule == null || rule.name == null || rule.name.isBlank()
				|| rule.material == null || rule.material.isBlank()
				|| rule.textures == null || rule.textures.length == 0)
			{
				throw new IllegalArgumentException("Material rule is incomplete");
			}

			SurfaceMaterial material;
			try
			{
				material = SurfaceMaterial.valueOf(
					rule.material.toUpperCase(Locale.ROOT));
			}
			catch (IllegalArgumentException ex)
			{
				throw new IllegalArgumentException(
					"Unknown material in rule " + rule.name + ": " + rule.material, ex);
			}
			if (material == SurfaceMaterial.UNKNOWN)
			{
				throw new IllegalArgumentException(
					"Exact rule must not assign UNKNOWN: " + rule.name);
			}

			int authoredVariant = authoredVariant(rule, material);
			Match match = new Match(material, authoredVariant,
				"texture:" + rule.name, true);
			for (int textureId : rule.textures)
			{
				if (textureId < 0 || textureId > MAX_TEXTURE_ID)
				{
					throw new IllegalArgumentException(
						"Texture ID out of range in rule " + rule.name + ": " + textureId);
				}
				if (matches[textureId] != null)
				{
					throw new IllegalArgumentException("Texture ID " + textureId
						+ " is assigned by both " + matches[textureId].source
						+ " and " + rule.name);
				}
				matches[textureId] = match;
			}
		}
		RuntimeRule[] terrainRules = buildTerrainRules(definition.terrainRules);
		RuntimeRule[] objectRules = buildObjectRules(definition.objectRules);
		return new SurfaceMaterialRuleCatalog(matches, terrainRules, objectRules);
	}

	private static RuntimeRule[] buildTerrainRules(TerrainRule[] rules)
	{
		if (rules == null)
		{
			return new RuntimeRule[0];
		}
		RuntimeRule[] result = new RuntimeRule[rules.length];
		for (int i = 0; i < rules.length; ++i)
		{
			TerrainRule rule = rules[i];
			validateCommonRule(rule, "terrain", i);
			int layer;
			if ("UNDERLAY".equalsIgnoreCase(rule.layer))
			{
				layer = 0;
			}
			else if ("OVERLAY".equalsIgnoreCase(rule.layer))
			{
				layer = 1;
			}
			else
			{
				throw new IllegalArgumentException(
					"Terrain rule has invalid layer: " + rule.name);
			}
			validateIds(rule.ids, "terrain definition", rule.name);
			SurfaceMaterial material = ruleMaterial(rule);
			result[i] = new RuntimeRule(material, authoredVariant(rule, material),
				"terrain:" + rule.name, layer, rule.ids, null,
				rule.area, rule.planes);
		}
		return result;
	}

	private static RuntimeRule[] buildObjectRules(ObjectRule[] rules)
	{
		if (rules == null)
		{
			return new RuntimeRule[0];
		}
		RuntimeRule[] result = new RuntimeRule[rules.length];
		for (int i = 0; i < rules.length; ++i)
		{
			ObjectRule rule = rules[i];
			validateCommonRule(rule, "object", i);
			validateIds(rule.objectIds, "object", rule.name);
			if (rule.textureIds != null)
			{
				validateIds(rule.textureIds, "texture", rule.name);
			}
			SurfaceMaterial material = ruleMaterial(rule);
			result[i] = new RuntimeRule(material, authoredSlot(rule),
				"object:" + rule.name, -1, rule.objectIds, rule.textureIds,
				rule.area, rule.planes);
		}
		return result;
	}

	private static void validateCommonRule(CommonRule rule, String type, int index)
	{
		if (rule == null || rule.name == null || rule.name.isBlank()
			|| rule.material == null || rule.material.isBlank())
		{
			throw new IllegalArgumentException(type + " rule " + index + " is incomplete");
		}
		if (rule.area != null && rule.area.length != 4)
		{
			throw new IllegalArgumentException(type + " rule has invalid area: " + rule.name);
		}
		if (rule.area != null
			&& (rule.area[0] > rule.area[2] || rule.area[1] > rule.area[3]))
		{
			throw new IllegalArgumentException(type + " rule has reversed area: " + rule.name);
		}
		if (rule.planes != null)
		{
			for (int plane : rule.planes)
			{
				if (plane < 0 || plane > 3)
				{
					throw new IllegalArgumentException(
						type + " rule has invalid plane: " + rule.name);
				}
			}
		}
	}

	private static SurfaceMaterial ruleMaterial(CommonRule rule)
	{
		try
		{
			return SurfaceMaterial.valueOf(rule.material.toUpperCase(Locale.ROOT));
		}
		catch (IllegalArgumentException ex)
		{
			throw new IllegalArgumentException(
				"Unknown material in rule " + rule.name + ": " + rule.material, ex);
		}
	}

	private static int authoredVariant(CommonRule rule, SurfaceMaterial material)
	{
		int variant = rule.authoredVariant == null
			? material.getDefaultAuthoredVariant() : rule.authoredVariant;
		if (variant < 0 || variant > SurfaceMaterial.VARIANT_MASK)
		{
			throw new IllegalArgumentException(
				"Rule has invalid authored variant: " + rule.name);
		}
		return variant;
	}

	private static int authoredVariant(TextureRule rule, SurfaceMaterial material)
	{
		int variant = rule.authoredVariant == null
			? material.getDefaultAuthoredVariant() : rule.authoredVariant;
		if (variant < 0 || variant > SurfaceMaterial.VARIANT_MASK)
		{
			throw new IllegalArgumentException(
				"Rule has invalid authored variant: " + rule.name);
		}
		return variant;
	}

	private static int authoredSlot(ObjectRule rule)
	{
		int slot = rule.authoredSlot == null ? 0 : rule.authoredSlot;
		if (slot < 0 || slot > SurfaceMaterial.AUTHORED_SLOT_MASK)
		{
			throw new IllegalArgumentException(
				"Object rule has invalid authored slot: " + rule.name);
		}
		return slot;
	}

	private static void validateIds(int[] ids, String type, String ruleName)
	{
		if (ids == null || ids.length == 0)
		{
			throw new IllegalArgumentException(
				"Rule has no " + type + " IDs: " + ruleName);
		}
		for (int id : ids)
		{
			if (id < 0)
			{
				throw new IllegalArgumentException(
					"Rule has invalid " + type + " ID: " + ruleName);
			}
		}
	}

	private static SurfaceMaterialRuleCatalog loadBundled()
	{
		try (InputStream stream = SurfaceMaterialRuleCatalog.class
			.getResourceAsStream(RESOURCE))
		{
			if (stream == null)
			{
				throw new IllegalStateException("Missing material rule catalog: " + RESOURCE);
			}
			try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8))
			{
				return load(reader);
			}
		}
		catch (IOException | IllegalArgumentException ex)
		{
			throw new IllegalStateException("Unable to load material rule catalog", ex);
		}
	}

	static final class Match
	{
		private final SurfaceMaterial material;
		private final int authoredSlot;
		private final String source;
		private final boolean exact;

		private Match(SurfaceMaterial material, int authoredSlot,
			String source, boolean exact)
		{
			this.material = material;
			this.authoredSlot = authoredSlot;
			this.source = source;
			this.exact = exact;
		}

		SurfaceMaterial getMaterial()
		{
			return material;
		}

		int getAuthoredSlot()
		{
			return authoredSlot;
		}

		int getAuthoredVariant()
		{
			return authoredSlot;
		}

		int packTextureCode(int textureCode)
		{
			return material.packTextureCode(textureCode, authoredSlot);
		}

		String getSource()
		{
			return source;
		}

		boolean isExact()
		{
			return exact;
		}
	}

	private static final class Definition
	{
		private int version;
		private TextureRule[] textureRules;
		private TerrainRule[] terrainRules;
		private ObjectRule[] objectRules;
	}

	private static final class TextureRule
	{
		private String name;
		private String material;
		private Integer authoredVariant;
		private int[] textures;
	}

	private abstract static class CommonRule
	{
		String name;
		String material;
		Integer authoredVariant;
		int[] area;
		int[] planes;
	}

	private static final class TerrainRule extends CommonRule
	{
		private String layer;
		private int[] ids;
	}

	private static final class ObjectRule extends CommonRule
	{
		private int[] objectIds;
		private int[] textureIds;
		private Integer authoredSlot;
	}

	private static final class RuntimeRule
	{
		private final Match match;
		private final int layer;
		private final int[] ids;
		private final int[] textureIds;
		private final int[] area;
		private final int[] planes;

		private RuntimeRule(SurfaceMaterial material, int authoredSlot,
			String source,
			int layer, int[] ids, int[] textureIds, int[] area, int[] planes)
		{
			this.match = new Match(material, authoredSlot, source, true);
			this.layer = layer;
			this.ids = ids;
			this.textureIds = textureIds;
			this.area = area;
			this.planes = planes;
		}

		private boolean matchesTerrain(int candidateLayer, int definitionId,
			int worldX, int worldY, int plane)
		{
			return layer == candidateLayer && contains(ids, definitionId)
				&& matchesLocation(worldX, worldY, plane);
		}

		private boolean matchesObject(int textureId, int objectId,
			int worldX, int worldY, int plane)
		{
			return contains(ids, objectId)
				&& (textureIds == null || contains(textureIds, textureId))
				&& matchesLocation(worldX, worldY, plane);
		}

		private boolean matchesLocation(int worldX, int worldY, int plane)
		{
			return (planes == null || contains(planes, plane))
				&& (area == null || worldX >= area[0] && worldX <= area[2]
					&& worldY >= area[1] && worldY <= area[3]);
		}

		private static boolean contains(int[] values, int candidate)
		{
			for (int value : values)
			{
				if (value == candidate)
				{
					return true;
				}
			}
			return false;
		}
	}
}
