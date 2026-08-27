/*
 * Copyright (c) 2026, RuneLite GPU Experimental Renderer
 * All rights reserved.
 */
package net.runelite.client.plugins.gpu;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.LinkedHashSet;
import java.util.Set;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

/**
 * Opt-in upload-time material diagnostic. This overlay is registered only while
 * enabled, so the normal renderer pays no per-frame inspector cost.
 */
final class MaterialInspectorOverlay extends Overlay
{
	private static final int UNDERLAY = 0;
	private static final int OVERLAY = 1;
	private static final int MAX_MODEL_FACES = 8;
	private static final Color TILE_COLOR = new Color(255, 190, 64, 180);

	private final Client client;
	private final GpuPluginConfig config;
	private final TooltipManager tooltipManager;

	@Inject
	MaterialInspectorOverlay(Client client, GpuPluginConfig config,
		TooltipManager tooltipManager)
	{
		this.client = client;
		this.config = config;
		this.tooltipManager = tooltipManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!config.materialInspector()
			|| client.getGameState() != GameState.LOGGED_IN
			|| client.isMenuOpen())
		{
			return null;
		}

		Tile tile = client.getTopLevelWorldView().getSelectedSceneTile();
		if (tile == null)
		{
			return null;
		}

		Polygon polygon = Perspective.getCanvasTilePoly(
				client,
				tile.getLocalLocation()
		);

		Point mouse = client.getMouseCanvasPosition();

		if (mouse == null)
		{
			return null;
		}

		if (polygon != null)
		{
			OverlayUtil.renderPolygon(
					graphics,
					polygon,
					TILE_COLOR
			);
		}
		tooltipManager.add(new Tooltip(buildTooltip(tile)));
		return null;
	}

	private String buildTooltip(Tile tile)
	{
		Scene scene = client.getTopLevelWorldView().getScene();
		WorldPoint world = tile.getWorldLocation();
		int sceneOffset = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;
		int sceneX = world.getX() - scene.getBaseX() + sceneOffset;
		int sceneY = world.getY() - scene.getBaseY() + sceneOffset;
		int plane = tile.getRenderLevel();
		int underlayId = definitionId(scene.getUnderlayIds(), plane, sceneX, sceneY);
		int overlayId = definitionId(scene.getOverlayIds(), plane, sceneX, sceneY);

		StringBuilder text = new StringBuilder(320)
			.append("<col=ffbe40>GPU material inspector</col>")
			.append("<br>World: ").append(world.getX()).append(',')
			.append(world.getY()).append(',').append(world.getPlane())
			.append(" | render ").append(plane)
			.append("<br>Underlay: ").append(underlayId)
			.append(" | overlay: ").append(overlayId);

		SceneTilePaint paint = tile.getSceneTilePaint();
		if (paint != null)
		{
			int layer = overlayId >= 0 ? OVERLAY : underlayId >= 0 ? UNDERLAY : -1;
			int definitionId = layer == OVERLAY ? overlayId : underlayId;
			SurfaceMaterialRuleCatalog.Match match =
				SurfaceMaterialClassifier.classifyPaintMatch(paint, layer, definitionId,
					world.getX(), world.getY(), plane);
			appendMatch(text, "Paint", paint.getTexture(), layer, definitionId, match);
		}

		SceneTileModel model = tile.getSceneTileModel();
		if (model != null)
		{
			appendModel(text, model, underlayId, overlayId, world, plane);
		}

		Set<Integer> objectIds = new LinkedHashSet<>();
		addObject(objectIds, tile.getWallObject());
		addObject(objectIds, tile.getDecorativeObject());
		addObject(objectIds, tile.getGroundObject());
		GameObject[] gameObjects = tile.getGameObjects();
		if (gameObjects != null)
		{
			for (GameObject gameObject : gameObjects)
			{
				addObject(objectIds, gameObject);
			}
		}
		for (int objectId : objectIds)
		{
			SurfaceMaterialRuleCatalog.Match match =
				SurfaceMaterialClassifier.classifyObjectMatch(-1, objectId,
					world.getX(), world.getY(), plane);
			text.append("<br>Object ").append(objectId).append(": ")
				.append(match.getMaterial()).append(" [")
				.append("slot ").append(match.getAuthoredSlot()).append(", ")
				.append(match.getSource()).append(", ")
				.append(match.isExact() ? "exact" : "fallback").append(']');
		}
		return text.toString();
	}

	private static void appendModel(StringBuilder text, SceneTileModel model,
		int underlayId, int overlayId, WorldPoint world, int plane)
	{
		int[] textures = model.getTriangleTextureId();
		int[] colorsA = model.getTriangleColorA();
		int[] colorsB = model.getTriangleColorB();
		int[] colorsC = model.getTriangleColorC();
		int faceCount = Math.min(model.getFaceX().length, MAX_MODEL_FACES);
		for (int face = 0; face < faceCount; ++face)
		{
			int texture = textures == null ? -1 : textures[face];
			int layer = SurfaceMaterialClassifier.terrainLayerForFace(model.getShape(), face);
			int definitionId = layer == OVERLAY ? overlayId : underlayId;
			SurfaceMaterialRuleCatalog.Match match =
				SurfaceMaterialClassifier.classifyTerrainFaceMatch(model, face, texture,
					colorsA[face], colorsB[face], colorsC[face], layer, definitionId,
					world.getX(), world.getY(), plane);
			appendMatch(text, "Face " + face, texture, layer, definitionId, match);
		}
		if (model.getFaceX().length > faceCount)
		{
			text.append("<br>… ").append(model.getFaceX().length - faceCount)
				.append(" more faces");
		}
	}

	private static void appendMatch(StringBuilder text, String label,
		int texture, int layer, int definitionId,
		SurfaceMaterialRuleCatalog.Match match)
	{
		text.append("<br>").append(label).append(": ")
			.append(match.getMaterial()).append(" [")
			.append("slot ").append(match.getAuthoredSlot()).append(", ")
			.append(match.getSource()).append(", ")
			.append(match.isExact() ? "exact" : "heuristic").append(']')
			.append(" tex=").append(texture)
			.append(" ").append(layer == OVERLAY ? "overlay" : layer == UNDERLAY
				? "underlay" : "layer?")
			.append('=').append(definitionId);
	}

	private static int definitionId(short[][][] definitions,
		int plane, int sceneX, int sceneY)
	{
		if (plane < 0 || plane >= definitions.length
			|| sceneX < 0 || sceneX >= definitions[plane].length
			|| sceneY < 0 || sceneY >= definitions[plane][sceneX].length)
		{
			return -1;
		}
		return SceneUploader.decodeTerrainDefinitionId(
			definitions[plane][sceneX][sceneY]);
	}

	private static void addObject(Set<Integer> objectIds, TileObject object)
	{
		if (object != null)
		{
			objectIds.add(object.getId());
		}
	}
}
