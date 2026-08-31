/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 * All rights reserved.
 */
package net.runelite.client.plugins.gpu;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

/** Exact-ID authored appearance registry. Filenames are the mapping. */
@Slf4j
final class HdTextureRegistry
{
	static final int VANILLA_LAYER_COUNT = TextureManager.TEXTURE_COUNT;
	private static final String ROOT = "net/runelite/client/plugins/gpu/hd-textures/";
	private static final Pattern ASSET = Pattern.compile(
		"(vanilla/texture|terrain/underlay|terrain/overlay|objects/object)-(\\d+)\\.png");
	private static final HdTextureRegistry INSTANCE = load();

	@Value
	static class Asset
	{
		int layer;
		String resource;
	}

	@Value
	static class ObjectOverride
	{
		int layer;
		UvMode uvMode;
		float uvScale;
	}

	enum UvMode
	{
		VANILLA,
		PLANAR
	}

	private final Map<Integer, Asset> vanilla;
	private final Map<Integer, ObjectOverride> objects;
	private final Map<Integer, Asset> underlays;
	private final Map<Integer, Asset> overlays;
	private final List<Asset> synthetic;
	private final int layerCount;

	private HdTextureRegistry(Map<Integer, Asset> vanilla,
		Map<Integer, ObjectOverride> objects, Map<Integer, Asset> underlays,
		Map<Integer, Asset> overlays, List<Asset> synthetic, int layerCount)
	{
		this.vanilla = vanilla;
		this.objects = objects;
		this.underlays = underlays;
		this.overlays = overlays;
		this.synthetic = synthetic;
		this.layerCount = layerCount;
	}

	static HdTextureRegistry get()
	{
		return INSTANCE;
	}

	int getLayerCount()
	{
		return layerCount;
	}

	List<Asset> getVanillaAssets()
	{
		return List.copyOf(vanilla.values());
	}

	Asset getVanilla(int id)
	{
		return vanilla.get(id);
	}

	List<Asset> getSyntheticAssets()
	{
		return synthetic;
	}

	ObjectOverride getObject(int id)
	{
		return objects.get(id);
	}

	Asset getUnderlay(int id)
	{
		return underlays.get(id);
	}

	Asset getOverlay(int id)
	{
		return overlays.get(id);
	}

	private static HdTextureRegistry load()
	{
		Map<String, URL> resources = discover();
		Map<Integer, Asset> vanilla = new TreeMap<>();
		Map<Integer, ObjectOverride> objects = new HashMap<>();
		Map<Integer, Asset> underlays = new TreeMap<>(), overlays = new TreeMap<>();
		List<String> objectPaths = new ArrayList<>(), underlayPaths = new ArrayList<>(), overlayPaths = new ArrayList<>();
		for (String path : resources.keySet())
		{
			Matcher match = ASSET.matcher(path);
			if (!match.matches())
			{
				continue;
			}
			int id = Integer.parseInt(match.group(2));
			if (match.group(1).equals("vanilla/texture"))
			{
				if (id < 0 || id >= VANILLA_LAYER_COUNT)
				{
					throw new IllegalArgumentException("Vanilla texture ID is outside 0-255: " + path);
				}
				putUnique(vanilla, id, new Asset(id, ROOT + path), "vanilla texture");
			}
			else if (match.group(1).equals("objects/object"))
			{
				objectPaths.add(path);
			}
			else if (match.group(1).equals("terrain/underlay"))
			{
				underlayPaths.add(path);
			}
			else
			{
				overlayPaths.add(path);
			}
		}
		Collections.sort(objectPaths);
		Collections.sort(underlayPaths);
		Collections.sort(overlayPaths);
		List<Asset> synthetic = new ArrayList<>();
		int layer = VANILLA_LAYER_COUNT;
		for (String path : objectPaths)
		{
			int id = id(path);
			Asset asset = new Asset(layer++, ROOT + path);
			synthetic.add(asset);
			ObjectMetadata metadata = metadata(ROOT + "objects/object-" + id + ".json");
			UvMode mode = metadata == null || metadata.uvMode == null ? UvMode.VANILLA : UvMode.valueOf(metadata.uvMode);
			float scale = metadata == null || metadata.uvScale <= 0 ? 1f : metadata.uvScale;
			putUnique(objects, id, new ObjectOverride(asset.layer, mode, scale), "object override");
		}
		for (String path : underlayPaths)
		{
			Asset asset = new Asset(layer++, ROOT + path);
			synthetic.add(asset);
			putUnique(underlays, id(path), asset, "underlay");
		}
		for (String path : overlayPaths)
		{
			Asset asset = new Asset(layer++, ROOT + path);
			synthetic.add(asset);
			putUnique(overlays, id(path), asset, "overlay");
		}
		if (layer > 511)
		{
			throw new IllegalStateException("HD texture registry exceeds the packed 511-layer limit: " + layer);
		}
		log.info("Loaded {} remastered vanilla textures, {} object overrides, {} underlays, {} overlays",
			vanilla.size(), objects.size(), underlays.size(), overlays.size());
		return new HdTextureRegistry(vanilla, objects, underlays, overlays, List.copyOf(synthetic), layer);
	}

