/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.plugins.gpu;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.client.callback.RenderCallbackManager;

@Slf4j
class SceneUploader
{
	private static final int SURFACE_DETAIL_ANCHOR_STRIDE = 6;
	private static final int GRASS_STAT_VEGETATION_TILES = 0;
	private static final int GRASS_STAT_ELIGIBLE_TILES = 1;
	private static final int GRASS_STAT_ELIGIBLE_TRIANGLES = 2;
	private static final int GRASS_STAT_ROOTS = 3;
	// A shaped tile can contain every supported detail material. Candidate
	// Dense production grass uses 36--48 anchors per eligible tile. The remaining
	// scatter types fit within the same bounded per-tile storage.
	private static final int MAX_SURFACE_DETAIL_ANCHORS_PER_TILE = 64;
	private static final int SURFACE_DETAIL_NONE = -1;
	private static final int SURFACE_DETAIL_GRASS = 0;
	private static final int SURFACE_DETAIL_PEBBLE = 1;
	private static final int SURFACE_DETAIL_SAND = 2;
	private static final int SURFACE_DETAIL_DIRT = 3;
	private static final int SURFACE_DETAIL_TYPE_COUNT = 4;
	private static final int TERRAIN_LAYER_UNDERLAY = 0;
	private static final int TERRAIN_LAYER_OVERLAY = 1;
	private static final int NO_TERRAIN_DEFINITION = -1;
	private static final int TURF_TEXTURE = 129;
	// First vegetation proof material: Lumbridge's observed grass underlay.
	// First vegetation proof materials: Lumbridge's observed grass underlays.
	private static final int INVISIBLE_HSL = 12345678;

	private static final class SurfaceDetailMaterial
	{
		private final int type;
		private final int packedHsl;

		private SurfaceDetailMaterial(int type, int packedHsl)
		{
			this.type = type;
			this.packedHsl = packedHsl;
		}
	}

	// tex.w already carries the eight shoreline bits for terrain. The next bit
	// distinguishes scene terrain from models without widening the stock vertex
	// format, allowing weather accumulation to avoid coating every object.
	private static final int TERRAIN_FLAG = 1 << 8;
	private static final int TERRAIN_BLEND_WEST = 1 << 9;
	private static final int TERRAIN_BLEND_EAST = 1 << 10;
	private static final int TERRAIN_BLEND_SOUTH = 1 << 11;
	private static final int TERRAIN_BLEND_NORTH = 1 << 12;
	// Edge opposite each vertex of a shaped water triangle. The water shader uses
	// these with procedural barycentrics to identify diagonal/internal banks.
	private static final int WATER_FACE_EDGE_0 = 1 << 13;
	private static final int WATER_FACE_EDGE_1 = 1 << 14;
	private static final int WATER_FACE_EDGE_2 = 1 << 15;
	// Generated underwater terrain is rendered into the opaque scene snapshot.
	// Reserve half of the packed alpha byte as a stable substrate marker for the
	// later water pass. Bits 16-23 deliberately remain clear so this introduces
	// no vertex depth bias, while the low 16 bits retain the original HSL.
	private static final int WATER_BED_ALPHA_MARKER = 128;
	private static final int WATER_BED_RINGS = 3;
	// Keep a narrow readable shelf at the bank, then descend rapidly enough that
	// the generated substrate is already optically deep before its outer edge.
	// This avoids exposing a second artificial coastline at the end of the mesh.
	private static final int[] WATER_BED_DEPTHS = {20, 72, 260, 520};
	private static final int WATER_BED_SAND_HUE = 8;
	private static final int WATER_BED_SAND_SATURATION = 3;
	private static final int WATER_BED_SAND_LUMINANCE = 82;
	private static final int WATER_BED_SEARCH_DIAMETER = WATER_BED_RINGS * 2 + 1;
	private static final int WATER_BED_SEARCH_CAPACITY =
		WATER_BED_SEARCH_DIAMETER * WATER_BED_SEARCH_DIAMETER;

	private static final class TerrainMaterial
	{
		private final int texture;
		private final int swColor;
		private final int seColor;
		private final int neColor;
		private final int nwColor;
		private final int representativeColor;
		private final float distanceSquared;
		private final int sceneX;
		private final int sceneY;
		private final int face;

		private TerrainMaterial(
			int texture,
			int swColor,
			int seColor,
			int neColor,
			int nwColor,
			float distanceSquared,
			int sceneX,
			int sceneY,
			int face)
		{
			this.texture = texture;
			this.representativeColor = averageTerrainColor(texture,
				swColor, seColor, neColor, nwColor);
			this.swColor = visibleTerrainColor(swColor, representativeColor);
			this.seColor = visibleTerrainColor(seColor, representativeColor);
			this.neColor = visibleTerrainColor(neColor, representativeColor);
			this.nwColor = visibleTerrainColor(nwColor, representativeColor);
			this.distanceSquared = distanceSquared;
			this.sceneX = sceneX;
			this.sceneY = sceneY;
			this.face = face;
		}
	}

	private final int[] modelLocalXI;
	private final int[] modelLocalYI;
	private final int[] modelLocalZI;

	private final float[] u, v;
	private final int[] waterBedQueueX = new int[WATER_BED_SEARCH_CAPACITY];
	private final int[] waterBedQueueY = new int[WATER_BED_SEARCH_CAPACITY];
	private final int[] waterBedVisited = new int[WATER_BED_SEARCH_CAPACITY];
	private int waterBedVisitStamp;

	private final RenderCallbackManager renderCallbackManager;
	private final GpuPluginConfig config;
	private final TreeReplacementRegistry treeReplacementRegistry;
	private int basex, basez, rid, level;

	SceneUploader(RenderCallbackManager renderCallbackManager, GpuPluginConfig config,
		TreeReplacementRegistry treeReplacementRegistry)
	{
		this.renderCallbackManager = renderCallbackManager;
		this.config = config;
		this.treeReplacementRegistry = treeReplacementRegistry;
		modelLocalXI = new int[ModelUploader.MAX_VERTEX_COUNT];
		modelLocalYI = new int[ModelUploader.MAX_VERTEX_COUNT];
		modelLocalZI = new int[ModelUploader.MAX_VERTEX_COUNT];
		u = new float[3];
		v = new float[3];
	}

	void zoneSize(Scene scene, Zone zone, int mzx, int mzz)
	{
		Tile[][][] tiles = scene.getExtendedTiles();

		for (int z = 3; z >= 0; --z)
		{
			for (int xoff = 0; xoff < 8; ++xoff)
			{
				for (int zoff = 0; zoff < 8; ++zoff)
				{
					Tile t = tiles[z][(mzx << 3) + xoff][(mzz << 3) + zoff];
					if (t != null)
					{
						zoneSize(scene, zone, t, z);
					}
				}
			}
		}
	}

	void uploadZone(Scene scene, Zone zone, int mzx, int mzz)
	{
		zone.clearWaterRanges();
		int[][][] roofs = scene.getRoofs();
		Set<Integer> roofIds = new HashSet<>();

		var vb = zone.vboO != null ? new GpuIntBuffer(zone.vboO.vb) : null;
		var ab = zone.vboA != null ? new GpuIntBuffer(zone.vboA.vb) : null;

		for (int level = 0; level <= 3; ++level)
		{
			for (int xoff = 0; xoff < 8; ++xoff)
			{
				for (int zoff = 0; zoff < 8; ++zoff)
				{
					int rid = roofs[level][(mzx << 3) + xoff][(mzz << 3) + zoff];
					if (rid > 0)
					{
						roofIds.add(rid);
					}
				}
			}
		}

		zone.rids = new int[4][roofIds.size()];
		zone.roofStart = new int[4][roofIds.size()];
		zone.roofEnd = new int[4][roofIds.size()];

		for (int level = 0; level <= 3; ++level)
		{
			this.level = level;

			if (level == 0)
			{
				uploadZoneLevel(scene, zone, mzx, mzz, level, false, roofIds, vb, ab);
				uploadZoneLevel(scene, zone, mzx, mzz, level, true, roofIds, vb, ab);
				uploadZoneLevel(scene, zone, mzx, mzz, 1, true, roofIds, vb, ab);
				uploadZoneLevel(scene, zone, mzx, mzz, 2, true, roofIds, vb, ab);
				uploadZoneLevel(scene, zone, mzx, mzz, 3, true, roofIds, vb, ab);
			}
			else
			{
				uploadZoneLevel(scene, zone, mzx, mzz, level, false, roofIds, vb, ab);
			}

			if (zone.vboO != null)
			{
				int pos = zone.vboO.vb.position();
				zone.levelOffsets[level] = pos;
			}
		}

		buildSurfaceDetailAnchors(scene, zone, mzx, mzz);
	}

	/**
	 * Builds deterministic anchors for the lightweight surface-detail pass. Only
	 * true ground terrain is considered: object meshes, roofs, bridges, and upper
	 * floors never enter this path. Paint tiles use the stock terrain split while
	 * shaped tiles are sampled against their actual triangles, so every accepted
	 * anchor has the exact height of the surface beneath it.
	 */
	private void buildSurfaceDetailAnchors(Scene scene, Zone zone, int mzx, int mzz)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		int[][][] tileHeights = scene.getTileHeights();
		byte[][][] tileSettings = scene.getExtendedTileSettings();
		int[][][] roofs = scene.getRoofs();
		short[][][] underlays = scene.getUnderlayIds();
		short[][][] overlays = scene.getOverlayIds();
		int sceneOffset = scene.getWorldViewId() == WorldView.TOPLEVEL ? GpuPlugin.SCENE_OFFSET : 0;
		float[] anchors = new float[8 * 8 * MAX_SURFACE_DETAIL_ANCHORS_PER_TILE
			* SURFACE_DETAIL_ANCHOR_STRIDE];
		int writeOffset = 0;
		int tilesScanned = 0;
		int tilesWithUnderlay = 0;
		int tilesWithOverlay = 0;
		int[] grassStats = new int[4];

		for (int level = 0; level < 4; ++level)
		{
			// Storage plane zero plus render level zero is deliberately strict. Green
			// roof/floor HSL values are not a reliable vegetation material signal.
			if (level != 0)
			{
				zone.surfaceDetailLevelOffsets[level] = writeOffset;
				continue;
			}

			for (int xoff = 0; xoff < 8; ++xoff)
			{
				for (int zoff = 0; zoff < 8; ++zoff)
				{
					int sceneX = (mzx << 3) + xoff;
					int sceneZ = (mzz << 3) + zoff;
					Tile tile = tiles[level][sceneX][sceneZ];
					if (tile == null)
					{
						continue;
					}
					tilesScanned++;
					int underlayId = decodeTerrainDefinitionId(
						underlays[level][sceneX][sceneZ]);
					int overlayId = decodeTerrainDefinitionId(
						overlays[level][sceneX][sceneZ]);
					if (underlayId >= 0)
					{
						tilesWithUnderlay++;
					}
					if (overlayId >= 0)
					{
						tilesWithOverlay++;
					}

					int heightLevel = tile.getRenderLevel();
					int tileFlags = tileSettings[level][sceneX][sceneZ] & 0xff;
					int bridgeFlags = tileSettings[1][sceneX][sceneZ] & 0xff;
					int roofId = roofs[level][sceneX][sceneZ];
					if (!surfaceDetailTileEligible(level, tile.getPlane(), heightLevel,
						tileFlags, bridgeFlags, roofId, tile.getBridge() != null)
						|| heightLevel >= tileHeights.length
						|| sceneX + 1 >= tileHeights[heightLevel].length
						|| sceneZ + 1 >= tileHeights[heightLevel][sceneX].length)
					{
						continue;
					}

					int swHeight = tileHeights[heightLevel][sceneX][sceneZ];
					int seHeight = tileHeights[heightLevel][sceneX + 1][sceneZ];
					int neHeight = tileHeights[heightLevel][sceneX + 1][sceneZ + 1];
					int nwHeight = tileHeights[heightLevel][sceneX][sceneZ + 1];
					int worldTileX = sceneToWorldTile(scene.getBaseX(), sceneX, sceneOffset);
					int worldTileZ = sceneToWorldTile(scene.getBaseY(), sceneZ, sceneOffset);
					SceneTilePaint paint = tile.getSceneTilePaint();
					if (paint != null)
					{
						SurfaceDetailMaterial material = surfaceMaterial(
							paint, underlayId, overlayId,
							worldTileX, worldTileZ, level);
						boolean vegetationEnabled = material != null
							&& material.type == SURFACE_DETAIL_GRASS;
						if (vegetationEnabled)
						{
							grassStats[GRASS_STAT_VEGETATION_TILES]++;
						}
						int minHeight = Math.min(Math.min(swHeight, seHeight), Math.min(neHeight, nwHeight));
						int maxHeight = Math.max(Math.max(swHeight, seHeight), Math.max(neHeight, nwHeight));
						if (material != null
							&& maxHeight - minHeight <= surfaceDetailMaxSlope(material.type))
						{
							if (vegetationEnabled)
							{
								grassStats[GRASS_STAT_ELIGIBLE_TILES]++;
								grassStats[GRASS_STAT_ELIGIBLE_TRIANGLES] += 2;
							}
							int anchorCount = surfaceAnchorCount(material.type,
								worldTileX, worldTileZ);
							for (int anchor = 0; anchor < anchorCount; ++anchor)
							{
								float[] sample = material.type == SURFACE_DETAIL_GRASS
									? surfaceTriangleSample(anchor, worldTileX, worldTileZ, level)
									: new float[]{
										surfaceSampleCoordinate(material.type, anchor, true,
											worldTileX, worldTileZ, level),
										surfaceSampleCoordinate(material.type, anchor, false,
											worldTileX, worldTileZ, level)};
								float tileX = sample[0];
								float tileZ = sample[1];
								float height = paintHeight(tileX, tileZ,
									swHeight, seHeight, neHeight, nwHeight);
								writeOffset = putSurfaceDetailAnchor(anchors, writeOffset,
									(xoff + tileX) * Perspective.LOCAL_TILE_SIZE,
									height,
									(zoff + tileZ) * Perspective.LOCAL_TILE_SIZE,
									surfaceGeometrySeed(worldTileX, worldTileZ, level,
										material.type, anchor), material);
								if (vegetationEnabled)
								{
									grassStats[GRASS_STAT_ROOTS]++;
								}
							}
						}
					}
					else
					{
						SceneTileModel model = tile.getSceneTileModel();
						if (model != null)
						{
							writeOffset = appendModelSurfaceDetails(anchors, writeOffset,
								model, xoff, zoff, worldTileX, worldTileZ, level,
								underlayId, overlayId, grassStats);
						}
					}
				}
			}

			zone.surfaceDetailLevelOffsets[level] = writeOffset;
		}

