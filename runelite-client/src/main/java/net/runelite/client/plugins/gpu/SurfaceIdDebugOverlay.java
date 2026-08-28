/* Copyright (c) 2026, RuneLite GPU Experimental Renderer */
package net.runelite.client.plugins.gpu;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.DecorativeObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Perspective;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

final class SurfaceIdDebugOverlay extends Overlay
{
	private final Client client;
	private final GpuPluginConfig config;

	@Inject
	SurfaceIdDebugOverlay(Client client, GpuPluginConfig config)
	{
		this.client = client;
		this.config = config;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_SCENE);
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		SurfaceIdDebugMode mode = config.surfaceIdDebugMode();
		if (mode == SurfaceIdDebugMode.OFF || client.getGameState() != GameState.LOGGED_IN)
		{
			return null;
		}
		WorldView view = client.getTopLevelWorldView();
		if (view == null || view.getScene() == null)
		{
			return null;
		}
		if (mode == SurfaceIdDebugMode.OBJECT_IDS)
		{
			renderObjects(graphics, view.getScene(), false);
		}
		else if (mode == SurfaceIdDebugMode.WALL_IDS)
		{
			renderObjects(graphics, view.getScene(), true);
		}
		else if (mode == SurfaceIdDebugMode.ROOFTOP_IDS)
		{
			renderRoofs(graphics, view.getScene());
		}
		else
		{
			short[][][] ids = mode == SurfaceIdDebugMode.OVERLAY_IDS
				? view.getScene().getOverlayIds() : view.getScene().getUnderlayIds();
			renderTerrain(graphics, view, ids);
		}
		return null;
	}

	private void renderObjects(Graphics2D g, Scene scene, boolean wallsOnly)
	{
		Tile[][][] tiles = scene.getTiles();
		for (Tile[][] plane : tiles)
		{
			if (plane == null) continue;
			for (Tile[] row : plane)
			{
				if (row == null) continue;
				for (Tile tile : row)
				{
					if (tile == null) continue;
					renderObject(g, tile.getWallObject());
					if (!wallsOnly) renderObject(g, tile.getGroundObject());
					if (wallsOnly) continue;
					DecorativeObject decorative = tile.getDecorativeObject();
					if (decorative != null)
					{
						renderHull(g, decorative.getConvexHull(), decorative.getId());
						renderHull(g, decorative.getConvexHull2(), decorative.getId());
					}
					GameObject[] objects = tile.getGameObjects();
					if (objects != null) for (GameObject object : objects) renderHull(g, object == null ? null : object.getConvexHull(), object == null ? 0 : object.getId());
				}
			}
		}
	}

	private void renderRoofs(Graphics2D g, Scene scene)
	{
		for (Tile[][] plane : scene.getTiles())
		{
			if (plane == null) continue;
			for (Tile[] row : plane)
			{
				if (row == null) continue;
				for (Tile tile : row)
				{
					if (tile == null || tile.getGameObjects() == null) continue;
					for (GameObject object : tile.getGameObjects())
					{
						if (object != null) renderHull(g, object.getConvexHull(), object.getId());
					}
				}
			}
		}
	}

	private void renderObject(Graphics2D g, TileObject object)
	{
		if (object == null) return;
		Polygon poly = Perspective.getCanvasTilePoly(client, object.getLocalLocation());
		renderHull(g, poly, object.getId());
	}

	private void renderHull(Graphics2D g, Shape shape, int id)
	{
		if (shape == null) return;
		Color c = SurfaceIdDebugColors.colorForId(id);
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 150));
		g.fill(shape);
		g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 235));
		g.setStroke(new BasicStroke(1.2f));
		g.draw(shape);

		// Keep the exact source ID visible so colors require no cross-reference.
		java.awt.Rectangle bounds = shape.getBounds();
		String label = Integer.toString(id);
		Font oldFont = g.getFont();
		g.setFont(oldFont.deriveFont(Font.BOLD, 16f));
		int x = bounds.x + 2;
		int y = bounds.y + 13;
		g.setColor(new Color(0, 0, 0, 225));
		g.fillRect(x - 2, y - 16, g.getFontMetrics().stringWidth(label) + 5, 19);
		g.setColor(new Color(0, 0, 0, 255));
		g.drawString(label, x + 1, y + 1);
		g.setColor(Color.WHITE);
		g.drawString(label, x, y);
		g.setFont(oldFont);
	}

	private void renderTerrain(Graphics2D g, WorldView view, short[][][] ids)
	{
		if (ids == null) return;
		Scene scene = view.getScene();
		int p = view.getPlane();
		if (p < 0 || p >= ids.length || p >= scene.getTiles().length) return;
		Tile[][] tiles = scene.getTiles()[p];
		short[][] values = ids[p];
		if (tiles == null || values == null) return;
		for (int x = 0; x < Math.min(tiles.length, values.length); ++x)
		{
			if (tiles[x] == null || values[x] == null) continue;
			for (int y = 0; y < Math.min(tiles[x].length, values[x].length); ++y)
			{
				if (tiles[x][y] == null || values[x][y] == 0) continue;
				Polygon poly = Perspective.getCanvasTilePoly(client, tiles[x][y].getLocalLocation());
				renderHull(g, poly, (values[x][y] & 0xffff) - 1);
			}
		}
	}

}