	private static int id(String path)
	{
		Matcher matcher = ASSET.matcher(path);
		if (!matcher.matches())
		{
			throw new IllegalArgumentException(path);
		}
		return Integer.parseInt(matcher.group(2));
	}

	private static <T> void putUnique(Map<Integer, T> target, int id, T value, String namespace)
	{
		if (target.putIfAbsent(id, value) != null)
		{
			throw new IllegalArgumentException("Duplicate " + namespace + " ID " + id);
		}
	}

	private static ObjectMetadata metadata(String resource)
	{
		try (var in = HdTextureRegistry.class.getClassLoader().getResourceAsStream(resource))
		{
			return in == null ? null : new Gson().fromJson(new java.io.InputStreamReader(in), ObjectMetadata.class);
		}
		catch (Exception ex)
		{
			throw new IllegalArgumentException("Invalid HD texture metadata " + resource, ex);
		}
	}

	private static Map<String, URL> discover()
	{
		Map<String, URL> result = new TreeMap<>();
		try
		{
			Enumeration<URL> roots = HdTextureRegistry.class.getClassLoader().getResources(ROOT);
			while (roots.hasMoreElements())
			{
				URL root = roots.nextElement();
				if (root.getProtocol().equals("file"))
				{
					scanDirectory(new File(root.toURI()), "", result);
				}
				else if (root.getProtocol().equals("jar"))
				{
					scanJar((JarURLConnection) root.openConnection(), result);
				}
			}
		}
		catch (Exception ex)
		{
			throw new IllegalStateException("Unable to discover HD textures", ex);
		}
		return result;
	}

	private static void scanDirectory(File directory, String prefix, Map<String, URL> result) throws IOException
	{
		File[] files = directory.listFiles();
		if (files == null)
		{
			return;
		}
		for (File file : files)
		{
			String path = prefix + file.getName();
			if (file.isDirectory())
			{
				scanDirectory(file, path + "/", result);
			}
			else
			{
				result.put(path, file.toURI().toURL());
			}
		}
	}

	private static void scanJar(JarURLConnection connection, Map<String, URL> result) throws IOException
	{
		try (JarFile jar = connection.getJarFile())
		{
			for (Enumeration<JarEntry> entries = jar.entries(); entries.hasMoreElements(); )
			{
				String name = entries.nextElement().getName();
				if (name.startsWith(ROOT) && !name.endsWith("/"))
				{
					result.put(name.substring(ROOT.length()),
						new URL("jar:" + connection.getJarFileURL() + "!/" + name));
				}
			}
		}
	}

	private static class ObjectMetadata
	{
		String uvMode;
		float uvScale = 1f;
	}
}