		zone.surfaceDetailAnchors = Arrays.copyOf(anchors, writeOffset);
		zone.surfaceDetailTilesScanned = tilesScanned;
		zone.surfaceDetailTilesWithUnderlay = tilesWithUnderlay;
		zone.surfaceDetailTilesWithOverlay = tilesWithOverlay;
		zone.surfaceDetailVegetationTiles = grassStats[GRASS_STAT_VEGETATION_TILES];
		zone.surfaceDetailEligibleTiles = grassStats[GRASS_STAT_ELIGIBLE_TILES];
		zone.surfaceDetailEligibleTriangles = grassStats[GRASS_STAT_ELIGIBLE_TRIANGLES];
		zone.surfaceDetailGrassRoots = grassStats[GRASS_STAT_ROOTS];
	}

	private static int appendModelSurfaceDetails(float[] anchors, int writeOffset,
		SceneTileModel model, int xoff, int zoff,
		int worldTileX, int worldTileZ, int level,
		int underlayId, int overlayId, int[] grassStats)
	{
		int[] faceX = model.getFaceX();
		int[] faceY = model.getFaceY();
		int[] faceZ = model.getFaceZ();
		int[] vertexX = model.getVertexX();
		int[] vertexY = model.getVertexY();
		int[] vertexZ = model.getVertexZ();
		int[] textures = model.getTriangleTextureId();
		int[] colorA = model.getTriangleColorA();
		int[] colorB = model.getTriangleColorB();
		int[] colorC = model.getTriangleColorC();
		int faceCount = Math.min(faceX.length,
			Math.min(faceY.length, faceZ.length));
		SurfaceDetailMaterial[] materials = new SurfaceDetailMaterial[faceCount];
		int[] detailTypes = new int[faceCount];
		Arrays.fill(detailTypes, SURFACE_DETAIL_NONE);
		boolean[] sampleable = new boolean[faceCount];
		boolean vegetationEnabled = false;
		boolean vegetationEligible = false;

		for (int face = 0; face < faceCount; ++face)
		{
			if (!validModelFace(face, faceX, faceY, faceZ,
				vertexX, vertexY, vertexZ, colorA))
			{
				continue;
			}

			int texture = textures != null && face < textures.length
				? textures[face] : -1;
			int a = faceX[face];
			int b = faceY[face];
			int c = faceZ[face];
			int minHeight = Math.min(vertexY[a], Math.min(vertexY[b], vertexY[c]));
			int maxHeight = Math.max(vertexY[a], Math.max(vertexY[b], vertexY[c]));
			long areaTwice = Math.abs((long) (vertexX[b] - vertexX[a])
				* (vertexZ[c] - vertexZ[a])
				- (long) (vertexZ[b] - vertexZ[a])
				* (vertexX[c] - vertexX[a]));
			if (areaTwice <= 0 || isWaterTexture(texture))
			{
				continue;
			}

			int hslA = colorA[face];
			int hslB = face < colorB.length ? colorB[face] : hslA;
			int hslC = face < colorC.length ? colorC[face] : hslA;
			SurfaceDetailMaterial material = surfaceMaterial(model, face,
				texture, hslA, hslB, hslC, underlayId, overlayId,
				worldTileX, worldTileZ, level);
			if (material != null && material.type == SURFACE_DETAIL_GRASS)
			{
				vegetationEnabled = true;
			}
			if (material != null
				&& maxHeight - minHeight <= surfaceDetailMaxSlope(material.type))
			{
				materials[face] = material;
				detailTypes[face] = material.type;
				sampleable[face] = true;
				if (material.type == SURFACE_DETAIL_GRASS)
				{
					vegetationEligible = true;
					grassStats[GRASS_STAT_ELIGIBLE_TRIANGLES]++;
				}
			}
		}
		if (vegetationEnabled)
		{
			grassStats[GRASS_STAT_VEGETATION_TILES]++;
		}

		int tileBaseX = modelTileBase(vertexX);
		int tileBaseZ = modelTileBase(vertexZ);
		for (int type = SURFACE_DETAIL_GRASS; type < SURFACE_DETAIL_TYPE_COUNT; ++type)
		{
			int anchorCount = surfaceAnchorCount(type, worldTileX, worldTileZ);
			for (int anchor = 0; anchor < anchorCount; ++anchor)
			{
				float tileX = surfaceSampleCoordinate(type, anchor,
					true, worldTileX, worldTileZ, level);
				float tileZ = surfaceSampleCoordinate(type, anchor,
					false, worldTileX, worldTileZ, level);
				float sampleX = tileBaseX + tileX * Perspective.LOCAL_TILE_SIZE;
				float sampleZ = tileBaseZ + tileZ * Perspective.LOCAL_TILE_SIZE;
				int face = containingModelFace(sampleX, sampleZ, sampleable,
					faceX, faceY, faceZ, vertexX, vertexZ);
				if (face < 0 || materials[face] == null
					|| materials[face].type != type)
				{
					continue;
				}
				if (!surfaceDetailFootprintClear(sampleX, sampleZ, face, type,
					detailTypes, sampleable, faceX, faceY, faceZ, vertexX, vertexZ))
				{
					continue;
				}

				float height = modelFaceHeight(sampleX, sampleZ, face,
					faceX, faceY, faceZ, vertexX, vertexY, vertexZ);
				writeOffset = putSurfaceDetailAnchor(anchors, writeOffset,
					(xoff + tileX) * Perspective.LOCAL_TILE_SIZE,
					height,
					(zoff + tileZ) * Perspective.LOCAL_TILE_SIZE,
					surfaceGeometrySeed(worldTileX, worldTileZ, level,
						type, anchor), materials[face]);
				if (type == SURFACE_DETAIL_GRASS)
				{
					grassStats[GRASS_STAT_ROOTS]++;
				}
			}
		}
		if (vegetationEligible)
		{
			grassStats[GRASS_STAT_ELIGIBLE_TILES]++;
		}
		return writeOffset;
	}

	private static int modelTileBase(int[] vertices)
	{
		int minimum = Integer.MAX_VALUE;
		for (int vertex : vertices)
		{
			minimum = Math.min(minimum, vertex);
		}
		return Math.floorDiv(minimum, Perspective.LOCAL_TILE_SIZE)
			* Perspective.LOCAL_TILE_SIZE;
	}

	private static boolean validModelFace(int face,
		int[] faceX, int[] faceY, int[] faceZ,
		int[] vertexX, int[] vertexY, int[] vertexZ, int[] colorA)
	{
		if (face >= colorA.length || colorA[face] == INVISIBLE_HSL)
		{
			return false;
		}
		int a = faceX[face];
		int b = faceY[face];
		int c = faceZ[face];
		int vertexCount = Math.min(vertexX.length,
			Math.min(vertexY.length, vertexZ.length));
		return a >= 0 && a < vertexCount
			&& b >= 0 && b < vertexCount
			&& c >= 0 && c < vertexCount;
	}

	private static int containingModelFace(float sampleX, float sampleZ,
		boolean[] sampleable,
		int[] faceX, int[] faceY, int[] faceZ,
		int[] vertexX, int[] vertexZ)
	{
		for (int face = 0; face < sampleable.length; ++face)
		{
			if (!sampleable[face])
			{
				continue;
			}
			int a = faceX[face];
			int b = faceY[face];
			int c = faceZ[face];
			float denominator = (vertexZ[b] - vertexZ[c]) * (float) (vertexX[a] - vertexX[c])
				+ (vertexX[c] - vertexX[b]) * (float) (vertexZ[a] - vertexZ[c]);
			if (Math.abs(denominator) < 0.0001f)
			{
				continue;
			}
			float weightA = ((vertexZ[b] - vertexZ[c]) * (sampleX - vertexX[c])
				+ (vertexX[c] - vertexX[b]) * (sampleZ - vertexZ[c])) / denominator;
			float weightB = ((vertexZ[c] - vertexZ[a]) * (sampleX - vertexX[c])
				+ (vertexX[a] - vertexX[c]) * (sampleZ - vertexZ[c])) / denominator;
			float weightC = 1.0f - weightA - weightB;
			if (weightA >= -0.0001f && weightB >= -0.0001f && weightC >= -0.0001f)
			{
				return face;
			}
		}
		return -1;
	}

	static boolean surfaceDetailFootprintClear(
		float sampleX,
		float sampleZ,
		int face,
		int type,
		int[] detailTypes,
		boolean[] sampleable,
		int[] faceX,
		int[] faceY,
		int[] faceZ,
		int[] vertexX,
		int[] vertexZ)
	{
		float clearance = surfaceDetailEdgeClearance(type);
		if (clearance <= 0.0f || face < 0 || face >= detailTypes.length)
		{
			return false;
		}

		int a = faceX[face];
		int b = faceY[face];
		int c = faceZ[face];
		float clearanceSquared = clearance * clearance;
		return surfaceDetailEdgeClear(sampleX, sampleZ, face, a, b, type,
			clearanceSquared, detailTypes, sampleable,
			faceX, faceY, faceZ, vertexX, vertexZ)
			&& surfaceDetailEdgeClear(sampleX, sampleZ, face, b, c, type,
				clearanceSquared, detailTypes, sampleable,
				faceX, faceY, faceZ, vertexX, vertexZ)
			&& surfaceDetailEdgeClear(sampleX, sampleZ, face, c, a, type,
				clearanceSquared, detailTypes, sampleable,
				faceX, faceY, faceZ, vertexX, vertexZ);
	}

	private static boolean surfaceDetailEdgeClear(
		float sampleX,
		float sampleZ,
		int face,
		int vertexA,
		int vertexB,
		int type,
		float clearanceSquared,
		int[] detailTypes,
		boolean[] sampleable,
		int[] faceX,
		int[] faceY,
		int[] faceZ,
		int[] vertexX,
		int[] vertexZ)
	{
		return pointSegmentDistanceSquared(sampleX, sampleZ,
			vertexX[vertexA], vertexZ[vertexA],
			vertexX[vertexB], vertexZ[vertexB]) >= clearanceSquared
			|| hasAdjacentDetailFace(face, vertexA, vertexB, type,
				detailTypes, sampleable, faceX, faceY, faceZ);
	}

	static float surfaceDetailEdgeClearance(int type)
	{
		switch (type)
		{
			case SURFACE_DETAIL_GRASS:
				return 4.0f;
			case SURFACE_DETAIL_PEBBLE:
				return 14.0f;
			case SURFACE_DETAIL_SAND:
				return 11.0f;
			case SURFACE_DETAIL_DIRT:
				return 12.0f;
			default:
				return -1.0f;
		}
	}

	private static boolean hasAdjacentDetailFace(
		int face,
		int edgeVertexA,
		int edgeVertexB,
		int type,
		int[] detailTypes,
		boolean[] sampleable,
		int[] faceX,
		int[] faceY,
		int[] faceZ)
	{
		for (int otherFace = 0; otherFace < detailTypes.length; ++otherFace)
		{
			if (otherFace == face || !sampleable[otherFace]
				|| detailTypes[otherFace] != type)
			{
				continue;
			}
			boolean hasA = faceX[otherFace] == edgeVertexA
				|| faceY[otherFace] == edgeVertexA
				|| faceZ[otherFace] == edgeVertexA;
			boolean hasB = faceX[otherFace] == edgeVertexB
				|| faceY[otherFace] == edgeVertexB
				|| faceZ[otherFace] == edgeVertexB;
			if (hasA && hasB)
			{
				return true;
			}
		}
		return false;
	}

	private static float pointSegmentDistanceSquared(
		float pointX,
		float pointZ,
		float edgeAX,
		float edgeAZ,
		float edgeBX,
		float edgeBZ)
	{
		float edgeX = edgeBX - edgeAX;
		float edgeZ = edgeBZ - edgeAZ;
		float lengthSquared = edgeX * edgeX + edgeZ * edgeZ;
		if (lengthSquared <= 0.0001f)
		{
			float dx = pointX - edgeAX;
			float dz = pointZ - edgeAZ;
			return dx * dx + dz * dz;
		}
		float projection = ((pointX - edgeAX) * edgeX
			+ (pointZ - edgeAZ) * edgeZ) / lengthSquared;
		projection = Math.max(0.0f, Math.min(1.0f, projection));
		float dx = pointX - (edgeAX + edgeX * projection);
		float dz = pointZ - (edgeAZ + edgeZ * projection);
		return dx * dx + dz * dz;
	}

	private static float modelFaceHeight(float sampleX, float sampleZ, int face,
		int[] faceX, int[] faceY, int[] faceZ,
		int[] vertexX, int[] vertexY, int[] vertexZ)
	{
		int a = faceX[face];
		int b = faceY[face];
		int c = faceZ[face];
		float denominator = (vertexZ[b] - vertexZ[c]) * (float) (vertexX[a] - vertexX[c])
			+ (vertexX[c] - vertexX[b]) * (float) (vertexZ[a] - vertexZ[c]);
		float weightA = ((vertexZ[b] - vertexZ[c]) * (sampleX - vertexX[c])
			+ (vertexX[c] - vertexX[b]) * (sampleZ - vertexZ[c])) / denominator;
		float weightB = ((vertexZ[c] - vertexZ[a]) * (sampleX - vertexX[c])
			+ (vertexX[a] - vertexX[c]) * (sampleZ - vertexZ[c])) / denominator;
		float weightC = 1.0f - weightA - weightB;
		return weightA * vertexY[a] + weightB * vertexY[b] + weightC * vertexY[c];
	}

	private static float paintHeight(float tileX, float tileZ,
		int swHeight, int seHeight, int neHeight, int nwHeight)
	{
		if (tileX + tileZ <= 1.0f)
		{
			return swHeight
				+ (seHeight - swHeight) * tileX
				+ (nwHeight - swHeight) * tileZ;
		}
		return neHeight
			+ (nwHeight - neHeight) * (1.0f - tileX)
			+ (seHeight - neHeight) * (1.0f - tileZ);
	}

	private static int putSurfaceDetailAnchor(float[] anchors, int writeOffset,
		float x, float y, float z, float geometrySeed, SurfaceDetailMaterial material)
	{
		anchors[writeOffset++] = x;
		anchors[writeOffset++] = y;
		anchors[writeOffset++] = z;
		anchors[writeOffset++] = geometrySeed;
		anchors[writeOffset++] = material.packedHsl;
		anchors[writeOffset++] = material.type;
		return writeOffset;
	}

	static int surfaceAnchorCount(int type, int worldTileX, int worldTileZ)
	{
		int hash = surfaceHash(worldTileX, worldTileZ, 0, type * 53);
		switch (type)
		{
			case SURFACE_DETAIL_GRASS:
				return 36 + Math.floorMod(hash, 13);
			case SURFACE_DETAIL_PEBBLE:
				return 4 + (hash & 1);
			case SURFACE_DETAIL_SAND:
			case SURFACE_DETAIL_DIRT:
				return 2;
			default:
				return 0;
		}
	}

	/** Uniform area samples for the two triangles making up a paint tile. */
	static float[] surfaceTriangleSample(int anchor, int worldTileX,
		int worldTileZ, int level)
	{
		int hashA = surfaceHash(worldTileX, worldTileZ, level,
			911 + anchor * 17);
		int hashB = surfaceHash(worldTileX, worldTileZ, level,
			1319 + anchor * 23);
		float r1 = hashUnit(hashA);
		float r2 = hashUnit(hashB);
		float root = (float) Math.sqrt(r1);
		float b = root * r2;
		float c = root * (1.0f - r2);
		// Alternate triangles so every eligible paint tile receives coverage on
		// both of its actual terrain triangles.
		if ((anchor & 1) == 0)
		{
			// SW, SE, NW
			return new float[]{b, c};
		}
		// NE, NW, SE
		return new float[]{1.0f - b, 1.0f - c};
	}

	static int surfaceDetailMaxSlope(int type)
	{
		switch (type)
		{
			case SURFACE_DETAIL_GRASS:
				return 72;
			case SURFACE_DETAIL_PEBBLE:
				return 48;
			case SURFACE_DETAIL_SAND:
				return 32;
			case SURFACE_DETAIL_DIRT:
				return 48;
			default:
				return -1;
		}
	}

	/**
	 * Returns a deterministic stratified coordinate inside one terrain tile.
	 * Grass may approach the bank slightly more closely; all opaque scatter keeps
	 * a wider footprint margin so its generated pieces do not spill onto docks,
	 * water, or an adjacent material.
	 */
	static float surfaceSampleCoordinate(int type, int anchor, boolean xAxis,
		int worldTileX, int worldTileZ, int level)
	{
		int columns;
		int rows;
		int step;
		float margin;
		switch (type)
		{
			case SURFACE_DETAIL_GRASS:
				columns = 4;
				rows = 3;
				step = 5;
				margin = 0.07f;
				break;
			case SURFACE_DETAIL_PEBBLE:
				columns = 3;
				rows = 2;
				step = 5;
				margin = 0.11f;
				break;
			case SURFACE_DETAIL_SAND:
			case SURFACE_DETAIL_DIRT:
				columns = 2;
				rows = 2;
				step = 3;
				margin = 0.11f;
				break;
			default:
				return 0.5f;
		}

		int cellCount = columns * rows;
		int cellOffset = Math.floorMod(surfaceHash(worldTileX, worldTileZ,
			level, 307 + type * 31), cellCount);
		int cell = Math.floorMod(cellOffset + Math.max(anchor, 0) * step, cellCount);
		int coordinate = xAxis ? cell % columns : cell / columns;
		int dimension = xAxis ? columns : rows;
		int jitterSample = 401 + type * 67 + Math.max(anchor, 0) * 2
			+ (xAxis ? 0 : 1);
		float jitter = 0.18f + hashUnit(surfaceHash(worldTileX, worldTileZ,
			level, jitterSample)) * 0.64f;
		float stratified = (coordinate + jitter) / dimension;
		return margin + stratified * (1.0f - margin * 2.0f);
	}

	static float surfaceGeometrySeed(int worldTileX, int worldTileZ, int level,
		int type, int anchor)
	{
		// The draw owner avalanches this stable seed before density selection rather
		// than thresholding it directly. That preserves the full range of plant
		// silhouettes, rock profiles, and color variation at every density setting.
		return hashUnit(surfaceHash(worldTileX, worldTileZ, level,
			701 + type * 97 + anchor * 11));
	}

	static int decodeTerrainDefinitionId(short encodedId)
	{
		return (encodedId & 0xffff) - 1;
	}

	static int sceneToWorldTile(int sceneBase, int extendedSceneCoordinate,
		int sceneOffset)
	{
		return sceneBase + extendedSceneCoordinate - sceneOffset;
	}

	static int paintTerrainLayer(int underlayId, int overlayId)
	{
		return overlayId >= 0
			? TERRAIN_LAYER_OVERLAY
			: underlayId >= 0
				? TERRAIN_LAYER_UNDERLAY
				: NO_TERRAIN_DEFINITION;
	}

	static boolean surfaceDetailTileEligible(
		int storagePlane,
		int tilePlane,
		int renderLevel,
		int tileFlags,
		int bridgeFlags,
		int roofId,
		boolean hasBridge)
	{
		return storagePlane == 0
			&& tilePlane == 0
			&& renderLevel == 0
			&& (tileFlags & Constants.TILE_FLAG_UNDER_ROOF) == 0
			&& (bridgeFlags & Constants.TILE_FLAG_BRIDGE) == 0
			&& roofId <= 0
			&& !hasBridge;
	}

	static int eligibleSurfaceDetailType(
		SurfaceMaterial material,
		int texture,
		int layer,
		int underlayId,
		int overlayId)
	{
		boolean knownLayer = layer == TERRAIN_LAYER_UNDERLAY
			|| layer == TERRAIN_LAYER_OVERLAY;
		if (!material.supportsSurfaceDetails()
			|| !knownLayer && (material != SurfaceMaterial.GRASS
				|| texture != TURF_TEXTURE)
			|| layer == TERRAIN_LAYER_OVERLAY && isCarpetOverlay(overlayId))
		{
			return SURFACE_DETAIL_NONE;
		}

		boolean naturalUnderlay = layer == TERRAIN_LAYER_UNDERLAY
			&& underlayId >= 0;
		switch (material)
		{
			case GRASS:
				// The POC is deliberately limited to one exact terrain material.
				// Do not infer vegetation from a green HSL fallback on arbitrary
				// ground or indoor floors.
				return naturalUnderlay && overlayId < 0 && isPocGrassUnderlay(underlayId)
					? SURFACE_DETAIL_GRASS : SURFACE_DETAIL_NONE;
			case STONE:
				return naturalUnderlay && texture < 0
					? SURFACE_DETAIL_PEBBLE : SURFACE_DETAIL_NONE;
			case SAND:
				return naturalUnderlay
					|| layer == TERRAIN_LAYER_OVERLAY && isSandOverlay(overlayId)
					? SURFACE_DETAIL_SAND : SURFACE_DETAIL_NONE;
			case DIRT:
				return naturalUnderlay && texture < 0
					? SURFACE_DETAIL_DIRT : SURFACE_DETAIL_NONE;
			default:
				return SURFACE_DETAIL_NONE;
		}
	}

	private static boolean isSandOverlay(int overlayId)
	{
		return overlayId == 25 || overlayId == 26 || overlayId == 76;
	}

	private static boolean isPocGrassUnderlay(int underlayId)
	{
		return underlayId >= 46 && underlayId <= 50
			|| underlayId >= 59 && underlayId <= 64;
	}

	private static boolean isCarpetOverlay(int overlayId)
	{
		return overlayId == 13 || overlayId == 163;
	}

	private static SurfaceDetailMaterial surfaceMaterial(
		SceneTilePaint paint, int underlayId, int overlayId,
		int worldX, int worldY, int plane)
	{
		int layer = paintTerrainLayer(underlayId, overlayId);
		int definitionId = layer == TERRAIN_LAYER_OVERLAY ? overlayId : underlayId;
		SurfaceMaterial surfaceMaterial = SurfaceMaterialClassifier.classifyPaintMatch(
			paint, layer, definitionId, worldX, worldY, plane).getMaterial();
		int detailType = eligibleSurfaceDetailType(surfaceMaterial,
			paint.getTexture(), paintTerrainLayer(underlayId, overlayId),
			underlayId, overlayId);
		// Keep the POC tied to the exact mapped underlay even when a paint tile's
		// classifier falls back through an overlay/material hint. The overlay must
		// still be absent so grass never appears beneath a visible authored surface.
		if (detailType == SURFACE_DETAIL_NONE
			&& isPocGrassUnderlay(underlayId)
			&& overlayId < 0
			&& !isWaterTexture(paint.getTexture()))
		{
			detailType = SURFACE_DETAIL_GRASS;
		}
		if (detailType == SURFACE_DETAIL_NONE)
		{
			return null;
		}
		int[] colors = {
			paint.getSwColor(), paint.getSeColor(),
			paint.getNeColor(), paint.getNwColor()
		};
		for (int color : colors)
		{
			if (color == INVISIBLE_HSL)
			{
				return null;
			}
		}

		// Textured tile corner values are lighting intensities rather than packed
		// HSL. Convert the paint's authored RGB material hint instead.
		if (paint.getTexture() >= 0)
		{
			int rgb = paint.getRBG();
			if (rgb < 0 || rgb > 0xffffff)
			{
				return null;
			}
			int packedHsl = SurfaceMaterialClassifier.rgbToPackedHsl(rgb);
			return new SurfaceDetailMaterial(detailType, packedHsl);
		}

		return new SurfaceDetailMaterial(detailType,
			averagePackedHsl(colors, detailType));
	}

	private static SurfaceDetailMaterial surfaceMaterial(
		SceneTileModel model, int face,
		int texture, int colorA, int colorB, int colorC,
		int underlayId, int overlayId,
		int worldX, int worldY, int plane)
	{
		int[] colors = {colorA, colorB, colorC};
		int layer = SurfaceMaterialClassifier.terrainLayerForFace(
			model.getShape(), face);
		int definitionId = layer == TERRAIN_LAYER_OVERLAY ? overlayId : underlayId;
		SurfaceMaterial surfaceMaterial = SurfaceMaterialClassifier
			.classifyTerrainFaceMatch(model, face, texture,
				colorA, colorB, colorC, layer, definitionId,
				worldX, worldY, plane).getMaterial();
		int detailType = eligibleSurfaceDetailType(surfaceMaterial, texture,
			layer, underlayId, overlayId);
		if (detailType == SURFACE_DETAIL_NONE
			&& isPocGrassUnderlay(underlayId)
			&& layer == TERRAIN_LAYER_UNDERLAY
			&& overlayId < 0
			&& !isWaterTexture(texture))
		{
			detailType = SURFACE_DETAIL_GRASS;
		}
		if (detailType == SURFACE_DETAIL_NONE)
		{
			return null;
		}
		if (texture >= 0)
		{
			int rgb = layer == 1 ? model.getModelOverlay()
				: layer == 0 ? model.getModelUnderlay() : -1;
			if (rgb >= 0 && rgb <= 0xffffff)
			{
				return new SurfaceDetailMaterial(detailType,
					SurfaceMaterialClassifier.rgbToPackedHsl(rgb));
			}

			// Textured triangle colors are lightness values. Preserve that value as
			// a neutral fallback rather than interpreting the low integer as packed
			// hue/saturation and generating a black detail instance.
			int luminance = Math.max(0, Math.min(127,
				Math.round(((colorA & 127) + (colorB & 127) + (colorC & 127)) / 3.0f)));
			return new SurfaceDetailMaterial(detailType, luminance);
		}
		return new SurfaceDetailMaterial(detailType,
			averagePackedHsl(colors, detailType));
	}

	private static boolean validPackedHsl(int color)
	{
		return SurfaceMaterialClassifier.isValidPackedHsl(color);
	}

	private static int averagePackedHsl(int[] colors, int surfaceType)
	{
		int firstHue = -1;
		int hueDelta = 0;
		int saturation = 0;
		int luminance = 0;
		int count = 0;
		for (int color : colors)
		{
			if (!validPackedHsl(color)
				|| SurfaceMaterialClassifier.classifyPackedHsl(color).getDetailType()
					!= surfaceType)
			{
				continue;
			}
			int hue = color >> 10 & 63;
			if (firstHue < 0)
			{
				firstHue = hue;
			}
			int delta = hue - firstHue;
			if (delta > 32)
			{
				delta -= 64;
			}
			else if (delta < -32)
			{
				delta += 64;
			}
			hueDelta += delta;
			saturation += color >> 7 & 7;
			luminance += color & 127;
			++count;
		}
		if (count == 0)
		{
			return 0;
		}
		int hue = Math.round(firstHue + hueDelta / (float) count) & 63;
		return hue << 10
			| Math.round(saturation / (float) count) << 7
			| Math.round(luminance / (float) count);
	}

	private static int surfaceHash(int x, int z, int level, int sample)
	{
		int hash = x * 0x1f1f1f1f ^ z * 0x45d9f3b ^ level * 0x27d4eb2d ^ sample * 0x165667b1;
		hash ^= hash >>> 16;
		hash *= 0x7feb352d;
		hash ^= hash >>> 15;
		hash *= 0x846ca68b;
		return hash ^ hash >>> 16;
	}

	private static float hashUnit(int hash)
	{
		return (hash & 0x00ffffff) / 16777216f;
	}

	private void uploadZoneLevel(Scene scene, Zone zone, int mzx, int mzz, int level, boolean visbelow, Set<Integer> roofIds, GpuIntBuffer vb, GpuIntBuffer ab)
	{
		int ridx = 0;

		// upload the roofs and save their positions
		for (int id : roofIds)
		{
			int pos = zone.vboO != null ? zone.vboO.vb.position() : 0;

			uploadZoneLevelRoof(scene, zone, mzx, mzz, level, id, visbelow, vb, ab);

			int endpos = zone.vboO != null ? zone.vboO.vb.position() : 0;

			if (endpos > pos)
			{
				zone.rids[level][ridx] = id;
				zone.roofStart[level][ridx] = pos;
				zone.roofEnd[level][ridx] = endpos;
				++ridx;
			}
		}

		// upload everything else
		uploadZoneLevelRoof(scene, zone, mzx, mzz, level, 0, visbelow, vb, ab);
	}

	private void uploadZoneLevelRoof(Scene scene, Zone zone, int mzx, int mzz, int level, int roofId, boolean visbelow, GpuIntBuffer vb, GpuIntBuffer ab)
	{
		byte[][][] settings = scene.getExtendedTileSettings();
		int[][][] roofs = scene.getRoofs();
		Tile[][][] tiles = scene.getExtendedTiles();

		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? GpuPlugin.SCENE_OFFSET >> 3 : 0;
		this.basex = (mzx - offset) << 10;
		this.basez = (mzz - offset) << 10;

		for (int xoff = 0; xoff < 8; ++xoff)
		{
			for (int zoff = 0; zoff < 8; ++zoff)
			{
				int msx = (mzx << 3) + xoff;
				int msz = (mzz << 3) + zoff;

				boolean isbridge = (settings[1][msx][msz] & Constants.TILE_FLAG_BRIDGE) != 0;
				int maplevel = level;
				if (isbridge)
				{
					++maplevel;
				}

				boolean isvisbelow = maplevel <= 3 && (settings[maplevel][msx][msz] & Constants.TILE_FLAG_VIS_BELOW) != 0;
				if (isvisbelow != visbelow)
				{
					continue;
				}

				int rid;
				if (isvisbelow || maplevel == 0)
				{
					rid = 0;
				}
				else
				{
					rid = roofs[maplevel - 1][msx][msz];
				}

				if (rid == roofId)
				{
					Tile t = tiles[level][msx][msz];
					if (t != null)
					{
						this.rid = rid;
						uploadZoneTile(scene, zone, t, level, vb, ab);
					}
				}
			}
		}
	}

	private void zoneSize(Scene scene, Zone z, Tile t, int storagePlane)
	{
		SceneTilePaint paint = t.getSceneTilePaint();
		if (paint != null)
		{
			z.sizeO += 2;
			if (isWaterTexture(paint.getTexture())
				&& paint.getNeColor() != INVISIBLE_HSL
				&& findNearestTerrainMaterial(scene, storagePlane, t,
					tileCenterX(scene, t), tileCenterY(scene, t)) != null)
			{
				z.sizeO += 2;
			}
		}

		SceneTileModel model = t.getSceneTileModel();
		if (model != null)
		{
			z.sizeO += model.getFaceX().length;
			int[] triangleTextures = model.getTriangleTextureId();
			int[] triangleColors = model.getTriangleColorA();
			TerrainMaterial material = triangleTextures != null
				? findNearestTerrainMaterial(scene, storagePlane, t,
					tileCenterX(scene, t), tileCenterY(scene, t)) : null;
			if (material != null)
			{
				for (int face = 0; face < model.getFaceX().length; ++face)
				{
					if (face < triangleTextures.length
						&& isWaterTexture(triangleTextures[face])
						&& face < triangleColors.length
						&& triangleColors[face] != INVISIBLE_HSL)
					{
						++z.sizeO;
					}
				}
			}
		}

		WallObject wallObject = t.getWallObject();
		if (wallObject != null)
		{
			zoneRenderableSize(z, wallObject.getRenderable1());
			zoneRenderableSize(z, wallObject.getRenderable2());
		}

		DecorativeObject decorativeObject = t.getDecorativeObject();
		if (decorativeObject != null)
		{
			zoneRenderableSize(z, decorativeObject.getRenderable());
			zoneRenderableSize(z, decorativeObject.getRenderable2());
		}

		GroundObject groundObject = t.getGroundObject();
		if (groundObject != null)
		{
			zoneRenderableSize(z, groundObject.getRenderable());
		}

		GameObject[] gameObjects = t.getGameObjects();
		for (GameObject gameObject : gameObjects)
		{
			if (gameObject == null)
			{
				continue;
			}

			if (!gameObject.getSceneMinLocation().equals(t.getSceneLocation()))
			{
				continue;
			}

			Renderable renderable = gameObject.getRenderable();
			zoneRenderableSize(z, renderable);
		}

		Tile bridge = t.getBridge();
		if (bridge != null)
		{
			zoneSize(scene, z, bridge, storagePlane);
		}
	}

	private int uploadZoneTile(
		Scene scene,
		Zone zone,
		Tile t,
		int storagePlane,
		GpuIntBuffer vertexBuffer,
		GpuIntBuffer ab)
	{
		int len = 0;
		boolean drawTile = renderCallbackManager.drawTile(scene, t);

		SceneTilePaint paint = t.getSceneTilePaint();
		if (paint != null && drawTile)
		{
			Point tilePoint = t.getSceneLocation();
			int waterStart = vertexBuffer.getBuffer().position();
			int surfaceLength = upload(scene, t, paint, storagePlane,
				t.getRenderLevel(), tilePoint.getX(), tilePoint.getY(),
				vertexBuffer,
				tilePoint.getX() * 128 - basex, tilePoint.getY() * 128 - basez
			);
			len = surfaceLength;
			if (surfaceLength > 0 && isWaterTexture(paint.getTexture()))
			{
				zone.addWaterRange(waterStart,
					vertexBuffer.getBuffer().position(), rid, level);

				TerrainMaterial material = findNearestTerrainMaterial(
					scene, storagePlane, t, tileCenterX(scene, t), tileCenterY(scene, t));
				if (material != null)
				{
					len += uploadWaterBed(scene, t, storagePlane,
						material, vertexBuffer,
						tilePoint.getX() * Perspective.LOCAL_TILE_SIZE - basex,
						tilePoint.getY() * Perspective.LOCAL_TILE_SIZE - basez);
				}
			}
		}

		SceneTileModel model = t.getSceneTileModel();
		if (model != null && drawTile)
		{
			Point tilePoint = t.getSceneLocation();
			len += upload(scene, zone, t, model, storagePlane,
				tilePoint.getX() << 7,
				tilePoint.getY() << 7, vertexBuffer);
		}
		WallObject wallObject = t.getWallObject();
		if (wallObject != null && renderCallbackManager.drawObject(scene, wallObject))
		{
			Renderable renderable1 = wallObject.getRenderable1();
			uploadZoneRenderable(scene, renderable1, zone, 0, wallObject.getX(), wallObject.getZ(), wallObject.getY(), -1, -1, -1, -1, wallObject, vertexBuffer, ab);

			Renderable renderable2 = wallObject.getRenderable2();
			uploadZoneRenderable(scene, renderable2, zone, 0, wallObject.getX(), wallObject.getZ(), wallObject.getY(), -1, -1, -1, -1, wallObject, vertexBuffer, ab);
		}

		DecorativeObject decorativeObject = t.getDecorativeObject();
		if (decorativeObject != null && renderCallbackManager.drawObject(scene, decorativeObject))
		{
			Renderable renderable = decorativeObject.getRenderable();
			uploadZoneRenderable(scene, renderable, zone, 0, decorativeObject.getX() + decorativeObject.getXOffset(), decorativeObject.getZ(), decorativeObject.getY() + decorativeObject.getYOffset(), -1, -1, -1, -1, decorativeObject, vertexBuffer, ab);

			Renderable renderable2 = decorativeObject.getRenderable2();
			uploadZoneRenderable(scene, renderable2, zone, 0, decorativeObject.getX() + decorativeObject.getXOffset2(), decorativeObject.getZ(), decorativeObject.getY() + decorativeObject.getYOffset2(), -1, -1, -1, -1, decorativeObject, vertexBuffer, ab);
		}

		GroundObject groundObject = t.getGroundObject();
		if (groundObject != null && renderCallbackManager.drawObject(scene, groundObject))
		{
			Renderable renderable = groundObject.getRenderable();
			uploadZoneRenderable(scene, renderable, zone, 0, groundObject.getX(), groundObject.getZ(), groundObject.getY(),
				-1, -1, -1, -1,
				groundObject,
				vertexBuffer, ab);
		}

		GameObject[] gameObjects = t.getGameObjects();
		for (GameObject gameObject : gameObjects)
		{
			if (gameObject == null)
			{
				continue;
			}

			Point min = gameObject.getSceneMinLocation(), max = gameObject.getSceneMaxLocation();

			if (!min.equals(t.getSceneLocation()))
			{
				continue;
			}

			if (!renderCallbackManager.drawObject(scene, gameObject))
			{
				continue;
			}

			Renderable renderable = gameObject.getRenderable();
			uploadZoneRenderable(scene, renderable, zone, gameObject.getModelOrientation(), gameObject.getX(), gameObject.getZ(), gameObject.getY(),
				min.getX(), min.getY(), max.getX(), max.getY(),
				gameObject,
				vertexBuffer, ab);
		}

		Tile bridge = t.getBridge();
		if (bridge != null)
		{
			// Bridge geometry can belong to another roof plane. Preserve it in the
			// atmospheric caster set unless it is uploaded through its own normal
			// tile/roof grouping, where its coverage can be classified reliably.
			len += uploadZoneTile(scene, zone, bridge, storagePlane,
				vertexBuffer, ab);
		}

		return len;
	}

	private void zoneRenderableSize(Zone z, Renderable r)
	{
		Model m = null;
		if (r instanceof Model)
		{
			m = (Model) r;
		}
		else if (r instanceof DynamicObject)
		{
			m = ((DynamicObject) r).getModelZbuf();
		}
		if (m == null)
		{
			return;
		}

		byte[] transparencies = m.getFaceTransparencies();
		int faceCount = m.getFaceCount();
		if (transparencies != null)
		{
			for (int face = 0; face < faceCount; ++face)
			{
				boolean alpha = transparencies[face] != 0;
				if (alpha)
				{
					z.sizeA++;
				}
				else
				{
					z.sizeO++;
				}
			}
			return;
		}
		z.sizeO += faceCount;
	}

	private void uploadZoneRenderable(Scene scene, Renderable r, Zone zone, int orient,
		int x, int y, int z, int lx, int lz, int ux, int uz,
		TileObject tileObject, GpuIntBuffer vb, GpuIntBuffer ab)
	{
		int sceneOffset = scene.getWorldViewId() == WorldView.TOPLEVEL
			? GpuPlugin.SCENE_OFFSET : 0;
		int objectWorldX = sceneToWorldTile(scene.getBaseX(),
			tileObject.getX() >> 7, sceneOffset);
		int objectWorldY = sceneToWorldTile(scene.getBaseY(),
			tileObject.getY() >> 7, sceneOffset);
		TreeReplacementRegistry.Definition auxiliaryTree = treeReplacementRegistry == null
			|| scene.getWorldViewId() != WorldView.TOPLEVEL
			? null : treeReplacementRegistry.resolveAuxiliary(tileObject.getId());
		if (auxiliaryTree != null && hasNearbyTreePrimary(scene, tileObject, auxiliaryTree))
		{
			// This is visual companion geometry (for example the stock foliage
			// tiles around an oak), not the authoritative game object. Keep it in
			// the scene while omitting only its uploaded model.
			return;
		}
		TreeReplacementRegistry.Definition treeReplacement = treeReplacementRegistry == null
			|| scene.getWorldViewId() != WorldView.TOPLEVEL
			? null : treeReplacementRegistry.resolve(tileObject.getId(), objectWorldX,
				objectWorldY, tileObject.getPlane());
		if (treeReplacement != null)
		{
			int hash = tileObject.getId() * 0x1f1f1f1f
				^ objectWorldX * 0x45d9f3b ^ objectWorldY * 0x119de1f3
				^ tileObject.getPlane() * 0x27d4eb2d;
			hash ^= hash >>> 16;
			float seed = (hash & 0x00ffffff) / 16777216.0f;
			zone.addTreeReplacement(treeReplacement.index, tileObject.getId(),
				x - basex, y, z - basez, orient, rid, level, seed);
			return;
		}
		int pos = zone.vboA != null ? zone.vboA.vb.position() : 0;
		Model model = null;
		if (r instanceof Model)
		{
			model = (Model) r;
			uploadStaticModel(model, orient, x - basex, y, z - basez,
				tileObject.getId(), objectWorldX, objectWorldY,
				tileObject.getPlane(), vb, ab);
		}
		else if (r instanceof DynamicObject)
		{
			model = ((DynamicObject) r).getModelZbuf();
			if (model != null)
			{
				uploadStaticModel(model, orient, x - basex, y, z - basez,
					tileObject.getId(), objectWorldX, objectWorldY,
					tileObject.getPlane(), vb, ab);
			}
		}
		int endpos = zone.vboA != null ? zone.vboA.vb.position() : 0;
		if (endpos > pos)
		{
			assert model != null;
			if (lx > -1)
			{
				lx -= basex >> 7;
				lz -= basez >> 7;
				ux -= basex >> 7;
				uz -= basez >> 7;
				assert lx >= 0 : lx;
				assert lz >= 0 : lz;
				assert ux < 25 : ux; // largest object?
				assert uz < 25 : uz;
			}
			zone.addAlphaModel(zone.glVaoA, model, pos, endpos,
				x - basex, y, z - basez,
				lx, lz, ux, uz,
				rid, level, tileObject.getId());
		}
	}

	private static boolean hasNearbyTreePrimary(Scene scene, TileObject auxiliary,
		TreeReplacementRegistry.Definition definition)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		int plane = auxiliary.getPlane();
		if (plane < 0 || plane >= tiles.length)
		{
			return false;
		}
		int centerX = auxiliary.getX() >> 7;
		int centerZ = auxiliary.getY() >> 7;
		int radius = definition.auxiliaryRadius;
		for (int x = Math.max(0, centerX - radius);
			x <= Math.min(tiles[plane].length - 1, centerX + radius); ++x)
		{
			for (int z = Math.max(0, centerZ - radius);
				z <= Math.min(tiles[plane][x].length - 1, centerZ + radius); ++z)
			{
				if (tileContainsTreePrimary(tiles[plane][x][z], definition))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static boolean tileContainsTreePrimary(Tile tile,
		TreeReplacementRegistry.Definition definition)
	{
		if (tile == null)
		{
			return false;
		}
		WallObject wall = tile.getWallObject();
		DecorativeObject decoration = tile.getDecorativeObject();
		GroundObject ground = tile.getGroundObject();
		if (wall != null && definition.matchesPrimaryObject(wall.getId())
			|| decoration != null && definition.matchesPrimaryObject(decoration.getId())
			|| ground != null && definition.matchesPrimaryObject(ground.getId()))
		{
			return true;
		}
		for (GameObject gameObject : tile.getGameObjects())
		{
			if (gameObject != null && definition.matchesPrimaryObject(gameObject.getId()))
			{
				return true;
			}
		}
		return tile.getBridge() != null
			&& tileContainsTreePrimary(tile.getBridge(), definition);
	}

	private int upload(Scene scene, Tile sceneTile, SceneTilePaint tile,
		int storagePlane, int tileZ, int tileX, int tileY,
		GpuIntBuffer vertexBuffer, int lx, int lz)
	{
		tileX += scene.getWorldViewId() == WorldView.TOPLEVEL ? GpuPlugin.SCENE_OFFSET : 0;
		tileY += scene.getWorldViewId() == WorldView.TOPLEVEL ? GpuPlugin.SCENE_OFFSET : 0;

		final int[][][] tileHeights = scene.getTileHeights();
		final int swHeight = tileHeights[tileZ][tileX][tileY];
		final int seHeight = tileHeights[tileZ][tileX + 1][tileY];
		final int neHeight = tileHeights[tileZ][tileX + 1][tileY + 1];
		final int nwHeight = tileHeights[tileZ][tileX][tileY + 1];

		int blendStrength = config.terrainTextureBlending()
			? config.terrainBlendStrength()
			: 0;
		final int swColor = blendTerrainCornerColor(scene, sceneTile, tile,
			tileX, tileY, 0, 0, blendStrength);
		final int seColor = blendTerrainCornerColor(scene, sceneTile, tile,
			tileX, tileY, 1, 0, blendStrength);
		final int neColor = blendTerrainCornerColor(scene, sceneTile, tile,
			tileX, tileY, 1, 1, blendStrength);
		final int nwColor = blendTerrainCornerColor(scene, sceneTile, tile,
			tileX, tileY, 0, 1, blendStrength);

		if (neColor == 12345678)
		{
			return 0;
		}

		// 0,0
		final int lx0 = lx;
		final int ly0 = swHeight;
		final int lz0 = lz;
		final int hsl0 = swColor;

		// 1,0
		final int lx1 = lx + Perspective.LOCAL_TILE_SIZE;
		final int ly1 = seHeight;
		final int lz1 = lz;
		final int hsl1 = seColor;

		// 1,1
		final int lx2 = lx + Perspective.LOCAL_TILE_SIZE;
		final int ly2 = neHeight;
		final int lz2 = lz + Perspective.LOCAL_TILE_SIZE;
		final int hsl2 = neColor;

		// 0,1
		final int lx3 = lx;
		final int ly3 = nwHeight;
		final int lz3 = lz + Perspective.LOCAL_TILE_SIZE;
		final int hsl3 = nwColor;

		int terrainPlane = sceneTile.getRenderLevel();
		int underlayId = decodeTerrainDefinitionId(
			scene.getUnderlayIds()[terrainPlane][tileX][tileY]);
		int overlayId = decodeTerrainDefinitionId(
			scene.getOverlayIds()[terrainPlane][tileX][tileY]);
		int terrainLayer = paintTerrainLayer(underlayId, overlayId);
		int definitionId = terrainLayer == TERRAIN_LAYER_OVERLAY
			? overlayId : underlayId;
		int sceneOffset = scene.getWorldViewId() == WorldView.TOPLEVEL
			? GpuPlugin.SCENE_OFFSET : 0;
		int worldTileX = sceneToWorldTile(scene.getBaseX(), tileX, sceneOffset);
		int worldTileY = sceneToWorldTile(scene.getBaseY(), tileY, sceneOffset);
		SurfaceMaterialRuleCatalog.Match materialMatch = SurfaceMaterialClassifier.classifyPaintMatch(
			tile, terrainLayer, definitionId,
			worldTileX, worldTileY, sceneTile.getPlane());
		HdTextureRegistry.Asset terrainAsset = exactTerrainAsset(
			tile.getTexture(), terrainLayer, definitionId);
		int tex = terrainAsset == null
			? materialMatch.packTextureCode(tile.getTexture() + 1)
			: materialMatch.getMaterial().packTextureCode(terrainAsset.getLayer() + 1,
				materialMatch.getAuthoredSlot());
		int shoreEdges = TERRAIN_FLAG;
		if (isWaterTexture(tile.getTexture()))
		{
			shoreEdges |= waterShoreMask(scene, storagePlane,
				sceneTile.getRenderLevel(), tileX, tileY);
		}
		else if (tile.getTexture() >= 0 || terrainAsset != null)
		{
			int plane = sceneTile.getPlane();
			int effectiveTexture = terrainAsset == null
				? tile.getTexture() : terrainAsset.getLayer();
			Tile[][][] tiles = scene.getExtendedTiles();
			shoreEdges |= getTerrainTextureBlendNeighbor(scene, tiles, plane,
				tileX - 1, tileY, sceneTile, effectiveTexture) != null
				? TERRAIN_BLEND_WEST : 0;
			shoreEdges |= getTerrainTextureBlendNeighbor(scene, tiles, plane,
				tileX + 1, tileY, sceneTile, effectiveTexture) != null
				? TERRAIN_BLEND_EAST : 0;
			shoreEdges |= getTerrainTextureBlendNeighbor(scene, tiles, plane,
				tileX, tileY - 1, sceneTile, effectiveTexture) != null
				? TERRAIN_BLEND_SOUTH : 0;
			shoreEdges |= getTerrainTextureBlendNeighbor(scene, tiles, plane,
				tileX, tileY + 1, sceneTile, effectiveTexture) != null
				? TERRAIN_BLEND_NORTH : 0;
		}
		vertexBuffer.put22224(lx2, ly2, lz2, hsl2);
		vertexBuffer.put2222(tex, 256, 256, shoreEdges);

		vertexBuffer.put22224(lx3, ly3, lz3, hsl3);
		vertexBuffer.put2222(tex, 0, 256, shoreEdges);

		vertexBuffer.put22224(lx1, ly1, lz1, hsl1);
		vertexBuffer.put2222(tex, 256, 0, shoreEdges);

		vertexBuffer.put22224(lx0, ly0, lz0, hsl0);
		vertexBuffer.put2222(tex, 0, 0, shoreEdges);

		vertexBuffer.put22224(lx1, ly1, lz1, hsl1);
		vertexBuffer.put2222(tex, 256, 0, shoreEdges);

		vertexBuffer.put22224(lx3, ly3, lz3, hsl3);
		vertexBuffer.put2222(tex, 0, 256, shoreEdges);

		return 6;
	}

	private int uploadWaterBed(
		Scene scene,
		Tile sceneTile,
		int storagePlane,
		TerrainMaterial material,
		GpuIntBuffer vertexBuffer,
		int lx,
		int lz)
	{
		Point tilePoint = sceneTile.getSceneLocation();
		int sceneOffset = scene.getWorldViewId() == WorldView.TOPLEVEL
			? GpuPlugin.SCENE_OFFSET : 0;
		int tileX = tilePoint.getX() + sceneOffset;
		int tileY = tilePoint.getY() + sceneOffset;
		int renderLevel = sceneTile.getRenderLevel();
		int[][][] tileHeights = scene.getTileHeights();

		int swHeight = tileHeights[renderLevel][tileX][tileY]
			+ waterBedDepthAt(scene, storagePlane, renderLevel, tileX, tileY);
		int seHeight = tileHeights[renderLevel][tileX + 1][tileY]
			+ waterBedDepthAt(scene, storagePlane, renderLevel, tileX + 1, tileY);
		int neHeight = tileHeights[renderLevel][tileX + 1][tileY + 1]
			+ waterBedDepthAt(scene, storagePlane, renderLevel, tileX + 1, tileY + 1);
		int nwHeight = tileHeights[renderLevel][tileX][tileY + 1]
			+ waterBedDepthAt(scene, storagePlane, renderLevel, tileX, tileY + 1);
		int tileSize = Perspective.LOCAL_TILE_SIZE;
		int texture = SurfaceMaterial.SAND.packTextureCode(material.texture + 1,
			SurfaceMaterial.SAND.getDefaultAuthoredVariant());
		int terrainFlags = waterBedTerrainFlags(material);

		vertexBuffer.put22224(lx + tileSize, neHeight, lz + tileSize,
			markWaterBedColor(material, material.neColor));
		vertexBuffer.put2222(texture, 256, 256, terrainFlags);
		vertexBuffer.put22224(lx, nwHeight, lz + tileSize,
			markWaterBedColor(material, material.nwColor));
		vertexBuffer.put2222(texture, 0, 256, terrainFlags);
		vertexBuffer.put22224(lx + tileSize, seHeight, lz,
			markWaterBedColor(material, material.seColor));
		vertexBuffer.put2222(texture, 256, 0, terrainFlags);

		vertexBuffer.put22224(lx, swHeight, lz,
			markWaterBedColor(material, material.swColor));
		vertexBuffer.put2222(texture, 0, 0, terrainFlags);
		vertexBuffer.put22224(lx + tileSize, seHeight, lz,
			markWaterBedColor(material, material.seColor));
		vertexBuffer.put2222(texture, 256, 0, terrainFlags);
		vertexBuffer.put22224(lx, nwHeight, lz + tileSize,
			markWaterBedColor(material, material.nwColor));
		vertexBuffer.put2222(texture, 0, 256, terrainFlags);
		return 6;
	}

	private static int markWaterBedColor(TerrainMaterial material, int hsl)
	{
		return WATER_BED_ALPHA_MARKER << 24
			| sandWaterBedColor(hsl, material.texture >= 0);
	}

	private static int sandWaterBedColor(int hsl, boolean textured)
	{
		int luminance = hsl & 127;
		int freshLuminance = Math.max(58, Math.min(127,
			Math.round(luminance * 0.58f
				+ WATER_BED_SAND_LUMINANCE * 0.42f)));
		if (textured)
		{
			// Textured terrain uses the packed color as its light value in the
			// stock texture path. Preserve those upper bits (and therefore the
			// copied material detail) while lifting the muddy/dark shoreline tone.
			return hsl & 0xff80 | freshLuminance;
		}

		int hue = hsl >> 10 & 63;
		int hueDelta = WATER_BED_SAND_HUE - hue;
		if (hueDelta > 32)
		{
			hueDelta -= 64;
		}
		else if (hueDelta < -32)
		{
			hueDelta += 64;
		}
		int sandHue = hue + Math.round(hueDelta * 0.72f) & 63;
		int saturation = hsl >> 7 & 7;
		int sandSaturation = Math.max(0, Math.min(7,
			Math.round(saturation * 0.35f
				+ WATER_BED_SAND_SATURATION * 0.65f)));
		return sandHue << 10 | sandSaturation << 7 | freshLuminance;
	}

	/**
	 * Smooth the duplicated colors at a painted terrain grid vertex. RuneLite
	 * stores each tile's four corners independently, so compatible neighboring
	 * tiles can disagree at the exact same world position and expose a hard grid
	 * line. Only equal-texture, non-water paint tiles on the same rendered level
	 * participate; paths, shores, overlays, and deliberate material boundaries
	 * therefore remain crisp.
	 */
	private static int blendTerrainCornerColor(
		Scene scene,
		Tile sceneTile,
		SceneTilePaint paint,
		int tileX,
		int tileY,
		int cornerX,
		int cornerY,
		int blendPercent)
	{
		int original = getPaintCornerColor(paint, cornerX, cornerY);
		int texture = paint.getTexture();
		if (blendPercent <= 0 || original == INVISIBLE_HSL || isWaterTexture(texture))
		{
			return original;
		}

		Tile[][][] tiles = scene.getExtendedTiles();
		int plane = sceneTile.getPlane();
		if (plane < 0 || plane >= tiles.length)
		{
			return original;
		}

		int gridX = tileX + cornerX;
		int gridY = tileY + cornerY;
		int material = terrainMaterialSignature(scene, plane, tileX, tileY);
		float strength = Math.min(blendPercent, 100) / 100f;

		if (texture >= 0)
		{
			int sum = original;
			int count = 1;
			for (int neighborX = gridX - 1; neighborX <= gridX; ++neighborX)
			{
				for (int neighborY = gridY - 1; neighborY <= gridY; ++neighborY)
				{
					Tile neighbor = getTerrainBlendNeighbor(scene, tiles, plane,
						neighborX, neighborY, sceneTile, texture, material);
					if (neighbor == null)
					{
						continue;
					}

					int candidate = getPaintCornerColor(neighbor.getSceneTilePaint(),
						gridX - neighborX, gridY - neighborY);
					if (candidate != INVISIBLE_HSL && Math.abs(candidate - original) <= 24)
					{
						sum += candidate;
						++count;
					}
				}
			}

			float average = sum / (float) count;
			return Math.max(0, Math.min(127,
				Math.round(original + (average - original) * strength)));
		}

		int originalHue = original >> 10 & 63;
		int originalSaturation = original >> 7 & 7;
		int originalLuminance = original & 127;
		int hueDeltaSum = 0;
		int saturationSum = originalSaturation;
		int luminanceSum = originalLuminance;
		int count = 1;

		for (int neighborX = gridX - 1; neighborX <= gridX; ++neighborX)
		{
			for (int neighborY = gridY - 1; neighborY <= gridY; ++neighborY)
			{
				Tile neighbor = getTerrainBlendNeighbor(scene, tiles, plane,
					neighborX, neighborY, sceneTile, texture, material);
				if (neighbor == null)
				{
					continue;
				}

				int candidate = getPaintCornerColor(neighbor.getSceneTilePaint(),
					gridX - neighborX, gridY - neighborY);
				if (!compatibleTerrainHsl(original, candidate))
				{
					continue;
				}

				int candidateHue = candidate >> 10 & 63;
				int hueDelta = candidateHue - originalHue;
				if (hueDelta > 32)
				{
					hueDelta -= 64;
				}
				else if (hueDelta < -32)
				{
					hueDelta += 64;
				}

				hueDeltaSum += hueDelta;
				saturationSum += candidate >> 7 & 7;
				luminanceSum += candidate & 127;
				++count;
			}
		}

		int hue = originalHue + Math.round(hueDeltaSum / (float) count * strength);
		int averageSaturation = Math.round(saturationSum / (float) count);
		int averageLuminance = Math.round(luminanceSum / (float) count);
		int saturation = Math.round(originalSaturation
			+ (averageSaturation - originalSaturation) * strength);
		int luminance = Math.round(originalLuminance
			+ (averageLuminance - originalLuminance) * strength);

		return (hue & 63) << 10
			| Math.max(0, Math.min(7, saturation)) << 7
			| Math.max(0, Math.min(127, luminance));
	}

	private static Tile getTerrainBlendNeighbor(
		Scene scene,
		Tile[][][] tiles,
		int plane,
		int tileX,
		int tileY,
		Tile current,
		int texture,
		int material)
	{
		if (tileX < 0 || tileX >= tiles[plane].length
			|| tileY < 0 || tileY >= tiles[plane][tileX].length)
		{
			return null;
		}

		Tile neighbor = tiles[plane][tileX][tileY];
		if (neighbor == null || neighbor == current
			|| neighbor.getRenderLevel() != current.getRenderLevel())
		{
			return null;
		}

		SceneTilePaint neighborPaint = neighbor.getSceneTilePaint();
		return neighborPaint != null
			&& neighborPaint.getTexture() == texture
			&& (texture >= 0
				|| terrainMaterialSignature(scene, plane, tileX, tileY) == material)
			? neighbor
			: null;
	}

	private Tile getTerrainTextureBlendNeighbor(Scene scene, Tile[][][] tiles,
		int plane, int tileX, int tileY, Tile current, int effectiveTexture)
	{
		if (plane < 0 || plane >= tiles.length
			|| tileX < 0 || tileX >= tiles[plane].length
			|| tileY < 0 || tileY >= tiles[plane][tileX].length)
		{
			return null;
		}
		Tile neighbor = tiles[plane][tileX][tileY];
		if (neighbor == null || neighbor == current
			|| neighbor.getRenderLevel() != current.getRenderLevel())
		{
			return null;
		}
		SceneTilePaint paint = neighbor.getSceneTilePaint();
		if (paint == null || isWaterTexture(paint.getTexture()))
		{
			return null;
		}
		int terrainPlane = neighbor.getRenderLevel();
		int underlayId = decodeTerrainDefinitionId(
			scene.getUnderlayIds()[terrainPlane][tileX][tileY]);
		int overlayId = decodeTerrainDefinitionId(
			scene.getOverlayIds()[terrainPlane][tileX][tileY]);
		int terrainLayer = paintTerrainLayer(underlayId, overlayId);
		int definitionId = terrainLayer == TERRAIN_LAYER_OVERLAY
			? overlayId : underlayId;
		HdTextureRegistry.Asset asset = exactTerrainAsset(
			paint.getTexture(), terrainLayer, definitionId);
		int neighborTexture = asset == null ? paint.getTexture() : asset.getLayer();
		return neighborTexture == effectiveTexture ? neighbor : null;
	}

	private static int terrainMaterialSignature(Scene scene, int plane, int tileX, int tileY)
	{
		short[][][] underlays = scene.getUnderlayIds();
		short[][][] overlays = scene.getOverlayIds();
		if (plane < 0 || plane >= underlays.length
			|| tileX < 0 || tileX >= underlays[plane].length
			|| tileY < 0 || tileY >= underlays[plane][tileX].length)
		{
			return -1;
		}

		return underlays[plane][tileX][tileY] & 0xffff
			| (overlays[plane][tileX][tileY] & 0xffff) << 16;
	}

	private static int getPaintCornerColor(SceneTilePaint paint, int cornerX, int cornerY)
	{
		if (cornerY == 0)
		{
			return cornerX == 0 ? paint.getSwColor() : paint.getSeColor();
		}
		return cornerX == 0 ? paint.getNwColor() : paint.getNeColor();
	}

	private static boolean compatibleTerrainHsl(int first, int second)
	{
		if (second == INVISIBLE_HSL)
		{
			return false;
		}

		int firstHue = first >> 10 & 63;
		int secondHue = second >> 10 & 63;
		int hueDifference = Math.abs(firstHue - secondHue);
		hueDifference = Math.min(hueDifference, 64 - hueDifference);
		int firstSaturation = first >> 7 & 7;
		int secondSaturation = second >> 7 & 7;
		int luminanceDifference = Math.abs((first & 127) - (second & 127));

		boolean neutralColors = firstSaturation <= 1 && secondSaturation <= 1;
		return (neutralColors || hueDifference <= 5)
			&& Math.abs(firstSaturation - secondSaturation) <= 2
			&& luminanceDifference <= 28;
	}

	private static boolean isWaterTexture(int textureId)
	{
		return SurfaceMaterialClassifier.isWaterTexture(textureId);
	}

	private static float tileCenterX(Scene scene, Tile tile)
	{
		int sceneOffset = scene.getWorldViewId() == WorldView.TOPLEVEL
			? GpuPlugin.SCENE_OFFSET : 0;
		return tile.getSceneLocation().getX() + sceneOffset + 0.5f;
	}

	private static float tileCenterY(Scene scene, Tile tile)
	{
		int sceneOffset = scene.getWorldViewId() == WorldView.TOPLEVEL
			? GpuPlugin.SCENE_OFFSET : 0;
		return tile.getSceneLocation().getY() + sceneOffset + 0.5f;
	}

	private static boolean validTileCell(Tile[][][] tiles, int storagePlane,
		int tileX, int tileY)
	{
		return storagePlane >= 0 && storagePlane < tiles.length
			&& tileX >= 0 && tileX < tiles[storagePlane].length
			&& tileY >= 0 && tileY < tiles[storagePlane][tileX].length;
	}

	private static boolean tileHasWater(Tile tile)
	{
		SceneTilePaint paint = tile.getSceneTilePaint();
		if (paint != null && paint.getNeColor() != INVISIBLE_HSL
			&& isWaterTexture(paint.getTexture()))
		{
			return true;
		}

		SceneTileModel model = tile.getSceneTileModel();
		if (model == null || model.getTriangleTextureId() == null)
		{
			return false;
		}

		int[] textures = model.getTriangleTextureId();
		int[] colors = model.getTriangleColorA();
		int faceCount = Math.min(textures.length, colors.length);
		for (int face = 0; face < faceCount; ++face)
		{
			if (colors[face] != INVISIBLE_HSL && isWaterTexture(textures[face]))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean modelHasVisibleNonWater(SceneTileModel model)
	{
		if (model == null)
		{
			return false;
		}

		int[] colors = model.getTriangleColorA();
		int[] textures = model.getTriangleTextureId();
		for (int face = 0; face < colors.length; ++face)
		{
			int texture = textures != null && face < textures.length
				? textures[face] : -1;
			if (colors[face] != INVISIBLE_HSL && !isWaterTexture(texture))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean tileHasVisibleNonWater(Tile tile)
	{
		SceneTilePaint paint = tile.getSceneTilePaint();
		if (paint != null && paint.getNeColor() != INVISIBLE_HSL
			&& !isWaterTexture(paint.getTexture()))
		{
			return true;
		}
		return modelHasVisibleNonWater(tile.getSceneTileModel());
	}

	private TerrainMaterial findNearestTerrainMaterial(
		Scene scene,
		int storagePlane,
		Tile targetTile,
		float targetX,
		float targetY)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		Point targetPoint = targetTile.getSceneLocation();
		int sceneOffset = scene.getWorldViewId() == WorldView.TOPLEVEL
			? GpuPlugin.SCENE_OFFSET : 0;
		int targetTileX = targetPoint.getX() + sceneOffset;
		int targetTileY = targetPoint.getY() + sceneOffset;
		int renderLevel = targetTile.getRenderLevel();

		// A mixed shaped tile can carry its own bank beside a water face.
		TerrainMaterial internalBank = terrainMaterialFromTile(targetTile,
			targetTileX, targetTileY, targetX, targetY, true);
		if (internalBank != null)
		{
			return internalBank;
		}

		// Walk outward through connected water only. A geometric square search can
		// jump across an island or dock and borrow material from an unrelated bank.
		if (waterBedVisitStamp == Integer.MAX_VALUE)
		{
			Arrays.fill(waterBedVisited, 0);
			waterBedVisitStamp = 1;
		}
		else
		{
			++waterBedVisitStamp;
		}
		int visitStamp = waterBedVisitStamp;
		int center = WATER_BED_RINGS;
		int head = 0;
		int tail = 1;
		waterBedQueueX[0] = targetTileX;
		waterBedQueueY[0] = targetTileY;
		waterBedVisited[center * WATER_BED_SEARCH_DIAMETER + center] = visitStamp;

		for (int waterDistance = 0;
			waterDistance < WATER_BED_RINGS && head < tail; ++waterDistance)
		{
			int levelEnd = tail;
			TerrainMaterial best = null;
			while (head < levelEnd)
			{
				int waterX = waterBedQueueX[head];
				int waterY = waterBedQueueY[head++];
				for (int dx = -1; dx <= 1; ++dx)
				{
					for (int dy = -1; dy <= 1; ++dy)
					{
						if (dx == 0 && dy == 0)
						{
							continue;
						}

						int sceneX = waterX + dx;
						int sceneY = waterY + dy;
						int offsetX = sceneX - targetTileX;
						int offsetY = sceneY - targetTileY;
						if (Math.abs(offsetX) > WATER_BED_RINGS
							|| Math.abs(offsetY) > WATER_BED_RINGS
							|| !validTileCell(tiles, storagePlane, sceneX, sceneY))
						{
							continue;
						}

						TerrainMaterial candidate = terrainMaterialAtLayer(
							tiles, storagePlane, renderLevel,
							sceneX, sceneY, targetX, targetY);
						if (betterTerrainMaterial(candidate, best))
						{
							best = candidate;
						}

						int visitX = offsetX + center;
						int visitY = offsetY + center;
						int visitIndex = visitX * WATER_BED_SEARCH_DIAMETER + visitY;
						if (waterDistance + 1 < WATER_BED_RINGS
							&& waterBedVisited[visitIndex] != visitStamp
							&& isWaterTile(scene, storagePlane, renderLevel,
								sceneX, sceneY))
						{
							waterBedVisited[visitIndex] = visitStamp;
							waterBedQueueX[tail] = sceneX;
							waterBedQueueY[tail++] = sceneY;
						}
					}
				}
			}

			if (best != null)
			{
				return best;
			}
		}
		return null;
	}

	private static TerrainMaterial terrainMaterialAtLayer(
		Tile[][][] tiles,
		int preferredStoragePlane,
		int renderLevel,
		int sceneX,
		int sceneY,
		float targetX,
		float targetY)
	{
		TerrainMaterial waterLayerMaterial = null;
		TerrainMaterial landLayerMaterial = null;
		boolean foundWaterLayer = false;
		for (int pass = 0; pass < tiles.length; ++pass)
		{
			int storagePlane = (preferredStoragePlane + pass) % tiles.length;
			if (!validTileCell(tiles, storagePlane, sceneX, sceneY))
			{
				continue;
			}

			for (Tile tile = tiles[storagePlane][sceneX][sceneY];
				tile != null; tile = tile.getBridge())
			{
				if (tile.getRenderLevel() != renderLevel)
				{
					continue;
				}

				if (tileHasWater(tile))
				{
					foundWaterLayer = true;
					TerrainMaterial material = terrainMaterialFromTile(tile,
						sceneX, sceneY, targetX, targetY, true);
					if (betterTerrainMaterial(material, waterLayerMaterial))
					{
						waterLayerMaterial = material;
					}
				}
				else
				{
					TerrainMaterial material = terrainMaterialFromTile(tile,
						sceneX, sceneY, targetX, targetY, false);
					if (betterTerrainMaterial(material, landLayerMaterial))
					{
						landLayerMaterial = material;
					}
				}
			}
		}

		// A bridge column can contain both deck and water tiles. Once matching-layer
		// water exists, only a non-water face in that very same shaped tile is a bank.
		return foundWaterLayer ? waterLayerMaterial : landLayerMaterial;
	}

	private static TerrainMaterial terrainMaterialFromTile(
		Tile tile,
		int sceneX,
		int sceneY,
		float targetX,
		float targetY,
		boolean mixedWaterOnly)
	{
		TerrainMaterial best = null;
		if (!mixedWaterOnly)
		{
			SceneTilePaint paint = tile.getSceneTilePaint();
			if (paint != null && paint.getNeColor() != INVISIBLE_HSL
				&& !isWaterTexture(paint.getTexture()))
			{
				float dx = sceneX + 0.5f - targetX;
				float dy = sceneY + 0.5f - targetY;
				best = new TerrainMaterial(paint.getTexture(),
					paint.getSwColor(), paint.getSeColor(),
					paint.getNeColor(), paint.getNwColor(),
					dx * dx + dy * dy, sceneX, sceneY, -1);
			}
		}

		SceneTileModel model = tile.getSceneTileModel();
		if (model == null)
		{
			return best;
		}

		int[] faceX = model.getFaceX();
		int[] faceY = model.getFaceY();
		int[] faceZ = model.getFaceZ();
		int[] vertexX = model.getVertexX();
		int[] vertexZ = model.getVertexZ();
		int[] colorA = model.getTriangleColorA();
		int[] colorB = model.getTriangleColorB();
		int[] colorC = model.getTriangleColorC();
		int[] textures = model.getTriangleTextureId();
		Point tilePoint = tile.getSceneLocation();
		int localTileX = tilePoint.getX() * Perspective.LOCAL_TILE_SIZE;
		int localTileY = tilePoint.getY() * Perspective.LOCAL_TILE_SIZE;
		for (int face = 0; face < faceX.length; ++face)
		{
			int texture = textures != null && face < textures.length
				? textures[face] : -1;
			if (face >= colorA.length || colorA[face] == INVISIBLE_HSL
				|| isWaterTexture(texture))
			{
				continue;
			}

			int a = faceX[face];
			int b = faceY[face];
			int c = faceZ[face];
			float sourceX = sceneX + ((vertexX[a] + vertexX[b] + vertexX[c]) / 3f
				- localTileX) / Perspective.LOCAL_TILE_SIZE;
			float sourceY = sceneY + ((vertexZ[a] + vertexZ[b] + vertexZ[c]) / 3f
				- localTileY) / Perspective.LOCAL_TILE_SIZE;
			float dx = sourceX - targetX;
			float dy = sourceY - targetY;
			int firstColor = colorA[face];
			int secondColor = face < colorB.length ? colorB[face] : firstColor;
			int thirdColor = face < colorC.length ? colorC[face] : firstColor;
			int representative = averageTerrainColor(texture,
				firstColor, secondColor, thirdColor);
			TerrainMaterial material = new TerrainMaterial(texture,
				representative, representative, representative, representative,
				dx * dx + dy * dy, sceneX, sceneY, face);
			if (betterTerrainMaterial(material, best))
			{
				best = material;
			}
		}
		return best;
	}

	private static boolean betterTerrainMaterial(TerrainMaterial candidate,
		TerrainMaterial current)
	{
		if (candidate == null)
		{
			return false;
		}
		if (current == null || candidate.distanceSquared < current.distanceSquared - 0.000001f)
		{
			return true;
		}
		if (Math.abs(candidate.distanceSquared - current.distanceSquared) > 0.000001f)
		{
			return false;
		}
		if (candidate.sceneX != current.sceneX)
		{
			return candidate.sceneX < current.sceneX;
		}
		if (candidate.sceneY != current.sceneY)
		{
			return candidate.sceneY < current.sceneY;
		}
		return candidate.face < current.face;
	}

	private static int visibleTerrainColor(int color, int fallback)
	{
		return color == INVISIBLE_HSL ? fallback : color;
	}

	private static int waterBedTerrainFlags(TerrainMaterial material)
	{
		return TERRAIN_FLAG | (material.texture >= 0
			? TERRAIN_BLEND_WEST | TERRAIN_BLEND_EAST
				| TERRAIN_BLEND_SOUTH | TERRAIN_BLEND_NORTH
			: 0);
	}

	private static int averageTerrainColor(int texture, int... colors)
	{
		int first = INVISIBLE_HSL;
		int count = 0;
		long directSum = 0;
		int hueDeltaSum = 0;
		int saturationSum = 0;
		int luminanceSum = 0;
		for (int color : colors)
		{
			if (color == INVISIBLE_HSL)
			{
				continue;
			}
			if (first == INVISIBLE_HSL)
			{
				first = color;
			}
			++count;
			directSum += color;
			if (texture < 0)
			{
				int referenceHue = first >> 10 & 63;
				int hue = color >> 10 & 63;
				int delta = hue - referenceHue;
				if (delta > 32)
				{
					delta -= 64;
				}
				else if (delta < -32)
				{
					delta += 64;
				}
				hueDeltaSum += delta;
				saturationSum += color >> 7 & 7;
				luminanceSum += color & 127;
			}
		}

		if (count == 0)
		{
			return 0;
		}
		if (texture >= 0)
		{
			return (int) Math.round(directSum / (double) count);
		}

		int referenceHue = first >> 10 & 63;
		int hue = Math.round(referenceHue + hueDeltaSum / (float) count) & 63;
		int saturation = Math.max(0, Math.min(7,
			Math.round(saturationSum / (float) count)));
		int luminance = Math.max(0, Math.min(127,
			Math.round(luminanceSum / (float) count)));
		return hue << 10 | saturation << 7 | luminance;
	}

	private static boolean hasNonWaterTerrainAtLayer(
		Tile[][][] tiles, int preferredStoragePlane,
		int renderLevel, int sceneX, int sceneY)
	{
		boolean foundWaterLayer = false;
		boolean mixedWaterBank = false;
		boolean landLayer = false;
		for (int pass = 0; pass < tiles.length; ++pass)
		{
			int storagePlane = (preferredStoragePlane + pass) % tiles.length;
			if (!validTileCell(tiles, storagePlane, sceneX, sceneY))
			{
				continue;
			}
			for (Tile tile = tiles[storagePlane][sceneX][sceneY];
				tile != null; tile = tile.getBridge())
			{
				if (tile.getRenderLevel() != renderLevel)
				{
					continue;
				}
				if (tileHasWater(tile))
				{
					foundWaterLayer = true;
					mixedWaterBank |= modelHasVisibleNonWater(tile.getSceneTileModel());
				}
				else
				{
					landLayer |= tileHasVisibleNonWater(tile);
				}
			}
		}
		return foundWaterLayer ? mixedWaterBank : landLayer;
	}

	private static int waterBedDepthAt(
		Scene scene,
		int storagePlane,
		int renderLevel,
		float gridX,
		float gridY)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		float nearest = Float.POSITIVE_INFINITY;
		int centerX = (int) Math.floor(gridX);
		int centerY = (int) Math.floor(gridY);
		int searchRadius = WATER_BED_RINGS + 1;
		for (int sceneX = centerX - searchRadius;
			sceneX <= centerX + searchRadius; ++sceneX)
		{
			for (int sceneY = centerY - searchRadius;
				sceneY <= centerY + searchRadius; ++sceneY)
			{
				if (!validTileCell(tiles, storagePlane, sceneX, sceneY)
					|| !hasNonWaterTerrainAtLayer(
						tiles, storagePlane, renderLevel, sceneX, sceneY))
				{
					continue;
				}

				float dx = Math.max(Math.max(sceneX - gridX, 0f),
					gridX - (sceneX + 1f));
				float dy = Math.max(Math.max(sceneY - gridY, 0f),
					gridY - (sceneY + 1f));
				nearest = Math.min(nearest, (float) Math.sqrt(dx * dx + dy * dy));
			}
		}

		if (!Float.isFinite(nearest))
		{
			nearest = WATER_BED_RINGS;
		}
		nearest = Math.min(nearest, WATER_BED_RINGS);
		int lower = Math.min((int) Math.floor(nearest), WATER_BED_RINGS - 1);
		float fraction = nearest - lower;
		return Math.round(WATER_BED_DEPTHS[lower]
			+ (WATER_BED_DEPTHS[lower + 1] - WATER_BED_DEPTHS[lower])
				* fraction);
	}

	private static boolean isWaterTile(Scene scene, int storagePlane,
		int renderLevel, int tileX, int tileY)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		for (int pass = 0; pass < tiles.length; ++pass)
		{
			int candidatePlane = (storagePlane + pass) % tiles.length;
			if (!validTileCell(tiles, candidatePlane, tileX, tileY))
			{
				continue;
			}
			for (Tile tile = tiles[candidatePlane][tileX][tileY];
				tile != null; tile = tile.getBridge())
			{
				if (tile.getRenderLevel() == renderLevel && tileHasWater(tile))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static int waterShoreMask(Scene scene, int storagePlane,
		int renderLevel, int tileX, int tileY)
	{
		int mask = 0;
		mask |= !isWaterTile(scene, storagePlane, renderLevel, tileX - 1, tileY) ? 1 : 0;
		mask |= !isWaterTile(scene, storagePlane, renderLevel, tileX + 1, tileY) ? 2 : 0;
		mask |= !isWaterTile(scene, storagePlane, renderLevel, tileX, tileY - 1) ? 4 : 0;
		mask |= !isWaterTile(scene, storagePlane, renderLevel, tileX, tileY + 1) ? 8 : 0;
		mask |= !isWaterTile(scene, storagePlane, renderLevel, tileX - 1, tileY - 1) ? 16 : 0;
		mask |= !isWaterTile(scene, storagePlane, renderLevel, tileX + 1, tileY - 1) ? 32 : 0;
		mask |= !isWaterTile(scene, storagePlane, renderLevel, tileX + 1, tileY + 1) ? 64 : 0;
		mask |= !isWaterTile(scene, storagePlane, renderLevel, tileX - 1, tileY + 1) ? 128 : 0;
		return mask;
	}

	private static int internalWaterShoreMask(
		int face,
		int[] faceX,
		int[] faceY,
		int[] faceZ,
		int[] triangleTextures,
		int[] vertexX,
		int[] vertexZ,
		int tileX,
		int tileZ)
	{
		int vertex0 = faceX[face];
		int vertex1 = faceY[face];
		int vertex2 = faceZ[face];
		int mask = 0;
		if (isInternalWaterBoundary(face, vertex1, vertex2,
			faceX, faceY, faceZ, triangleTextures,
			vertexX, vertexZ, tileX, tileZ))
		{
			mask |= WATER_FACE_EDGE_0;
		}
		if (isInternalWaterBoundary(face, vertex2, vertex0,
			faceX, faceY, faceZ, triangleTextures,
			vertexX, vertexZ, tileX, tileZ))
		{
			mask |= WATER_FACE_EDGE_1;
		}
		if (isInternalWaterBoundary(face, vertex0, vertex1,
			faceX, faceY, faceZ, triangleTextures,
			vertexX, vertexZ, tileX, tileZ))
		{
			mask |= WATER_FACE_EDGE_2;
		}
		return mask;
	}

	private static boolean isInternalWaterBoundary(
		int face,
		int edgeVertexA,
		int edgeVertexB,
		int[] faceX,
		int[] faceY,
		int[] faceZ,
		int[] triangleTextures,
		int[] vertexX,
		int[] vertexZ,
		int tileX,
		int tileZ)
	{
		// Cardinal shoreline bits already handle edges on the tile perimeter. The
		// barycentric bits are reserved for a water/non-water split inside a shaped
		// tile, avoiding double-bright foam along ordinary tile boundaries.
		if (isTilePerimeterEdge(edgeVertexA, edgeVertexB,
			vertexX, vertexZ, tileX, tileZ))
		{
			return false;
		}

		for (int otherFace = 0; otherFace < faceX.length; ++otherFace)
		{
			if (otherFace == face || !isWaterTexture(triangleTextures[otherFace]))
			{
				continue;
			}
			boolean hasA = faceX[otherFace] == edgeVertexA
				|| faceY[otherFace] == edgeVertexA
				|| faceZ[otherFace] == edgeVertexA;
			boolean hasB = faceX[otherFace] == edgeVertexB
				|| faceY[otherFace] == edgeVertexB
				|| faceZ[otherFace] == edgeVertexB;
			if (hasA && hasB)
			{
				return false;
			}
		}
		return true;
	}

	private static boolean isTilePerimeterEdge(
		int vertexA,
		int vertexB,
		int[] vertexX,
		int[] vertexZ,
		int tileX,
		int tileZ)
	{
		int localAx = vertexX[vertexA] - tileX;
		int localAz = vertexZ[vertexA] - tileZ;
		int localBx = vertexX[vertexB] - tileX;
		int localBz = vertexZ[vertexB] - tileZ;
		int tileSize = Perspective.LOCAL_TILE_SIZE;
		return localAx == 0 && localBx == 0
			|| localAx == tileSize && localBx == tileSize
			|| localAz == 0 && localBz == 0
			|| localAz == tileSize && localBz == tileSize;
	}


	private int upload(Scene scene, Zone zone, Tile sceneTile,
		SceneTileModel sceneTileModel, int storagePlane, int lx, int lz,
		GpuIntBuffer vertexBuffer)
	{
		final int[] faceX = sceneTileModel.getFaceX();
		final int[] faceY = sceneTileModel.getFaceY();
		final int[] faceZ = sceneTileModel.getFaceZ();

		final int[] vertexX = sceneTileModel.getVertexX();
		final int[] vertexY = sceneTileModel.getVertexY();
		final int[] vertexZ = sceneTileModel.getVertexZ();

		final int[] triangleColorA = sceneTileModel.getTriangleColorA();
		final int[] triangleColorB = sceneTileModel.getTriangleColorB();
		final int[] triangleColorC = sceneTileModel.getTriangleColorC();

		final int[] triangleTextures = sceneTileModel.getTriangleTextureId();
		Point tilePoint = sceneTile.getSceneLocation();
		int sceneOffset = scene.getWorldViewId() == WorldView.TOPLEVEL
			? GpuPlugin.SCENE_OFFSET : 0;
		int sceneTileX = tilePoint.getX() + sceneOffset;
		int sceneTileY = tilePoint.getY() + sceneOffset;
		int terrainPlane = sceneTile.getRenderLevel();
		int underlayId = decodeTerrainDefinitionId(
			scene.getUnderlayIds()[terrainPlane][sceneTileX][sceneTileY]);
		int overlayId = decodeTerrainDefinitionId(
			scene.getOverlayIds()[terrainPlane][sceneTileX][sceneTileY]);
		int worldTileX = sceneToWorldTile(scene.getBaseX(), sceneTileX, sceneOffset);
		int worldTileY = sceneToWorldTile(scene.getBaseY(), sceneTileY, sceneOffset);
		boolean modelHasWater = false;
		if (triangleTextures != null)
		{
			for (int textureId : triangleTextures)
			{
				if (isWaterTexture(textureId))
				{
					modelHasWater = true;
					break;
				}
			}
		}
		int outerWaterShoreMask = modelHasWater
			? waterShoreMask(scene, storagePlane, sceneTile.getRenderLevel(),
				sceneTileX, sceneTileY) : 0;
		TerrainMaterial waterBedMaterial = modelHasWater
			? findNearestTerrainMaterial(scene, storagePlane, sceneTile,
				sceneTileX + 0.5f, sceneTileY + 0.5f) : null;

		final int faceCount = faceX.length;

		int cnt = 0;
		for (int i = 0; i < faceCount; ++i)
		{
			final int vertex0 = faceX[i];
			final int vertex1 = faceY[i];
			final int vertex2 = faceZ[i];

			final int hsl0 = triangleColorA[i];
			final int hsl1 = triangleColorB[i];
			final int hsl2 = triangleColorC[i];

			if (hsl0 == 12345678)
			{
				continue;
			}

			int waterStart = vertexBuffer.getBuffer().position();
			cnt += 3;

			// vertexes are stored in scene local, convert to tile local
			int lx0 = vertexX[vertex0] - basex;
			int ly0 = vertexY[vertex0];
			int lz0 = vertexZ[vertex0] - basez;

			int lx1 = vertexX[vertex1] - basex;
			int ly1 = vertexY[vertex1];
			int lz1 = vertexZ[vertex1] - basez;

			int lx2 = vertexX[vertex2] - basex;
			int ly2 = vertexY[vertex2];
			int lz2 = vertexZ[vertex2] - basez;

			boolean waterFace = triangleTextures != null
				&& isWaterTexture(triangleTextures[i]);
			int texture = triangleTextures != null ? triangleTextures[i] : -1;
			int terrainLayer = SurfaceMaterialClassifier.terrainLayerForFace(
				sceneTileModel.getShape(), i);
			int definitionId = terrainLayer == TERRAIN_LAYER_OVERLAY
				? overlayId : underlayId;
			SurfaceMaterialRuleCatalog.Match materialMatch = SurfaceMaterialClassifier
				.classifyTerrainFaceMatch(sceneTileModel, i, texture,
					hsl0, hsl1, hsl2, terrainLayer, definitionId,
					worldTileX, worldTileY, sceneTile.getPlane());
			HdTextureRegistry.Asset terrainAsset = exactTerrainAsset(
				texture, terrainLayer, definitionId);
			int tex = terrainAsset == null
				? materialMatch.packTextureCode(texture + 1)
				: materialMatch.getMaterial().packTextureCode(terrainAsset.getLayer() + 1,
					materialMatch.getAuthoredSlot());
			int terrainFlags = TERRAIN_FLAG;
			if (waterFace)
			{
				terrainFlags |= outerWaterShoreMask;
				terrainFlags |= internalWaterShoreMask(i,
					faceX, faceY, faceZ, triangleTextures,
					vertexX, vertexZ, lx, lz);
			}
			vertexBuffer.put22224(lx0, ly0, lz0, hsl0);
			vertexBuffer.put2222(tex,
				(int) ((vertexX[vertex0] - lx) * 2f),
				(int) ((vertexZ[vertex0] - lz) * 2f),
				terrainFlags);

			vertexBuffer.put22224(lx1, ly1, lz1, hsl1);
			vertexBuffer.put2222(tex,
				(int) ((vertexX[vertex1] - lx) * 2f),
				(int) ((vertexZ[vertex1] - lz) * 2f),
				terrainFlags);

			vertexBuffer.put22224(lx2, ly2, lz2, hsl2);
			vertexBuffer.put2222(tex,
				(int) ((vertexX[vertex2] - lx) * 2f),
				(int) ((vertexZ[vertex2] - lz) * 2f),
				terrainFlags);

			if (waterFace)
			{
				zone.addWaterRange(waterStart,
					vertexBuffer.getBuffer().position(), rid, level);

				if (waterBedMaterial != null)
				{
					int renderLevel = sceneTile.getRenderLevel();
					int bedDepth0 = waterBedDepthAt(scene, storagePlane,
						renderLevel,
						sceneTileX + (vertexX[vertex0] - lx)
							/ (float) Perspective.LOCAL_TILE_SIZE,
						sceneTileY + (vertexZ[vertex0] - lz)
							/ (float) Perspective.LOCAL_TILE_SIZE);
					int bedDepth1 = waterBedDepthAt(scene, storagePlane,
						renderLevel,
						sceneTileX + (vertexX[vertex1] - lx)
							/ (float) Perspective.LOCAL_TILE_SIZE,
						sceneTileY + (vertexZ[vertex1] - lz)
							/ (float) Perspective.LOCAL_TILE_SIZE);
					int bedDepth2 = waterBedDepthAt(scene, storagePlane,
						renderLevel,
						sceneTileX + (vertexX[vertex2] - lx)
							/ (float) Perspective.LOCAL_TILE_SIZE,
						sceneTileY + (vertexZ[vertex2] - lz)
							/ (float) Perspective.LOCAL_TILE_SIZE);
					int bedTexture = SurfaceMaterial.SAND.packTextureCode(
						waterBedMaterial.texture + 1,
						SurfaceMaterial.SAND.getDefaultAuthoredVariant());
					int bedColor = waterBedMaterial.representativeColor;
					int bedFlags = waterBedTerrainFlags(waterBedMaterial);

					vertexBuffer.put22224(lx0, ly0 + bedDepth0, lz0,
						markWaterBedColor(waterBedMaterial, bedColor));
					vertexBuffer.put2222(bedTexture,
						(int) ((vertexX[vertex0] - lx) * 2f),
						(int) ((vertexZ[vertex0] - lz) * 2f), bedFlags);
					vertexBuffer.put22224(lx1, ly1 + bedDepth1, lz1,
						markWaterBedColor(waterBedMaterial, bedColor));
					vertexBuffer.put2222(bedTexture,
						(int) ((vertexX[vertex1] - lx) * 2f),
						(int) ((vertexZ[vertex1] - lz) * 2f), bedFlags);
					vertexBuffer.put22224(lx2, ly2 + bedDepth2, lz2,
						markWaterBedColor(waterBedMaterial, bedColor));
					vertexBuffer.put2222(bedTexture,
						(int) ((vertexX[vertex2] - lx) * 2f),
						(int) ((vertexZ[vertex2] - lz) * 2f), bedFlags);
					cnt += 3;
				}
			}
		}

		return cnt;
	}

	// scene upload
	private int uploadStaticModel(Model model, int orient, int x, int y, int z,
		int objectId, int worldX, int worldY, int plane,
		GpuIntBuffer vb, GpuIntBuffer ab)
	{
		final int vertexCount = model.getVerticesCount();
		final int triangleCount = model.getFaceCount();

		final float[] vertexX = model.getVerticesX();
		final float[] vertexY = model.getVerticesY();
		final float[] vertexZ = model.getVerticesZ();

		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final int[] color1s = model.getFaceColors1();
		final int[] color2s = model.getFaceColors2();
		final int[] color3s = model.getFaceColors3();

		final short[] faceTextures = model.getFaceTextures();
		final HdTextureRegistry.ObjectOverride objectOverride =
			HdTextureRegistry.get().getObject(objectId);
		final int authoredUvFlags = objectOverride != null
			&& objectOverride.getUvMode() == HdTextureRegistry.UvMode.PLANAR
			? ModelUploader.AUTHORED_PLANAR_UV_FLAG : 0;

		final byte[] transparencies = model.getFaceTransparencies();
		final byte[] bias = model.getFaceBias();

		int orientSin = 0;
		int orientCos = 0;
		if (orient != 0)
		{
			orientSin = Perspective.SINE[orient];
			orientCos = Perspective.COSINE[orient];
		}

		for (int v = 0; v < vertexCount; ++v)
		{
			int vx = (int) vertexX[v];
			int vy = (int) vertexY[v];
			int vz = (int) vertexZ[v];

			if (orient != 0)
			{
				int x0 = vx;
				vx = vz * orientSin + x0 * orientCos >> 16;
				vz = vz * orientCos - x0 * orientSin >> 16;
			}

			vx += x;
			vy += y;
			vz += z;

			modelLocalXI[v] = vx;
			modelLocalYI[v] = vy;
			modelLocalZI[v] = vz;
		}

		int len = 0;
		for (int face = 0; face < triangleCount; ++face)
		{
			int color1 = color1s[face];
			int color2 = color2s[face];
			int color3 = color3s[face];

			boolean alpha = (transparencies != null && transparencies[face] != 0);

			if (color3 == -1)
			{
				color2 = color3 = color1;
			}
			else if (color3 == -2)
			{
				continue;
			}

			int triangleA = indices1[face];
			int triangleB = indices2[face];
			int triangleC = indices3[face];

			int vx1 = modelLocalXI[triangleA];
			int vy1 = modelLocalYI[triangleA];
			int vz1 = modelLocalZI[triangleA];

			int vx2 = modelLocalXI[triangleB];
			int vy2 = modelLocalYI[triangleB];
			int vz2 = modelLocalZI[triangleB];

			int vx3 = modelLocalXI[triangleC];
			int vy3 = modelLocalYI[triangleC];
			int vz3 = modelLocalZI[triangleC];

			if (objectOverride != null
				&& objectOverride.getUvMode() == HdTextureRegistry.UvMode.PLANAR)
			{
				ModelUploader.computePlanarFaceUvs(vx1, vy1, vz1, vx2, vy2, vz2,
					vx3, vy3, vz3, objectOverride.getUvScale(), u, v);
			}
			else
			{
				ModelUploader.computeFaceUvs(model, face, u, v);
			}

			int su0 = (int) (u[0] * 256f);
			int sv0 = (int) (v[0] * 256f);

			int su1 = (int) (u[1] * 256f);
			int sv1 = (int) (v[1] * 256f);

			int su2 = (int) (u[2] * 256f);
			int sv2 = (int) (v[2] * 256f);

			int alphaBias = 0;
			alphaBias |= transparencies != null ? (transparencies[face] & 0xff) << 24 : 0;
			alphaBias |= bias != null ? (bias[face] & 0xff) << 16 : 0;
			int textureId = faceTextures != null ? faceTextures[face] : -1;
			SurfaceMaterialRuleCatalog.Match materialMatch =
				SurfaceMaterialClassifier.classifyObjectMatch(
					textureId, objectId, worldX, worldY, plane);
			int texture = objectOverride != null
				? materialMatch.getMaterial().packTextureCode(objectOverride.getLayer() + 1,
					materialMatch.getAuthoredSlot())
				: materialMatch.packTextureCode(textureId + 1);
			GpuIntBuffer buf = alpha ? ab : vb;

			buf.put22224(vx1, vy1, vz1, alphaBias | color1);
			buf.put2222(texture, su0, sv0, authoredUvFlags);

			buf.put22224(vx2, vy2, vz2, alphaBias | color2);
			buf.put2222(texture, su1, sv1, authoredUvFlags);

			buf.put22224(vx3, vy3, vz3, alphaBias | color3);
			buf.put2222(texture, su2, sv2, authoredUvFlags);

			len += 3;
		}

		return len;
	}

	private HdTextureRegistry.Asset exactTerrainAsset(
		int textureId, int terrainLayer, int definitionId)
	{
		if (!config.hdGroundTextures())
		{
			return null;
		}
		HdTextureRegistry registry = HdTextureRegistry.get();
		if (isWaterTexture(textureId)
			|| textureId >= 0 && registry.getVanilla(textureId) != null)
		{
			return null;
		}
		// Scene arrays store definition ID + 1; catalog/filenames preserve that
		// raw observed identity so exports can round-trip without translation.
		int rawId = definitionId + 1;
		return terrainLayer == TERRAIN_LAYER_OVERLAY
			? registry.getOverlay(rawId)
			: registry.getUnderlay(rawId);
	}
}
