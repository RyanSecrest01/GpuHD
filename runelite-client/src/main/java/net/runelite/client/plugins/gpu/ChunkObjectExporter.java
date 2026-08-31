package net.runelite.client.plugins.gpu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Model;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.WallObject;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.coords.WorldPoint;

@Singleton
@Slf4j
final class ChunkObjectExporter
{
	private static final Gson GSON =
			new GsonBuilder()
					.setPrettyPrinting()
					.create();

	private final Client client;

	@Inject
	ChunkObjectExporter(Client client)
	{
		this.client = client;
	}

	Path exportCurrentChunk()
	{
		if (client.getLocalPlayer() == null)
		{
			throw new IllegalStateException(
					"Cannot export objects before the local player exists."
			);
		}

		WorldView worldView =
				client.getLocalPlayer().getWorldView();

		if (worldView == null)
		{
			worldView =
					client.getTopLevelWorldView();
		}

		Scene scene =
				worldView.getScene();

		WorldPoint player =
				client.getLocalPlayer().getWorldLocation();

		/*
		 * OSRS map chunks are 8x8 world tiles.
		 */
		int chunkMinX =
				player.getX() & ~7;

		int chunkMinY =
				player.getY() & ~7;

		int chunkMaxX =
				chunkMinX + 7;

		int chunkMaxY =
				chunkMinY + 7;

		Map<Integer, ObjectRecord> records =
				new LinkedHashMap<>();

		Tile[][][] tiles =
				scene.getTiles();

		/*
		 * Scan every plane because roofs/walls above the player's
		 * current plane may still matter for authored materials.
		 */
		for (int plane = 0;
			plane < tiles.length;
			++plane)
		{
			Tile[][] planeTiles =
					tiles[plane];

			if (planeTiles == null)
			{
				continue;
			}

			for (Tile[] row : planeTiles)
			{
				if (row == null)
				{
					continue;
				}

				for (Tile tile : row)
				{
					if (tile == null)
					{
						continue;
					}

					WorldPoint location =
							tile.getWorldLocation();

					if (location == null)
					{
						continue;
					}

					int worldX =
							location.getX();

					int worldY =
							location.getY();

					if (worldX < chunkMinX
							|| worldX > chunkMaxX
							|| worldY < chunkMinY
							|| worldY > chunkMaxY)
					{
						continue;
					}

					add(
							records,
							tile.getWallObject(),
							"WALL",
							location
					);

					add(
							records,
							tile.getDecorativeObject(),
							"DECORATIVE",
							location
					);

					add(
							records,
							tile.getGroundObject(),
							"GROUND",
							location
					);

					GameObject[] gameObjects =
							tile.getGameObjects();

					if (gameObjects != null)
					{
						for (GameObject gameObject : gameObjects)
						{
							add(
									records,
									gameObject,
									"GAME",
									location
							);
						}
					}
				}
			}
		}

		List<ObjectRecord> sorted =
				new ArrayList<>(
						records.values()
				);

		sorted.sort(
				Comparator.comparingInt(
						record -> record.id
				)
		);

		try
		{
			Path catalog = updateMasterCatalog(scene, sorted, chunkMinX, chunkMinY);
			log.info("Merged chunk {},{} into {} ({} observed objects)",
					chunkMinX, chunkMinY, catalog, sorted.size());
			return catalog;
		}
		catch (IOException ex)
		{
			throw new IllegalStateException("Unable to update master surface catalog", ex);
		}
	}

