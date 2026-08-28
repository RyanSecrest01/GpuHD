/* Copyright (c) 2026, RuneLite GPU Experimental Renderer */
package net.runelite.client.plugins.gpu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Point;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.Texture;
import net.runelite.api.TextureProvider;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLite;

/**
 * Exports the exact terrain and object identities visible in the player's
 * current 8x8 world chunk. Semantic material data is deliberately diagnostic
 * only; the RuneLite IDs are the authoritative export fields.
 */
@Singleton
@Slf4j
final class ChunkObjectExporter
{
	private static final DateTimeFormatter TIMESTAMP =
		DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final Gson GSON = new GsonBuilder()
		.setPrettyPrinting()
		.serializeNulls()
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
			throw new IllegalStateException("Cannot export terrain before the local player exists.");
		}

		WorldView worldView = client.getLocalPlayer().getWorldView();
		if (worldView == null)
		{
			worldView = client.getTopLevelWorldView();
		}
		if (worldView == null || worldView.getScene() == null)
		{
			throw new IllegalStateException("Cannot export terrain without an active scene.");
		}

		Scene scene = worldView.getScene();
		WorldPoint player = client.getLocalPlayer().getWorldLocation();
		if (player == null)
		{
			throw new IllegalStateException("Cannot export terrain without the player's world location.");
		}

		int chunkMinX = player.getX() & ~7;
		int chunkMinY = player.getY() & ~7;
		int chunkMaxX = chunkMinX + 7;
		int chunkMaxY = chunkMinY + 7;

		List<TerrainRecord> terrain = new ArrayList<>();
		List<ObjectRecord> objects = new ArrayList<>();
		Set<TileObject> seenObjects = Collections.newSetFromMap(new IdentityHashMap<>());

		Tile[][][] tiles = scene.getTiles();
		for (int plane = 0; plane < tiles.length; ++plane)
		{
			Tile[][] planeTiles = tiles[plane];
			if (planeTiles == null)
			{
				continue;
			}

			for (int sceneX = 0; sceneX < planeTiles.length; ++sceneX)
			{
				Tile[] row = planeTiles[sceneX];
				if (row == null)
				{
					continue;
				}

				for (int sceneY = 0; sceneY < row.length; ++sceneY)
				{
					Tile tile = row[sceneY];
					if (tile == null)
					{
						continue;
					}

					WorldPoint tileWorld = tile.getWorldLocation();
					if (tileWorld == null || !inChunk(tileWorld, chunkMinX, chunkMinY,
						chunkMaxX, chunkMaxY))
					{
						continue;
					}

					Point scenePoint = tile.getSceneLocation();
					int actualSceneX = scenePoint == null ? sceneX : scenePoint.getX();
					int actualSceneY = scenePoint == null ? sceneY : scenePoint.getY();
					int tilePlane = tile.getPlane();
					terrain.add(terrainRecord(scene, tile, tileWorld, tilePlane,
						actualSceneX, actualSceneY));

					addObject(objects, seenObjects, tile.getWallObject(), "WALL", tileWorld);
					addObject(objects, seenObjects, tile.getDecorativeObject(), "DECORATIVE", tileWorld);
					addObject(objects, seenObjects, tile.getGroundObject(), "GROUND", tileWorld);
					GameObject[] gameObjects = tile.getGameObjects();
					if (gameObjects != null)
					{
						for (GameObject gameObject : gameObjects)
						{
							addObject(objects, seenObjects, gameObject, "GAME", tileWorld);
						}
					}
				}
			}
		}

		terrain.sort(Comparator
			.comparingInt((TerrainRecord record) -> record.plane)
			.thenComparingInt(record -> record.worldX)
			.thenComparingInt(record -> record.worldY));
		objects.sort(Comparator
			.comparingInt((ObjectRecord record) -> record.worldX)
			.thenComparingInt(record -> record.worldY)
			.thenComparingInt(record -> record.plane)
			.thenComparingInt(record -> typeOrder(record.type))
			.thenComparingInt(record -> record.objectId)
			.thenComparing(record -> record.orientation));

		Set<Integer> uniqueObjectIds = new TreeSet<>();
		for (ObjectRecord record : objects)
		{
			uniqueObjectIds.add(record.objectId);
		}

		ExportBundle bundle = new ExportBundle();
		bundle.schemaVersion = 1;
		bundle.worldViewId = scene.getWorldViewId();
		bundle.chunkMinX = chunkMinX;
		bundle.chunkMinY = chunkMinY;
		bundle.chunkMaxX = chunkMaxX;
		bundle.chunkMaxY = chunkMaxY;
		bundle.playerPlane = player.getPlane();
		bundle.uniqueObjectCount = uniqueObjectIds.size();
		bundle.objectPlacementCount = objects.size();
		bundle.terrainRecordCount = terrain.size();
		bundle.terrain = terrain;
		bundle.objects = objects;
		Set<Integer> exportedTextureIds = collectTextureIds(terrain, objects);
		bundle.textureIds = new ArrayList<>(exportedTextureIds);

		Path output = RuneLite.RUNELITE_DIR.toPath()
			.resolve("gpuhd-object-dumps")
			.resolve("chunk-" + chunkMinX + "-" + chunkMinY + "-"
				+ TIMESTAMP.format(LocalDateTime.now()));
		try
		{
			Files.createDirectories(output);
			exportTextures(output, exportedTextureIds);
			Files.writeString(output.resolve("terrain.csv"), terrainCsv(terrain), StandardCharsets.UTF_8);
			Files.writeString(output.resolve("objects.csv"), objectCsv(objects), StandardCharsets.UTF_8);
			Files.writeString(output.resolve("chunk.json"), GSON.toJson(bundle), StandardCharsets.UTF_8);
			log.info("Exported {} terrain records and {} object placements ({} unique IDs) to {}",
				terrain.size(), objects.size(), uniqueObjectIds.size(), output);
			return output;
		}
		catch (IOException ex)
		{
			throw new IllegalStateException("Unable to export chunk terrain and object identities", ex);
		}
	}

	private Set<Integer> collectTextureIds(List<TerrainRecord> terrain, List<ObjectRecord> objects)
	{
		Set<Integer> textureIds = new TreeSet<>();
		for (TerrainRecord record : terrain) textureIds.addAll(record.textureIds);
		for (ObjectRecord record : objects) textureIds.addAll(record.textureIds);
		textureIds.removeIf(id -> id < 0);
		return textureIds;
	}

	private void exportTextures(Path output, Set<Integer> textureIds) throws IOException
	{
		TextureProvider provider = client.getTextureProvider();
		if (provider == null || textureIds.isEmpty()) return;

		Path textureDirectory = output.resolve("textures");
		Files.createDirectories(textureDirectory);
		StringBuilder manifest = new StringBuilder("texture_id,file,width,height\n");
		Texture[] textures = provider.getTextures();
		double brightness = provider.getBrightness();
		provider.setBrightness(1.0d);
		try
		{
			for (int textureId : textureIds)
			{
				if (textures == null || textureId >= textures.length || textures[textureId] == null) continue;
				int[] pixels = provider.load(textureId);
				if (pixels == null || pixels.length == 0) continue;
				int size = (int) Math.sqrt(pixels.length);
				if (size * size != pixels.length) continue;

				BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
				for (int index = 0; index < pixels.length; ++index)
				{
					int pixel = pixels[index];
					image.setRGB(index % size, index / size,
						pixel == 0 ? 0 : 0xff000000 | pixel & 0xffffff);
				}
				String fileName = "texture-" + textureId + ".png";
				ImageIO.write(image, "PNG", textureDirectory.resolve(fileName).toFile());
				manifest.append(textureId).append(",textures/").append(fileName)
					.append(',').append(size).append(',').append(size).append('\n');
			}
		}
		finally
		{
			provider.setBrightness(brightness);
		}
		Files.writeString(output.resolve("texture-manifest.csv"), manifest.toString(), StandardCharsets.UTF_8);
	}

	private TerrainRecord terrainRecord(Scene scene, Tile tile, WorldPoint world,
		int plane, int sceneX, int sceneY)
	{
		TerrainRecord record = new TerrainRecord();
		record.worldX = world.getX();
		record.worldY = world.getY();
		record.plane = plane;
		record.sceneX = sceneX;
		record.sceneY = sceneY;
		record.renderLevel = tile.getRenderLevel();
		record.underlayId = definitionId(scene.getUnderlayIds(), plane, sceneX, sceneY);
		record.overlayId = definitionId(scene.getOverlayIds(), plane, sceneX, sceneY);
		record.underlayDebugColor = debugColor(record.underlayId);
		record.overlayDebugColor = debugColor(record.overlayId);
		record.shape = tile.getSceneTileModel() == null ? -1 : tile.getSceneTileModel().getShape();
		record.rotation = tile.getSceneTileModel() == null ? -1 : tile.getSceneTileModel().getRotation();

		SceneTilePaint paint = tile.getSceneTilePaint();
		SceneTileModel model = tile.getSceneTileModel();
		record.tileType = paint != null && model != null ? "BOTH"
			: paint != null ? "PAINT" : model != null ? "MODEL" : "NONE";
		List<Integer> rgbValues = new ArrayList<>();
		Set<Integer> textureIds = new TreeSet<>();
		if (paint != null)
		{
			record.paintRgb = validRgb(paint.getRBG()) ? paint.getRBG() : null;
			if (record.paintRgb != null)
			{
				rgbValues.add(record.paintRgb);
			}
			record.paintSwHsl = paint.getSwColor();
			record.paintSeHsl = paint.getSeColor();
			record.paintNeHsl = paint.getNeColor();
			record.paintNwHsl = paint.getNwColor();
			addTexture(textureIds, paint.getTexture());
		}
		if (model != null)
		{
			record.modelUnderlayColor = validRgb(model.getModelUnderlay())
				? model.getModelUnderlay() : null;
			record.modelOverlayColor = validRgb(model.getModelOverlay())
				? model.getModelOverlay() : null;
			int[] textures = model.getTriangleTextureId();
			if (textures != null)
			{
				for (int texture : textures)
				{
					addTexture(textureIds, texture);
				}
			}
			record.modelTriangleHsl = triangleColors(model);
		}
		record.textureIds = new ArrayList<>(textureIds);
		record.textureDebugColors = debugColors(record.textureIds);
		record.averageRgb = averageRgb(rgbValues);
		return record;
	}

	private void addObject(List<ObjectRecord> records, Set<TileObject> seen,
		TileObject object, String type, WorldPoint tileWorld)
	{
		if (object == null || !seen.add(object))
		{
			return;
		}

		WorldPoint world = object.getWorldLocation() == null ? tileWorld : object.getWorldLocation();
		if (world == null)
		{
			return;
		}
		ObjectRecord record = new ObjectRecord();
		record.objectId = object.getId();
		record.name = objectName(record.objectId);
		record.type = type;
		record.worldX = world.getX();
		record.worldY = world.getY();
		record.plane = object.getPlane();
		record.orientation = orientation(object);
		record.objectDebugColor = SurfaceIdDebugColors.hexForId(record.objectId);
		Set<Integer> textureIds = new TreeSet<>();
		collectObjectTextures(textureIds, object);
		record.textureIds = new ArrayList<>(textureIds);
		record.textureDebugColors = debugColors(record.textureIds);

		Set<String> materials = new LinkedHashSet<>();
		Set<Integer> slots = new TreeSet<>();
		if (record.textureIds.isEmpty())
		{
			addObjectDiagnostic(materials, slots, -1, record);
		}
		else
		{
			for (int textureId : record.textureIds)
			{
				addObjectDiagnostic(materials, slots, textureId, record);
			}
		}
		record.currentMaterial = String.join("|", materials);
		record.currentAuthoredSlot = joinInts(slots);
		record.textureDebugColors = debugColors(record.textureIds);
		record.textureIdsCsv = joinInts(record.textureIds);
		record.textureDebugColorsCsv = String.join("|", record.textureDebugColors);
		records.add(record);
	}

	private void addObjectDiagnostic(Set<String> materials, Set<Integer> slots,
		int textureId, ObjectRecord record)
	{
		SurfaceMaterialRuleCatalog.Match match = SurfaceMaterialClassifier.classifyObjectMatch(
			textureId, record.objectId, record.worldX, record.worldY, record.plane);
		materials.add(match.getMaterial().name());
		slots.add(match.getAuthoredSlot());
	}

	private void collectObjectTextures(Set<Integer> textures, TileObject object)
	{
		if (object instanceof WallObject)
		{
			WallObject wall = (WallObject) object;
			collectTextures(textures, wall.getRenderable1());
			collectTextures(textures, wall.getRenderable2());
		}
		else if (object instanceof DecorativeObject)
		{
			DecorativeObject decorative = (DecorativeObject) object;
			collectTextures(textures, decorative.getRenderable());
			collectTextures(textures, decorative.getRenderable2());
		}
		else if (object instanceof GroundObject)
		{
			collectTextures(textures, ((GroundObject) object).getRenderable());
		}
		else if (object instanceof GameObject)
		{
			collectTextures(textures, ((GameObject) object).getRenderable());
		}
	}

	private static void collectTextures(Set<Integer> textures, Renderable renderable)
	{
		Model model = null;
		if (renderable instanceof Model)
		{
			model = (Model) renderable;
		}
		else if (renderable instanceof DynamicObject)
		{
			model = ((DynamicObject) renderable).getModelZbuf();
		}
		if (model == null || model.getFaceTextures() == null)
		{
			return;
		}
		for (short textureId : model.getFaceTextures())
		{
			addTexture(textures, textureId);
		}
	}

	private static String orientation(TileObject object)
	{
		if (object instanceof GameObject)
		{
			return Integer.toString(((GameObject) object).getOrientation());
		}
		if (object instanceof WallObject)
		{
			WallObject wall = (WallObject) object;
			return wall.getOrientationA() + "|" + wall.getOrientationB();
		}
		if (object instanceof DecorativeObject)
		{
			return Integer.toString((((DecorativeObject) object).getConfig() >>> 6) & 3);
		}
		if (object instanceof GroundObject)
		{
			return Integer.toString((((GroundObject) object).getConfig() >>> 6) & 3);
		}
		return "";
	}

	private String objectName(int id)
	{
		try
		{
			ObjectComposition definition = client.getObjectDefinition(id);
			return definition == null || definition.getName() == null ? "" : definition.getName();
		}
		catch (RuntimeException ex)
		{
			return "";
		}
	}

	private static int definitionId(short[][][] definitions, int plane, int sceneX, int sceneY)
	{
		if (definitions == null || plane < 0 || plane >= definitions.length
			|| definitions[plane] == null || sceneX < 0 || sceneX >= definitions[plane].length
			|| definitions[plane][sceneX] == null || sceneY < 0
			|| sceneY >= definitions[plane][sceneX].length)
		{
			return -1;
		}
		int encoded = definitions[plane][sceneX][sceneY] & 0xffff;
		return encoded == 0 ? -1 : encoded - 1;
	}

	private static boolean inChunk(WorldPoint world, int minX, int minY, int maxX, int maxY)
	{
		return world.getX() >= minX && world.getX() <= maxX
			&& world.getY() >= minY && world.getY() <= maxY;
	}

	private static void addTexture(Set<Integer> textures, int textureId)
	{
		if (textureId >= 0)
		{
			textures.add(textureId);
		}
	}

	private static List<String> debugColors(List<Integer> ids)
	{
		List<String> colors = new ArrayList<>();
		for (int id : ids)
		{
			colors.add(SurfaceIdDebugColors.hexForId(id));
		}
		return colors;
	}

	private static String debugColor(int id)
	{
		return id < 0 ? null : SurfaceIdDebugColors.hexForId(id);
	}

	private static boolean validRgb(int rgb)
	{
		return rgb >= 0 && rgb <= 0xffffff;
	}

	private static String averageRgb(List<Integer> values)
	{
		if (values.isEmpty())
		{
			return null;
		}
		long red = 0;
		long green = 0;
		long blue = 0;
		for (int value : values)
		{
			red += value >> 16 & 0xff;
			green += value >> 8 & 0xff;
			blue += value & 0xff;
		}
		int count = values.size();
		return String.format(java.util.Locale.ROOT, "#%02X%02X%02X",
			(int) Math.round((double) red / count),
			(int) Math.round((double) green / count),
			(int) Math.round((double) blue / count));
	}

	private static String triangleColors(SceneTileModel model)
	{
		int[] a = model.getTriangleColorA();
		int[] b = model.getTriangleColorB();
		int[] c = model.getTriangleColorC();
		if (a == null || b == null || c == null)
		{
			return null;
		}
		int count = Math.min(a.length, Math.min(b.length, c.length));
		List<String> colors = new ArrayList<>();
		for (int face = 0; face < count; ++face)
		{
			colors.add(a[face] + "/" + b[face] + "/" + c[face]);
		}
		return String.join("|", colors);
	}

	private static int typeOrder(String type)
	{
		switch (type)
		{
			case "WALL": return 0;
			case "GAME": return 1;
			case "DECORATIVE": return 2;
			case "GROUND": return 3;
			default: return 4;
		}
	}

	private static String joinInts(Iterable<Integer> values)
	{
		List<String> strings = new ArrayList<>();
		for (Integer value : values)
		{
			strings.add(Integer.toString(value));
		}
		return String.join("|", strings);
	}

	private static String csv(Object value)
	{
		if (value == null)
		{
			return "";
		}
		String text = value.toString();
		String escaped = text.replace("\"", "\"\"");
		return escaped.contains(",") || escaped.contains("\"")
			|| escaped.contains("\n") ? "\"" + escaped + "\"" : escaped;
	}

	private static String objectCsv(List<ObjectRecord> records)
	{
		StringBuilder csv = new StringBuilder(
			"object_id,name,type,world_x,world_y,plane,orientation,texture_ids,"
				+ "texture_debug_colors,object_debug_color,current_material,current_authored_slot\n");
		for (ObjectRecord record : records)
		{
			csv.append(record.objectId).append(',').append(csv(record.name)).append(',')
				.append(record.type).append(',').append(record.worldX).append(',')
				.append(record.worldY).append(',').append(record.plane).append(',')
				.append(csv(record.orientation)).append(',').append(csv(record.textureIdsCsv))
				.append(',').append(csv(record.textureDebugColorsCsv)).append(',')
				.append(record.objectDebugColor).append(',').append(csv(record.currentMaterial))
				.append(',').append(csv(record.currentAuthoredSlot)).append('\n');
		}
		return csv.toString();
	}

	private static String terrainCsv(List<TerrainRecord> records)
	{
		StringBuilder csv = new StringBuilder(
			"world_x,world_y,plane,scene_x,scene_y,render_level,underlay_id,"
			+ "underlay_debug_color,overlay_id,overlay_debug_color,texture_ids,"
			+ "texture_debug_colors,tile_type,shape,rotation,paint_rgb,paint_sw_hsl,"
			+ "paint_se_hsl,paint_ne_hsl,paint_nw_hsl,model_underlay_color,"
			+ "model_overlay_color,model_triangle_hsl,average_rgb\n");
		for (TerrainRecord record : records)
		{
			csv.append(record.worldX).append(',').append(record.worldY).append(',')
				.append(record.plane).append(',').append(record.sceneX).append(',')
				.append(record.sceneY).append(',').append(record.renderLevel).append(',')
				.append(record.underlayId).append(',').append(csv(record.underlayDebugColor))
				.append(',').append(record.overlayId).append(',').append(csv(record.overlayDebugColor))
				.append(',').append(csv(joinInts(record.textureIds))).append(',')
				.append(csv(String.join("|", record.textureDebugColors))).append(',')
				.append(record.tileType).append(',').append(record.shape).append(',')
				.append(record.rotation).append(',').append(csv(record.paintRgb))
				.append(',').append(csv(record.paintSwHsl)).append(',').append(csv(record.paintSeHsl))
				.append(',').append(csv(record.paintNeHsl)).append(',').append(csv(record.paintNwHsl))
				.append(',').append(csv(record.modelUnderlayColor)).append(',')
				.append(csv(record.modelOverlayColor)).append(',').append(csv(record.modelTriangleHsl))
				.append(',').append(csv(record.averageRgb)).append('\n');
		}
		return csv.toString();
	}

	private static final class ExportBundle
	{
		int schemaVersion;
		int worldViewId;
		int chunkMinX;
		int chunkMinY;
		int chunkMaxX;
		int chunkMaxY;
		int playerPlane;
		int uniqueObjectCount;
		int objectPlacementCount;
		int terrainRecordCount;
		List<TerrainRecord> terrain;
		List<ObjectRecord> objects;
		List<Integer> textureIds;
	}

	private static final class TerrainRecord
	{
		int worldX;
		int worldY;
		int plane;
		int sceneX;
		int sceneY;
		int renderLevel;
		int underlayId;
		String underlayDebugColor;
		int overlayId;
		String overlayDebugColor;
		List<Integer> textureIds = new ArrayList<>();
		List<String> textureDebugColors = new ArrayList<>();
		String tileType;
		int shape;
		int rotation;
		Integer paintRgb;
		Integer paintSwHsl;
		Integer paintSeHsl;
		Integer paintNeHsl;
		Integer paintNwHsl;
		Integer modelUnderlayColor;
		Integer modelOverlayColor;
		String modelTriangleHsl;
		String averageRgb;
	}

	private static final class ObjectRecord
	{
		int objectId;
		String name;
		String type;
		int worldX;
		int worldY;
		int plane;
		String orientation;
		List<Integer> textureIds = new ArrayList<>();
		List<String> textureDebugColors = new ArrayList<>();
		transient String textureIdsCsv;
		transient String textureDebugColorsCsv;
		String objectDebugColor;
		String currentMaterial;
		String currentAuthoredSlot;
	}
}