	private Path updateMasterCatalog(Scene scene, List<ObjectRecord> objects,
			int minX, int minY) throws IOException
	{
		Path root = findRepositoryRoot();
		Path file = root.resolve("data/master-surface-catalog.json");
		Files.createDirectories(file.getParent());
		MasterCatalog catalog = Files.exists(file)
				? GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), MasterCatalog.class)
				: new MasterCatalog();
		if (catalog == null) catalog = new MasterCatalog();
		catalog.normalize();

		for (ObjectRecord observed : objects)
		{
			ObjectRecord stored = catalog.objects.computeIfAbsent(observed.id, ignored -> new ObjectRecord());
			stored.id = observed.id;
			if (observed.name != null && !observed.name.isBlank())
			{
				stored.name = observed.name;
			}
			stored.types.addAll(observed.types);
			stored.textureIds.addAll(observed.textureIds);
			stored.orientations.addAll(observed.orientations);
			String sighting = minX + ":" + minY + ":" + observed.samplePlane;
			stored.sightings.add(sighting);
			stored.observedCounts.put(sighting, observed.occurrences);
			stored.occurrences = stored.observedCounts.values().stream().mapToInt(Integer::intValue).sum();
			if (stored.sampleWorldX == null)
			{
				stored.sampleWorldX = observed.sampleWorldX;
				stored.sampleWorldY = observed.sampleWorldY;
				stored.samplePlane = observed.samplePlane;
			}
		}

		TextureProvider provider = client.getTextureProvider();
		Texture[] textures = provider == null ? null : provider.getTextures();
		if (textures != null) for (int id = 0; id < textures.length; id++)
		{
			if (textures[id] == null) continue;
			int[] pixels = provider.load(id); if (pixels == null) continue;
			TextureRecord record = catalog.textures.computeIfAbsent(id, ignored -> new TextureRecord());
			record.id = id;
			record.sourceWidth = (int) Math.sqrt(pixels.length);
			record.sourceHeight = record.sourceWidth;
			int transparent = 0; for (int pixel : pixels) if (pixel == 0) transparent++;
			record.hasTransparency = transparent > 0;
			record.transparentPixels = transparent;
			record.transparentCoverage = pixels.length == 0 ? 0 : transparent / (double) pixels.length;
		}

		short[][][] underlays = scene.getUnderlayIds(), overlays = scene.getOverlayIds();
		String terrainScope = minX + ":" + minY;
		Map<Integer, ColorObservation> observedUnderlays = new TreeMap<>();
		Map<Integer, ColorObservation> observedOverlays = new TreeMap<>();
		for (Tile[][] plane : scene.getTiles()) if (plane != null) for (Tile[] row : plane) if (row != null) for (Tile tile : row)
		{
			if (tile == null || tile.getWorldLocation() == null) continue;
			int x = tile.getWorldLocation().getX(), y = tile.getWorldLocation().getY();
			if (x < minX || x > minX + 7 || y < minY || y > minY + 7) continue;
			int p = tile.getPlane(), sx = tile.getSceneLocation().getX(), sy = tile.getSceneLocation().getY();
			int underlayId = underlays[p][sx][sy] & 0xffff;
			int overlayId = overlays[p][sx][sy] & 0xffff;
			mergeTerrainSighting(catalog.underlays, underlayId, x, y, p);
			mergeTerrainSighting(catalog.overlays, overlayId, x, y, p);
			observeTerrain(tile, underlayId, overlayId, observedUnderlays, observedOverlays);
		}
		mergeTerrainObservations(catalog.underlays, observedUnderlays, terrainScope);
		mergeTerrainObservations(catalog.overlays, observedOverlays, terrainScope);
		for (TerrainRecord record : catalog.underlays.values()) record.recalculateColor();
		for (TerrainRecord record : catalog.overlays.values()) record.recalculateColor();

		writeCatalog(file, GSON.toJson(catalog) + "\n");
		return file;
	}

	private static void writeCatalog(Path file, String json) throws IOException
	{
		Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
		try
		{
			Files.writeString(temporary, json, StandardCharsets.UTF_8);
			try
			{
				Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
			}
			catch (AtomicMoveNotSupportedException ex)
			{
				Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		finally
		{
			Files.deleteIfExists(temporary);
		}
	}

	private static void mergeTerrainSighting(Map<Integer, TerrainRecord> records,
			int id, int x, int y, int plane)
	{
		if (id == 0) return;
		TerrainRecord record = records.computeIfAbsent(id, ignored -> new TerrainRecord());
		record.id = id; record.sightings.add((x & ~7) + ":" + (y & ~7) + ":" + plane);
	}

	private static void observeTerrain(Tile tile, int underlayId, int overlayId,
			Map<Integer, ColorObservation> underlays,
			Map<Integer, ColorObservation> overlays)
	{
		SceneTilePaint paint = tile.getSceneTilePaint();
		if (paint != null)
		{
			int layer = SceneUploader.paintTerrainLayer(underlayId - 1, overlayId - 1);
			Map<Integer, ColorObservation> target = layer == 1 ? overlays : underlays;
			int id = layer == 1 ? overlayId : underlayId;
			mergeTerrainFace(target, id, paint.getTexture(),
					paint.getSwColor(), paint.getSeColor(), paint.getNwColor(), paint.getNeColor());
		}

		SceneTileModel model = tile.getSceneTileModel();
		if (model != null)
		{
			int[] colorsA = model.getTriangleColorA();
			int[] colorsB = model.getTriangleColorB();
			int[] colorsC = model.getTriangleColorC();
			int[] textures = model.getTriangleTextureId();
			for (int face = 0; face < colorsA.length; face++)
			{
				int layer = SurfaceMaterialClassifier.terrainLayerForFace(model.getShape(), face);
				if (layer < 0) continue;
				Map<Integer, ColorObservation> target = layer == 1 ? overlays : underlays;
				int id = layer == 1 ? overlayId : underlayId;
				int textureId = textures == null ? -1 : textures[face];
				mergeTerrainFace(target, id, textureId,
						colorsA[face], colorsB[face], colorsC[face]);
			}
		}
	}

	private static void mergeTerrainFace(Map<Integer, ColorObservation> records,
			int id, int textureId, int... colors)
	{
		if (id == 0) return;
		ColorObservation observation = records.computeIfAbsent(id, ignored -> new ColorObservation());
		if (textureId >= 0) observation.textureIds.add(textureId);
		for (int color : colors)
		{
			if (color >= 0 && color <= 0xffff)
			{
				observation.sum += color;
				observation.samples++;
			}
		}
	}

	private static void mergeTerrainObservations(Map<Integer, TerrainRecord> records,
			Map<Integer, ColorObservation> observations, String scope)
	{
		for (Map.Entry<Integer, ColorObservation> entry : observations.entrySet())
		{
			TerrainRecord record = records.computeIfAbsent(entry.getKey(), ignored -> new TerrainRecord());
			record.id = entry.getKey();
			record.colorObservations.put(scope, entry.getValue());
			record.observedTextureIds.addAll(entry.getValue().textureIds);
		}
	}

	private static Path findRepositoryRoot()
	{
		String configured = System.getProperty("gpuhd.repoRoot");
		if (configured != null && !configured.isBlank())
		{
			Path root = Path.of(configured).toAbsolutePath().normalize();
			if (isRepositoryRoot(root))
			{
				return root;
			}
			throw new IllegalStateException("Configured gpuhd.repoRoot is not a RuneLite repository: " + root);
		}
		Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		while (current != null)
		{
			if (isRepositoryRoot(current))
			{
				return current;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("GpuHD authoring export requires a repository root; set -Dgpuhd.repoRoot=<path>");
	}

	private static boolean isRepositoryRoot(Path path)
	{
		return Files.isDirectory(path.resolve("runelite-client"))
			&& (Files.exists(path.resolve("settings.gradle.kts"))
				|| Files.exists(path.resolve("settings.gradle")));
	}

	private void add(
			Map<Integer, ObjectRecord> records,
			TileObject object,
			String type,
			WorldPoint tileLocation)
	{
		if (object == null)
		{
			return;
		}

		int id =
				object.getId();

		ObjectRecord record =
				records.computeIfAbsent(
						id,
						ignored ->
						{
							ObjectRecord created =
									new ObjectRecord();

							created.id =
									id;

							created.name =
									getObjectName(id);

							return created;
						}
				);

		record.types.add(
				type
		);

		record.occurrences++;
		if (object instanceof GameObject) record.orientations.add(((GameObject) object).getOrientation());
		else if (object instanceof WallObject)
		{
			record.orientations.add(((WallObject) object).getOrientationA());
			record.orientations.add(((WallObject) object).getOrientationB());
		}
		collectTextures(record, object);

		/*
		 * Keep one example position so you can quickly locate
		 * an object again without bloating the dump.
		 */
		if (record.sampleWorldX == null)
		{
			record.sampleWorldX =
					tileLocation.getX();

			record.sampleWorldY =
					tileLocation.getY();

			record.samplePlane =
					tileLocation.getPlane();
		}
	}

	private static void collectTextures(ObjectRecord record, TileObject object)
	{
		List<Renderable> renderables = new ArrayList<>();
		if (object instanceof WallObject)
		{
			renderables.add(((WallObject) object).getRenderable1());
			renderables.add(((WallObject) object).getRenderable2());
		}
		else if (object instanceof GameObject) renderables.add(((GameObject) object).getRenderable());
		else if (object instanceof DecorativeObject)
		{
			renderables.add(((DecorativeObject) object).getRenderable());
			renderables.add(((DecorativeObject) object).getRenderable2());
		}
		else if (object instanceof GroundObject) renderables.add(((GroundObject) object).getRenderable());
		for (Renderable renderable : renderables)
		{
			if (renderable == null) continue;
			Model model = renderable instanceof Model ? (Model) renderable : renderable.getModel();
			if (model == null || model.getFaceTextures() == null) continue;
			for (short texture : model.getFaceTextures()) if (texture >= 0) record.textureIds.add((int) texture);
		}
	}

	private String getObjectName(int id)
	{
		try
		{
			ObjectComposition composition =
					client.getObjectDefinition(
							id
					);

			if (composition == null
					|| composition.getName() == null)
			{
				return "";
			}

			return composition.getName();
		}
		catch (RuntimeException ex)
		{
			return "";
		}
	}

	private static final class ObjectRecord
	{
		int id;
		String name;

		Set<String> types =
				new java.util.TreeSet<>();
		Set<Integer> textureIds = new java.util.TreeSet<>();
		Set<Integer> orientations = new java.util.TreeSet<>();
		Set<String> sightings = new java.util.TreeSet<>();
		Map<String, Integer> observedCounts = new TreeMap<>();

		int occurrences;

		Integer sampleWorldX;
		Integer sampleWorldY;
		Integer samplePlane;
	}

	private static final class MasterCatalog
	{
		int version = 1;
		Map<Integer, TextureRecord> textures = new TreeMap<>();
		Map<Integer, ObjectRecord> objects = new TreeMap<>();
		Map<Integer, TerrainRecord> underlays = new TreeMap<>();
		Map<Integer, TerrainRecord> overlays = new TreeMap<>();
		void normalize()
		{
			if (textures == null) textures = new TreeMap<>(); else textures = new TreeMap<>(textures);
			if (objects == null) objects = new TreeMap<>(); else objects = new TreeMap<>(objects);
			if (underlays == null) underlays = new TreeMap<>(); else underlays = new TreeMap<>(underlays);
			if (overlays == null) overlays = new TreeMap<>(); else overlays = new TreeMap<>(overlays);
			for (ObjectRecord record : objects.values())
			{
				if (record.types == null) record.types = new java.util.TreeSet<>();
				else record.types = new java.util.TreeSet<>(record.types);
				if (record.textureIds == null) record.textureIds = new java.util.TreeSet<>();
				else record.textureIds = new java.util.TreeSet<>(record.textureIds);
				if (record.orientations == null) record.orientations = new java.util.TreeSet<>();
				else record.orientations = new java.util.TreeSet<>(record.orientations);
				if (record.sightings == null) record.sightings = new java.util.TreeSet<>();
				else record.sightings = new java.util.TreeSet<>(record.sightings);
				if (record.observedCounts == null) record.observedCounts = new TreeMap<>();
				else record.observedCounts = new TreeMap<>(record.observedCounts);
			}
			normalizeTerrain(underlays);
			normalizeTerrain(overlays);
		}

		private static void normalizeTerrain(Map<Integer, TerrainRecord> records)
		{
			for (TerrainRecord record : records.values())
			{
				if (record.sightings == null) record.sightings = new java.util.TreeSet<>();
				else record.sightings = new java.util.TreeSet<>(record.sightings);
				if (record.observedTextureIds == null) record.observedTextureIds = new java.util.TreeSet<>();
				else record.observedTextureIds = new java.util.TreeSet<>(record.observedTextureIds);
				if (record.colorObservations == null) record.colorObservations = new TreeMap<>();
				else record.colorObservations = new TreeMap<>(record.colorObservations);
				for (ColorObservation observation : record.colorObservations.values())
				{
					if (observation.textureIds == null) observation.textureIds = new java.util.TreeSet<>();
					else observation.textureIds = new java.util.TreeSet<>(observation.textureIds);
				}
			}
		}
	}

	private static final class TextureRecord
	{
		int id;
		int sourceWidth;
		int sourceHeight;
		boolean hasTransparency;
		int transparentPixels;
		double transparentCoverage;
	}

	private static final class TerrainRecord
	{
		int id;
		long colorSum;
		int colorSamples;
		Integer averagePackedHsl;
		Set<Integer> observedTextureIds = new java.util.TreeSet<>();
		Set<String> sightings = new java.util.TreeSet<>();
		Map<String, ColorObservation> colorObservations = new TreeMap<>();
		void recalculateColor()
		{
			colorSum = colorObservations.values().stream().mapToLong(value -> value.sum).sum();
			colorSamples = colorObservations.values().stream().mapToInt(value -> value.samples).sum();
			averagePackedHsl = colorSamples == 0 ? null : (int) (colorSum / colorSamples);
		}
	}

	private static final class ColorObservation
	{
		long sum;
		int samples;
		Set<Integer> textureIds = new java.util.TreeSet<>();
	}
}
