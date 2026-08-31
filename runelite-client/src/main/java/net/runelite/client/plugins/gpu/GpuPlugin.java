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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Stopwatch;
import com.google.common.primitives.Ints;
import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.GraphicsConfiguration;
import java.awt.Image;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import net.runelite.client.config.Keybind;
import net.runelite.client.input.KeyManager;
import net.runelite.client.util.HotkeyListener;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.BufferProvider;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.FloatProjection;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.Perspective;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.TextureProvider;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.PostClientTick;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallbackManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.plugins.gpu.config.AntiAliasingMode;
import net.runelite.client.plugins.gpu.config.UIScalingMode;
import net.runelite.client.plugins.gpu.template.Template;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SOURCE_API;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_OTHER;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_PERFORMANCE;
import static org.lwjgl.opengl.GL43C.glDebugMessageControl;
import static org.lwjgl.opengl.GL45C.GL_CLIP_DEPTH_MODE;
import static org.lwjgl.opengl.GL45C.GL_CLIP_ORIGIN;
import static org.lwjgl.opengl.GL45C.GL_NEGATIVE_ONE_TO_ONE;
import static org.lwjgl.opengl.GL45C.GL_ZERO_TO_ONE;
import static org.lwjgl.opengl.GL45C.glClipControl;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLUtil;
import org.lwjgl.system.Callback;
import org.lwjgl.system.Configuration;
import javax.imageio.ImageIO;
import java.io.IOException;
import java.io.InputStream;


@PluginDescriptor(
	name = "GPU",
	description = "Offloads rendering to GPU",
	tags = {"fog", "draw distance"},
	loadInSafeMode = false
)
@Slf4j
public class GpuPlugin extends Plugin implements DrawCallbacks
{
	static final int MAX_DISTANCE = 184;
	static final int MAX_FOG_DEPTH = 100;
	static final int SCENE_OFFSET = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2; // offset for sxy -> msxy
	private static final int UNIFORM_BUFFER_SIZE = 5 * Float.BYTES;
	private static final int NUM_ZONES = Constants.EXTENDED_SCENE_SIZE >> 3;
	private static final int MAX_WORLDVIEWS = 4096;
	private static final int SURFACE_DETAIL_INSTANCE_FLOATS = 6;
	// Ten two-segment grass blades (120 vertices). Scatter details use the first
	// 60 vertices of the same instanced mesh and discard the remainder in GLSL.
	private static final int SURFACE_DETAIL_VERTICES_PER_INSTANCE = 120;
	private static final int MAX_SURFACE_DETAIL_INSTANCES = 32768;
	private static final float CELESTIAL_PEAK_ELEVATION = (float) Math.toRadians(78.0);

	@Inject
	private Client client;

	@Inject
	private ClientUI clientUI;

	@Inject
	private ClientThread clientThread;

	@Inject
	private GpuPluginConfig config;

	@Inject
	private TextureManager textureManager;

	@Inject
	private AuthoredMaterialAtlas authoredMaterialAtlas;

	@Inject
	private RegionManager regionManager;

	@Inject
	private DrawManager drawManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private RenderCallbackManager renderCallbackManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ChunkObjectExporter chunkObjectExporter;

	@Inject
	private KeyManager keyManager;

	@Inject
	private MaterialInspectorOverlay materialInspectorOverlay;

	private boolean materialInspectorOverlayRegistered;

	private Canvas canvas;
	private AWTContext awtContext;
	private Callback debugCallback;

	private boolean lwjglInitted = false;
	private GLCapabilities glCapabilities;

	static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vert.glsl")
		.add(GL_FRAGMENT_SHADER, "frag.glsl");

	static final Shader UI_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vertui.glsl")
		.add(GL_FRAGMENT_SHADER, "fragui.glsl");

	static final Shader SKY_PROGRAM = new Shader()
			.add(GL_VERTEX_SHADER, "sky_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "sky_frag.glsl");

	static final Shader SHADOW_PROGRAM = new Shader()
			.add(GL_VERTEX_SHADER, "shadow_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "shadow_frag.glsl");

	static final Shader SHADOW_DEBUG_PROGRAM = new Shader()
			.add(GL_VERTEX_SHADER, "shadow_debug_vert.glsl")
			.add(GL_FRAGMENT_SHADER, "shadow_debug_frag.glsl");
	static final Shader ATMOSPHERE_SHADOW_FILTER_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "atmosphere_shadow_filter_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "atmosphere_shadow_filter_frag.glsl");
	static final Shader WEATHER_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "weather_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "weather_frag.glsl");
	static final Shader VOLUMETRIC_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "volumetric_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "volumetric_frag.glsl");
	static final Shader VOLUMETRIC_COMPOSITE_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "volumetric_composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "volumetric_composite_frag.glsl");
	static final Shader GRASS_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "grass_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "grass_frag.glsl");
	static final Shader GRASS_GLB_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "grass_glb_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "grass_glb_frag.glsl");
	static final Shader GRASS_GLB_DEBUG_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "grass_glb_debug_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "grass_debug_frag.glsl");
	static final Shader GRASS_ROOT_DEBUG_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "grass_root_debug_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "grass_root_debug_frag.glsl");
	static final Shader GRASS_DEBUG_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "grass_debug_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "grass_debug_frag.glsl");
	static final Shader TREE_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "tree_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "tree_frag.glsl");
	static final Shader TREE_SHADOW_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "tree_shadow_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "tree_shadow_frag.glsl");
	static final Shader WATER_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "water_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "water_frag.glsl");

	static int glProgram;
	private int glUiProgram;

	private int glShadowProgram;
	private int glShadowDebugProgram;
	private int glAtmosphereShadowFilterProgram;

	private int glSkyProgram;
	private int glWeatherProgram;
	private int glVolumetricProgram;
	private int glVolumetricCompositeProgram;
	private int glGrassProgram;
	private int glGrassGlbProgram;
	private int glGrassGlbDebugProgram;
	private int glGrassRootDebugProgram;
	private int glGrassDebugProgram;
	private int vaoGrassGlbHandle;
	private int vboGrassGlbHandle;
	private int eboGrassGlbHandle;
	private int grassGlbIndexCount;
	private float[] grassGlbNormalizedMin;
	private float[] grassGlbNormalizedMax;
	private int uniGrassGlbProjection, uniGrassGlbCamera, uniGrassGlbFocus;
	private int uniGrassGlbWorldOffset, uniGrassGlbTime, uniGrassGlbDrawRadius;
	private int uniGrassGlbHeightScale, uniGrassGlbWindStrength, uniGrassGlbWeatherMode;
	private int uniGrassGlbLightDirection, uniGrassGlbLightIntensity, uniGrassGlbAmbientLight;
	private int uniGrassGlbFogColor, uniGrassGlbNightFactor, uniGrassGlbShadowMap;
	private int uniGrassGlbShadowLightProj, uniGrassGlbShadowsEnabled, uniGrassGlbShadowStrength;
	private int uniGrassGlbMaterialLightingEnabled, uniGrassGlbMaterialLightingStrength;
	private int uniGrassGlbDebugMode;
	private int uniGrassGlbDebugProjection, uniGrassGlbDebugBaseCenter;
	private int uniGrassGlbDebugScale, uniGrassGlbDebugColor;
	private int uniGrassRootDebugProjection, uniGrassRootDebugMarkerSize;
	private TreeReplacementRegistry treeReplacementRegistry;
	private TreeGpuAsset[][] treeLodAssets = new TreeGpuAsset[0][0];
	private final List<TreeGpuAsset> loadedTreeAssets = new java.util.ArrayList<>();
	private int glTreeProgram;
	private int glTreeShadowProgram;
	private int uniTreeProjection, uniTreeBaseColorTexture, uniTreeShadowMap;
	private int uniTreeShadowLightProj, uniTreeBaseColorFactor, uniTreeLightDirection;
	private int uniTreeDirectionalLightingEnabled, uniTreeDirectionalLightingStrength;
	private int uniTreeEnvironmentFillStrength, uniTreeNightFactor, uniTreeFoliageMaterial;
	private int uniTreeCameraPosition, uniTreePlayerPosition;
	private int uniTreeOcclusionMode, uniTreeBubbleRadius, uniTreeSightConeWidth;
	private int uniTreeMaximumFade, uniTreeCameraTopDownFactor;
	private int uniTreeTime, uniTreeWindDirection, uniTreeWindStrength;
	private int uniTreeMaterialWindResponse;
	private int uniTreeShadowStrength, uniTreeAlphaCutoff, uniTreeFoliageTransmission;
	private int uniTreeHasBaseColorTexture, uniTreeShadowsEnabled;
	private int uniTreeShadowProjection, uniTreeShadowBaseColorTexture;
	private int uniTreeShadowBaseColorFactor, uniTreeShadowAlphaCutoff;
	private int uniTreeShadowHasBaseColorTexture;
	private int uniTreeShadowTime, uniTreeShadowWindDirection, uniTreeShadowWindStrength;
	private int uniTreeShadowMaterialWindResponse;
	private int glWaterProgram;
	private int uniWeatherProjection, uniWeatherCamera, uniWeatherTime;
	private int uniWeatherRadius, uniWeatherFallSpeed, uniWeatherWind, uniWeatherStreakLength;
	private int uniWeatherSnow, uniWeatherStorm, uniWeatherSevere, uniWeatherIntensity;
	private int uniVolumetricSceneColor, uniVolumetricSceneDepth, uniVolumetricUvTransform;
	private int uniVolumetricCelestialRayStrength, uniVolumetricMoonProfile;
	private int uniVolumetricWeatherMode, uniVolumetricWorldProjection;
	private int uniVolumetricCamera, uniVolumetricLightDirection;
	private int uniVolumetricRayColor;
	private int uniVolumetricShadowLightProj, uniVolumetricShadowMap;
	private int uniVolumetricShadowMapValid, uniVolumetricZeroToOneDepth;
	private int uniAtmosphereShadowFilterSourceDepth;
	private int uniVolumetricCompositeSceneColor, uniVolumetricCompositeSceneDepth;
	private int uniVolumetricCompositeRays, uniVolumetricCompositeSceneUvTransform;
	private int uniVolumetricCompositeRayUvTransform, uniVolumetricCompositeRayTexelSize;
	private int uniGrassProjection, uniGrassCamera, uniGrassFocus, uniGrassWorldOffset;
	private int uniGrassTime, uniGrassDrawRadius, uniGrassHeightScale, uniGrassWindStrength;
	private int uniGrassWeatherModeVert, uniGrassLightDirection;
	private int uniGrassLightIntensity, uniGrassAmbientLight;
	private int uniGrassLightningFlash, uniGrassWeatherModeFrag, uniGrassNightFactor;
	private int uniGrassBrightness, uniGrassEnhancedColors;
	private int uniGrassSaturation, uniGrassContrast, uniGrassFogColor;
	private int uniGrassShadowMap, uniGrassShadowLightProj;
	private int uniGrassShadowsEnabled, uniGrassShadowStrength;
	private int uniGrassMaterialDebugMode;
	private int uniGrassMaterialLightingEnabled, uniGrassMaterialLightingStrength;
	private int uniGrassWetSurfacesEnabled, uniGrassWetSurfaceStrength;
	private int uniWaterProjection, uniWaterBase;
	private int uniWaterSceneColor, uniWaterSceneDepth, uniWaterSkyTexture;
	private int uniWaterShadowMap, uniWaterWorldProjection, uniWaterShadowLightProj;
	private int uniWaterUvTransform, uniWaterTargetSize, uniWaterCamera;
	private int uniWaterLightDirection, uniWaterFogColor, uniWaterTime;
	private int uniWaterPassStrength, uniWaterPassOpacity, uniWaterDrawDistance;
	private int uniWaterNightFactor, uniWaterLightningFlash, uniWaterWeatherDensity;
	private int uniWaterWeatherMode, uniWaterShadowMapValid;
	private int uniWaterZeroToOneDepth, uniWaterSkyReflectionEnabled;
	private int uniWaterMaterialDebugMode;
	private final float[] weatherProjection = Mat4.identity();
	private float weatherCameraX, weatherCameraY, weatherCameraZ;
	private float atmosphereAnchorX, atmosphereAnchorY, atmosphereAnchorZ;
	private final WeatherAudioController weatherAudio = new WeatherAudioController();
	private long lastThunderCycle = Long.MIN_VALUE;
	private long grassTimeOriginMillis = -1L;
	private long treeTimeOriginMillis = -1L;
	private long treeVisibilityUpdateMillis = -1L;
	private boolean treeVisibilityInitialized;
	private float treeVisibilityCameraX, treeVisibilityCameraY, treeVisibilityCameraZ;
	private float treeVisibilityPlayerX, treeVisibilityPlayerY, treeVisibilityPlayerZ;
	private float treeVisibilityMaximumFade, treeVisibilityTopDownFactor;
	private float treeVisibilityBubbleRadius, treeVisibilitySightConeWidth;
	private static final int TREE_PROFILE_QUERY_COUNT = 16;
	private final int[] treeFoliageSampleQueries = new int[TREE_PROFILE_QUERY_COUNT];
	private boolean treeFoliageQueriesPending;
	private boolean treeFoliageQueryCapture;
	private int treeFoliageQueriesUsed;
	private int treeFoliagePendingQueryCount;
	private long treeProfileFoliageSamples;
	private long treeProfileLastLogMillis;
	private int treeProfileVisibleTrees;
	private long treeProfileTreeTriangles;
	private long treeProfileFoliageTriangles;
	private int treeProfileDrawCalls;
	private int treeProfileFoliageDrawCalls;
	private int treeProfileShadowDrawCalls;
	private int treeProfileGrassInstances;
	private long treeProfileGrassTriangles;
	private int treeProfileGrassDrawCalls;
	private final int[] treeProfileVisibleTreesByLod = new int[4];
	private final long[] treeProfileTrianglesByLod = new long[4];
	private final int[] treeProfileGrassByBand = new int[4];
	private int treeProfileShadowCasters;
	private boolean grassPocStatusLogged;
	private boolean grassDebugDrawLogged;
	private boolean grassGlbBoundsLogged;
	private boolean grassDiagnosticsPending = true;
	private int grassDiagnosticsGeneration;
	private int vaoSkyHandle;
	private int vboSkyHandle;
	private int vaoGrassHandle;
	private int vboGrassInstanceHandle;
	private int vaoGrassDebugHandle;
	private int vboGrassDebugHandle;
	private int uniGrassDebugProjection;
	private int uniGrassDebugColor;
	private int uniGrassDebugBaseCenter;
	private int uniGrassDebugInstanceSpacing;
	private final FloatBuffer grassDebugBuffer =
		GpuFloatBuffer.allocateDirect(576);
	private final FloatBuffer grassInstanceBuffer =
		GpuFloatBuffer.allocateDirect(
			MAX_SURFACE_DETAIL_INSTANCES * SURFACE_DETAIL_INSTANCE_FLOATS);
	private int grassVisibilityFrame = 1;
	private int activeSkyTexture;
	private float currentFogR, currentFogG, currentFogB;
	private int currentDrawDistance;
	private int uniSkyProj;
	private int uniSkyTexture;
	private int uniSkySunDirection;
	private int uniSkyRayColor;
	private int uniSkyRayStrength;
	private int uniSkyNightFactor;
	private int uniSkyMoonDirection;
	private int uniSkyCelestialVisibility;

	private int uniEnhancedColors;
	private int uniSaturation;
	private int uniContrast;

	private int uniShadowLightProj;
	private int uniShadowBase;
	private int uniShadowDebugMap;

	private int uniLightDirection;
	private int uniCameraPosition;
	private int uniCelestialNightFactorMain;
	private int uniEnhancedWater;
	private int uniWaterStrength;
	private int uniWaterOpacity;
	private int uniLightningFlash;
	private int uniWeatherModeMain;
	private int uniWeatherTimeMain;
	private int uniWeatherDensityMain;

	private int interfaceTexture;
	private int interfacePbo;

	private int cosmicSkyTexture;
	private int nightSkyTexture;
	private int daySkyTexture;
	private int sunsetSkyTexture;
	private final int[] rainSkyTextures = new int[3];
	private int snowSkyTexture;
	private final int[] lightningSkyTextures = new int[4];

	private int vaoUiHandle;
	private int vboUiHandle;

	private int fboScene;
	private boolean sceneFboValid;
	private int rboColorBuffer;
	private int rboDepthBuffer;
	private int fboSceneResolved = -1;
	private int sceneColorTexture;
	private int sceneDepthTexture;
	private int fboVolumetric = -1;
	private int volumetricTexture;
	private int volumetricTargetWidth;
	private int volumetricTargetHeight;
	private int sceneTargetWidth;
	private int sceneTargetHeight;
	private final int[] sceneViewport = new int[4];

	// =====================================================
	// Shadow map
	// =====================================================

	private static final int SHADOW_MAP_SIZE = 4096;
	private static final int ATMOSPHERE_SHADOW_MAP_SIZE = 1024;
	private static final int ATMOSPHERE_FILTERED_SHADOW_MAP_SIZE = 512;
	private static final float[] MORNING_SUN = {0.65f, 0.55f, -0.52f};
	private static final float[] NOON_SUN = {0.035f, 1.0f, -0.025f};
	private static final float[] EVENING_SUN = {-0.65f, 0.48f, 0.52f};

	private static final class FrameEnvironment
	{
		private boolean initialized;
		private long timeMillis;
		private SkyMode skyMode = SkyMode.OFF;
		private float nightFactor;
		private final float[] sunDirection = new float[3];
		private final float[] moonDirection = new float[3];
		private final float[] activeLightDirection = new float[3];
		private final float[] activeSceneDirection = new float[3];
	}

	private final FrameEnvironment frameEnvironment = new FrameEnvironment();

	private int shadowFbo;
	private int shadowDepthTexture;
	private int atmosphereShadowFbo;
	private int atmosphereShadowDepthTexture;
	private int atmosphereFilteredShadowFbo;
	private int atmosphereFilteredShadowDepthTexture;
	private boolean surfaceShadowMapValid;
	private boolean atmosphereShadowMapValid;

	private int textureArrayId;

	private final GLBuffer glUniformBuffer = new GLBuffer("uniform buffer");

	private int lastCanvasWidth;
	private int lastCanvasHeight;
	private int lastStretchedCanvasWidth;
	private int lastStretchedCanvasHeight;
	private AntiAliasingMode lastAntiAliasingMode;
	private int lastAnisotropicFilteringLevel = -1;

	private GpuFloatBuffer uniformBuffer;

	private int cameraYaw, cameraPitch;

	static class RenderThread
	{
		VAOList vaoO, vaoA;
		float[] tmp = new float[3];
		ModelUploader modelUploader;
	}

	private RenderThread[] rts;

	private SceneUploader clientUploader, mapUploader;

	static class SceneContext
	{
		final float[] projection = Mat4.identity();

		final int sizeX, sizeZ;
		Zone[][] zones;

		private int cameraX, cameraY, cameraZ;
		private int minLevel, level, maxLevel;
		private Set<Integer> hideRoofIds;

		SceneContext(int sizeX, int sizeZ)
		{
			this.sizeX = sizeX;
			this.sizeZ = sizeZ;
			zones = new Zone[sizeX][sizeZ];
			for (int x = 0; x < sizeX; ++x)
			{
				for (int z = 0; z < sizeZ; ++z)
				{
					zones[x][z] = new Zone();
				}
			}
		}

		void free()
		{
			for (int x = 0; x < sizeX; ++x)
			{
				for (int z = 0; z < sizeZ; ++z)
				{
					zones[x][z].free();
				}
			}
		}
	}

	SceneContext context(Scene scene)
	{
		int wvid = scene.getWorldViewId();
		if (wvid == WorldView.TOPLEVEL)
		{
			return root;
		}
		return subs[wvid];
	}

	SceneContext context(WorldView wv)
	{
		int wvid = wv.getId();
		if (wvid == WorldView.TOPLEVEL)
		{
			return root;
		}
		return subs[wvid];
	}

	private SceneContext root;
	private SceneContext[] subs;
	private Zone[][] nextZones;
	private Map<Integer, Integer> nextRoofChanges;

	// Uniforms
	private int uniShadowMap;
	private int uniShadowLightProjMain;
	private int uniShadowsEnabled;
	private int uniShadowStrength;
	private final float[] currentShadowLightProj = new float[16];
	private final float[] currentAtmosphereLightProj = new float[16];

	private int uniUseFog;
	private int uniFogColor;
	private int uniFogDepth;
	private int uniAuthoredMaterialNormals;
	private int uniDrawDistance;
	private int uniExpandedMapLoadingChunks;
	private int uniSmoothBanding;
	private int uniWorldProj;
	static int uniEntityProj;
	static int uniEntityTint;
	private int uniBrightness;
	private int uniTex;
	private int uniTexSourceDimensions;
	private int uniTexTargetDimensions;
	private int uniUiAlphaOverlay;
	private int uniTextures;
	private int uniTextureAnimations;
	private int uniAuthoredMaterialAlbedos;
	private int uniBlockMain;
	private int uniTextureLightMode;
	private int uniTerrainTextureBlending;
	private int uniTerrainBlendStrength;
	private int uniMaterialDebugMode;
	private int uniMaterialLightingEnabled;
	private int uniMaterialLightingStrength;
	private int uniDirectionalLightingEnabled;
	private int uniDirectionalLightingStrength, uniEnvironmentFillStrength;
	private int uniWetSurfacesEnabled;
	private int uniWetSurfaceStrength;
	private int uniTick;
	private int uniColorblindIntensity;
	private int uniUiColorblindIntensity;
	static int uniBase;

	static final float[] IDENTITY = Mat4.identity();


	private void exportCurrentChunkObjects()
	{
		Path output =
				chunkObjectExporter.exportCurrentChunk();

		log.info(
				"Chunk object dump written to {}",
				output
		);
	}

	private final HotkeyListener exportChunkObjectsHotkey =
			new HotkeyListener(
					() -> config.exportChunkObjectsHotkey()
			)
			{
				@Override
				public void hotkeyPressed()
				{
					clientThread.invokeLater(
							GpuPlugin.this::exportCurrentChunkObjects
					);
				}
			};

	@Override
	protected void startUp()
	{
		keyManager.registerKeyListener(
				exportChunkObjectsHotkey
		);
		root = new SceneContext(NUM_ZONES, NUM_ZONES);
		subs = new SceneContext[MAX_WORLDVIEWS];
		int numThreads = config.numThreads();
		rts = new RenderThread[numThreads + 1];
		for (int i = 0; i < rts.length; ++i)
		{
			var rt = rts[i] = new RenderThread();
			rt.modelUploader = new ModelUploader();
		}
		try
		{
			treeReplacementRegistry = TreeReplacementRegistry.load();
		}
		catch (IOException ex)
		{
			throw new IllegalStateException("Unable to load tree replacement catalog", ex);
		}
		clientUploader = new SceneUploader(renderCallbackManager, config,
			treeReplacementRegistry);
		mapUploader = new SceneUploader(renderCallbackManager, config,
			treeReplacementRegistry);
		syncMaterialInspectorOverlay();
		clientThread.invoke(() ->
		{
			try
			{
				fboScene = -1;
				lastAnisotropicFilteringLevel = -1;

				AWTContext.loadNatives();

				canvas = client.getCanvas();

				synchronized (canvas.getTreeLock())
				{
					if (!canvas.isValid())
					{
						return false;
					}

					awtContext = new AWTContext(canvas);
					awtContext.configurePixelFormat(0, 0, 0);
				}

				awtContext.createGLContext();

				canvas.setIgnoreRepaint(true);

				// lwjgl defaults to lwjgl- + user.name, but this breaks if the username would cause an invalid path
				// to be created.
				Configuration.SHARED_LIBRARY_EXTRACT_DIRECTORY.set("lwjgl-rl");

				glCapabilities = GL.createCapabilities();

				log.info("Using device: {}", glGetString(GL_RENDERER));
				log.info("Using driver: {}", glGetString(GL_VERSION));

				if (!glCapabilities.OpenGL33)
				{
					throw new RuntimeException("OpenGL 3.3 is required but not available");
				}

				lwjglInitted = true;

				checkGLErrors();
				if (log.isDebugEnabled() && glCapabilities.glDebugMessageControl != 0)
				{
					debugCallback = GLUtil.setupDebugMessageCallback();
					if (debugCallback != null)
					{
						// [LWJGL] OpenGL debug message
						//	ID: 0x20071
						//	Source: API
						//	Type: OTHER
						//	Severity: NOTIFICATION
						//	Message: Buffer detailed info: Buffer object 2 (bound to GL_PIXEL_UNPACK_BUFFER_ARB, usage hint is GL_STREAM_DRAW) has been mapped WRITE_ONLY in SYSTEM HEAP memory (fast).
						glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_OTHER,
							GL_DONT_CARE, 0x20071, false);

						// [LWJGL] OpenGL debug message
						//	ID: 0x20052
						//	Source: API
						//	Type: PERFORMANCE
						//	Severity: MEDIUM
						//	Message: Pixel-path performance warning: Pixel transfer is synchronized with 3D rendering.
						glDebugMessageControl(GL_DEBUG_SOURCE_API, GL_DEBUG_TYPE_PERFORMANCE,
							GL_DONT_CARE, 0x20052, false);
					}
				}

				setupSyncMode();

				initBuffers();
				initVao();
				initSkyVao();
				initGrassVao();
				initGrassGlbVao();
				initGrassDebugVao();
				initTreeAssets();
				initProgram();
				authoredMaterialAtlas.initialize();
				initInterfaceTexture();
				initSkyTextures();
				initShadowMap();
				if (glCapabilities.OpenGL45)
				{
					glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE); // 1 near 0 far
				}

				client.setDrawCallbacks(this);
				setupGpuFlags();
				client.setExpandedMapLoading(config.expandedMapLoadingZones());

				// force rebuild of main buffer provider to enable alpha channel
				client.resizeCanvas();

				lastCanvasWidth = lastCanvasHeight = -1;
				lastStretchedCanvasWidth = lastStretchedCanvasHeight = -1;
				lastAntiAliasingMode = null;

				textureArrayId = -1;

				if (client.getGameState() == GameState.LOGGED_IN)
				{
					startupWorldLoad();
				}

				checkGLErrors();
			}
			catch (Throwable e)
			{
				log.error("Error starting GPU plugin", e);

				SwingUtilities.invokeLater(() ->
				{
					try
					{
						pluginManager.setPluginEnabled(this, false);
						pluginManager.stopPlugin(this);
					}
					catch (PluginInstantiationException ex)
					{
						log.error("error stopping plugin", ex);
					}
				});

				shutDown();
			}
			return true;
		});
	}

	private void setupGpuFlags()
	{
		int cpus = Runtime.getRuntime().availableProcessors();
		int threads = Math.min(cpus - 1, config.numThreads());
		log.debug("Using {} render threads", threads);
		client.setGpuFlags(DrawCallbacks.GPU
			| (config.removeVertexSnapping() ? DrawCallbacks.NO_VERTEX_SNAPPING : 0)
			| DrawCallbacks.ZBUF
			| DrawCallbacks.RENDER_THREADS(threads)
		);
	}

	private void shutdownSkyVao()
	{
		if (vboSkyHandle != 0)
		{
			glDeleteBuffers(vboSkyHandle);
			vboSkyHandle = 0;
		}

		if (vaoSkyHandle != 0)
		{
			glDeleteVertexArrays(vaoSkyHandle);
			vaoSkyHandle = 0;
		}
	}

	private void startupWorldLoad()
	{
		WorldView root = client.getTopLevelWorldView();
		Scene scene = root.getScene();
		loadScene(root, scene);
		swapScene(scene);

		for (WorldEntity subEntity : root.worldEntities())
		{
			WorldView sub = subEntity.getWorldView();
			log.debug("WorldView loading: {}", sub.getId());
			loadSubScene(sub, sub.getScene());
			swapSub(sub.getScene());
		}
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(
				exportChunkObjectsHotkey
		);
		removeMaterialInspectorOverlay();
		weatherAudio.shutdown();
		clientThread.invoke(() ->
		{
			client.setGpuFlags(0);
			client.setDrawCallbacks(null);
			client.setUnlockedFps(false);
			client.setExpandedMapLoading(0);

			if (lwjglInitted)
			{
				if (textureArrayId != -1)
				{
					textureManager.freeTextureArray(textureArrayId);
					textureArrayId = -1;
				}

				root.free();

				shutdownSkyTextures();
				shutdownGrassVao();
				shutdownGrassDebugVao();
				shutdownTreeAssets();
				shutdownSkyVao();
				shutdownInterfaceTexture();
				authoredMaterialAtlas.shutdown();
				shutdownProgram();
				shutdownVao();
				shutdownBuffers();
				shutdownShadowMap();
				shutdownFbo();
			}

			if (awtContext != null)
			{
				awtContext.destroy();
				awtContext = null;
			}

			if (debugCallback != null)
			{
				debugCallback.free();
				debugCallback = null;
			}

			glCapabilities = null;

			// force main buffer provider rebuild to turn off alpha channel
			client.resizeCanvas();
		});
	}

	@Provides
	GpuPluginConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(GpuPluginConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged configChanged)
	{
		if (configChanged.getGroup().equals(GpuPluginConfig.GROUP))
		{
			if (configChanged.getKey().equals("grassDebugMode"))
			{
				grassDiagnosticsPending = true;
				grassDebugDrawLogged = false;
			}
			if (configChanged.getKey().equals("materialInspector"))
			{
				syncMaterialInspectorOverlay();
			}
			else if (configChanged.getKey().equals("unlockFps")
				|| configChanged.getKey().equals("vsyncMode")
				|| configChanged.getKey().equals("fpsTarget"))
			{
				log.debug("Rebuilding sync mode");
				clientThread.invokeLater(this::setupSyncMode);
			}
			else if (configChanged.getKey().equals("expandedMapLoadingChunks"))
			{
				clientThread.invokeLater(() ->
				{
					client.setExpandedMapLoading(config.expandedMapLoadingZones());
					if (client.getGameState() == GameState.LOGGED_IN)
					{
						client.setGameState(GameState.LOADING);
					}
				});
			}
			else if (configChanged.getKey().equals("removeVertexSnapping"))
			{
				log.debug("Toggle {}", configChanged.getKey());
				setupGpuFlags();
			}
			else if (configChanged.getKey().equals("hdGroundTextures")
				|| configChanged.getKey().equals("terrainTextureBlending")
				|| configChanged.getKey().equals("terrainBlendStrength"))
			{
				// Shared terrain-corner colors are baked into zone VBOs. Force a
				// normal scene rebuild so the CPU and shader portions stay in sync.
				clientThread.invokeLater(() ->
				{
					if (client.getGameState() == GameState.LOGGED_IN)
					{
						client.setGameState(GameState.LOADING);
					}
				});
			}
			else if (configChanged.getKey().equals("uiScalingMode") || configChanged.getKey().equals("colorBlindMode"))
			{
				clientThread.invokeLater(() ->
				{
					log.debug("Recompiling shaders");
					shutdownProgram();
					initProgram();
				});
			}
			else if (configChanged.getKey().equals("numThreads"))
			{
				clientThread.invokeLater(() ->
				{
					for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
					{
						rts[i].vaoO.free();
						rts[i].vaoA.free();
					}

					int numThreads = config.numThreads();
					rts = new RenderThread[numThreads + 1];
					for (int i = 0; i < rts.length; ++i)
					{
						var rt = new RenderThread();
						rt.modelUploader = new ModelUploader();
						rt.vaoO = new VAOList(i > 0);
						rt.vaoA = new VAOList(i > 0);
						rts[i] = rt;
					}

					setupGpuFlags();
				});
			}
		}
	}

	private void setupSyncMode()
	{
		final boolean unlockFps = config.unlockFps();
		client.setUnlockedFps(unlockFps);

		// Without unlocked fps, the client manages sync on its 20ms timer
		GpuPluginConfig.SyncMode syncMode = unlockFps
			? this.config.syncMode()
			: GpuPluginConfig.SyncMode.OFF;

		int swapInterval = 0;
		switch (syncMode)
		{
			case ON:
				swapInterval = 1;
				break;
			case OFF:
				swapInterval = 0;
				break;
			case ADAPTIVE:
				swapInterval = -1;
				break;
		}

		int actualSwapInterval = awtContext.setSwapInterval(swapInterval);
		if (actualSwapInterval != swapInterval)
		{
			log.info("unsupported swap interval {}, got {}", swapInterval, actualSwapInterval);
		}

		client.setUnlockedFpsTarget(actualSwapInterval == 0 ? config.fpsTarget() : 0);
		checkGLErrors();
	}

	private Template createTemplate()
	{
		Template template = new Template();
		template.add(key ->
		{
			switch (key)
			{
				case "texture_config":
					return "#define TEXTURE_COUNT " + TextureManager.TEXTURE_COUNT + "\n";
				case "sampling_mode":
					return "#define SAMPLING_MODE " + config.uiScalingMode().ordinal() + "\n";
				case "colorblind_mode":
					return "#define COLORBLIND_MODE " + config.colorBlindMode().ordinal() + "\n";
			}
			return null;
		});
		template.addInclude(GpuPlugin.class);
		return template;
	}

	private void initProgram() throws ShaderException
	{
		// macOS core profile has no default VAO,
		// so shaders won't validate unless a VAO is bound.
		glBindVertexArray(vaoUiHandle);

		Template template = createTemplate();

		glProgram =
				PROGRAM.compile(template);

		glUiProgram =
				UI_PROGRAM.compile(template);

		glSkyProgram =
				SKY_PROGRAM.compile(template);

		glShadowProgram =
				SHADOW_PROGRAM.compile(template);

		glShadowDebugProgram =
				SHADOW_DEBUG_PROGRAM.compile(template);
		glAtmosphereShadowFilterProgram =
			ATMOSPHERE_SHADOW_FILTER_PROGRAM.compile(template);
		glWeatherProgram = WEATHER_PROGRAM.compile(template);
		glVolumetricProgram = VOLUMETRIC_PROGRAM.compile(template);
		glVolumetricCompositeProgram = VOLUMETRIC_COMPOSITE_PROGRAM.compile(template);
		glGrassProgram = GRASS_PROGRAM.compile(template);
		glGrassGlbProgram = GRASS_GLB_PROGRAM.compile(template);
		glGrassGlbDebugProgram = GRASS_GLB_DEBUG_PROGRAM.compile(template);
		glGrassRootDebugProgram = GRASS_ROOT_DEBUG_PROGRAM.compile(template);
		glGrassDebugProgram = GRASS_DEBUG_PROGRAM.compile(template);
		glTreeProgram = TREE_PROGRAM.compile(template);
		glTreeShadowProgram = TREE_SHADOW_PROGRAM.compile(template);
		glWaterProgram = WATER_PROGRAM.compile(template);

		glBindVertexArray(0);

		initUniforms();

		log.info("Shadow shader compiled successfully");
		log.info("Shadow debug shader compiled successfully");
	}

	private static float[] makeOrthographic(
			float left,
			float right,
			float bottom,
			float top,
			float near,
			float far)
	{
		float[] m = new float[16];

		m[0] = 2.0f / (right - left);
		m[5] = 2.0f / (top - bottom);
		m[10] = -2.0f / (far - near);

		m[12] = -(right + left) / (right - left);
		m[13] = -(top + bottom) / (top - bottom);
		m[14] = -(far + near) / (far - near);

		m[15] = 1.0f;

		return m;
	}

	@VisibleForTesting
	static int halfResolutionViewportOrigin(int coordinate)
	{
		return Math.floorDiv(coordinate, 2);
	}

	@VisibleForTesting
	static int halfResolutionViewportExtent(int coordinate, int extent)
	{
		int halfOrigin = halfResolutionViewportOrigin(coordinate);
		int halfEnd = Math.floorDiv(coordinate + extent + 1, 2);
		return Math.max(1, halfEnd - halfOrigin);
	}

	@VisibleForTesting
	static float[] makeLightViewRotation(float lightX, float lightY, float lightZ)
	{
		float lightLength = (float) Math.sqrt(
				lightX * lightX + lightY * lightY + lightZ * lightZ);
		if (lightLength < 1e-6f)
		{
			return Mat4.identity();
		}
		float forwardX = -lightX / lightLength;
		float forwardY = -lightY / lightLength;
		float forwardZ = -lightZ / lightLength;

		// Build an orthonormal light-camera basis using world up.
		float rightX = -forwardZ;
		float rightY = 0.0f;
		float rightZ = forwardX;
		float rightLength = (float) Math.sqrt(rightX * rightX + rightZ * rightZ);
		if (rightLength < 1e-4f)
		{
			rightX = 1.0f;
			rightZ = 0.0f;
		}
		else
		{
			rightX /= rightLength;
			rightZ /= rightLength;
		}

		float upX = -rightZ * forwardY;
		// up = right x forward. The old reversed Y term sheared light space,
		// which made surface illumination disagree with otherwise-correct shadows.
		float upY = rightZ * forwardX - rightX * forwardZ;
		float upZ = rightX * forwardY;

		return new float[]
		{
			rightX, upX, -forwardX, 0.0f,
			rightY, upY, -forwardY, 0.0f,
			rightZ, upZ, -forwardZ, 0.0f,
			0.0f, 0.0f, 0.0f, 1.0f
		};
	}

	private boolean renderLightDepthMap(
		Scene scene,
		float cameraX,
		float cameraY,
		float cameraZ,
		int framebuffer,
		int mapSize,
		float[] lightProjectionTarget,
		boolean atmosphereCasters)
	{
		float[] sceneLight = getActiveSceneLightDirection();
		SceneContext ctx = context(scene);

		if (ctx == null)
		{
			return false;
		}

		// =====================================================
		// Save current OpenGL state that we modify
		// =====================================================

		int[] previousViewport = new int[4];

		glGetIntegerv(
				GL_VIEWPORT,
				previousViewport
		);
		int previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		int previousReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
		int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
		int previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
		int previousDepthFunc = glGetInteger(GL_DEPTH_FUNC);
		boolean previousDepthTest = glIsEnabled(GL_DEPTH_TEST);
		boolean previousDepthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
		boolean previousCullFace = atmosphereCasters && glIsEnabled(GL_CULL_FACE);
		double previousClearDepth = glGetDouble(GL_DEPTH_CLEAR_VALUE);
		int previousClipOrigin = glCapabilities.OpenGL45
			? glGetInteger(GL_CLIP_ORIGIN) : GL_LOWER_LEFT;
		int previousClipDepthMode = glCapabilities.OpenGL45
			? glGetInteger(GL_CLIP_DEPTH_MODE) : GL_NEGATIVE_ONE_TO_ONE;

		// =====================================================
		// Bind shadow framebuffer
		// =====================================================

		glBindFramebuffer(
				GL_FRAMEBUFFER,
				framebuffer
		);

		// The shadow matrices use conventional OpenGL [-1, 1] clip depth.
		// RuneLite's OpenGL 4.5 path otherwise keeps [0, 1] clip depth active.
		if (glCapabilities.OpenGL45)
		{
			glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE);
		}

		glViewport(
				0,
				0,
				mapSize,
				mapSize
		);

		/*
		 * Shadow map uses normal depth:
		 *
		 * near = 0
		 * far  = 1
		 */
		glDepthMask(true);
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_LESS);
		if (atmosphereCasters)
		{
			// Macro blockers need both sides of thin roofs and walls. Restrict the
			// culling change to this pass; the surface-shadow pass stays untouched.
			glDisable(GL_CULL_FACE);
		}

		glClearDepth(1.0);
		glClear(GL_DEPTH_BUFFER_BIT);

		glUseProgram(
				glShadowProgram
		);

		// =====================================================
		// Build an angled light camera matching the visible sun.
		// =====================================================

		/*
		 * 80 tiles in every direction around the center.
		 *
		 * Total coverage = ~160 x 160 tiles.
		 */
		float shadowRadius =
				58.0f * Perspective.LOCAL_TILE_SIZE;
		float shadowTexelSize = 2.0f * shadowRadius / mapSize;
		float[] lightViewRotation =
			makeLightViewRotation(sceneLight[0], sceneLight[1], sceneLight[2]);
		float shadowCenterX;
		float shadowCenterY;
		float shadowCenterZ;
		if (atmosphereCasters)
		{
			// The atmosphere pass receives the camera focal point, which is stable
			// while the camera orbits. Snap only the light-space right/up axes so
			// rotating or zooming cannot slide blocker texels across the world.
			float centerRight = lightViewRotation[0] * cameraX
				+ lightViewRotation[4] * cameraY
				+ lightViewRotation[8] * cameraZ;
			float centerUp = lightViewRotation[1] * cameraX
				+ lightViewRotation[5] * cameraY
				+ lightViewRotation[9] * cameraZ;
			float rightDelta = Math.round(centerRight / shadowTexelSize)
				* shadowTexelSize - centerRight;
			float upDelta = Math.round(centerUp / shadowTexelSize)
				* shadowTexelSize - centerUp;

			shadowCenterX = cameraX
				+ lightViewRotation[0] * rightDelta
				+ lightViewRotation[1] * upDelta;
			shadowCenterY = cameraY
				+ lightViewRotation[4] * rightDelta
				+ lightViewRotation[5] * upDelta;
			shadowCenterZ = cameraZ
				+ lightViewRotation[8] * rightDelta
				+ lightViewRotation[9] * upDelta;
		}
		else
		{
			// Preserve the established surface-shadow anchoring exactly.
			shadowCenterX = Math.round(cameraX / shadowTexelSize) * shadowTexelSize;
			shadowCenterY = Math.round(cameraY / shadowTexelSize) * shadowTexelSize;
			shadowCenterZ = Math.round(cameraZ / shadowTexelSize) * shadowTexelSize;
		}

		/*
		 * Start with orthographic projection.
		 *
		 * Directional lights use orthographic projection
		 * because sunlight is effectively parallel rather
		 * than perspective.
		 */
		float[] lightProjection =
				makeOrthographic(
						-shadowRadius,
						shadowRadius,
						-shadowRadius,
						shadowRadius,
						-20000.0f,
						20000.0f
				);

		// =====================================================
		// IMPORTANT:
		//
		// Projection
		//     × rotation
		//     × translation
		//
		// Same general ordering RuneLite uses for its camera.
		// =====================================================

		Mat4.mul(
				lightProjection,
				lightViewRotation
		);

		/*
		 * THEN center the light camera around the current
		 * gameplay area.
		 *
		 * Previously we translated BEFORE rotating, which
		 * rotated the translation as well and pushed the
		 * useful scene into a strange section of the map.
		 */
		Mat4.mul(
				lightProjection,
				Mat4.translate(
						-shadowCenterX,
						-shadowCenterY,
						-shadowCenterZ
				)
		);

		System.arraycopy(
				lightProjection,
				0,
				lightProjectionTarget,
				0,
				16
		);

		// Send light-space matrix to shadow shader.
		glUniformMatrix4fv(
				uniShadowLightProj,
				false,
				lightProjection
		);

		// =====================================================
		// Render static opaque RuneLite zones
		// =====================================================

		int offset =
				scene.getWorldViewId() == WorldView.TOPLEVEL
						? (SCENE_OFFSET >> 3)
						: 0;
		boolean deferWater = advancedWaterEnabled(scene);

		for (int zx = 0; zx < ctx.sizeX; ++zx)
		{
			for (int zz = 0; zz < ctx.sizeZ; ++zz)
			{
				Zone zone =
						ctx.zones[zx][zz];

				if (!zone.initialized)
				{
					continue;
				}

				if (atmosphereCasters)
				{
					zone.renderAtmosphereShadow(
						zx - offset,
						zz - offset,
						ctx.minLevel,
						ctx.level,
						ctx.maxLevel,
						ctx.hideRoofIds,
						uniShadowBase,
						deferWater
					);
				}
				else
				{
					zone.renderSurfaceShadow(
						zx - offset,
						zz - offset,
						ctx.minLevel,
						ctx.level,
						ctx.maxLevel,
						ctx.hideRoofIds,
						uniShadowBase,
						deferWater
					);
				}
			}
		}
		drawTreeShadows(scene, lightProjection);

		// =====================================================
		// Restore normal RuneLite render state
		// =====================================================
		if (atmosphereCasters)
		{
			if (previousCullFace)
			{
				glEnable(GL_CULL_FACE);
			}
			else
			{
				glDisable(GL_CULL_FACE);
			}
		}

		glBindVertexArray(previousVao);

		/*
		 * Go back to RuneLite's normal scene framebuffer.
		 */
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer);

		glUseProgram(previousProgram);

		/*
		 * RuneLite's normal renderer uses reversed depth.
		 */
		glDepthFunc(previousDepthFunc);
		glDepthMask(previousDepthMask);
		if (previousDepthTest)
		{
			glEnable(GL_DEPTH_TEST);
		}
		else
		{
			glDisable(GL_DEPTH_TEST);
		}

		glClearDepth(previousClearDepth);

		if (glCapabilities.OpenGL45)
		{
			glClipControl(previousClipOrigin, previousClipDepthMode);
		}

		/*
		 * Restore EXACT previous viewport.
		 */
		glViewport(
				previousViewport[0],
				previousViewport[1],
				previousViewport[2],
				previousViewport[3]
		);

		checkGLErrors();
		return true;
	}

	private boolean filterAtmosphereShadowMap()
	{
		if (glAtmosphereShadowFilterProgram == 0
			|| atmosphereShadowDepthTexture == 0
			|| atmosphereFilteredShadowFbo == 0
			|| atmosphereFilteredShadowDepthTexture == 0)
		{
			return false;
		}

		int[] previousViewport = new int[4];
		glGetIntegerv(GL_VIEWPORT, previousViewport);
		int previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		int previousReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
		int previousProgram = glGetInteger(GL_CURRENT_PROGRAM);
		int previousVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
		int previousDepthFunc = glGetInteger(GL_DEPTH_FUNC);
		boolean previousDepthTest = glIsEnabled(GL_DEPTH_TEST);
		boolean previousDepthMask = glGetBoolean(GL_DEPTH_WRITEMASK);
		boolean previousBlend = glIsEnabled(GL_BLEND);
		boolean previousCullFace = glIsEnabled(GL_CULL_FACE);
		boolean previousScissorTest = glIsEnabled(GL_SCISSOR_TEST);
		double previousClearDepth = glGetDouble(GL_DEPTH_CLEAR_VALUE);
		int previousActiveTexture = glGetInteger(GL_ACTIVE_TEXTURE);
		glActiveTexture(GL_TEXTURE0);
		int previousTexture0 = glGetInteger(GL_TEXTURE_BINDING_2D);

		glBindFramebuffer(GL_FRAMEBUFFER, atmosphereFilteredShadowFbo);
		glViewport(
			0,
			0,
			ATMOSPHERE_FILTERED_SHADOW_MAP_SIZE,
			ATMOSPHERE_FILTERED_SHADOW_MAP_SIZE);

		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);
		glDisable(GL_SCISSOR_TEST);
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_ALWAYS);
		glDepthMask(true);
		glClearDepth(1.0);
		glClear(GL_DEPTH_BUFFER_BIT);

		glUseProgram(glAtmosphereShadowFilterProgram);
		glBindVertexArray(vaoUiHandle);
		glBindTexture(GL_TEXTURE_2D, atmosphereShadowDepthTexture);
		glUniform1i(uniAtmosphereShadowFilterSourceDepth, 0);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		// Restore the exact GL state present after the raw blocker pass. This
		// filter runs in the middle of RuneLite's scene setup, so leaking even an
		// active texture or depth mode here can corrupt the main reversed-depth
		// render later in the same frame.
		glBindTexture(GL_TEXTURE_2D, previousTexture0);
		glActiveTexture(previousActiveTexture);
		glBindVertexArray(previousVao);
		glUseProgram(previousProgram);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer);
		glViewport(
			previousViewport[0],
			previousViewport[1],
			previousViewport[2],
			previousViewport[3]);
		glDepthFunc(previousDepthFunc);
		glDepthMask(previousDepthMask);
		if (previousDepthTest)
		{
			glEnable(GL_DEPTH_TEST);
		}
		else
		{
			glDisable(GL_DEPTH_TEST);
		}
		if (previousBlend)
		{
			glEnable(GL_BLEND);
		}
		else
		{
			glDisable(GL_BLEND);
		}
		if (previousCullFace)
		{
			glEnable(GL_CULL_FACE);
		}
		else
		{
			glDisable(GL_CULL_FACE);
		}
		if (previousScissorTest)
		{
			glEnable(GL_SCISSOR_TEST);
		}
		else
		{
			glDisable(GL_SCISSOR_TEST);
		}
		glClearDepth(previousClearDepth);

		checkGLErrors();
		return true;
	}

	private void initUniforms()
	{
		uniShadowMap = glGetUniformLocation(glProgram, "shadowMap");
		uniAuthoredMaterialAlbedos = glGetUniformLocation(glProgram, "authoredMaterialAlbedos");
		uniShadowLightProjMain = glGetUniformLocation(glProgram, "shadowLightProj");
		uniShadowsEnabled = glGetUniformLocation(glProgram, "shadowsEnabled");
		uniShadowStrength = glGetUniformLocation(glProgram, "shadowStrength");
		uniSkyTexture = glGetUniformLocation(glSkyProgram, "skyTexture");
		uniSkyProj = glGetUniformLocation(glSkyProgram, "skyProj");
		uniSkySunDirection = glGetUniformLocation(glSkyProgram, "sunDirection");
		uniSkyRayColor = glGetUniformLocation(glSkyProgram, "rayColor");
		uniSkyRayStrength = glGetUniformLocation(glSkyProgram, "rayStrength");
		uniSkyNightFactor = glGetUniformLocation(glSkyProgram, "nightFactor");
		uniSkyMoonDirection = glGetUniformLocation(glSkyProgram, "moonDirection");
		uniSkyCelestialVisibility = glGetUniformLocation(glSkyProgram, "celestialVisibility");
		uniWorldProj = glGetUniformLocation(glProgram, "worldProj");
		uniEntityProj = glGetUniformLocation(glProgram, "entityProj");
		uniEntityTint = glGetUniformLocation(glProgram, "entityTint");
		uniEnhancedColors = glGetUniformLocation(glProgram, "enhancedColors");
		uniShadowLightProj = glGetUniformLocation(glShadowProgram, "lightProj");
		uniShadowBase = glGetUniformLocation(glShadowProgram, "base");
		uniSaturation = glGetUniformLocation(glProgram, "saturation");
		uniContrast = glGetUniformLocation(glProgram, "contrast");
		uniLightDirection = glGetUniformLocation(glProgram, "lightDirection");
		uniCameraPosition = glGetUniformLocation(glProgram, "cameraPosition");
		uniCelestialNightFactorMain = glGetUniformLocation(
			glProgram, "celestialNightFactor");
		uniEnhancedWater = glGetUniformLocation(glProgram, "enhancedWater");
		uniWaterStrength = glGetUniformLocation(glProgram, "waterStrength");
		uniWaterOpacity = glGetUniformLocation(glProgram, "waterOpacity");
		uniLightningFlash = glGetUniformLocation(glProgram, "lightningFlash");
		uniWeatherModeMain = glGetUniformLocation(glProgram, "weatherMode");
		uniWeatherTimeMain = glGetUniformLocation(glProgram, "weatherTime");
		uniWeatherDensityMain = glGetUniformLocation(glProgram, "weatherDensity");
		uniSmoothBanding = glGetUniformLocation(glProgram, "smoothBanding");
		uniBrightness = glGetUniformLocation(glProgram, "brightness");
		uniUseFog = glGetUniformLocation(glProgram, "useFog");
		uniFogColor = glGetUniformLocation(glProgram, "fogColor");
		uniFogDepth = glGetUniformLocation(glProgram, "fogDepth");
		uniDrawDistance = glGetUniformLocation(glProgram, "drawDistance");
		uniExpandedMapLoadingChunks = glGetUniformLocation(glProgram, "expandedMapLoadingChunks");
		uniTextureLightMode = glGetUniformLocation(glProgram, "textureLightMode");
		uniTerrainTextureBlending = glGetUniformLocation(glProgram, "terrainTextureBlending");
		uniTerrainBlendStrength = glGetUniformLocation(glProgram, "terrainBlendStrength");
		uniMaterialDebugMode = glGetUniformLocation(glProgram, "materialDebugMode");
		uniMaterialLightingEnabled = glGetUniformLocation(glProgram, "materialLightingEnabled");
		uniMaterialLightingStrength = glGetUniformLocation(glProgram, "materialLightingStrength");
		uniDirectionalLightingEnabled = glGetUniformLocation(
			glProgram, "directionalLightingEnabled");
		uniDirectionalLightingStrength = glGetUniformLocation(
			glProgram, "directionalLightingStrength");
		uniEnvironmentFillStrength = glGetUniformLocation(
			glProgram, "environmentFillStrength");
		uniWetSurfacesEnabled = glGetUniformLocation(glProgram, "wetSurfacesEnabled");
		uniWetSurfaceStrength = glGetUniformLocation(glProgram, "wetSurfaceStrength");
		uniTick = glGetUniformLocation(glProgram, "tick");
		uniBlockMain = glGetUniformBlockIndex(glProgram, "uniforms");
		uniTextures = glGetUniformLocation(glProgram, "textures");
		uniAuthoredMaterialNormals = glGetUniformLocation(glProgram, "authoredMaterialNormals");
		uniTextureAnimations = glGetUniformLocation(glProgram, "textureAnimations");
		uniBase = glGetUniformLocation(glProgram, "base");
		uniColorblindIntensity = glGetUniformLocation(glProgram, "colorblindIntensity");

		uniTex = glGetUniformLocation(glUiProgram, "tex");
		uniTexTargetDimensions = glGetUniformLocation(glUiProgram, "targetDimensions");
		uniTexSourceDimensions = glGetUniformLocation(glUiProgram, "sourceDimensions");
		uniUiAlphaOverlay = glGetUniformLocation(glUiProgram, "alphaOverlay");
		uniUiColorblindIntensity = glGetUniformLocation(glUiProgram, "colorblindIntensity");
		uniShadowDebugMap = glGetUniformLocation(glShadowDebugProgram, "shadowMap");
		uniAtmosphereShadowFilterSourceDepth = glGetUniformLocation(
			glAtmosphereShadowFilterProgram, "sourceDepth");
		uniWeatherProjection = glGetUniformLocation(glWeatherProgram, "projection");
		uniWeatherCamera = glGetUniformLocation(glWeatherProgram, "cameraPosition");
		uniWeatherTime = glGetUniformLocation(glWeatherProgram, "time");
		uniWeatherRadius = glGetUniformLocation(glWeatherProgram, "radius");
		uniWeatherFallSpeed = glGetUniformLocation(glWeatherProgram, "fallSpeed");
		uniWeatherWind = glGetUniformLocation(glWeatherProgram, "wind");
		uniWeatherStreakLength = glGetUniformLocation(glWeatherProgram, "streakLength");
		uniWeatherSnow = glGetUniformLocation(glWeatherProgram, "snow");
		uniWeatherStorm = glGetUniformLocation(glWeatherProgram, "storm");
		uniWeatherSevere = glGetUniformLocation(glWeatherProgram, "severe");
		uniWeatherIntensity = glGetUniformLocation(glWeatherProgram, "intensity");
		uniVolumetricSceneColor = glGetUniformLocation(glVolumetricProgram, "sceneColor");
		uniVolumetricSceneDepth = glGetUniformLocation(glVolumetricProgram, "sceneDepth");
		uniVolumetricUvTransform = glGetUniformLocation(glVolumetricProgram, "sceneUvTransform");
		uniVolumetricCelestialRayStrength = glGetUniformLocation(
			glVolumetricProgram, "celestialRayStrength");
		uniVolumetricMoonProfile = glGetUniformLocation(glVolumetricProgram, "moonProfile");
		uniVolumetricWeatherMode = glGetUniformLocation(glVolumetricProgram, "weatherMode");
		uniVolumetricWorldProjection = glGetUniformLocation(glVolumetricProgram, "worldProjection");
		uniVolumetricCamera = glGetUniformLocation(glVolumetricProgram, "cameraPosition");
		uniVolumetricLightDirection = glGetUniformLocation(glVolumetricProgram, "lightDirection");
		uniVolumetricRayColor = glGetUniformLocation(glVolumetricProgram, "rayColor");
		uniVolumetricShadowLightProj = glGetUniformLocation(glVolumetricProgram, "shadowLightProj");
		uniVolumetricShadowMap = glGetUniformLocation(glVolumetricProgram, "shadowMap");
		uniVolumetricShadowMapValid = glGetUniformLocation(glVolumetricProgram, "shadowMapValid");
		uniVolumetricZeroToOneDepth = glGetUniformLocation(glVolumetricProgram, "zeroToOneDepth");
		uniVolumetricCompositeSceneColor = glGetUniformLocation(
			glVolumetricCompositeProgram, "sceneColor");
		uniVolumetricCompositeSceneDepth = glGetUniformLocation(
			glVolumetricCompositeProgram, "sceneDepth");
		uniVolumetricCompositeRays = glGetUniformLocation(
			glVolumetricCompositeProgram, "rayTexture");
		uniVolumetricCompositeSceneUvTransform = glGetUniformLocation(
			glVolumetricCompositeProgram, "sceneUvTransform");
		uniVolumetricCompositeRayUvTransform = glGetUniformLocation(
			glVolumetricCompositeProgram, "rayUvTransform");
		uniVolumetricCompositeRayTexelSize = glGetUniformLocation(
			glVolumetricCompositeProgram, "rayTexelSize");
		uniGrassProjection = glGetUniformLocation(glGrassProgram, "projection");
		uniGrassDebugProjection = glGetUniformLocation(glGrassDebugProgram, "projection");
		uniGrassDebugColor = glGetUniformLocation(glGrassDebugProgram, "debugColor");
		uniGrassDebugBaseCenter = glGetUniformLocation(glGrassDebugProgram, "baseCenter");
		uniGrassDebugInstanceSpacing = glGetUniformLocation(
			glGrassDebugProgram, "instanceSpacing");
		uniGrassGlbDebugProjection = glGetUniformLocation(
			glGrassGlbDebugProgram, "projection");
		uniGrassGlbDebugBaseCenter = glGetUniformLocation(
			glGrassGlbDebugProgram, "baseCenter");
		uniGrassGlbDebugScale = glGetUniformLocation(
			glGrassGlbDebugProgram, "modelScale");
		uniGrassGlbDebugColor = glGetUniformLocation(
			glGrassGlbDebugProgram, "debugColor");
		uniGrassRootDebugProjection = glGetUniformLocation(
			glGrassRootDebugProgram, "projection");
		uniGrassRootDebugMarkerSize = glGetUniformLocation(
			glGrassRootDebugProgram, "markerSize");
		uniTreeProjection = glGetUniformLocation(glTreeProgram, "projection");
		uniTreeBaseColorTexture = glGetUniformLocation(glTreeProgram, "baseColorTexture");
		uniTreeShadowMap = glGetUniformLocation(glTreeProgram, "shadowMap");
		uniTreeShadowLightProj = glGetUniformLocation(glTreeProgram, "shadowLightProj");
		uniTreeBaseColorFactor = glGetUniformLocation(glTreeProgram, "baseColorFactor");
		uniTreeLightDirection = glGetUniformLocation(glTreeProgram, "lightDirection");
		uniTreeDirectionalLightingEnabled = glGetUniformLocation(
			glTreeProgram, "directionalLightingEnabled");
		uniTreeDirectionalLightingStrength = glGetUniformLocation(
			glTreeProgram, "directionalLightingStrength");
		uniTreeEnvironmentFillStrength = glGetUniformLocation(
			glTreeProgram, "environmentFillStrength");
		uniTreeNightFactor = glGetUniformLocation(glTreeProgram, "nightFactor");
		uniTreeFoliageMaterial = glGetUniformLocation(glTreeProgram, "foliageMaterial");
		uniTreeCameraPosition = glGetUniformLocation(glTreeProgram, "cameraPosition");
		uniTreePlayerPosition = glGetUniformLocation(glTreeProgram, "playerPosition");
		uniTreeOcclusionMode = glGetUniformLocation(glTreeProgram, "treeOcclusionMode");
		uniTreeBubbleRadius = glGetUniformLocation(glTreeProgram, "treeBubbleRadius");
		uniTreeSightConeWidth = glGetUniformLocation(glTreeProgram, "treeSightConeWidth");
		uniTreeMaximumFade = glGetUniformLocation(glTreeProgram, "treeMaximumFade");
		uniTreeCameraTopDownFactor = glGetUniformLocation(
			glTreeProgram, "cameraTopDownFactor");
		uniTreeTime = glGetUniformLocation(glTreeProgram, "time");
		uniTreeWindDirection = glGetUniformLocation(glTreeProgram, "windDirection");
		uniTreeWindStrength = glGetUniformLocation(glTreeProgram, "windStrength");
		uniTreeMaterialWindResponse = glGetUniformLocation(
			glTreeProgram, "materialWindResponse");
		uniTreeShadowStrength = glGetUniformLocation(glTreeProgram, "shadowStrength");
		uniTreeAlphaCutoff = glGetUniformLocation(glTreeProgram, "alphaCutoff");
		uniTreeFoliageTransmission = glGetUniformLocation(glTreeProgram, "foliageTransmission");
		uniTreeHasBaseColorTexture = glGetUniformLocation(glTreeProgram, "hasBaseColorTexture");
		uniTreeShadowsEnabled = glGetUniformLocation(glTreeProgram, "shadowsEnabled");
		uniTreeShadowProjection = glGetUniformLocation(glTreeShadowProgram, "lightProj");
		uniTreeShadowBaseColorTexture = glGetUniformLocation(
			glTreeShadowProgram, "baseColorTexture");
		uniTreeShadowBaseColorFactor = glGetUniformLocation(
			glTreeShadowProgram, "baseColorFactor");
		uniTreeShadowAlphaCutoff = glGetUniformLocation(
			glTreeShadowProgram, "alphaCutoff");
		uniTreeShadowHasBaseColorTexture = glGetUniformLocation(
			glTreeShadowProgram, "hasBaseColorTexture");
		uniTreeShadowTime = glGetUniformLocation(glTreeShadowProgram, "time");
		uniTreeShadowWindDirection = glGetUniformLocation(
			glTreeShadowProgram, "windDirection");
		uniTreeShadowWindStrength = glGetUniformLocation(
			glTreeShadowProgram, "windStrength");
		uniTreeShadowMaterialWindResponse = glGetUniformLocation(
			glTreeShadowProgram, "materialWindResponse");
		uniGrassCamera = glGetUniformLocation(glGrassProgram, "cameraPosition");
		uniGrassFocus = glGetUniformLocation(glGrassProgram, "focusPosition");
		uniGrassWorldOffset = glGetUniformLocation(glGrassProgram, "worldOffset");
		uniGrassTime = glGetUniformLocation(glGrassProgram, "time");
		uniGrassDrawRadius = glGetUniformLocation(glGrassProgram, "drawRadius");
		uniGrassHeightScale = glGetUniformLocation(glGrassProgram, "heightScale");
		uniGrassWindStrength = glGetUniformLocation(glGrassProgram, "windStrength");
		uniGrassWeatherModeVert = glGetUniformLocation(glGrassProgram, "weatherMode");
		uniGrassLightDirection = glGetUniformLocation(glGrassProgram, "lightDirection");
		uniGrassLightIntensity = glGetUniformLocation(glGrassProgram, "lightIntensity");
		uniGrassAmbientLight = glGetUniformLocation(glGrassProgram, "ambientLight");
		uniGrassLightningFlash = glGetUniformLocation(glGrassProgram, "lightningFlash");
		uniGrassWeatherModeFrag = glGetUniformLocation(glGrassProgram, "weatherMode");
		uniGrassNightFactor = glGetUniformLocation(glGrassProgram, "nightFactor");
		uniGrassBrightness = glGetUniformLocation(glGrassProgram, "brightness");
		uniGrassEnhancedColors = glGetUniformLocation(glGrassProgram, "enhancedColors");
		uniGrassSaturation = glGetUniformLocation(glGrassProgram, "saturation");
		uniGrassContrast = glGetUniformLocation(glGrassProgram, "contrast");
		uniGrassFogColor = glGetUniformLocation(glGrassProgram, "fogColor");
		uniGrassShadowMap = glGetUniformLocation(glGrassProgram, "shadowMap");
		uniGrassShadowLightProj = glGetUniformLocation(glGrassProgram, "shadowLightProj");
		uniGrassShadowsEnabled = glGetUniformLocation(glGrassProgram, "shadowsEnabled");
		uniGrassShadowStrength = glGetUniformLocation(glGrassProgram, "shadowStrength");
		uniGrassMaterialDebugMode = glGetUniformLocation(glGrassProgram, "materialDebugMode");
		uniGrassMaterialLightingEnabled = glGetUniformLocation(
			glGrassProgram, "materialLightingEnabled");
		uniGrassMaterialLightingStrength = glGetUniformLocation(
			glGrassProgram, "materialLightingStrength");
		uniGrassWetSurfacesEnabled = glGetUniformLocation(glGrassProgram, "wetSurfacesEnabled");
		uniGrassWetSurfaceStrength = glGetUniformLocation(glGrassProgram, "wetSurfaceStrength");
		uniGrassGlbProjection = glGetUniformLocation(glGrassGlbProgram, "projection");
		uniGrassGlbCamera = glGetUniformLocation(glGrassGlbProgram, "cameraPosition");
		uniGrassGlbFocus = glGetUniformLocation(glGrassGlbProgram, "focusPosition");
		uniGrassGlbWorldOffset = glGetUniformLocation(glGrassGlbProgram, "worldOffset");
		uniGrassGlbTime = glGetUniformLocation(glGrassGlbProgram, "time");
		uniGrassGlbDrawRadius = glGetUniformLocation(glGrassGlbProgram, "drawRadius");
		uniGrassGlbHeightScale = glGetUniformLocation(glGrassGlbProgram, "heightScale");
		uniGrassGlbWindStrength = glGetUniformLocation(glGrassGlbProgram, "windStrength");
		uniGrassGlbWeatherMode = glGetUniformLocation(glGrassGlbProgram, "weatherMode");
		uniGrassGlbLightDirection = glGetUniformLocation(glGrassGlbProgram, "lightDirection");
		uniGrassGlbLightIntensity = glGetUniformLocation(glGrassGlbProgram, "lightIntensity");
		uniGrassGlbAmbientLight = glGetUniformLocation(glGrassGlbProgram, "ambientLight");
		uniGrassGlbFogColor = glGetUniformLocation(glGrassGlbProgram, "fogColor");
		uniGrassGlbNightFactor = glGetUniformLocation(glGrassGlbProgram, "nightFactor");
		uniGrassGlbShadowMap = glGetUniformLocation(glGrassGlbProgram, "shadowMap");
		uniGrassGlbShadowLightProj = glGetUniformLocation(glGrassGlbProgram, "shadowLightProj");
		uniGrassGlbShadowsEnabled = glGetUniformLocation(glGrassGlbProgram, "shadowsEnabled");
		uniGrassGlbShadowStrength = glGetUniformLocation(glGrassGlbProgram, "shadowStrength");
		uniGrassGlbMaterialLightingEnabled = glGetUniformLocation(
			glGrassGlbProgram, "materialLightingEnabled");
		uniGrassGlbMaterialLightingStrength = glGetUniformLocation(
			glGrassGlbProgram, "materialLightingStrength");
		uniGrassGlbDebugMode = glGetUniformLocation(glGrassGlbProgram, "debugMode");
		uniWaterProjection = glGetUniformLocation(glWaterProgram, "projection");
		uniWaterBase = glGetUniformLocation(glWaterProgram, "base");
		uniWaterSceneColor = glGetUniformLocation(glWaterProgram, "sceneColor");
		uniWaterSceneDepth = glGetUniformLocation(glWaterProgram, "sceneDepth");
		uniWaterSkyTexture = glGetUniformLocation(glWaterProgram, "skyTexture");
		uniWaterShadowMap = glGetUniformLocation(glWaterProgram, "shadowMap");
		uniWaterWorldProjection = glGetUniformLocation(glWaterProgram, "worldProjection");
		uniWaterShadowLightProj = glGetUniformLocation(glWaterProgram, "shadowLightProj");
		uniWaterUvTransform = glGetUniformLocation(glWaterProgram, "sceneUvTransform");
		uniWaterTargetSize = glGetUniformLocation(glWaterProgram, "sceneTargetSize");
		uniWaterCamera = glGetUniformLocation(glWaterProgram, "cameraPosition");
		uniWaterLightDirection = glGetUniformLocation(glWaterProgram, "lightDirection");
		uniWaterFogColor = glGetUniformLocation(glWaterProgram, "fogColor");
		uniWaterTime = glGetUniformLocation(glWaterProgram, "time");
		uniWaterPassStrength = glGetUniformLocation(glWaterProgram, "waterStrength");
		uniWaterPassOpacity = glGetUniformLocation(glWaterProgram, "waterOpacity");
		uniWaterDrawDistance = glGetUniformLocation(glWaterProgram, "drawDistance");
		uniWaterNightFactor = glGetUniformLocation(glWaterProgram, "nightFactor");
		uniWaterLightningFlash = glGetUniformLocation(glWaterProgram, "lightningFlash");
		uniWaterWeatherDensity = glGetUniformLocation(glWaterProgram, "weatherDensity");
		uniWaterWeatherMode = glGetUniformLocation(glWaterProgram, "weatherMode");
		uniWaterShadowMapValid = glGetUniformLocation(glWaterProgram, "shadowMapValid");
		uniWaterZeroToOneDepth = glGetUniformLocation(glWaterProgram, "zeroToOneDepth");
		uniWaterSkyReflectionEnabled = glGetUniformLocation(glWaterProgram, "skyReflectionEnabled");
		uniWaterMaterialDebugMode = glGetUniformLocation(glWaterProgram, "materialDebugMode");
	}

	private void shutdownProgram()
	{
		if (glShadowDebugProgram != 0)
		{
			glDeleteProgram(glShadowDebugProgram);
			glShadowDebugProgram = 0;
		}

		if (glShadowProgram != 0)
		{
			glDeleteProgram(glShadowProgram);
			glShadowProgram = 0;
		}

		if (glAtmosphereShadowFilterProgram != 0)
		{
			glDeleteProgram(glAtmosphereShadowFilterProgram);
			glAtmosphereShadowFilterProgram = 0;
		}

		if (glSkyProgram != 0)
		{
			glDeleteProgram(glSkyProgram);
			glSkyProgram = 0;
		}

		if (glProgram != 0)
		{
			glDeleteProgram(glProgram);
			glProgram = 0;
		}

		if (glUiProgram != 0)
		{
			glDeleteProgram(glUiProgram);
			glUiProgram = 0;
		}

		if (glWeatherProgram != 0)
		{
			glDeleteProgram(glWeatherProgram);
			glWeatherProgram = 0;
		}

		if (glVolumetricProgram != 0)
		{
			glDeleteProgram(glVolumetricProgram);
			glVolumetricProgram = 0;
		}

		if (glVolumetricCompositeProgram != 0)
		{
			glDeleteProgram(glVolumetricCompositeProgram);
			glVolumetricCompositeProgram = 0;
		}

		if (glGrassProgram != 0)
		{
			glDeleteProgram(glGrassProgram);
			glGrassProgram = 0;
		}

		if (glGrassGlbProgram != 0)
		{
			glDeleteProgram(glGrassGlbProgram);
			glGrassGlbProgram = 0;
		}

		if (glGrassGlbDebugProgram != 0)
		{
			glDeleteProgram(glGrassGlbDebugProgram);
			glGrassGlbDebugProgram = 0;
		}

		if (glGrassRootDebugProgram != 0)
		{
			glDeleteProgram(glGrassRootDebugProgram);
			glGrassRootDebugProgram = 0;
		}
		if (glTreeProgram != 0)
		{
			glDeleteProgram(glTreeProgram);
			glTreeProgram = 0;
		}
		if (glTreeShadowProgram != 0)
		{
			glDeleteProgram(glTreeShadowProgram);
			glTreeShadowProgram = 0;
		}

		if (glWaterProgram != 0)
		{
			glDeleteProgram(glWaterProgram);
			glWaterProgram = 0;
		}
	}

	private void syncMaterialInspectorOverlay()
	{
		if (config.materialInspector())
		{
			if (!materialInspectorOverlayRegistered)
			{
				overlayManager.add(materialInspectorOverlay);
				materialInspectorOverlayRegistered = true;
			}
		}
		else
		{
			removeMaterialInspectorOverlay();
		}
	}

	private void removeMaterialInspectorOverlay()
	{
		if (materialInspectorOverlayRegistered)
		{
			overlayManager.remove(materialInspectorOverlay);
			materialInspectorOverlayRegistered = false;
		}
	}

	private void initVao()
	{
		// Create UI VAO
		vaoUiHandle = glGenVertexArrays();
		// Create UI buffer
		vboUiHandle = glGenBuffers();
		glBindVertexArray(vaoUiHandle);

		FloatBuffer vboUiBuf = GpuFloatBuffer.allocateDirect(5 * 4);
		vboUiBuf.put(new float[]{
			// positions     // texture coords
			1f, 1f, 0f, 1f, 0f, // top right
			1f, -1f, 0f, 1f, 1f, // bottom right
			-1f, -1f, 0f, 0f, 1f, // bottom left
			-1f, 1f, 0f, 0f, 0f  // top left
		});
		vboUiBuf.rewind();
		glBindBuffer(GL_ARRAY_BUFFER, vboUiHandle);
		glBufferData(GL_ARRAY_BUFFER, vboUiBuf, GL_STATIC_DRAW);

		// position attribute
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 5 * Float.BYTES, 0);
		glEnableVertexAttribArray(0);

		// texture coord attribute
		glVertexAttribPointer(1, 2, GL_FLOAT, false, 5 * Float.BYTES, 3 * Float.BYTES);
		glEnableVertexAttribArray(1);

		// unbind VAO/VBO
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	private void shutdownVao()
	{
		glDeleteBuffers(vboUiHandle);
		vboUiHandle = 0;

		glDeleteVertexArrays(vaoUiHandle);
		vaoUiHandle = 0;
	}

	private void initGrassVao()
	{
		vaoGrassHandle = glGenVertexArrays();
		vboGrassInstanceHandle = glGenBuffers();
		glBindVertexArray(vaoGrassHandle);
		glBindBuffer(GL_ARRAY_BUFFER, vboGrassInstanceHandle);
		glBufferData(GL_ARRAY_BUFFER,
			(long) MAX_SURFACE_DETAIL_INSTANCES
				* SURFACE_DETAIL_INSTANCE_FLOATS * Float.BYTES,
			GL_STREAM_DRAW);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 4, GL_FLOAT, false,
			SURFACE_DETAIL_INSTANCE_FLOATS * Float.BYTES, 0);
		glVertexAttribDivisor(0, 1);
		glEnableVertexAttribArray(1);
		glVertexAttribPointer(1, 2, GL_FLOAT, false,
			SURFACE_DETAIL_INSTANCE_FLOATS * Float.BYTES, 4L * Float.BYTES);
		glVertexAttribDivisor(1, 1);
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	private void initGrassGlbVao()
	{
		try
		{
			GlbGrassMesh mesh = GlbGrassMesh.load(
				"/net/runelite/client/plugins/gpu/glb/grass green by Steve B - 8q6D0D_SuBE.glb");
			vaoGrassGlbHandle = glGenVertexArrays();
			vboGrassGlbHandle = glGenBuffers();
			eboGrassGlbHandle = glGenBuffers();
			grassGlbIndexCount = mesh.indices.length;
			grassGlbNormalizedMin = mesh.normalizedMin.clone();
			grassGlbNormalizedMax = mesh.normalizedMax.clone();
			FloatBuffer vertices = GpuFloatBuffer.allocateDirect(mesh.vertices.length);
			vertices.put(mesh.vertices).flip();
			IntBuffer indices = ByteBuffer.allocateDirect(
				mesh.indices.length * Integer.BYTES)
				.order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
			indices.put(mesh.indices).flip();
			glBindVertexArray(vaoGrassGlbHandle);
			glBindBuffer(GL_ARRAY_BUFFER, vboGrassGlbHandle);
			glBufferData(GL_ARRAY_BUFFER, vertices, GL_STATIC_DRAW);
			glEnableVertexAttribArray(0);
			glVertexAttribPointer(0, 3, GL_FLOAT, false,
				GlbGrassMesh.FLOATS_PER_VERTEX * Float.BYTES, 0);
			glEnableVertexAttribArray(1);
			glVertexAttribPointer(1, 3, GL_FLOAT, false,
				GlbGrassMesh.FLOATS_PER_VERTEX * Float.BYTES, 3L * Float.BYTES);
			glEnableVertexAttribArray(2);
			glVertexAttribPointer(2, 2, GL_FLOAT, false,
				GlbGrassMesh.FLOATS_PER_VERTEX * Float.BYTES, 6L * Float.BYTES);
			glBindBuffer(GL_ARRAY_BUFFER, vboGrassInstanceHandle);
			glEnableVertexAttribArray(4);
			glVertexAttribPointer(4, 4, GL_FLOAT, false,
				SURFACE_DETAIL_INSTANCE_FLOATS * Float.BYTES, 0);
			glVertexAttribDivisor(4, 1);
			glEnableVertexAttribArray(5);
			glVertexAttribPointer(5, 2, GL_FLOAT, false,
				SURFACE_DETAIL_INSTANCE_FLOATS * Float.BYTES, 4L * Float.BYTES);
			glVertexAttribDivisor(5, 1);
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, eboGrassGlbHandle);
			glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);
			glBindVertexArray(0);
			glBindBuffer(GL_ARRAY_BUFFER, 0);
			log.info("Loaded production grass GLB: {} vertices, {} indices",
				mesh.vertices.length / GlbGrassMesh.FLOATS_PER_VERTEX, mesh.indices.length);
			log.info("Grass GLB bounds raw={}..{}, transformed={}..{}, RuneLite-local rootY=0 tipY=-64 meshHeight=64",
				java.util.Arrays.toString(mesh.rawMin), java.util.Arrays.toString(mesh.rawMax),
				java.util.Arrays.toString(mesh.transformedMin),
				java.util.Arrays.toString(mesh.transformedMax));
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Unable to load production grass GLB; procedural fallback remains active", ex);
			vaoGrassGlbHandle = 0;
			grassGlbIndexCount = 0;
		}
	}

	private void initGrassDebugVao()
	{
		vaoGrassDebugHandle = glGenVertexArrays();
		vboGrassDebugHandle = glGenBuffers();
		glBindVertexArray(vaoGrassDebugHandle);
		glBindBuffer(GL_ARRAY_BUFFER, vboGrassDebugHandle);
		glBufferData(GL_ARRAY_BUFFER, 576L * Float.BYTES, GL_DYNAMIC_DRAW);
		glEnableVertexAttribArray(0);
		glVertexAttribPointer(0, 3, GL_FLOAT, false, 3 * Float.BYTES, 0);
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	private void initTreeAssets()
	{
		for (int i = 0; i < treeFoliageSampleQueries.length; ++i)
		{
			treeFoliageSampleQueries[i] = glGenQueries();
		}
		List<TreeReplacementRegistry.Definition> definitions =
			treeReplacementRegistry.getDefinitions();
		treeLodAssets = new TreeGpuAsset[definitions.size()][4];
		loadedTreeAssets.clear();
		int loaded = 0;
		for (TreeReplacementRegistry.Definition definition : definitions)
		{
			Map<String, TreeGpuAsset> definitionAssets = new HashMap<>();
			for (int lod = 0; lod < 4; ++lod)
			{
				String resource = definition.resourcePath(lod);
				if (resource == null || GpuPlugin.class.getResource(resource) == null)
				{
					continue;
				}
				try
				{
					TreeGpuAsset asset = definitionAssets.get(resource);
					if (asset == null)
					{
						asset = TreeGpuAsset.load(definition, resource);
						definitionAssets.put(resource, asset);
						loadedTreeAssets.add(asset);
						loaded++;
						log.info("Loaded tree {} LOD{}: {} source primitives -> {} draw groups, "
							+ "{} triangles in shared VBO/IBO",
							definition.name, lod, asset.mesh.sourcePrimitiveCount,
							asset.mesh.primitives.length, asset.mesh.indices.length / 3);
					}
					treeLodAssets[definition.index][lod] = asset;
				}
				catch (IOException | RuntimeException ex)
				{
					log.warn("Tree replacement {} LOD{} could not be loaded",
						definition.name, lod, ex);
				}
			}
			TreeGpuAsset[] authoredLods = treeLodAssets[definition.index].clone();
			for (int lod = 0; lod < 4; ++lod)
			{
				if (treeLodAssets[definition.index][lod] == null)
				{
					treeLodAssets[definition.index][lod] = nearestTreeLodAsset(
						authoredLods, lod);
				}
			}
			if (treeLodAssets[definition.index][0] != null)
			{
				treeReplacementRegistry.setActive(definition, true);
			}
		}
		log.info("Tree replacement registry ready: {} definitions, {} unique LOD assets",
			definitions.size(), loaded);
	}

	private static TreeGpuAsset nearestTreeLodAsset(TreeGpuAsset[] assets, int lod)
	{
		for (int distance = 1; distance < assets.length; ++distance)
		{
			int lower = lod - distance;
			if (lower >= 0 && assets[lower] != null)
			{
				return assets[lower];
			}
			int higher = lod + distance;
			if (higher < assets.length && assets[higher] != null)
			{
				return assets[higher];
			}
		}
		return assets[lod];
	}

	private void shutdownTreeAssets()
	{
		for (TreeGpuAsset asset : loadedTreeAssets)
		{
			asset.destroy();
			treeReplacementRegistry.setActive(asset.definition, false);
		}
		loadedTreeAssets.clear();
		treeLodAssets = new TreeGpuAsset[0][0];
		for (int i = 0; i < treeFoliageSampleQueries.length; ++i)
		{
			if (treeFoliageSampleQueries[i] != 0)
			{
				glDeleteQueries(treeFoliageSampleQueries[i]);
				treeFoliageSampleQueries[i] = 0;
			}
		}
		treeFoliageQueriesPending = false;
		treeFoliagePendingQueryCount = 0;
	}

	private void shutdownGrassVao()
	{
		if (vboGrassInstanceHandle != 0)
		{
			glDeleteBuffers(vboGrassInstanceHandle);
			vboGrassInstanceHandle = 0;
		}
		if (vaoGrassHandle != 0)
		{
			glDeleteVertexArrays(vaoGrassHandle);
			vaoGrassHandle = 0;
		}
	}

	private void shutdownGrassDebugVao()
	{
		if (vboGrassDebugHandle != 0)
		{
			glDeleteBuffers(vboGrassDebugHandle);
			vboGrassDebugHandle = 0;
		}
		if (vaoGrassDebugHandle != 0)
		{
			glDeleteVertexArrays(vaoGrassDebugHandle);
			vaoGrassDebugHandle = 0;
		}
		if (vboGrassGlbHandle != 0)
		{
			glDeleteBuffers(vboGrassGlbHandle);
			vboGrassGlbHandle = 0;
		}
		if (eboGrassGlbHandle != 0)
		{
			glDeleteBuffers(eboGrassGlbHandle);
			eboGrassGlbHandle = 0;
		}
		if (vaoGrassGlbHandle != 0)
		{
			glDeleteVertexArrays(vaoGrassGlbHandle);
			vaoGrassGlbHandle = 0;
		}
		grassGlbIndexCount = 0;
		grassGlbNormalizedMin = null;
		grassGlbNormalizedMax = null;
	}

	private void initSkyVao()
	{
		/*
		 * Position XYZ + texture UV.
		 *
		 * These UV coordinates reproduce the cube mapping used by
		 * the Celestial/Hyper Realistic Sky Minecraft pack.
		 *
		 * Atlas:
		 *
		 * +----------+----------+----------+
		 * | BOTTOM   |   TOP    |  FRONT   |
		 * +----------+----------+----------+
		 * | LEFT     |   BACK   |  RIGHT   |
		 * +----------+----------+----------+
		 */

		float U0 = 0.0f;
		float U1 = 1.0f / 3.0f;
		float U2 = 2.0f / 3.0f;
		float U3 = 1.0f;

		float V0 = 0.0f;
		float V1 = 0.5f;
		float V2 = 1.0f;

		float[] vertices = {

				// =====================================================
				// LEFT  (x = -1)
				// Minecraft UV: bottom-left atlas cell
				// =====================================================

				-1f,  1f, -1f,   U1, V1,
				-1f,  1f,  1f,   U0, V1,
				-1f, -1f,  1f,   U0, V2,

				-1f,  1f, -1f,   U1, V1,
				-1f, -1f,  1f,   U0, V2,
				-1f, -1f, -1f,   U1, V2,


				// =====================================================
				// FRONT (z = +1)
				// Minecraft UV: top-right atlas cell
				// =====================================================

				1f,  1f,  1f,    U2, V0,
				-1f,  1f,  1f,    U3, V0,
				-1f, -1f,  1f,    U3, V1,

				1f,  1f,  1f,    U2, V0,
				-1f, -1f,  1f,    U3, V1,
				1f, -1f,  1f,    U2, V1,


				// =====================================================
				// RIGHT (x = +1)
				// Minecraft UV: bottom-right atlas cell
				// =====================================================

				1f,  1f,  1f,   U3, V1,
				1f,  1f, -1f,   U2, V1,
				1f, -1f, -1f,   U2, V2,

				1f,  1f,  1f,   U3, V1,
				1f, -1f, -1f,   U2, V2,
				1f, -1f,  1f,   U3, V2,


				// =====================================================
				// BACK (z = -1)
				// Minecraft UV: bottom-middle atlas cell
				// =====================================================

				1f,  1f, -1f,   U2, V1,
				-1f,  1f, -1f,   U1, V1,
				-1f, -1f, -1f,   U1, V2,

				1f,  1f, -1f,   U2, V1,
				-1f, -1f, -1f,   U1, V2,
				1f, -1f, -1f,   U2, V2,


				// =====================================================
				// TOP
				// Atlas: top-middle
				// =====================================================

				-1f,  1f, -1f,    U1, V0,
				-1f,  1f,  1f,    U1, V1,
				1f,  1f,  1f,    U2, V1,

				-1f,  1f, -1f,    U1, V0,
				1f,  1f,  1f,    U2, V1,
				1f,  1f, -1f,    U2, V0,


				// =====================================================
				// BOTTOM
				// Atlas: top-left
				// =====================================================

				-1f, -1f,  1f,    U0, V1,
				-1f, -1f, -1f,    U0, V0,
				1f, -1f, -1f,    U1, V0,

				-1f, -1f,  1f,    U0, V1,
				1f, -1f, -1f,    U1, V0,
				1f, -1f,  1f,    U1, V1
		};

		vaoSkyHandle = glGenVertexArrays();
		vboSkyHandle = glGenBuffers();

		glBindVertexArray(vaoSkyHandle);
		glBindBuffer(GL_ARRAY_BUFFER, vboSkyHandle);

		glBufferData(
				GL_ARRAY_BUFFER,
				vertices,
				GL_STATIC_DRAW
		);

		// XYZ position
		glVertexAttribPointer(
				0,
				3,
				GL_FLOAT,
				false,
				5 * Float.BYTES,
				0
		);

		glEnableVertexAttribArray(0);

		// UV
		glVertexAttribPointer(
				1,
				2,
				GL_FLOAT,
				false,
				5 * Float.BYTES,
				3 * Float.BYTES
		);

		glEnableVertexAttribArray(1);

		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);
	}

	private void initBuffers()
	{
		uniformBuffer = new GpuFloatBuffer(UNIFORM_BUFFER_SIZE);
		initGlBuffer(glUniformBuffer);
		Zone.initBuffer();

		for (int i = 0; i < rts.length; ++i)
		{
			rts[i].vaoO = new VAOList(i > 0);
			rts[i].vaoA = new VAOList(i > 0);
		}
	}

	private void initGlBuffer(GLBuffer glBuffer)
	{
		glBuffer.glBufferId = glGenBuffers();
	}

	private void shutdownBuffers()
	{
		destroyGlBuffer(glUniformBuffer);
		uniformBuffer = null;
		Zone.freeBuffer();

		for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
		{
			if (rts[i].vaoO != null)
			{
				rts[i].vaoO.free();
				rts[i].vaoO = null;
			}
			if (rts[i].vaoA != null)
			{
				rts[i].vaoA.free();
				rts[i].vaoA = null;
			}
		}
	}

	private void destroyGlBuffer(GLBuffer glBuffer)
	{
		if (glBuffer.glBufferId != -1)
		{
			glDeleteBuffers(glBuffer.glBufferId);
			glBuffer.glBufferId = -1;
		}
		glBuffer.size = -1;
	}

	private void initInterfaceTexture()
	{
		interfacePbo = glGenBuffers();

		interfaceTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private int loadSkyCubemap(String resourceName)
	{
		try (InputStream stream = GpuPlugin.class.getResourceAsStream(resourceName))
		{
			if (stream == null)
			{
				throw new RuntimeException("Could not find sky texture: " + resourceName);
			}

			BufferedImage image = ImageIO.read(stream);

			if (image == null)
			{
				throw new RuntimeException("Could not decode sky texture: " + resourceName);
			}

			int faceWidth = image.getWidth() / 3;
			int faceHeight = image.getHeight() / 2;

			if (faceWidth != faceHeight)
			{
				throw new RuntimeException(
						"Sky atlas cells must be square. Got " +
								faceWidth + "x" + faceHeight
				);
			}

			log.info(
					"Loading cubemap {}: atlas={}x{}, face={}x{}",
					resourceName,
					image.getWidth(),
					image.getHeight(),
					faceWidth,
					faceHeight
			);

			int texture = glGenTextures();

			glBindTexture(GL_TEXTURE_CUBE_MAP, texture);

			glPixelStorei(GL_UNPACK_ALIGNMENT, 1);

			/*
			 * Atlas:
			 *
			 * +----------+----------+----------+
			 * | cap A    | cap B    | WALL 4   |
			 * +----------+----------+----------+
			 * | WALL 1   | WALL 2   | WALL 3   |
			 * +----------+----------+----------+
			 *
			 * RuneLite calibration:
			 *
			 * +Z = NORTH
			 * +X = EAST
			 * -Z = SOUTH
			 * -X = WEST
			 *
			 * Initial wall mapping:
			 *
			 * NORTH = WALL 1
			 * EAST  = WALL 2
			 * SOUTH = WALL 3
			 * WEST  = WALL 4
			 */

			uploadCubemapFace(
					image,
					GL_TEXTURE_CUBE_MAP_POSITIVE_Z, // NORTH
					0,
					1,
					faceWidth
			);

			uploadCubemapFace(
					image,
					GL_TEXTURE_CUBE_MAP_POSITIVE_X, // EAST
					1,
					1,
					faceWidth
			);

			uploadCubemapFace(
					image,
					GL_TEXTURE_CUBE_MAP_NEGATIVE_Z, // SOUTH
					2,
					1,
					faceWidth
			);

			uploadCubemapFace(
					image,
					GL_TEXTURE_CUBE_MAP_NEGATIVE_X, // WEST
					2,
					0,
					faceWidth
			);

			/*
			 * Caps. RuneLite's rendered elevation points toward -Y, so the
			 * atlas TOP belongs on the cubemap's negative-Y face. Keep these
			 * separate from the calibrated wall mapping above.
			 */
			uploadCubemapFace(
					image,
					GL_TEXTURE_CUBE_MAP_POSITIVE_Y,
					0,
					0,
					faceWidth
			);

			uploadCubemapFaceRotatedCounterClockwise(
					image,
					GL_TEXTURE_CUBE_MAP_NEGATIVE_Y,
					1,
					0,
					faceWidth
			);

			glTexParameteri(
					GL_TEXTURE_CUBE_MAP,
					GL_TEXTURE_MIN_FILTER,
					GL_LINEAR
			);

			glTexParameteri(
					GL_TEXTURE_CUBE_MAP,
					GL_TEXTURE_MAG_FILTER,
					GL_LINEAR
			);

			glTexParameteri(
					GL_TEXTURE_CUBE_MAP,
					GL_TEXTURE_WRAP_S,
					GL_CLAMP_TO_EDGE
			);

			glTexParameteri(
					GL_TEXTURE_CUBE_MAP,
					GL_TEXTURE_WRAP_T,
					GL_CLAMP_TO_EDGE
			);

			glTexParameteri(
					GL_TEXTURE_CUBE_MAP,
					GL_TEXTURE_WRAP_R,
					GL_CLAMP_TO_EDGE
			);

			glBindTexture(GL_TEXTURE_CUBE_MAP, 0);

			log.info("Loaded sky cubemap successfully: {}", resourceName);

			return texture;
		}
		catch (IOException e)
		{
			throw new RuntimeException(
					"Failed to load sky cubemap: " + resourceName,
					e
			);
		}
	}

	private void uploadCubemapFace(
			BufferedImage atlas,
			int target,
			int cellX,
			int cellY,
			int faceSize)
	{
		ByteBuffer pixels =
				ByteBuffer.allocateDirect(faceSize * faceSize * 4);

		int startX = cellX * faceSize;
		int startY = cellY * faceSize;

		/*
		 * BufferedImage origin is top-left.
		 * OpenGL texture origin is bottom-left,
		 * so upload rows bottom-to-top.
		 */
		for (int y = faceSize - 1; y >= 0; y--)
		{
			for (int x = 0; x < faceSize; x++)
			{
				int argb =
						atlas.getRGB(
								startX + x,
								startY + y
						);

				pixels.put((byte) ((argb >> 16) & 0xFF));
				pixels.put((byte) ((argb >> 8) & 0xFF));
				pixels.put((byte) (argb & 0xFF));
				pixels.put((byte) ((argb >> 24) & 0xFF));
			}
		}

		pixels.flip();

		glTexImage2D(
				target,
				0,
				GL_RGBA8,
				faceSize,
				faceSize,
				0,
				GL_RGBA,
				GL_UNSIGNED_BYTE,
				pixels
		);
	}

	/**
	 * Upload the atlas roof with the orientation expected by OpenGL's negative-Y
	 * cubemap face. This is intentionally roof-only: the four calibrated wall
	 * faces must retain their existing uploads and orientation.
	 */
	private void uploadCubemapFaceRotatedCounterClockwise(
			BufferedImage atlas,
			int target,
			int cellX,
			int cellY,
			int faceSize)
	{
		ByteBuffer pixels =
				ByteBuffer.allocateDirect(faceSize * faceSize * 4);

		int startX = cellX * faceSize;
		int startY = cellY * faceSize;

		/*
		 * Iterate destination rows bottom-to-top for OpenGL, while selecting
		 * source pixels from a 90-degree counter-clockwise rotation. This makes
		 * all four roof edges meet the already-calibrated wall faces.
		 */
		for (int y = faceSize - 1; y >= 0; y--)
		{
			for (int x = 0; x < faceSize; x++)
			{
				int argb = atlas.getRGB(
						startX + faceSize - 1 - y,
						startY + x
				);

				pixels.put((byte) ((argb >> 16) & 0xFF));
				pixels.put((byte) ((argb >> 8) & 0xFF));
				pixels.put((byte) (argb & 0xFF));
				pixels.put((byte) ((argb >> 24) & 0xFF));
			}
		}

		pixels.flip();

		glTexImage2D(
				target,
				0,
				GL_RGBA8,
				faceSize,
				faceSize,
				0,
				GL_RGBA,
				GL_UNSIGNED_BYTE,
				pixels
		);
	}

	private void initSkyTextures()
	{
		cosmicSkyTexture = loadSkyCubemap("cosmic_test.png");
		nightSkyTexture = loadSkyCubemap("night_test.png");
		daySkyTexture = loadSkyCubemap("day_test.png");
		sunsetSkyTexture = loadSkyCubemap("sunset_test.png");
		rainSkyTextures[0] = loadSkyCubemap("weather/skies/rain/sky283_day_rain.png");
		rainSkyTextures[1] = loadSkyCubemap("weather/skies/rain/sky280_sunset_rain.png");
		rainSkyTextures[2] = loadSkyCubemap("weather/skies/rain/sky282_night_rain.png");
		// Snow and blizzard deliberately share the same high-cloud, low-horizon
		// atlas. Loading it once also avoids keeping four unused 1024px cubemaps
		// resident on the GPU.
		snowSkyTexture = loadSkyCubemap("weather/skies/snow/sky273_day_snow.png");
		lightningSkyTextures[0] = loadSkyCubemap("weather/skies/lightning/sky303_lightning1_stage1.png");
		lightningSkyTextures[1] = loadSkyCubemap("weather/skies/lightning/sky304_lightning1_stage2.png");
		lightningSkyTextures[2] = loadSkyCubemap("weather/skies/lightning/sky307_lightning3_stage1.png");
		lightningSkyTextures[3] = loadSkyCubemap("weather/skies/lightning/sky308_lightning3_stage2.png");
	}

	private void shutdownInterfaceTexture()
	{
		glDeleteBuffers(interfacePbo);
		glDeleteTextures(interfaceTexture);
		interfaceTexture = -1;
	}

	private void initShadowMap()
	{
		initSurfaceShadowMap();
		initAtmosphereShadowMap();
	}

	private void initSurfaceShadowMap()
	{
		// =====================================================
		// Create depth texture
		// =====================================================

		shadowDepthTexture = glGenTextures();

		glBindTexture(
				GL_TEXTURE_2D,
				shadowDepthTexture
		);

		glTexImage2D(
				GL_TEXTURE_2D,
				0,
				GL_DEPTH_COMPONENT24,
				SHADOW_MAP_SIZE,
				SHADOW_MAP_SIZE,
				0,
				GL_DEPTH_COMPONENT,
				GL_FLOAT,
				(ByteBuffer) null
		);

		// Keep depth taps exact; frag.glsl performs explicit soft PCF filtering.
		glTexParameteri(
				GL_TEXTURE_2D,
				GL_TEXTURE_MIN_FILTER,
				GL_NEAREST
		);

		glTexParameteri(
				GL_TEXTURE_2D,
				GL_TEXTURE_MAG_FILTER,
				GL_NEAREST
		);

		glTexParameteri(
				GL_TEXTURE_2D,
				GL_TEXTURE_WRAP_S,
				GL_CLAMP_TO_EDGE
		);

		glTexParameteri(
				GL_TEXTURE_2D,
				GL_TEXTURE_WRAP_T,
				GL_CLAMP_TO_EDGE
		);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);


		// =====================================================
		// Create framebuffer
		// =====================================================

		shadowFbo = glGenFramebuffers();

		glBindFramebuffer(
				GL_FRAMEBUFFER,
				shadowFbo
		);

		glFramebufferTexture2D(
				GL_FRAMEBUFFER,
				GL_DEPTH_ATTACHMENT,
				GL_TEXTURE_2D,
				shadowDepthTexture,
				0
		);


		/*
		 * This framebuffer has NO color buffer.
		 * It exists only to store depth from the sun.
		 */
		glDrawBuffer(GL_NONE);
		glReadBuffer(GL_NONE);


		// =====================================================
		// Verify framebuffer
		// =====================================================

		int status =
				glCheckFramebufferStatus(
						GL_FRAMEBUFFER
				);

		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException(
					"Shadow framebuffer is incomplete. Status: "
							+ status
			);
		}


		// =====================================================
		// Restore state
		// =====================================================

		glBindFramebuffer(
				GL_FRAMEBUFFER,
				awtContext.getFramebuffer(false)
		);

		glBindTexture(
				GL_TEXTURE_2D,
				0
		);

		log.info(
				"Initialized {}x{} shadow map",
				SHADOW_MAP_SIZE,
				SHADOW_MAP_SIZE
		);
	}

	private void initAtmosphereShadowMap()
	{
		atmosphereShadowDepthTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, atmosphereShadowDepthTexture);
		glTexImage2D(
			GL_TEXTURE_2D,
			0,
			GL_DEPTH_COMPONENT24,
			ATMOSPHERE_SHADOW_MAP_SIZE,
			ATMOSPHERE_SHADOW_MAP_SIZE,
			0,
			GL_DEPTH_COMPONENT,
			GL_FLOAT,
			(ByteBuffer) null);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);

		atmosphereShadowFbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, atmosphereShadowFbo);
		glFramebufferTexture2D(
			GL_FRAMEBUFFER,
			GL_DEPTH_ATTACHMENT,
			GL_TEXTURE_2D,
			atmosphereShadowDepthTexture,
			0);
		glDrawBuffer(GL_NONE);
		glReadBuffer(GL_NONE);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException(
				"Atmosphere shadow framebuffer is incomplete. Status: " + status);
		}

		atmosphereFilteredShadowDepthTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, atmosphereFilteredShadowDepthTexture);
		glTexImage2D(
			GL_TEXTURE_2D,
			0,
			GL_DEPTH_COMPONENT24,
			ATMOSPHERE_FILTERED_SHADOW_MAP_SIZE,
			ATMOSPHERE_FILTERED_SHADOW_MAP_SIZE,
			0,
			GL_DEPTH_COMPONENT,
			GL_FLOAT,
			(ByteBuffer) null);
		// Linear comparison sampling supplies the final sub-texel PCF after the
		// explicit macro filter has removed isolated roof/triangle detail.
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(
			GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);

		atmosphereFilteredShadowFbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, atmosphereFilteredShadowFbo);
		glFramebufferTexture2D(
			GL_FRAMEBUFFER,
			GL_DEPTH_ATTACHMENT,
			GL_TEXTURE_2D,
			atmosphereFilteredShadowDepthTexture,
			0);
		glDrawBuffer(GL_NONE);
		glReadBuffer(GL_NONE);
		status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException(
				"Filtered atmosphere shadow framebuffer is incomplete. Status: " + status);
		}

		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		glBindTexture(GL_TEXTURE_2D, 0);
		log.info(
			"Initialized {}x{} atmosphere blocker map and {}x{} macro filter",
			ATMOSPHERE_SHADOW_MAP_SIZE,
			ATMOSPHERE_SHADOW_MAP_SIZE,
			ATMOSPHERE_FILTERED_SHADOW_MAP_SIZE,
			ATMOSPHERE_FILTERED_SHADOW_MAP_SIZE);
	}

	private void shutdownShadowMap()
	{
		if (shadowFbo != 0)
		{
			glDeleteFramebuffers(shadowFbo);
			shadowFbo = 0;
		}

		if (shadowDepthTexture != 0)
		{
			glDeleteTextures(shadowDepthTexture);
			shadowDepthTexture = 0;
		}

		if (atmosphereShadowFbo != 0)
		{
			glDeleteFramebuffers(atmosphereShadowFbo);
			atmosphereShadowFbo = 0;
		}

		if (atmosphereShadowDepthTexture != 0)
		{
			glDeleteTextures(atmosphereShadowDepthTexture);
			atmosphereShadowDepthTexture = 0;
		}

		if (atmosphereFilteredShadowFbo != 0)
		{
			glDeleteFramebuffers(atmosphereFilteredShadowFbo);
			atmosphereFilteredShadowFbo = 0;
		}

		if (atmosphereFilteredShadowDepthTexture != 0)
		{
			glDeleteTextures(atmosphereFilteredShadowDepthTexture);
			atmosphereFilteredShadowDepthTexture = 0;
		}
		surfaceShadowMapValid = false;
		atmosphereShadowMapValid = false;
	}

	private void initFbo(int width, int height, int aaSamples)
	{
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

		width = getScaledValue(transform.getScaleX(), width);
		height = getScaledValue(transform.getScaleY(), height);

		if (aaSamples > 0)
		{
			glEnable(GL_MULTISAMPLE);
		}
		else
		{
			glDisable(GL_MULTISAMPLE);
		}

		// Create and bind the FBO
		fboScene = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboScene);

		// Color render buffer
		rboColorBuffer = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboColorBuffer);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, aaSamples, GL_RGBA, width, height);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rboColorBuffer);

		// Depth render buffer
		rboDepthBuffer = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboDepthBuffer);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, aaSamples, GL_DEPTH_COMPONENT32F, width, height);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rboDepthBuffer);

		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("FBO is incomplete. status: " + status);
		}

		// Resolve the multisampled scene into sampleable color and reversed-depth
		// textures. Deferred water and volumetric light read these, then write back
		// to fboScene; no attachment is sampled while it is being rendered to.
		fboSceneResolved = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboSceneResolved);

		sceneColorTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, sceneColorTexture);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0,
			GL_RGBA, GL_UNSIGNED_BYTE, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
			GL_TEXTURE_2D, sceneColorTexture, 0);

		sceneDepthTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, sceneDepthTexture);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, width, height, 0,
			GL_DEPTH_COMPONENT, GL_FLOAT, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_COMPARE_MODE, GL_NONE);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT,
			GL_TEXTURE_2D, sceneDepthTexture, 0);

		status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException(
				"Resolved scene FBO is incomplete. status: " + status);
		}

		// Volumetric lighting is evaluated at half resolution. Besides reducing the
		// shadow-map march cost, this gives the atmospheric signal a stable physical
		// footprint instead of exposing individual terrain triangles and roof slats.
		volumetricTargetWidth = Math.max(1, (width + 1) / 2);
		volumetricTargetHeight = Math.max(1, (height + 1) / 2);
		fboVolumetric = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboVolumetric);

		volumetricTexture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, volumetricTexture);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F,
			volumetricTargetWidth, volumetricTargetHeight, 0,
			GL_RGBA, GL_HALF_FLOAT, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
			GL_TEXTURE_2D, volumetricTexture, 0);

		status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException(
				"Volumetric FBO is incomplete. status: " + status);
		}

		sceneTargetWidth = width;
		sceneTargetHeight = height;

		// Reset
		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
		glBindTexture(GL_TEXTURE_2D, 0);
		glBindRenderbuffer(GL_RENDERBUFFER, 0);
	}

	private void shutdownFbo()
	{
		if (fboScene != -1)
		{
			glDeleteFramebuffers(fboScene);
			fboScene = -1;
		}

		if (rboColorBuffer != 0)
		{
			glDeleteRenderbuffers(rboColorBuffer);
			rboColorBuffer = 0;
		}

		if (rboDepthBuffer != 0)
		{
			glDeleteRenderbuffers(rboDepthBuffer);
			rboDepthBuffer = 0;
		}

		if (fboSceneResolved != -1)
		{
			glDeleteFramebuffers(fboSceneResolved);
			fboSceneResolved = -1;
		}

		if (sceneColorTexture != 0)
		{
			glDeleteTextures(sceneColorTexture);
			sceneColorTexture = 0;
		}

		if (sceneDepthTexture != 0)
		{
			glDeleteTextures(sceneDepthTexture);
			sceneDepthTexture = 0;
		}

		if (fboVolumetric != -1)
		{
			glDeleteFramebuffers(fboVolumetric);
			fboVolumetric = -1;
		}

		if (volumetricTexture != 0)
		{
			glDeleteTextures(volumetricTexture);
			volumetricTexture = 0;
		}

		sceneTargetWidth = 0;
		sceneTargetHeight = 0;
		volumetricTargetWidth = 0;
		volumetricTargetHeight = 0;
	}

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		ctx.cameraX = (int) cameraX;
		ctx.cameraY = (int) cameraY;
		ctx.cameraZ = (int) cameraZ;
		ctx.minLevel = minLevel;
		ctx.level = level;
		ctx.maxLevel = maxLevel;
		ctx.hideRoofIds = hideRoofIds;

		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			if (++grassVisibilityFrame <= 0)
			{
				grassVisibilityFrame = 1;
				for (Zone[] zoneRow : root.zones)
				{
					for (Zone zone : zoneRow)
					{
						zone.surfaceDetailVisibleFrame = -1;
						zone.waterVisibleFrame = -1;
						zone.treeReplacementVisibleFrame = -1;
					}
				}
			}
			for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
			{
				rts[i].vaoO.map();
				rts[i].vaoA.map();
			}

			this.cameraYaw = client.getCameraYaw();
			this.cameraPitch = client.getCameraPitch();
			preSceneDrawToplevel(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw);
		}
		else
		{
			System.arraycopy(((FloatProjection) entityProjection).getProjection(), 0, ctx.projection, 0, 16);
			glUniformMatrix4fv(uniEntityProj, false, ctx.projection);
			glUniform4i(uniEntityTint, scene.getOverrideHue(), scene.getOverrideSaturation(), scene.getOverrideLuminance(), scene.getOverrideAmount());
		}
	}

	private void setEnvironmentRayColor(int uniform)
	{
		SkyMode environmentSky = getEnvironmentSkyMode();
		float rayR = 1.0f;
		float rayG = 0.82f;
		float rayB = 0.55f;
		if (environmentSky == SkyMode.DAY)
		{
			rayR = 1.0f;
			rayG = 0.93f;
			rayB = 0.72f;
		}
		else if (environmentSky == SkyMode.NIGHT)
		{
			rayR = 0.42f;
			rayG = 0.55f;
			rayB = 0.85f;
		}
		else if (environmentSky == SkyMode.COSMIC)
		{
			rayR = 0.62f;
			rayG = 0.38f;
			rayB = 0.92f;
		}

		glUniform3f(uniform, rayR, rayG, rayB);
	}

	private static float smoothStep(float value)
	{
		float t = Math.max(0.0f, Math.min(1.0f, value));
		return t * t * (3.0f - 2.0f * t);
	}

	private static void copyDirection(float[] source, float[] target)
	{
		System.arraycopy(source, 0, target, 0, 3);
	}

	private static void setArcDirection(
		float[] target,
		float[] start,
		float[] end,
		float progress)
	{
		float t = smoothStep(progress);
		double startAzimuth = Math.atan2(start[2], start[0]);
		double endAzimuth = Math.atan2(end[2], end[0]);
		while (endAzimuth <= startAzimuth)
		{
			endAzimuth += Math.PI * 2.0;
		}

		double startElevation = Math.atan2(
			start[1], Math.sqrt(start[0] * start[0] + start[2] * start[2]));
		double endElevation = Math.atan2(
			end[1], Math.sqrt(end[0] * end[0] + end[2] * end[2]));
		double baseElevation = startElevation + (endElevation - startElevation) * t;
		double elevation = baseElevation
			+ Math.sin(Math.PI * t) * (CELESTIAL_PEAK_ELEVATION - baseElevation);
		double azimuth = startAzimuth + (endAzimuth - startAzimuth) * t;
		double horizontal = Math.cos(elevation);

		target[0] = (float) (horizontal * Math.cos(azimuth));
		target[1] = (float) Math.sin(elevation);
		target[2] = (float) (horizontal * Math.sin(azimuth));
	}

	private static float getStaticNightFactor(SkyMode skyMode)
	{
		if (skyMode == SkyMode.NIGHT || skyMode == SkyMode.COSMIC)
		{
			return 1.0f;
		}
		return skyMode == SkyMode.SUNSET ? 0.18f : 0.0f;
	}

	private SkyMode resolveEnvironmentSkyMode(double cyclePhase)
	{
		WeatherMode weather = config.weatherMode();
		if (weather == WeatherMode.STORM)
		{
			return SkyMode.NIGHT;
		}
		if (weather == WeatherMode.RAIN)
		{
			return SkyMode.SUNSET;
		}
		if (weather == WeatherMode.SNOW || weather == WeatherMode.BLIZZARD)
		{
			return SkyMode.DAY;
		}
		if (!config.dayNightCycle())
		{
			return config.skyMode();
		}

		if (cyclePhase < 0.35)
		{
			return SkyMode.DAY;
		}
		if (cyclePhase < 0.50)
		{
			return SkyMode.SUNSET;
		}
		if (cyclePhase < 0.85)
		{
			return SkyMode.NIGHT;
		}
		return SkyMode.SUNSET;
	}

	private FrameEnvironment updateFrameEnvironment(long timeMillis)
	{
		FrameEnvironment environment = frameEnvironment;
		environment.initialized = true;
		environment.timeMillis = timeMillis;

		boolean cycleEnabled = config.dayNightCycle();
		long cycleMillis = Math.max(2, config.dayNightCycleMinutes()) * 60_000L;
		double phase = cycleEnabled
			? (timeMillis % cycleMillis) / (double) cycleMillis : 0.0;
		environment.skyMode = resolveEnvironmentSkyMode(phase);

		if (cycleEnabled)
		{
			if (phase < 0.50)
			{
				setArcDirection(environment.sunDirection, MORNING_SUN, EVENING_SUN,
					(float) (phase / 0.50));
			}
			else if (phase < 0.85)
			{
				setArcDirection(environment.sunDirection, EVENING_SUN, MORNING_SUN,
					(float) ((phase - 0.50) / 0.35));
			}
			else
			{
				copyDirection(MORNING_SUN, environment.sunDirection);
			}
			copyDirection(environment.sunDirection, environment.moonDirection);

			if (config.weatherMode() == WeatherMode.CLEAR)
			{
				if (phase < 0.35)
				{
					environment.nightFactor = 0.0f;
				}
				else if (phase < 0.50)
				{
					environment.nightFactor = smoothStep(
						(float) ((phase - 0.35) / 0.15));
				}
				else if (phase < 0.85)
				{
					environment.nightFactor = 1.0f;
				}
				else
				{
					environment.nightFactor = 1.0f - smoothStep(
						(float) ((phase - 0.85) / 0.15));
				}
			}
			else
			{
				environment.nightFactor = getStaticNightFactor(environment.skyMode);
			}
		}
		else
		{
			SunPosition sunPosition = config.sunPosition();
			float[] selectedSun = sunPosition == SunPosition.NOON ? NOON_SUN
				: sunPosition == SunPosition.EVENING ? EVENING_SUN : MORNING_SUN;
			MoonPosition moonPosition = config.moonPosition();
			float[] selectedMoon = moonPosition == MoonPosition.OVERHEAD ? NOON_SUN
				: moonPosition == MoonPosition.NORTHWEST ? EVENING_SUN : MORNING_SUN;
			copyDirection(selectedSun, environment.sunDirection);
			copyDirection(selectedMoon, environment.moonDirection);
			environment.nightFactor = getStaticNightFactor(environment.skyMode);
		}

		float[] activeDirection = environment.skyMode == SkyMode.NIGHT
			|| environment.skyMode == SkyMode.COSMIC
			? environment.moonDirection : environment.sunDirection;
		copyDirection(activeDirection, environment.activeLightDirection);
		// RuneLite scene elevation grows toward negative Y. Convert once here so
		// surface shadows and atmospheric blockers share the exact same convention.
		environment.activeSceneDirection[0] = activeDirection[0];
		environment.activeSceneDirection[1] = -activeDirection[1];
		environment.activeSceneDirection[2] = activeDirection[2];
		return environment;
	}

	private void ensureFrameEnvironment()
	{
		if (!frameEnvironment.initialized)
		{
			updateFrameEnvironment(System.currentTimeMillis());
		}
	}

	private SkyMode getEnvironmentSkyMode()
	{
		ensureFrameEnvironment();
		return frameEnvironment.skyMode;
	}

	private float[] getSunDirection()
	{
		ensureFrameEnvironment();
		return frameEnvironment.sunDirection;
	}

	private float[] getMoonDirection()
	{
		ensureFrameEnvironment();
		return frameEnvironment.moonDirection;
	}

	private float[] getActiveLightDirection()
	{
		ensureFrameEnvironment();
		return frameEnvironment.activeLightDirection;
	}

	private float[] getActiveSceneLightDirection()
	{
		ensureFrameEnvironment();
		return frameEnvironment.activeSceneDirection;
	}

	@VisibleForTesting
	static boolean isMoonEnvironment(SkyMode skyMode)
	{
		return skyMode == SkyMode.NIGHT || skyMode == SkyMode.COSMIC;
	}

	private float getSunRayStrength()
	{
		return config.godRays()
			? Ints.constrainToRange(config.godRaysStrength(), 0, 200) / 100.0f
			: 0.0f;
	}

	private float getMoonRayStrength()
	{
		return config.moonRays()
			? Ints.constrainToRange(config.moonRaysStrength(), 0, 200) / 100.0f
			: 0.0f;
	}

	private float getActiveCelestialRayStrength()
	{
		SkyMode skyMode = getEnvironmentSkyMode();
		if (skyMode == SkyMode.OFF)
		{
			return 0.0f;
		}
		return isMoonEnvironment(skyMode)
			? getMoonRayStrength() : getSunRayStrength();
	}

	private void drawCustomSky(
			int viewportWidth,
			int viewportHeight,
			float cameraPitch,
			float cameraYaw)
	{
		glUseProgram(glSkyProgram);

		/*
		 * SAME projection + rotations RuneLite uses for the world,
		 * but deliberately NO camera translation.
		 *
		 * Therefore:
		 * walking = sky doesn't move
		 * rotating = sky rotates naturally
		 */
		float[] skyProjection = Mat4.scale(
				client.getScale(),
				client.getScale(),
				1
		);

		Mat4.mul(
				skyProjection,
				Mat4.projection(
						client.getViewportWidth(),
						client.getViewportHeight(),
						50
				)
		);

		Mat4.mul(skyProjection, Mat4.rotateX(cameraPitch));
		Mat4.mul(skyProjection, Mat4.rotateY(cameraYaw));
		glUniformMatrix4fv(
				uniSkyProj,
				false,
				skyProjection
		);

		int selectedSkyTexture;
		WeatherMode weather = config.weatherMode();
		int lightningTexture = getLightningTexture(frameEnvironment.timeMillis);

		if (lightningTexture != 0 && weather == WeatherMode.STORM)
		{
			selectedSkyTexture = lightningTexture;
		}
		else if (weather == WeatherMode.RAIN)
		{
			selectedSkyTexture = rainSkyTextures[0];
		}
		else if (weather == WeatherMode.STORM)
		{
			selectedSkyTexture = rainSkyTextures[2];
		}
		else if (weather == WeatherMode.SNOW || weather == WeatherMode.BLIZZARD)
		{
			selectedSkyTexture = snowSkyTexture;
		}
		else switch (getEnvironmentSkyMode())
		{
			case DAY:
				selectedSkyTexture = daySkyTexture;
				break;

			case SUNSET:
				selectedSkyTexture = sunsetSkyTexture;
				break;

			case NIGHT:
				selectedSkyTexture = nightSkyTexture;
				break;

			case COSMIC:
			default:
				selectedSkyTexture = cosmicSkyTexture;
				break;
		}
		activeSkyTexture = selectedSkyTexture;

		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_CUBE_MAP, selectedSkyTexture);
		glUniform1i(uniSkyTexture, 0);
		float[] sun = getSunDirection();
		// RuneLite model-space height and the sky cube's vertical axis are opposed.
		glUniform3f(uniSkySunDirection, sun[0], -sun[1], sun[2]);
		float[] moon = getMoonDirection();
		glUniform3f(uniSkyMoonDirection, moon[0], -moon[1], moon[2]);
		setEnvironmentRayColor(uniSkyRayColor);
		glUniform1f(
				uniSkyRayStrength,
				config.celestialGlareStrength() / 100.0f
		);
		glUniform1f(uniSkyNightFactor, frameEnvironment.nightFactor);
		// A blizzard still uses the sun's direction and warm scattering, but the
		// dense cloud deck hides most of the actual disc and halo.
		glUniform1f(uniSkyCelestialVisibility,
			weather == WeatherMode.BLIZZARD ? 0.18f : 1.0f);

		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_CULL_FACE);
		glDisable(GL_BLEND);

		glBindVertexArray(vaoSkyHandle);

		glDrawArrays(
				GL_TRIANGLES,
				0,
				36
		);

		glBindVertexArray(0);

		glBindTexture(GL_TEXTURE_CUBE_MAP, 0);

		glDepthMask(true);
		glEnable(GL_DEPTH_TEST);
		glEnable(GL_CULL_FACE);
		glEnable(GL_BLEND);

		glUseProgram(glProgram);
	}

	private void preSceneDrawToplevel(Scene scene,
		float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw)
	{
		updateFrameEnvironment(System.currentTimeMillis());
		scene.setDrawDistance(getDrawDistance());

		// UBO
		uniformBuffer.clear();
		uniformBuffer
				.put(cameraYaw)
				.put(cameraPitch)
				.put(cameraX)
				.put(cameraY)
				.put(cameraZ);
		uniformBuffer.flip();

		glBindBuffer(GL_UNIFORM_BUFFER, glUniformBuffer.glBufferId);
		glBufferData(GL_UNIFORM_BUFFER, uniformBuffer.getBuffer(), GL_DYNAMIC_DRAW);
		glBindBuffer(GL_UNIFORM_BUFFER, 0);
		uniformBuffer.clear();

		glBindBufferBase(GL_UNIFORM_BUFFER, 0, glUniformBuffer.glBufferId);

		checkGLErrors();

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		final int viewportHeight = client.getViewportHeight();
		final int viewportWidth = client.getViewportWidth();

		// Setup FBO and anti-aliasing
		{
			final AntiAliasingMode antiAliasingMode = config.antiAliasingMode();
			final Dimension stretchedDimensions = client.getStretchedDimensions();

			final int stretchedCanvasWidth =
					client.isStretchedEnabled() ? stretchedDimensions.width : canvasWidth;

			final int stretchedCanvasHeight =
					client.isStretchedEnabled() ? stretchedDimensions.height : canvasHeight;

			// Re-create fbo
			if (lastStretchedCanvasWidth != stretchedCanvasWidth
					|| lastStretchedCanvasHeight != stretchedCanvasHeight
					|| lastAntiAliasingMode != antiAliasingMode)
			{
				shutdownFbo();

				// Bind default FBO to check whether anti-aliasing is forced
				glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

				final int forcedAASamples = glGetInteger(GL_SAMPLES);
				final int maxSamples = glGetInteger(GL_MAX_SAMPLES);

				final int samples =
						forcedAASamples != 0
								? forcedAASamples
								: Math.min(antiAliasingMode.getSamples(), maxSamples);

				log.debug(
						"AA samples: {}, max samples: {}, forced samples: {}",
						samples,
						maxSamples,
						forcedAASamples
				);

				initFbo(
						stretchedCanvasWidth,
						stretchedCanvasHeight,
						samples
				);

				lastStretchedCanvasWidth = stretchedCanvasWidth;
				lastStretchedCanvasHeight = stretchedCanvasHeight;
				lastAntiAliasingMode = antiAliasingMode;
			}

			glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboScene);
		}

		// Setup anisotropic filtering
		final int anisotropicFilteringLevel =
				config.anisotropicFilteringLevel();

		if (textureArrayId != -1
				&& lastAnisotropicFilteringLevel != anisotropicFilteringLevel)
		{
			textureManager.setAnisotropicFilteringLevel(
					textureArrayId,
					anisotropicFilteringLevel
			);

			lastAnisotropicFilteringLevel =
					anisotropicFilteringLevel;
		}

		// Setup viewport
		int renderWidthOff = client.getViewportXOffset();
		int renderHeightOff = client.getViewportYOffset();

		int renderCanvasHeight = canvasHeight;
		int renderViewportHeight = viewportHeight;
		int renderViewportWidth = viewportWidth;

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();

			renderCanvasHeight = dim.height;

			double scaleFactorY =
					dim.getHeight() / canvasHeight;

			double scaleFactorX =
					dim.getWidth() / canvasWidth;

			final int padding = 1;

			renderViewportHeight =
					(int) Math.ceil(scaleFactorY * renderViewportHeight)
							+ padding * 2;

			renderViewportWidth =
					(int) Math.ceil(scaleFactorX * renderViewportWidth)
							+ padding * 2;

			renderHeightOff =
					(int) Math.floor(scaleFactorY * renderHeightOff)
							- padding;

			renderWidthOff =
					(int) Math.floor(scaleFactorX * renderWidthOff)
							- padding;
		}

		glDpiAwareViewport(
				renderWidthOff,
				renderCanvasHeight
						- renderViewportHeight
						- renderHeightOff,
				renderViewportWidth,
				renderViewportHeight
		);

		glUseProgram(glProgram);

		// =====================================================
		// Setup uniforms
		// =====================================================

		final int drawDistance = getDrawDistance();

		int fogDepth = config.fogDepth();

		final int sky =
				client.getSkyboxColor();

		// Keep only the stock, user-controlled distance fog. The custom
		// sky-aware haze was removed because it washed out nearby geometry.
		float fogR = (sky >> 16 & 0xFF) / 255f;
		float fogG = (sky >> 8 & 0xFF) / 255f;
		float fogB = (sky & 0xFF) / 255f;

		glUniform1i(
				uniUseFog,
				fogDepth > 0 ? 1 : 0
		);
		currentFogR = fogR;
		currentFogG = fogG;
		currentFogB = fogB;
		currentDrawDistance = drawDistance * Perspective.LOCAL_TILE_SIZE;

		glUniform4f(
				uniFogColor,
				fogR,
				fogG,
				fogB,
				1f
		);

		glUniform1i(
				uniFogDepth,
				fogDepth
		);

		glUniform1i(
				uniDrawDistance,
				drawDistance * Perspective.LOCAL_TILE_SIZE
		);

		glUniform1i(
				uniExpandedMapLoadingChunks,
				client.getExpandedMapLoading()
		);

		glUniform1f(
				uniColorblindIntensity,
				config.colorBlindIntensity()
		);

		// =====================================================
		// Existing GPU texture settings
		// =====================================================

		TextureProvider textureProvider =
				client.getTextureProvider();

		glUniform1f(
				uniBrightness,
				(float) textureProvider.getBrightness()
		);

		glUniform1f(
				uniSmoothBanding,
				config.smoothBanding() ? 0f : 1f
		);

		glUniform1f(
				uniTextureLightMode,
				config.brightTextures() ? 1f : 0f
		);

		glUniform1i(
				uniTerrainTextureBlending,
				config.terrainTextureBlending() ? 1 : 0
		);

		glUniform1f(
				uniTerrainBlendStrength,
				config.terrainBlendStrength() / 100f
		);
		glUniform1i(uniMaterialDebugMode, config.materialDebugMode().getId());
		glUniform1i(uniMaterialLightingEnabled, config.materialLighting() ? 1 : 0);
		glUniform1f(
			uniMaterialLightingStrength,
			config.materialLightingStrength() / 100.0f
		);
		glUniform1i(
			uniDirectionalLightingEnabled,
			config.directionalLighting() ? 1 : 0
		);
		glUniform1f(
			uniDirectionalLightingStrength,
			config.directionalLightingStrength() / 100.0f
		);
		glUniform1f(
			uniEnvironmentFillStrength,
			config.environmentFillStrength() / 100.0f
		);
		glUniform1i(uniWetSurfacesEnabled, config.wetSurfaces() ? 1 : 0);
		glUniform1f(
			uniWetSurfaceStrength,
			config.wetSurfaceStrength() / 100.0f
		);

		// =====================================================
		// Enhanced colors
		// =====================================================

		glUniform1i(
				uniEnhancedColors,
				config.enhancedColors() ? 1 : 0
		);

		glUniform1f(
				uniSaturation,
				1.0f + (config.saturation() - 100) / 50.0f
		);

		glUniform1f(
				uniContrast,
				1.0f + (config.contrast() - 100) / 50.0f
		);

		// Surface color remains RuneLite's stock texture/HSL result. These shared
		// environment values are only consumed by selective shadows and explicitly
		// tagged material effects such as wet terrain.
		float[] sun = getActiveLightDirection();
		glUniform3f(
				uniLightDirection,
				sun[0],
				sun[1],
				sun[2]
		);
		glUniform3f(
				uniCameraPosition,
				cameraX,
				cameraY,
				cameraZ
		);
		glUniform1f(uniCelestialNightFactorMain, frameEnvironment.nightFactor);
		glUniform1i(
				uniEnhancedWater,
				config.enhancedWater() ? 1 : 0
		);
		glUniform1f(
				uniWaterStrength,
				config.waterStrength() / 100.0f
		);
		glUniform1f(
				uniWaterOpacity,
				config.waterOpacity() / 100.0f
		);
		WeatherMode activeWeather = config.weatherMode();
		float lightningFlash = activeWeather == WeatherMode.STORM
			? getLightningFlash(frameEnvironment.timeMillis) : 0.0f;
		glUniform1f(uniLightningFlash, lightningFlash);
		glUniform1i(uniWeatherModeMain, activeWeather.ordinal());
		glUniform1f(uniWeatherTimeMain,
			(frameEnvironment.timeMillis % 600_000L) / 1000.0f);
		glUniform1f(uniWeatherDensityMain, config.weatherDensity() / 100.0f);

		// =====================================================
		// Texture animation tick
		// =====================================================

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			glUniform1i(
					uniTick,
					client.getGameCycle() & 127
			);
		}

		// =====================================================
		// Calculate projection matrix
		// =====================================================

		float[] projectionMatrix =
				Mat4.scale(
						client.getScale(),
						client.getScale(),
						1
				);

		Mat4.mul(
				projectionMatrix,
				Mat4.projection(
						viewportWidth,
						viewportHeight,
						50
				)
		);

		Mat4.mul(
				projectionMatrix,
				Mat4.rotateX(cameraPitch)
		);

		Mat4.mul(
				projectionMatrix,
				Mat4.rotateY(cameraYaw)
		);

		Mat4.mul(
				projectionMatrix,
				Mat4.translate(
						-cameraX,
						-cameraY,
						-cameraZ
				)
		);

		glUniformMatrix4fv(
				uniWorldProj,
				false,
				projectionMatrix
		);
		System.arraycopy(projectionMatrix, 0, weatherProjection, 0, 16);
		weatherCameraX = cameraX;
		weatherCameraY = cameraY;
		weatherCameraZ = cameraZ;
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			// The client focal point uses X/Y for the ground plane and Z for
			// elevation; GPU world space uses X/Z for the ground plane and Y
			// for elevation.
			atmosphereAnchorX = client.getCameraFocalPointX();
			atmosphereAnchorY = client.getCameraFocalPointZ();
			atmosphereAnchorZ = client.getCameraFocalPointY();
		}
		else
		{
			atmosphereAnchorX = cameraX;
			atmosphereAnchorY = cameraY;
			atmosphereAnchorZ = cameraZ;
		}

		glUniformMatrix4fv(
				uniEntityProj,
				false,
				IDENTITY
		);

		glUniform4i(
				uniEntityTint,
				0,
				0,
				0,
				0
		);

		// =====================================================
		// Bind world-render uniforms/textures
		// =====================================================

		glUniformBlockBinding(
				glProgram,
				uniBlockMain,
				0
		);

		// texture sampler array is bound to texture1
		glUniform1i(
				uniTextures,
				1
		);

		// =====================================================
		// Authored material normal atlas
		// =====================================================

				glActiveTexture(GL_TEXTURE7);

				glBindTexture(
						GL_TEXTURE_2D_ARRAY,
						authoredMaterialAtlas.getNormalTextureArrayId()
				);

				glUniform1i(
						uniAuthoredMaterialNormals,
						7
				);

		// Authored albedo atlas
		glActiveTexture(GL_TEXTURE8);

		glBindTexture(
				GL_TEXTURE_2D_ARRAY,
				authoredMaterialAtlas.getAlbedoTextureArrayId()
		);

		glUniform1i(
				uniAuthoredMaterialAlbedos,
				8
		);



		// Return to RuneLite's expected active texture.
		glActiveTexture(GL_TEXTURE0);

		// Enable face culling
		glEnable(GL_CULL_FACE);

		// Enable blending
		glEnable(GL_BLEND);

		glBlendFuncSeparate(
				GL_SRC_ALPHA,
				GL_ONE_MINUS_SRC_ALPHA,
				GL_ONE,
				GL_ONE
		);

		// Enable depth testing
		glDepthFunc(GL_GREATER);
		glEnable(GL_DEPTH_TEST);

		// =====================================================
		// Draw custom sky
		// =====================================================

		surfaceShadowMapValid = false;
		atmosphereShadowMapValid = false;
		if (config.dynamicShadows())
		{
			surfaceShadowMapValid = renderLightDepthMap(
				scene,
				cameraX,
				cameraY,
				cameraZ,
				shadowFbo,
				SHADOW_MAP_SIZE,
				currentShadowLightProj,
				false
			);
		}
		if (getActiveCelestialRayStrength() > 0.0f)
		{
			boolean atmosphereRawMapValid = renderLightDepthMap(
				scene,
				atmosphereAnchorX,
				atmosphereAnchorY,
				atmosphereAnchorZ,
				atmosphereShadowFbo,
				ATMOSPHERE_SHADOW_MAP_SIZE,
				currentAtmosphereLightProj,
				true
			);
			atmosphereShadowMapValid = atmosphereRawMapValid
				&& filterAtmosphereShadowMap();
		}

// Back on normal shader now
		glUseProgram(glProgram);

		glUniformMatrix4fv(
				uniShadowLightProjMain,
				false,
				currentShadowLightProj
		);

		glUniform1i(
				uniShadowsEnabled,
				surfaceShadowMapValid ? 1 : 0
		);

		glUniform1f(
				uniShadowStrength,
				config.shadowStrength() / 100.0f
		);

		glActiveTexture(GL_TEXTURE2);

		glBindTexture(
				GL_TEXTURE_2D,
				shadowDepthTexture
		);

		glUniform1i(
				uniShadowMap,
				2
		);

		glActiveTexture(GL_TEXTURE0);

		drawSkybox(
				scene,
				sky,
				cameraX,
				cameraY,
				cameraZ,
				cameraPitch,
				cameraYaw
		);

		checkGLErrors();
	}

	private void drawSkybox(
			Scene scene,
			int sky,
			float cameraX,
			float cameraY,
			float cameraZ,
			float cameraPitch,
			float cameraYaw)
	{
		// Normal RuneLite sky color
		float skyR = ((sky >> 16) & 0xFF) / 255f;
		float skyG = ((sky >> 8) & 0xFF) / 255f;
		float skyB = (sky & 0xFF) / 255f;

		glClearColor(skyR, skyG, skyB, 1f);
		glClearDepth(0d);
		glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

		// Render our custom sky unless Custom Sky is OFF
		if (getEnvironmentSkyMode() != SkyMode.OFF)
		{
			drawCustomSky(
					client.getViewportWidth(),
					client.getViewportHeight(),
					cameraPitch,
					cameraYaw
			);
		}
		else
		{
			activeSkyTexture = 0;
		}
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			if (config.materialDebugMode() == MaterialDebugMode.OFF)
			{
				drawVolumetricLighting();
				drawWeather();
			}
			postDrawToplevel();
		}
		else
		{
			glUniform4i(uniEntityTint, 0, 0, 0, 0);
			glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
		}
	}

	@VisibleForTesting
	static float surfaceDetailSelection(float geometrySeed, int detailType)
	{
		// Density selection must not compare the geometry seed directly. Doing so
		// truncates shape/color variation whenever density is below 100%.
		// Avalanche the stable absolute-world geometry seed instead of scene-local
		// GPU coordinates, so a map rebase cannot reshuffle visible details.
		int hash = Float.floatToRawIntBits(geometrySeed)
			^ detailType * 0x27d4eb2d ^ 0x45d9f3b;
		hash ^= hash >>> 16;
		hash *= 0x7feb352d;
		hash ^= hash >>> 15;
		hash *= 0x846ca68b;
		hash ^= hash >>> 16;
		return (hash & 0x00ffffff) / 16777216.0f;
	}

	private int prepareTreeInstances(Scene scene, boolean visibleOnly)
	{
		for (TreeGpuAsset asset : loadedTreeAssets)
		{
			asset.instances.clear();
			asset.instanceCount = 0;
		}
		if (visibleOnly)
		{
			java.util.Arrays.fill(treeProfileVisibleTreesByLod, 0);
			java.util.Arrays.fill(treeProfileTrianglesByLod, 0L);
		}
		else
		{
			treeProfileShadowCasters = 0;
		}
		SceneContext ctx = context(scene);
		if (ctx == null || scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			return 0;
		}
		int offset = SCENE_OFFSET >> 3;
		int[] bands = vegetationDistanceBands();
		int total = 0;
		for (int zx = 0; zx < ctx.sizeX; ++zx)
		{
			for (int zz = 0; zz < ctx.sizeZ; ++zz)
			{
				Zone zone = ctx.zones[zx][zz];
				if (!zone.initialized || visibleOnly
					&& zone.treeReplacementVisibleFrame != grassVisibilityFrame)
				{
					continue;
				}
				float baseX = (zx - offset) * 1024.0f;
				float baseZ = (zz - offset) * 1024.0f;
				for (Zone.TreeReplacementInstance instance : zone.treeReplacements)
				{
					if (instance.definition < 0 || instance.definition >= treeLodAssets.length
						|| instance.level < ctx.minLevel || instance.level > ctx.maxLevel
						|| instance.level > ctx.level && instance.roofId > 0
							&& ctx.hideRoofIds.contains(instance.roofId))
					{
						continue;
					}
					float worldX = baseX + instance.x;
					float worldZ = baseZ + instance.z;
					float dx = worldX - weatherCameraX;
					float dz = worldZ - weatherCameraZ;
					float distanceTiles = (float) Math.sqrt(dx * dx + dz * dz)
						/ Perspective.LOCAL_TILE_SIZE;
					float staggeredDistance = distanceTiles + (instance.seed - 0.5f) * 1.5f;
					int lod;
					if (visibleOnly)
					{
						lod = vegetationLod(staggeredDistance,
							bands[0], bands[1], bands[2]);
					}
					else
					{
						// Tree shadows deliberately end at the mid boundary. Near trees
						// retain LOD0 shadows; the mid band requests a cheaper LOD2.
						if (distanceTiles >= bands[1])
						{
							continue;
						}
						lod = distanceTiles < bands[0] ? 0 : 2;
					}
					TreeGpuAsset asset = treeLodAssets[instance.definition][lod];
					if (asset == null || asset.instances.remaining() < TreeGpuAsset.INSTANCE_FLOATS)
					{
						continue;
					}
					TreeReplacementRegistry.Definition definition = asset.definition;
					float yaw = instance.orientation * (float) (Math.PI * 2.0 / 2048.0)
						+ (float) Math.toRadians(definition.rotationOffset);
					if (definition.randomYaw)
					{
						yaw += (instance.seed - 0.5f) * (float) Math.toRadians(10.0);
					}
					float scale = definition.scale * (0.95f + instance.seed * 0.10f);
					asset.instances.put(worldX).put(instance.y)
						.put(worldZ).put(yaw).put(scale).put(instance.seed);
					if (visibleOnly)
					{
						treeProfileVisibleTreesByLod[lod]++;
						treeProfileTrianglesByLod[lod] += enabledTreeTriangles(asset);
					}
					else
					{
						treeProfileShadowCasters++;
					}
					total++;
				}
			}
		}
		for (TreeGpuAsset asset : loadedTreeAssets)
		{
			asset.uploadInstances();
		}
		return total;
	}

	private long enabledTreeTriangles(TreeGpuAsset asset)
	{
		long triangles = 0;
		for (VegetationGlbMesh.Primitive primitive : asset.mesh.primitives)
		{
			boolean foliage = asset.mesh.materials[primitive.material].alphaCutoff > 0.0f;
			if (foliage ? config.renderTreeFoliage() : config.renderTreeTrunks())
			{
				triangles += primitive.indexCount / 3L;
			}
		}
		return triangles;
	}

	private int[] vegetationDistanceBands()
	{
		int near = config.vegetationNearDistance();
		int mid = Math.max(near + 1, config.vegetationMidDistance());
		int far = Math.max(mid + 1, config.vegetationFarDistance());
		// Three boundaries define four bands: near, mid, far, and extreme.
		return new int[]{near, mid, far};
	}

	@VisibleForTesting
	static int vegetationLod(float distanceTiles, int near, int mid, int far)
	{
		return distanceTiles < near ? 0
			: distanceTiles < mid ? 1 : distanceTiles < far ? 2 : 3;
	}

	private void drawTrees(Scene scene)
	{
		treeProfileVisibleTrees = 0;
		treeProfileTreeTriangles = 0;
		treeProfileFoliageTriangles = 0;
		treeProfileDrawCalls = 0;
		treeProfileFoliageDrawCalls = 0;
		pollTreeFoliageSampleQueries();
		if (!config.renderTreeFoliage())
		{
			treeProfileFoliageSamples = 0;
		}
		if (glTreeProgram == 0 || loadedTreeAssets.isEmpty())
		{
			return;
		}
		treeProfileVisibleTrees = prepareTreeInstances(scene, true);
		if (treeProfileVisibleTrees == 0)
		{
			return;
		}
		treeFoliageQueriesUsed = 0;
		treeFoliageQueryCapture = config.treeRenderProfiling()
			&& !treeFoliageQueriesPending;
		float[] light = getActiveSceneLightDirection();
		float treeTime = treeAnimationSeconds();
		float windSign = config.weatherWind() < 0 ? -1.0f : 1.0f;
		int treeOcclusionMode = updateTreeVisibilityState();
		glUseProgram(glTreeProgram);
		glUniformMatrix4fv(uniTreeProjection, false, weatherProjection);
		glUniformMatrix4fv(uniTreeShadowLightProj, false, currentShadowLightProj);
		glUniform3f(uniTreeLightDirection, light[0], light[1], light[2]);
		glUniform1i(uniTreeDirectionalLightingEnabled,
			config.directionalLighting() ? 1 : 0);
		glUniform1f(uniTreeDirectionalLightingStrength,
			config.directionalLightingStrength() / 100.0f);
		glUniform1f(uniTreeEnvironmentFillStrength,
			config.environmentFillStrength() / 100.0f);
		glUniform1f(uniTreeNightFactor, frameEnvironment.nightFactor);
		glUniform3f(uniTreeCameraPosition, treeVisibilityCameraX,
			treeVisibilityCameraY, treeVisibilityCameraZ);
		glUniform3f(uniTreePlayerPosition, treeVisibilityPlayerX,
			treeVisibilityPlayerY, treeVisibilityPlayerZ);
		glUniform1i(uniTreeOcclusionMode, treeOcclusionMode);
		glUniform1f(uniTreeBubbleRadius, treeVisibilityBubbleRadius);
		glUniform1f(uniTreeSightConeWidth, treeVisibilitySightConeWidth);
		glUniform1f(uniTreeMaximumFade, treeVisibilityMaximumFade);
		glUniform1f(uniTreeCameraTopDownFactor, treeVisibilityTopDownFactor);
		glUniform1f(uniTreeTime, treeTime);
		glUniform2f(uniTreeWindDirection, 0.82f * windSign, 0.57f * windSign);
		glUniform1f(uniTreeShadowStrength, config.shadowStrength() / 100.0f);
		glUniform1i(uniTreeShadowsEnabled,
			surfaceShadowMapValid && shadowDepthTexture != 0 ? 1 : 0);
		glUniform1i(uniTreeBaseColorTexture, 0);
		glActiveTexture(GL_TEXTURE5);
		glBindTexture(GL_TEXTURE_2D, shadowDepthTexture);
		glUniform1i(uniTreeShadowMap, 5);
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_GREATER);
		glDepthMask(true);
		glDisable(GL_BLEND);

		for (TreeGpuAsset asset : loadedTreeAssets)
		{
			if (asset == null || asset.instanceCount == 0)
			{
				continue;
			}
			glUniform1f(uniTreeFoliageTransmission,
				asset.definition.foliageTransmission);
			glUniform1f(uniTreeWindStrength, treeWindStrength(asset.definition));
			glBindVertexArray(asset.vao);
			for (VegetationGlbMesh.Primitive primitive : asset.mesh.primitives)
			{
				VegetationGlbMesh.Material material = asset.mesh.materials[primitive.material];
				boolean foliage = material.alphaCutoff > 0.0f;
				if (foliage ? !config.renderTreeFoliage() : !config.renderTreeTrunks())
				{
					continue;
				}
				bindTreeMaterial(material, asset.materialTextures[primitive.material], false);
				boolean query = foliage && treeFoliageQueryCapture
					&& treeFoliageQueriesUsed < treeFoliageSampleQueries.length;
				if (query)
				{
					glBeginQuery(GL_SAMPLES_PASSED,
						treeFoliageSampleQueries[treeFoliageQueriesUsed]);
				}
				glDrawElementsInstanced(GL_TRIANGLES, primitive.indexCount,
					GL_UNSIGNED_INT, (long) primitive.firstIndex * Integer.BYTES,
					asset.instanceCount);
				if (query)
				{
					glEndQuery(GL_SAMPLES_PASSED);
					treeFoliageQueriesUsed++;
				}
				long triangles = (long) primitive.indexCount / 3L
					* asset.instanceCount;
				treeProfileTreeTriangles += triangles;
				treeProfileDrawCalls++;
				if (foliage)
				{
					treeProfileFoliageTriangles += triangles;
					treeProfileFoliageDrawCalls++;
				}
			}
		}
		if (treeFoliageQueriesUsed > 0)
		{
			treeFoliagePendingQueryCount = treeFoliageQueriesUsed;
			treeFoliageQueriesPending = true;
		}
		glBindVertexArray(0);
		glActiveTexture(GL_TEXTURE5);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, 0);
		restoreSceneRenderState();
	}

	private void bindTreeMaterial(VegetationGlbMesh.Material material,
		int texture, boolean shadow)
	{
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, texture);
		if (material.doubleSided)
		{
			glDisable(GL_CULL_FACE);
		}
		else
		{
			glEnable(GL_CULL_FACE);
		}
		int factor = shadow ? uniTreeShadowBaseColorFactor : uniTreeBaseColorFactor;
		int cutoff = shadow ? uniTreeShadowAlphaCutoff : uniTreeAlphaCutoff;
		int hasTexture = shadow ? uniTreeShadowHasBaseColorTexture
			: uniTreeHasBaseColorTexture;
		glUniform4f(factor, material.baseColorFactor[0], material.baseColorFactor[1],
			material.baseColorFactor[2], material.baseColorFactor[3]);
		glUniform1f(cutoff, material.alphaCutoff);
		glUniform1i(hasTexture, texture != 0 ? 1 : 0);
		glUniform1f(shadow ? uniTreeShadowMaterialWindResponse
			: uniTreeMaterialWindResponse, material.windResponse);
		if (!shadow)
		{
			glUniform1i(uniTreeFoliageMaterial,
				material.alphaCutoff > 0.0f ? 1 : 0);
		}
	}

	private void drawTreeShadows(Scene scene, float[] lightProjection)
	{
		treeProfileShadowDrawCalls = 0;
		if (!config.treeShadows())
		{
			return;
		}
		if (glTreeShadowProgram == 0 || loadedTreeAssets.isEmpty()
			|| prepareTreeInstances(scene, false) == 0)
		{
			return;
		}
		glUseProgram(glTreeShadowProgram);
		glUniformMatrix4fv(uniTreeShadowProjection, false, lightProjection);
		glUniform1i(uniTreeShadowBaseColorTexture, 0);
		float windSign = config.weatherWind() < 0 ? -1.0f : 1.0f;
		glUniform1f(uniTreeShadowTime, treeAnimationSeconds());
		glUniform2f(uniTreeShadowWindDirection,
			0.82f * windSign, 0.57f * windSign);
		for (TreeGpuAsset asset : loadedTreeAssets)
		{
			if (asset == null || asset.instanceCount == 0)
			{
				continue;
			}
			glUniform1f(uniTreeShadowWindStrength,
				treeWindStrength(asset.definition));
			glBindVertexArray(asset.vao);
			for (VegetationGlbMesh.Primitive primitive : asset.mesh.primitives)
			{
				VegetationGlbMesh.Material material = asset.mesh.materials[primitive.material];
				bindTreeMaterial(material, asset.materialTextures[primitive.material], true);
				glDrawElementsInstanced(GL_TRIANGLES, primitive.indexCount,
					GL_UNSIGNED_INT, (long) primitive.firstIndex * Integer.BYTES,
					asset.instanceCount);
				treeProfileShadowDrawCalls++;
			}
		}
		glBindVertexArray(0);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private void pollTreeFoliageSampleQueries()
	{
		if (!treeFoliageQueriesPending)
		{
			return;
		}
		for (int i = 0; i < treeFoliagePendingQueryCount; ++i)
		{
			if (glGetQueryObjecti(treeFoliageSampleQueries[i],
				GL_QUERY_RESULT_AVAILABLE) == GL_FALSE)
			{
				return;
			}
		}
		long samples = 0;
		for (int i = 0; i < treeFoliagePendingQueryCount; ++i)
		{
			samples += glGetQueryObjectui64(treeFoliageSampleQueries[i],
				GL_QUERY_RESULT);
		}
		treeProfileFoliageSamples = samples;
		treeFoliagePendingQueryCount = 0;
		treeFoliageQueriesPending = false;
	}

	private float treeAnimationSeconds()
	{
		ensureFrameEnvironment();
		long now = frameEnvironment.timeMillis;
		if (treeTimeOriginMillis < 0L)
		{
			treeTimeOriginMillis = now;
		}
		return (now - treeTimeOriginMillis) / 1000.0f;
	}

	private float treeWindStrength(TreeReplacementRegistry.Definition definition)
	{
		return definition.windStrength
			* (0.85f + Math.abs(config.weatherWind()) / 100.0f * 0.60f);
	}

	private int updateTreeVisibilityState()
	{
		TreeOcclusionMode mode = config.treeOcclusion();
		// Profiles written by the old slider-based implementation can contain the
		// removed ADAPTIVE value. Fail safely to the new default preset.
		if (mode == null)
		{
			mode = TreeOcclusionMode.STRONG;
		}
		Player player = client.getLocalPlayer();
		if (mode == TreeOcclusionMode.OFF || player == null
			|| player.getLocalLocation() == null)
		{
			return TreeOcclusionMode.OFF.ordinal();
		}

		LocalPoint local = player.getLocalLocation();
		float targetPlayerX = local.getX();
		float targetPlayerZ = local.getY();
		float targetPlayerY = Perspective.getTileHeight(client, local,
			client.getPlane()) - 96.0f;
		float targetMaximumFade = mode.maximumFade;
		float targetBubbleRadius = mode.bubbleRadius;
		float targetSightConeWidth = mode.sightConeWidth;
		float targetTopDown = treeTopDownFactor(client.getCameraPitch());
		long now = frameEnvironment.timeMillis;
		float playerDx = targetPlayerX - treeVisibilityPlayerX;
		float playerDz = targetPlayerZ - treeVisibilityPlayerZ;
		boolean playerTeleported = playerDx * playerDx + playerDz * playerDz
			> 1024.0f * 1024.0f;

		if (!treeVisibilityInitialized || playerTeleported
			|| treeVisibilityUpdateMillis < 0L)
		{
			treeVisibilityCameraX = weatherCameraX;
			treeVisibilityCameraY = weatherCameraY;
			treeVisibilityCameraZ = weatherCameraZ;
			treeVisibilityPlayerX = targetPlayerX;
			treeVisibilityPlayerY = targetPlayerY;
			treeVisibilityPlayerZ = targetPlayerZ;
			treeVisibilityMaximumFade = targetMaximumFade;
			treeVisibilityTopDownFactor = targetTopDown;
			treeVisibilityBubbleRadius = targetBubbleRadius;
			treeVisibilitySightConeWidth = targetSightConeWidth;
			treeVisibilityInitialized = true;
		}
		else
		{
			float elapsed = Math.min(0.25f,
				Math.max(0.0f, (now - treeVisibilityUpdateMillis) / 1000.0f));
			float response = 1.0f - (float) Math.exp(
				-elapsed * mode.fadeSpeed);
			treeVisibilityCameraX += (weatherCameraX - treeVisibilityCameraX) * response;
			treeVisibilityCameraY += (weatherCameraY - treeVisibilityCameraY) * response;
			treeVisibilityCameraZ += (weatherCameraZ - treeVisibilityCameraZ) * response;
			treeVisibilityPlayerX += (targetPlayerX - treeVisibilityPlayerX) * response;
			treeVisibilityPlayerY += (targetPlayerY - treeVisibilityPlayerY) * response;
			treeVisibilityPlayerZ += (targetPlayerZ - treeVisibilityPlayerZ) * response;
			treeVisibilityMaximumFade +=
				(targetMaximumFade - treeVisibilityMaximumFade) * response;
			treeVisibilityTopDownFactor +=
				(targetTopDown - treeVisibilityTopDownFactor) * response;
			treeVisibilityBubbleRadius +=
				(targetBubbleRadius - treeVisibilityBubbleRadius) * response;
			treeVisibilitySightConeWidth +=
				(targetSightConeWidth - treeVisibilitySightConeWidth) * response;
		}
		treeVisibilityUpdateMillis = now;
		return mode.ordinal();
	}

	@VisibleForTesting
	static float treeTopDownFactor(int cameraPitch)
	{
		float normalized = Math.max(0.0f, Math.min(1.0f,
			(cameraPitch - 128.0f) / 255.0f));
		return normalized * normalized * (3.0f - 2.0f * normalized);
	}

	private void drawTreeOcclusionDebug()
	{
		if (glGrassDebugProgram == 0 || vaoGrassDebugHandle == 0
			|| vboGrassDebugHandle == 0)
		{
			return;
		}

		float bubbleRadius = treeVisibilityBubbleRadius;
		float viewX = treeVisibilityPlayerX - treeVisibilityCameraX;
		float viewY = treeVisibilityPlayerY - treeVisibilityCameraY;
		float viewZ = treeVisibilityPlayerZ - treeVisibilityCameraZ;
		float viewLength = (float) Math.sqrt(
			viewX * viewX + viewY * viewY + viewZ * viewZ);
		if (viewLength < 0.001f)
		{
			return;
		}
		viewX /= viewLength;
		viewY /= viewLength;
		viewZ /= viewLength;
		float rightX = -viewZ;
		float rightY = 0.0f;
		float rightZ = viewX;
		float rightLength = (float) Math.sqrt(rightX * rightX + rightZ * rightZ);
		if (rightLength < 0.001f)
		{
			rightX = 1.0f;
			rightZ = 0.0f;
		}
		else
		{
			rightX /= rightLength;
			rightZ /= rightLength;
		}
		float upX = viewY * rightZ - viewZ * rightY;
		float upY = viewZ * rightX - viewX * rightZ;
		float upZ = viewX * rightY - viewY * rightX;

		grassDebugBuffer.clear();
		final int bubbleSections = 16;
		for (int section = 0; section < bubbleSections; ++section)
		{
			float angle0 = (float) (section * Math.PI * 2.0 / bubbleSections);
			float angle1 = (float) ((section + 1) * Math.PI * 2.0 / bubbleSections);
			putDebugLine(
				treeVisibilityPlayerX + (float) Math.cos(angle0) * bubbleRadius,
				treeVisibilityPlayerY,
				treeVisibilityPlayerZ + (float) Math.sin(angle0) * bubbleRadius,
				treeVisibilityPlayerX + (float) Math.cos(angle1) * bubbleRadius,
				treeVisibilityPlayerY,
				treeVisibilityPlayerZ + (float) Math.sin(angle1) * bubbleRadius);
			float vertical0 = (float) Math.sin(angle0);
			float vertical1 = (float) Math.sin(angle1);
			float height0 = vertical0 < 0.0f
				? bubbleRadius * 4.5f : bubbleRadius * 1.3f;
			float height1 = vertical1 < 0.0f
				? bubbleRadius * 4.5f : bubbleRadius * 1.3f;
			putDebugLine(
				treeVisibilityPlayerX + rightX * (float) Math.cos(angle0) * bubbleRadius,
				treeVisibilityPlayerY + vertical0 * height0,
				treeVisibilityPlayerZ + rightZ * (float) Math.cos(angle0) * bubbleRadius,
				treeVisibilityPlayerX + rightX * (float) Math.cos(angle1) * bubbleRadius,
				treeVisibilityPlayerY + vertical1 * height1,
				treeVisibilityPlayerZ + rightZ * (float) Math.cos(angle1) * bubbleRadius);
		}
		drawTreeOcclusionDebugLines(bubbleSections * 4, 0.05f, 1.0f, 1.0f);

		float extension = Perspective.LOCAL_TILE_SIZE;
		float endX = treeVisibilityPlayerX + viewX * extension;
		float endY = treeVisibilityPlayerY + viewY * extension;
		float endZ = treeVisibilityPlayerZ + viewZ * extension;
		float width = treeVisibilitySightConeWidth;
		float[] centersX = {treeVisibilityCameraX, treeVisibilityPlayerX, endX};
		float[] centersY = {treeVisibilityCameraY, treeVisibilityPlayerY, endY};
		float[] centersZ = {treeVisibilityCameraZ, treeVisibilityPlayerZ, endZ};
		float[] radii = {width * 0.65f, width, width};
		grassDebugBuffer.clear();
		final int coneSections = 12;
		for (int ring = 0; ring < centersX.length; ++ring)
		{
			for (int section = 0; section < coneSections; ++section)
			{
				float angle0 = (float) (section * Math.PI * 2.0 / coneSections);
				float angle1 = (float) ((section + 1) * Math.PI * 2.0 / coneSections);
				putDebugLine(
					centersX[ring] + (rightX * (float) Math.cos(angle0)
						+ upX * (float) Math.sin(angle0)) * radii[ring],
					centersY[ring] + (rightY * (float) Math.cos(angle0)
						+ upY * (float) Math.sin(angle0)) * radii[ring],
					centersZ[ring] + (rightZ * (float) Math.cos(angle0)
						+ upZ * (float) Math.sin(angle0)) * radii[ring],
					centersX[ring] + (rightX * (float) Math.cos(angle1)
						+ upX * (float) Math.sin(angle1)) * radii[ring],
					centersY[ring] + (rightY * (float) Math.cos(angle1)
						+ upY * (float) Math.sin(angle1)) * radii[ring],
					centersZ[ring] + (rightZ * (float) Math.cos(angle1)
						+ upZ * (float) Math.sin(angle1)) * radii[ring]);
			}
		}
		for (int section = 0; section < coneSections; section += 3)
		{
			float angle = (float) (section * Math.PI * 2.0 / coneSections);
			float edgeX = rightX * (float) Math.cos(angle) + upX * (float) Math.sin(angle);
			float edgeY = rightY * (float) Math.cos(angle) + upY * (float) Math.sin(angle);
			float edgeZ = rightZ * (float) Math.cos(angle) + upZ * (float) Math.sin(angle);
			for (int segment = 0; segment < 2; ++segment)
			{
				putDebugLine(
					centersX[segment] + edgeX * radii[segment],
					centersY[segment] + edgeY * radii[segment],
					centersZ[segment] + edgeZ * radii[segment],
					centersX[segment + 1] + edgeX * radii[segment + 1],
					centersY[segment + 1] + edgeY * radii[segment + 1],
					centersZ[segment + 1] + edgeZ * radii[segment + 1]);
			}
		}
		drawTreeOcclusionDebugLines(coneSections * 6 + 16,
			1.0f, 0.08f, 0.82f);
	}

	private void putDebugLine(float x0, float y0, float z0,
		float x1, float y1, float z1)
	{
		putDebugVertex(x0, y0, z0);
		putDebugVertex(x1, y1, z1);
	}

	private void drawTreeOcclusionDebugLines(int vertexCount,
		float red, float green, float blue)
	{
		grassDebugBuffer.flip();
		glUseProgram(glGrassDebugProgram);
		glUniformMatrix4fv(uniGrassDebugProjection, false, weatherProjection);
		glUniform3f(uniGrassDebugBaseCenter, 0.0f, 0.0f, 0.0f);
		glUniform1f(uniGrassDebugInstanceSpacing, 0.0f);
		glUniform3f(uniGrassDebugColor, red, green, blue);
		glBindBuffer(GL_ARRAY_BUFFER, vboGrassDebugHandle);
		glBufferSubData(GL_ARRAY_BUFFER, 0, grassDebugBuffer);
		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_CULL_FACE);
		glDisable(GL_BLEND);
		glBindVertexArray(vaoGrassDebugHandle);
		glDrawArrays(GL_LINES, 0, vertexCount);
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glDepthMask(true);
		restoreSceneRenderState();
	}

	private void drawSurfaceDetails(Scene scene)
	{
		treeProfileGrassInstances = 0;
		treeProfileGrassTriangles = 0;
		treeProfileGrassDrawCalls = 0;
		java.util.Arrays.fill(treeProfileGrassByBand, 0);
		GrassDebugMode debugMode = config.grassDebugMode();
		boolean rootMarkerDebug = debugMode == GrassDebugMode.GLB_ROOT_MARKERS;
		if (debugMode != GrassDebugMode.OFF && !rootMarkerDebug)
		{
			if (debugMode == GrassDebugMode.GLB_LINE)
			{
				drawGlbGrassLineDebug();
				return;
			}
			if (debugMode == GrassDebugMode.GLB_TERRAIN)
			{
				drawGlbGrassTerrainDebug(scene);
				return;
			}
			if (debugMode == GrassDebugMode.GLB_TERRAIN_ALL)
			{
				drawGlbGrassAllTerrainDebug(scene);
				return;
			}
			if (debugMode == GrassDebugMode.GLB_CLUMP)
			{
				drawGlbGrassSimpleDebug(scene, true);
				return;
			}
			if (debugMode == GrassDebugMode.GLB_CLUMP_NO_DEPTH)
			{
				drawGlbGrassSimpleDebug(scene, false);
				return;
			}
			if (debugMode == GrassDebugMode.GREEN_TERRAIN)
			{
				drawTerrainGrassDebug(scene);
				return;
			}
			if (debugMode == GrassDebugMode.GREEN_INSTANCED_LINE)
			{
				drawGrassInstancedDebug();
				return;
			}
			drawGrassDebugQuad();
			return;
		}
		boolean grassEnabled = config.flowingGrass() || rootMarkerDebug;
		if ((!grassEnabled && !config.terrainDetail())
			|| glGrassProgram == 0
			|| vaoGrassHandle == 0
			|| vboGrassInstanceHandle == 0)
		{
			return;
		}

		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		int[] vegetationBands = vegetationDistanceBands();
		float grassRadius = vegetationBands[2]
			* (float) Perspective.LOCAL_TILE_SIZE;
		float stoneRadius = Math.min(config.terrainDetailDistance(), 18)
			* (float) Perspective.LOCAL_TILE_SIZE;
		float scatterRadius = Math.min(stoneRadius,
			9.0f * Perspective.LOCAL_TILE_SIZE);
		float maximumRadius = Math.max(
			grassEnabled ? grassRadius : 0.0f,
			config.terrainDetail() ? stoneRadius : 0.0f);
		float grassDensity = config.grassDensity() / 100.0f;
		float detailDensity = config.terrainDetailStrength() / 100.0f;
		int offset = SCENE_OFFSET >> 3;
		int minimumLevel = Math.max(0, ctx.minLevel);
		int maximumLevel = Math.min(3, ctx.maxLevel);
		int instanceCount = 0;
		int grassInstanceCount = 0;
		int eligibleTiles = 0;
		int eligibleTriangles = 0;
		grassInstanceBuffer.clear();

		gather:
		for (int zx = 0; zx < ctx.sizeX; ++zx)
		{
			for (int zz = 0; zz < ctx.sizeZ; ++zz)
			{
				Zone zone = ctx.zones[zx][zz];
				if (!zone.initialized
					|| zone.surfaceDetailVisibleFrame != grassVisibilityFrame
					|| zone.surfaceDetailAnchors.length == 0)
				{
					continue;
				}
				eligibleTiles += zone.surfaceDetailEligibleTiles;
				eligibleTriangles += zone.surfaceDetailEligibleTriangles;

				float baseX = (zx - offset) << 10;
				float baseZ = (zz - offset) << 10;
				float zoneCenterX = baseX + 512.0f;
				float zoneCenterZ = baseZ + 512.0f;
				float zoneCullRadius = maximumRadius + 724.0f;
				// Grass anchors and the world projection use the camera-local coordinate
				// space. Use the same camera origin for culling; the atmosphere focal
				// anchor may be in a different (focal/light-map) space.
				float zoneDx = zoneCenterX - weatherCameraX;
				float zoneDz = zoneCenterZ - weatherCameraZ;
				if (zoneDx * zoneDx + zoneDz * zoneDz
					> zoneCullRadius * zoneCullRadius)
				{
					continue;
				}

				for (int level = minimumLevel; level <= maximumLevel; ++level)
				{
					int start = level == 0 ? 0
						: zone.surfaceDetailLevelOffsets[level - 1];
					int end = Math.min(zone.surfaceDetailLevelOffsets[level],
						zone.surfaceDetailAnchors.length);
					for (int anchor = start; anchor + 5 < end;
						anchor += SURFACE_DETAIL_INSTANCE_FLOATS)
					{
						float seed = zone.surfaceDetailAnchors[anchor + 3];
						int detailType = Math.max(0, Math.min(3,
							Math.round(zone.surfaceDetailAnchors[anchor + 5])));
						boolean grassDetail = detailType == 0;
						boolean enabled = grassDetail
							? grassEnabled : config.terrainDetail();
						float worldX = baseX + zone.surfaceDetailAnchors[anchor];
						float worldZ = baseZ + zone.surfaceDetailAnchors[anchor + 2];
						float density = detailType == 0 ? grassDensity
							: detailType == 1 ? detailDensity * 0.82f
								: detailType == 2 ? detailDensity * 0.38f
									: detailDensity * 0.25f;
						float drawRadius = detailType <= 1
							? (grassDetail ? grassRadius : stoneRadius)
							: scatterRadius;
						if (!enabled)
						{
							continue;
						}

						float dx = worldX - weatherCameraX;
						float dz = worldZ - weatherCameraZ;
						float distanceTiles = (float) Math.sqrt(dx * dx + dz * dz)
							/ Perspective.LOCAL_TILE_SIZE;
						int distanceBand = vegetationLod(distanceTiles,
							vegetationBands[0], vegetationBands[1], vegetationBands[2]);
						if (grassDetail)
						{
							density *= grassDensityMultiplier(distanceTiles,
								vegetationBands[0], vegetationBands[1], vegetationBands[2]);
						}
						if (density <= 0.0f
							|| surfaceDetailSelection(seed, detailType) > density)
						{
							continue;
						}
						if (dx * dx + dz * dz > drawRadius * drawRadius)
						{
							continue;
						}

						grassInstanceBuffer.put(worldX);
						grassInstanceBuffer.put(zone.surfaceDetailAnchors[anchor + 1]);
						grassInstanceBuffer.put(worldZ);
						grassInstanceBuffer.put(seed);
						grassInstanceBuffer.put(zone.surfaceDetailAnchors[anchor + 4]);
						grassInstanceBuffer.put(zone.surfaceDetailAnchors[anchor + 5]);
						if (grassDetail)
						{
							grassInstanceCount++;
							treeProfileGrassByBand[distanceBand]++;
						}
						if (++instanceCount >= MAX_SURFACE_DETAIL_INSTANCES)
						{
							break gather;
						}
					}
				}
			}
		}

		if (instanceCount == 0)
		{
			logGrassTerrainDiagnostics(ctx, 0, 0);
			if (!grassPocStatusLogged && config.flowingGrass())
			{
				log.info("Flowing grass placement: eligibleTiles={}, eligibleTriangles={}, instances=0",
					eligibleTiles, eligibleTriangles);
				grassPocStatusLogged = true;
			}
			return;
		}
		if (glGrassDebugProgram != 0)
		{
			glDeleteProgram(glGrassDebugProgram);
			glGrassDebugProgram = 0;
		}
		if (!grassPocStatusLogged)
		{
			log.info("Flowing grass placement: eligibleTiles={}, eligibleTriangles={}, instances={}",
				eligibleTiles, eligibleTriangles, instanceCount);
			grassPocStatusLogged = true;
		}

		grassInstanceBuffer.flip();
		glBindBuffer(GL_ARRAY_BUFFER, vboGrassInstanceHandle);
		glBufferData(GL_ARRAY_BUFFER,
			(long) MAX_SURFACE_DETAIL_INSTANCES
				* SURFACE_DETAIL_INSTANCE_FLOATS * Float.BYTES,
			GL_STREAM_DRAW);
		glBufferSubData(GL_ARRAY_BUFFER, 0, grassInstanceBuffer);
		if (rootMarkerDebug)
		{
			drawGrassRootMarkers(instanceCount);
		}
		long now = frameEnvironment.timeMillis;
		if (grassTimeOriginMillis < 0L)
		{
			grassTimeOriginMillis = now;
		}
		WeatherMode weather = config.weatherMode();
		// Procedural grass has no stock RuneLite vertex color, so it keeps a small
		// material-local wrapped light response derived from the shared environment.
		// This does not relight any existing world surface.
		float direct = 0.55f + (0.16f - 0.55f) * frameEnvironment.nightFactor;
		float ambient = 0.46f + (0.30f - 0.46f) * frameEnvironment.nightFactor;
		if (weather == WeatherMode.STORM)
		{
			direct *= 0.35f;
			ambient *= 0.82f;
		}
		else if (weather == WeatherMode.BLIZZARD)
		{
			direct *= 0.25f;
			ambient *= 0.88f;
		}
		else if (weather == WeatherMode.RAIN || weather == WeatherMode.SNOW)
		{
			direct *= 0.72f;
		}
		float wind = config.grassWindStrength() / 100.0f
			* (0.90f + Math.abs(config.weatherWind()) / 100.0f * 0.35f);
		if (config.weatherWind() < 0)
		{
			wind = -wind;
		}
		float[] lightDirection = getActiveSceneLightDirection();
		boolean grassShadowMapValid = surfaceShadowMapValid
			&& shadowDepthTexture != 0;
		if (vaoGrassGlbHandle != 0 && glGrassGlbProgram != 0 && grassGlbIndexCount > 0)
		{
			drawGrassGlb(instanceCount, grassRadius, weather, wind,
				lightDirection, grassShadowMapValid, false, true);
			treeProfileGrassInstances = grassInstanceCount;
			treeProfileGrassTriangles = (long) grassGlbIndexCount / 3L
				* grassInstanceCount;
			treeProfileGrassDrawCalls = 1;
			logGrassTerrainDiagnostics(ctx, grassInstanceCount, grassInstanceCount);
			return;
		}

		glUseProgram(glGrassProgram);
		glUniformMatrix4fv(uniGrassProjection, false, weatherProjection);
		glUniform3f(uniGrassCamera, weatherCameraX, weatherCameraY, weatherCameraZ);
		glUniform3f(uniGrassFocus, weatherCameraX, weatherCameraY, weatherCameraZ);
		glUniform2f(uniGrassWorldOffset,
			scene.getBaseX() * (float) Perspective.LOCAL_TILE_SIZE,
			scene.getBaseY() * (float) Perspective.LOCAL_TILE_SIZE);
		glUniform1f(uniGrassTime,
			(now - grassTimeOriginMillis) / 1000.0f);
		glUniform4f(uniGrassDrawRadius,
			grassRadius, stoneRadius, scatterRadius, scatterRadius);
		glUniform1f(uniGrassHeightScale, 1.0f);
		glUniform1f(uniGrassWindStrength, wind);
		glUniform1i(uniGrassWeatherModeVert, weather.ordinal());
		glUniform3f(uniGrassLightDirection,
			lightDirection[0], lightDirection[1], lightDirection[2]);
		glUniform1f(uniGrassLightIntensity, direct);
		glUniform1f(uniGrassAmbientLight, ambient);
		glUniform1f(uniGrassLightningFlash,
			weather == WeatherMode.STORM ? getLightningFlash(now) : 0.0f);
		glUniform1i(uniGrassWeatherModeFrag, weather.ordinal());
		glUniform1f(uniGrassNightFactor, frameEnvironment.nightFactor);
		glUniform1f(uniGrassBrightness,
			(float) client.getTextureProvider().getBrightness());
		glUniform1i(uniGrassEnhancedColors,
			config.enhancedColors() ? 1 : 0);
		glUniform1f(uniGrassSaturation,
			1.0f + (config.saturation() - 100) / 50.0f);
		glUniform1f(uniGrassContrast,
			1.0f + (config.contrast() - 100) / 50.0f);
		glUniform4f(uniGrassFogColor,
			currentFogR, currentFogG, currentFogB, 1.0f);
		glUniformMatrix4fv(uniGrassShadowLightProj,
			false, currentShadowLightProj);
		glUniform1i(uniGrassShadowsEnabled,
			grassShadowMapValid ? 1 : 0);
		glUniform1f(uniGrassShadowStrength,
			config.shadowStrength() / 100.0f);
		glUniform1i(uniGrassMaterialDebugMode, config.materialDebugMode().getId());
		glUniform1i(uniGrassMaterialLightingEnabled,
			config.materialLighting() ? 1 : 0);
		glUniform1f(uniGrassMaterialLightingStrength,
			config.materialLightingStrength() / 100.0f);
		glUniform1i(uniGrassWetSurfacesEnabled, config.wetSurfaces() ? 1 : 0);
		glUniform1f(uniGrassWetSurfaceStrength,
			config.wetSurfaceStrength() / 100.0f);
		glActiveTexture(GL_TEXTURE5);
		glBindTexture(GL_TEXTURE_2D, shadowDepthTexture);
		glUniform1i(uniGrassShadowMap, 5);

		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_GREATER);
		glDepthMask(true);
		glDisable(GL_CULL_FACE);
		glDisable(GL_BLEND);
		glBindVertexArray(vaoGrassHandle);
		glDrawArraysInstanced(GL_TRIANGLES, 0,
			SURFACE_DETAIL_VERTICES_PER_INSTANCE, instanceCount);
		treeProfileGrassInstances = grassInstanceCount;
		treeProfileGrassTriangles = (long) SURFACE_DETAIL_VERTICES_PER_INSTANCE
			/ 3L * grassInstanceCount;
		treeProfileGrassDrawCalls = 1;
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glActiveTexture(GL_TEXTURE5);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
		restoreSceneRenderState();
		logGrassTerrainDiagnostics(ctx, grassInstanceCount, grassInstanceCount);
	}

	@VisibleForTesting
	static float grassDensityMultiplier(float distanceTiles,
		int near, int mid, int far)
	{
		if (distanceTiles <= near)
		{
			return 1.0f;
		}
		if (distanceTiles < mid)
		{
			float t = (distanceTiles - near) / Math.max(1.0f, mid - near);
			t = t * t * (3.0f - 2.0f * t);
			return 1.0f + (0.5f - 1.0f) * t;
		}
		if (distanceTiles < far)
		{
			float t = (distanceTiles - mid) / Math.max(1.0f, far - mid);
			return 0.5f * (1.0f - t) * (1.0f - t);
		}
		return 0.0f;
	}

	private void logTreeRenderProfile()
	{
		if (!config.treeRenderProfiling())
		{
			return;
		}
		long now = frameEnvironment.timeMillis;
		if (now - treeProfileLastLogMillis < 1000L)
		{
			return;
		}
		treeProfileLastLogMillis = now;
		int activeAssets = 0;
		int sharedTextures = 0;
		for (TreeGpuAsset asset : loadedTreeAssets)
		{
			if (asset != null)
			{
				activeAssets++;
				for (int texture : asset.materialTextures)
				{
					if (texture != 0)
					{
						sharedTextures++;
					}
				}
			}
		}
		long viewportPixels = Math.max(1L,
			(long) client.getViewportWidth() * client.getViewportHeight());
		double samplesPerPixel = treeProfileFoliageSamples
			/ (double) viewportPixels;
		long combinedTriangles = treeProfileTreeTriangles
			+ treeProfileGrassTriangles;
		log.info("Vegetation LOD profile (last frame): visibleTrees={} byLod={}, "
			+ "treeTriangles={} byLod={}, "
			+ "treeDrawCalls={} (foliage={}), foliageTriangles={}, "
			+ "shadowTreeDrawCalls={}, foliageSamplesPassed={} (~{}x viewport; "
			+ "lower bound, discarded/failed-depth fragments excluded), "
			+ "grassInstances={} byBand={}, grassTriangles={}, grassDrawCalls={}, "
			+ "vegetationShadowCasters={}, "
			+ "combinedTreeGrassTriangles={}, sharedGeometry=true "
			+ "(gpuAssets={}, sharedVboIboPairs={}, sharedTextures={})",
			treeProfileVisibleTrees,
			java.util.Arrays.toString(treeProfileVisibleTreesByLod),
			treeProfileTreeTriangles,
			java.util.Arrays.toString(treeProfileTrianglesByLod),
			treeProfileDrawCalls, treeProfileFoliageDrawCalls,
			treeProfileFoliageTriangles, treeProfileShadowDrawCalls,
			treeProfileFoliageSamples,
			Math.round(samplesPerPixel * 100.0) / 100.0,
			treeProfileGrassInstances,
			java.util.Arrays.toString(treeProfileGrassByBand),
			treeProfileGrassTriangles, treeProfileGrassDrawCalls,
			treeProfileShadowCasters, combinedTriangles,
			activeAssets, activeAssets, sharedTextures);
	}

	private void drawGrassGlb(int instanceCount, float grassRadius,
		WeatherMode weather, float wind, float[] lightDirection,
		boolean shadowMapValid, boolean debug, boolean instanced)
	{
		long now = frameEnvironment.timeMillis;
		glUseProgram(glGrassGlbProgram);
		glUniformMatrix4fv(uniGrassGlbProjection, false, weatherProjection);
		glUniform3f(uniGrassGlbCamera, weatherCameraX, weatherCameraY, weatherCameraZ);
		glUniform3f(uniGrassGlbFocus, weatherCameraX, weatherCameraY, weatherCameraZ);
		glUniform2f(uniGrassGlbWorldOffset, 0.0f, 0.0f);
		glUniform1f(uniGrassGlbTime,
			grassTimeOriginMillis < 0L ? 0.0f
				: (now - grassTimeOriginMillis) / 1000.0f);
		glUniform4f(uniGrassGlbDrawRadius, grassRadius, 0.0f, 0.0f, 0.0f);
		glUniform1f(uniGrassGlbHeightScale, 1.0f);
		glUniform1f(uniGrassGlbWindStrength, wind);
		glUniform1i(uniGrassGlbWeatherMode, weather.ordinal());
		glUniform3f(uniGrassGlbLightDirection,
			lightDirection[0], lightDirection[1], lightDirection[2]);
		glUniform1f(uniGrassGlbLightIntensity, 0.55f);
		glUniform1f(uniGrassGlbAmbientLight, 0.46f);
		glUniform4f(uniGrassGlbFogColor, currentFogR, currentFogG, currentFogB, 1.0f);
		glUniform1f(uniGrassGlbNightFactor, frameEnvironment.nightFactor);
		glUniformMatrix4fv(uniGrassGlbShadowLightProj, false, currentShadowLightProj);
		glUniform1i(uniGrassGlbShadowsEnabled, shadowMapValid ? 1 : 0);
		glUniform1f(uniGrassGlbShadowStrength, config.shadowStrength() / 100.0f);
		glUniform1i(uniGrassGlbMaterialLightingEnabled,
			config.materialLighting() ? 1 : 0);
		glUniform1f(uniGrassGlbMaterialLightingStrength,
			config.materialLightingStrength() / 100.0f);
		glUniform1i(uniGrassGlbDebugMode, debug ? 1 : 0);
		glActiveTexture(GL_TEXTURE5);
		glBindTexture(GL_TEXTURE_2D, shadowDepthTexture);
		glUniform1i(uniGrassGlbShadowMap, 5);
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_GREATER);
		glDepthMask(true);
		glDisable(GL_CULL_FACE);
		glDisable(GL_BLEND);
		glBindVertexArray(vaoGrassGlbHandle);
		if (!instanced)
		{
			glDrawElements(GL_TRIANGLES, grassGlbIndexCount, GL_UNSIGNED_INT, 0L);
		}
		else
		{
			glDrawElementsInstanced(GL_TRIANGLES, grassGlbIndexCount,
				GL_UNSIGNED_INT, 0L, instanceCount);
		}
		glBindVertexArray(0);
		glActiveTexture(GL_TEXTURE5);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
		restoreSceneRenderState();
	}

	private void logGrassTerrainDiagnostics(SceneContext ctx,
		int uploadedGrassInstances, int submittedGrassInstances)
	{
		if (!grassDiagnosticsPending || ctx == null)
		{
			return;
		}
		int tilesScanned = 0;
		int tilesWithUnderlay = 0;
		int tilesWithOverlay = 0;
		int vegetationTiles = 0;
		int eligibleTriangles = 0;
		int rootsGenerated = 0;
		for (Zone[] row : ctx.zones)
		{
			for (Zone zone : row)
			{
				if (!zone.initialized)
				{
					continue;
				}
				tilesScanned += zone.surfaceDetailTilesScanned;
				tilesWithUnderlay += zone.surfaceDetailTilesWithUnderlay;
				tilesWithOverlay += zone.surfaceDetailTilesWithOverlay;
				vegetationTiles += zone.surfaceDetailVegetationTiles;
				eligibleTriangles += zone.surfaceDetailEligibleTriangles;
				rootsGenerated += zone.surfaceDetailGrassRoots;
			}
		}
		log.info("Grass terrain diagnostics rebuild={}: scene tiles scanned={}, "
			+ "tiles with underlay={}, tiles with overlay={}, "
			+ "tiles resolved as vegetation-enabled={}, eligible terrain triangles={}, "
			+ "root positions generated={}, grass instances uploaded={}, "
			+ "grass instances submitted to draw={}, configured Lumbridge grass underlays=[46-50, 59-64]",
			grassDiagnosticsGeneration, tilesScanned, tilesWithUnderlay,
			tilesWithOverlay, vegetationTiles, eligibleTriangles, rootsGenerated,
			uploadedGrassInstances, submittedGrassInstances);
		grassDiagnosticsPending = false;
	}

	private void drawGlbGrassDebug(Scene scene)
	{
		if (vaoGrassGlbHandle == 0 || glGrassGlbProgram == 0 || grassGlbIndexCount == 0)
		{
			return;
		}
		Player player = client.getLocalPlayer();
		if (player == null || player.getLocalLocation() == null)
		{
			return;
		}
		LocalPoint local = player.getLocalLocation();
		float debugAnchorY = Perspective.getTileHeight(client, local, client.getPlane());
		grassInstanceBuffer.clear();
		// Stage A is deliberately independent of terrain filtering: place one
		// complete clump beside the player on the current tile.
		grassInstanceBuffer.put(local.getX() + 128.0f);
		grassInstanceBuffer.put(debugAnchorY);
		grassInstanceBuffer.put(local.getY());
		grassInstanceBuffer.put(0.5f);
		grassInstanceBuffer.put(0.0f);
		grassInstanceBuffer.put(0.0f);
		grassInstanceBuffer.flip();
		glBindBuffer(GL_ARRAY_BUFFER, vboGrassInstanceHandle);
		glBufferSubData(GL_ARRAY_BUFFER, 0, grassInstanceBuffer);
		float[] lightDirection = getActiveSceneLightDirection();
		drawGrassGlb(1, 100000.0f, config.weatherMode(), 0.0f,
			lightDirection, false, true, false);
		if (!grassDebugDrawLogged)
		{
			log.info("GLB grass Stage A final world height: min={} max={} (anchor=root)",
				debugAnchorY - 64.0f, debugAnchorY);
			grassDebugDrawLogged = true;
		}
	}

	private void drawGlbGrassLineDebug()
	{
		if (vaoGrassGlbHandle == 0 || glGrassGlbProgram == 0
			|| grassGlbIndexCount == 0 || vboGrassInstanceHandle == 0)
		{
			return;
		}
		Player player = client.getLocalPlayer();
		if (player == null || player.getLocalLocation() == null)
		{
			return;
		}
		LocalPoint local = player.getLocalLocation();
		float anchorY = Perspective.getTileHeight(client, local, client.getPlane());
		final int instances = 10;
		grassInstanceBuffer.clear();
		for (int i = 0; i < instances; i++)
		{
			// Fixed scene-space line for an unambiguous instancing proof. The GLB
			// shader's debug mode disables yaw, scale variation and wind.
			grassInstanceBuffer.put(local.getX() + 128.0f + i * 96.0f);
			grassInstanceBuffer.put(anchorY);
			grassInstanceBuffer.put(local.getY());
			grassInstanceBuffer.put(0.5f + i * 0.01f);
			grassInstanceBuffer.put(0.0f);
			grassInstanceBuffer.put(0.0f);
		}
		grassInstanceBuffer.flip();
		glBindBuffer(GL_ARRAY_BUFFER, vboGrassInstanceHandle);
		glBufferSubData(GL_ARRAY_BUFFER, 0, grassInstanceBuffer);
		drawGrassGlb(instances, 100000.0f, config.weatherMode(), 0.0f,
			getActiveSceneLightDirection(), false, true, true);
		if (!grassDebugDrawLogged)
		{
			log.info("GLB grass Stage B: vertices={}, instances=10",
				grassGlbIndexCount, instances);
			grassDebugDrawLogged = true;
		}
	}

	private void drawGlbGrassTerrainDebug(Scene scene)
	{
		if (vaoGrassGlbHandle == 0 || glGrassGlbProgram == 0
			|| grassGlbIndexCount == 0 || vboGrassInstanceHandle == 0)
		{
			return;
		}
		SceneContext ctx = context(scene);
		Player player = client.getLocalPlayer();
		if (ctx == null || player == null || player.getLocalLocation() == null)
		{
			return;
		}
		LocalPoint local = player.getLocalLocation();
		float bestDistance = Float.POSITIVE_INFINITY;
		float bestX = 0.0f;
		float bestY = 0.0f;
		float bestZ = 0.0f;
		int offset = SCENE_OFFSET >> 3;
		for (int zx = 0; zx < ctx.sizeX; ++zx)
		{
			for (int zz = 0; zz < ctx.sizeZ; ++zz)
			{
				Zone zone = ctx.zones[zx][zz];
				if (!zone.initialized
					|| zone.surfaceDetailVisibleFrame != grassVisibilityFrame)
				{
					continue;
				}
				int end = Math.min(zone.surfaceDetailLevelOffsets[0],
					zone.surfaceDetailAnchors.length);
				float baseX = (zx - offset) << 10;
				float baseZ = (zz - offset) << 10;
				for (int anchor = 0; anchor + 5 < end;
					anchor += SURFACE_DETAIL_INSTANCE_FLOATS)
				{
					if (Math.round(zone.surfaceDetailAnchors[anchor + 5]) != 0)
					{
						continue;
					}
					float x = baseX + zone.surfaceDetailAnchors[anchor];
					float z = baseZ + zone.surfaceDetailAnchors[anchor + 2];
					float dx = x - local.getX();
					float dz = z - local.getY();
					float distance = dx * dx + dz * dz;
					if (distance < bestDistance)
					{
						bestDistance = distance;
						bestX = x;
						bestY = zone.surfaceDetailAnchors[anchor + 1];
						bestZ = z;
					}
				}
			}
		}
		if (!Float.isFinite(bestDistance))
		{
			return;
		}
		grassInstanceBuffer.clear();
		grassInstanceBuffer.put(bestX);
		grassInstanceBuffer.put(bestY);
		grassInstanceBuffer.put(bestZ);
		grassInstanceBuffer.put(0.5f);
		grassInstanceBuffer.put(0.0f);
		grassInstanceBuffer.put(0.0f);
		grassInstanceBuffer.flip();
		glBindBuffer(GL_ARRAY_BUFFER, vboGrassInstanceHandle);
		glBufferSubData(GL_ARRAY_BUFFER, 0, grassInstanceBuffer);
		drawGrassGlb(1, 100000.0f, config.weatherMode(), 0.0f,
			getActiveSceneLightDirection(), false, true, true);
		if (!grassDebugDrawLogged)
		{
			log.info("GLB grass Stage C: one terrain anchor at ({}, {}, {})",
				bestX, bestY, bestZ);
			grassDebugDrawLogged = true;
		}
	}

	private void drawGlbGrassAllTerrainDebug(Scene scene)
	{
		if (vaoGrassGlbHandle == 0 || glGrassGlbProgram == 0
			|| grassGlbIndexCount == 0 || vboGrassInstanceHandle == 0)
		{
			return;
		}
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}
		int offset = SCENE_OFFSET >> 3;
		int instanceCount = 0;
		grassInstanceBuffer.clear();
		for (int zx = 0; zx < ctx.sizeX; ++zx)
		{
			for (int zz = 0; zz < ctx.sizeZ; ++zz)
			{
				Zone zone = ctx.zones[zx][zz];
				// Stage D is a placement diagnostic, so do not depend on the
				// per-frame opaque-draw visibility mark. Roof/visibility traversal
				// can legitimately skip a zone before this debug pass runs.
				if (!zone.initialized)
				{
					continue;
				}
				int end = Math.min(zone.surfaceDetailLevelOffsets[0],
					zone.surfaceDetailAnchors.length);
				float baseX = (zx - offset) << 10;
				float baseZ = (zz - offset) << 10;
				for (int anchor = 0; anchor + 5 < end;
					anchor += SURFACE_DETAIL_INSTANCE_FLOATS)
				{
					if (Math.round(zone.surfaceDetailAnchors[anchor + 5]) != 0)
					{
						continue;
					}
					grassInstanceBuffer.put(baseX + zone.surfaceDetailAnchors[anchor]);
					grassInstanceBuffer.put(zone.surfaceDetailAnchors[anchor + 1]);
					grassInstanceBuffer.put(baseZ + zone.surfaceDetailAnchors[anchor + 2]);
					grassInstanceBuffer.put(zone.surfaceDetailAnchors[anchor + 3]);
					grassInstanceBuffer.put(zone.surfaceDetailAnchors[anchor + 4]);
					grassInstanceBuffer.put(0.0f);
					if (++instanceCount >= MAX_SURFACE_DETAIL_INSTANCES)
					{
						break;
					}
				}
				if (instanceCount >= MAX_SURFACE_DETAIL_INSTANCES)
				{
					break;
				}
			}
			if (instanceCount >= MAX_SURFACE_DETAIL_INSTANCES)
			{
				break;
			}
		}
		if (instanceCount == 0)
		{
			return;
		}
		grassInstanceBuffer.flip();
		glBindBuffer(GL_ARRAY_BUFFER, vboGrassInstanceHandle);
		glBufferSubData(GL_ARRAY_BUFFER, 0, grassInstanceBuffer);
		drawGrassGlb(instanceCount, 100000.0f, config.weatherMode(), 0.0f,
			getActiveSceneLightDirection(), false, true, true);
		if (!grassDebugDrawLogged)
		{
			log.info("GLB grass Stage D: eligible terrain instances={}", instanceCount);
			grassDebugDrawLogged = true;
		}
	}

	private void drawGrassRootMarkers(int instanceCount)
	{
		if (glGrassRootDebugProgram == 0 || instanceCount <= 0)
		{
			return;
		}
		glUseProgram(glGrassRootDebugProgram);
		glUniformMatrix4fv(uniGrassRootDebugProjection, false, weatherProjection);
		glUniform1f(uniGrassRootDebugMarkerSize, 6.0f);
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_GREATER);
		glDepthMask(false);
		glDisable(GL_CULL_FACE);
		glDisable(GL_BLEND);
		glBindVertexArray(vaoGrassHandle);
		glDrawArraysInstanced(GL_LINES, 0, 6, instanceCount);
		glBindVertexArray(0);
		glDepthMask(true);
	}

	/** Test 1: the imported mesh through the same minimal debug path as the
	 * proven magenta geometry. This deliberately bypasses the vegetation shader,
	 * instancing, terrain filtering, wind, and random transforms. */
	private void drawGlbGrassSimpleDebug(Scene scene, boolean depthTest)
	{
		if (vaoGrassGlbHandle == 0 || glGrassGlbDebugProgram == 0
			|| grassGlbIndexCount == 0)
		{
			return;
		}
		Player player = client.getLocalPlayer();
		if (player == null || player.getLocalLocation() == null)
		{
			return;
		}
		LocalPoint local = player.getLocalLocation();
		float baseX = local.getX() + 128.0f;
		float baseZ = local.getY();
		// Sample the exact position where the debug clump is drawn. Sampling the
		// player's tile while drawing one tile away can bury the mesh on a slope.
		LocalPoint debugPoint = new LocalPoint((int) baseX, (int) baseZ);
		float anchorY = Perspective.getTileHeight(client, debugPoint, client.getPlane());
		glUseProgram(glGrassGlbDebugProgram);
		glUniformMatrix4fv(uniGrassGlbDebugProjection, false, weatherProjection);
		glUniform3f(uniGrassGlbDebugBaseCenter, baseX, anchorY, baseZ);
		glUniform1f(uniGrassGlbDebugScale, 1.0f);
		glUniform3f(uniGrassGlbDebugColor, 1.0f, 0.08f, 0.85f);
		if (depthTest)
		{
			glEnable(GL_DEPTH_TEST);
			glDepthFunc(GL_GREATER);
			glDepthMask(true);
		}
		else
		{
			glDisable(GL_DEPTH_TEST);
			glDepthMask(false);
		}
		glDisable(GL_CULL_FACE);
		glDisable(GL_BLEND);
		glBindVertexArray(vaoGrassGlbHandle);
		glDrawElements(GL_TRIANGLES, grassGlbIndexCount, GL_UNSIGNED_INT, 0L);
		glBindVertexArray(0);
		restoreSceneRenderState();
		if (!grassDebugDrawLogged)
		{
			log.info("GLB grass {}: mesh={}, anchor=({}, {}, {}), scale=1, depth={}, cull=off",
				depthTest ? "Test 1" : "Test 2", grassGlbIndexCount,
				baseX, anchorY, baseZ, depthTest ? "on" : "off");
			grassDebugDrawLogged = true;
		}
		if (!grassGlbBoundsLogged && grassGlbNormalizedMin != null
			&& grassGlbNormalizedMax != null)
		{
			float[] worldMin = {baseX + grassGlbNormalizedMin[0],
				anchorY + grassGlbNormalizedMin[1], baseZ + grassGlbNormalizedMin[2]};
			float[] worldMax = {baseX + grassGlbNormalizedMax[0],
				anchorY + grassGlbNormalizedMax[1], baseZ + grassGlbNormalizedMax[2]};
			log.info("GLB grass bounds RuneLite-local={}..{}, "
				+ "modelScale=1, finalWorld={}..{}, terrainHeight={}",
				java.util.Arrays.toString(grassGlbNormalizedMin),
				java.util.Arrays.toString(grassGlbNormalizedMax),
				java.util.Arrays.toString(worldMin), java.util.Arrays.toString(worldMax),
				anchorY);
			grassGlbBoundsLogged = true;
		}
	}

	private void drawAllTerrainRootMarkers(Scene scene)
	{
		if (glGrassDebugProgram == 0 || vaoGrassDebugHandle == 0
			|| vboGrassDebugHandle == 0)
		{
			return;
		}
		SceneContext ctx = context(scene);
		Player player = client.getLocalPlayer();
		if (ctx == null || player == null || player.getLocalLocation() == null)
		{
			return;
		}
		LocalPoint local = player.getLocalLocation();
		Tile[][][] tiles = scene.getExtendedTiles();
		int[][][] heights = scene.getTileHeights();
		int plane = client.getPlane();
		if (plane < 0 || plane >= tiles.length || plane >= heights.length)
		{
			return;
		}
		int centerSceneX = local.getX() / Perspective.LOCAL_TILE_SIZE + SCENE_OFFSET;
		int centerSceneZ = local.getY() / Perspective.LOCAL_TILE_SIZE + SCENE_OFFSET;
		float viewX = weatherCameraX - local.getX();
		float viewZ = weatherCameraZ - local.getY();
		float viewLength = (float) Math.sqrt(viewX * viewX + viewZ * viewZ);
		if (viewLength < 0.001f)
		{
			viewX = 0.0f;
			viewZ = 1.0f;
			viewLength = 1.0f;
		}
		float rightX = -viewZ / viewLength * 12.0f;
		float rightZ = viewX / viewLength * 12.0f;
		grassDebugBuffer.clear();
		int roots = 0;
		int triangles = 0;
		for (int radius = 0; radius < 24 && roots < 20; ++radius)
		{
			for (int sceneX = centerSceneX - radius;
				sceneX <= centerSceneX + radius && roots < 20; ++sceneX)
			{
				for (int sceneZ = centerSceneZ - radius;
					sceneZ <= centerSceneZ + radius && roots < 20; ++sceneZ)
				{
					if (Math.max(Math.abs(sceneX - centerSceneX),
						Math.abs(sceneZ - centerSceneZ)) != radius
						|| sceneX < 0 || sceneX >= tiles[plane].length
						|| sceneZ < 0 || sceneZ >= tiles[plane][sceneX].length)
					{
						continue;
					}
					Tile tile = tiles[plane][sceneX][sceneZ];
					if (tile == null)
					{
						continue;
					}
					int heightLevel = tile.getRenderLevel();
					SceneTilePaint paint = tile.getSceneTilePaint();
					if (paint != null && heightLevel >= 0 && heightLevel < heights.length
						&& sceneX + 1 < heights[heightLevel].length
						&& sceneZ + 1 < heights[heightLevel][sceneX].length)
					{
						int sw = heights[heightLevel][sceneX][sceneZ];
						int se = heights[heightLevel][sceneX + 1][sceneZ];
						int ne = heights[heightLevel][sceneX + 1][sceneZ + 1];
						int nw = heights[heightLevel][sceneX][sceneZ + 1];
						float baseX = (sceneX - SCENE_OFFSET)
							* (float) Perspective.LOCAL_TILE_SIZE;
						float baseZ = (sceneZ - SCENE_OFFSET)
							* (float) Perspective.LOCAL_TILE_SIZE;
						putRootMarker(baseX + Perspective.LOCAL_TILE_SIZE / 3.0f,
							(sw + se + nw) / 3.0f,
							baseZ + Perspective.LOCAL_TILE_SIZE / 3.0f, rightX, rightZ);
						roots++;
						triangles++;
						if (roots < 20)
						{
							putRootMarker(baseX + Perspective.LOCAL_TILE_SIZE * 2.0f / 3.0f,
								(ne + nw + se) / 3.0f,
								baseZ + Perspective.LOCAL_TILE_SIZE * 2.0f / 3.0f,
								rightX, rightZ);
							roots++;
							triangles++;
						}
						continue;
					}

					SceneTileModel model = tile.getSceneTileModel();
					if (model == null)
					{
						continue;
					}
					int[] faceX = model.getFaceX();
					int[] faceY = model.getFaceY();
					int[] faceZ = model.getFaceZ();
					int[] vertexX = model.getVertexX();
					int[] vertexY = model.getVertexY();
					int[] vertexZ = model.getVertexZ();
					int[] colors = model.getTriangleColorA();
					for (int face = 0; face < faceX.length && roots < 20; ++face)
					{
						if (face >= colors.length || colors[face] == 12345678)
						{
							continue;
						}
						int a = faceX[face];
						int b = faceY[face];
						int c = faceZ[face];
						putRootMarker((vertexX[a] + vertexX[b] + vertexX[c]) / 3.0f,
							(vertexY[a] + vertexY[b] + vertexY[c]) / 3.0f,
							(vertexZ[a] + vertexZ[b] + vertexZ[c]) / 3.0f,
							rightX, rightZ);
						roots++;
						triangles++;
					}
				}
			}
		}
		int vertexCount = roots * 6;
		grassDebugBuffer.flip();
		drawGrassDebugBuffer(vertexCount, 1.0f, 0.0f, 1.0f);
		logGrassTerrainDiagnostics(ctx, 0, 0);
		if (!grassDebugDrawLogged)
		{
			log.info("All-terrain root marker diagnostic: terrain triangles={}, "
				+ "roots generated={}, roots uploaded={}, markers submitted={}",
				triangles, roots, roots, vertexCount > 0 ? roots : 0);
			grassDebugDrawLogged = true;
		}
	}

	private void putRootMarker(float rootX, float rootY, float rootZ,
		float rightX, float rightZ)
	{
		float topY = rootY - 160.0f;
		putDebugVertex(rootX - rightX, rootY, rootZ - rightZ);
		putDebugVertex(rootX + rightX, rootY, rootZ + rightZ);
		putDebugVertex(rootX + rightX, topY, rootZ + rightZ);
		putDebugVertex(rootX - rightX, rootY, rootZ - rightZ);
		putDebugVertex(rootX + rightX, topY, rootZ + rightZ);
		putDebugVertex(rootX - rightX, topY, rootZ - rightZ);
	}

	private void drawGrassDebugQuad()
	{
		if (glGrassDebugProgram == 0 || vaoGrassDebugHandle == 0
			|| vboGrassDebugHandle == 0)
		{
			return;
		}
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}
		LocalPoint local = player.getLocalLocation();
		if (local == null)
		{
			return;
		}
		float px = local.getX();
		float pz = local.getY();
		float dx = weatherCameraX - px;
		float dz = weatherCameraZ - pz;
		float length = (float) Math.sqrt(dx * dx + dz * dz);
		if (length < 0.001f)
		{
			dx = 0.0f;
			dz = 1.0f;
			length = 1.0f;
		}
		dx /= length;
		dz /= length;
		float rightUnitX = -dz;
		float rightUnitZ = dx;
		float rightX = rightUnitX * 64.0f;
		float rightZ = rightUnitZ * 64.0f;
		// Place the marker clearly beside, rather than directly in front of, the
		// player. The offset is still player-relative and remains deterministic.
		float centerX = px + rightUnitX * 256.0f;
		float centerZ = pz + rightUnitZ * 256.0f;
		float bottomY = Perspective.getTileHeight(client, local, client.getPlane());
		float topY = bottomY - 256.0f;
		grassDebugBuffer.clear();
		int vertexCount;
		if (config.grassDebugMode() == GrassDebugMode.GREEN_CLUMP)
		{
			vertexCount = putDebugClump(centerX, centerZ, bottomY);
		}
		else if (config.grassDebugMode() == GrassDebugMode.GREEN_BLADE)
		{
			vertexCount = putDebugBlade(centerX, centerZ, bottomY,
				rightX, rightZ);
		}
		else
		{
			putDebugVertex(centerX - rightX, bottomY, centerZ - rightZ);
			putDebugVertex(centerX + rightX, bottomY, centerZ + rightZ);
			putDebugVertex(centerX + rightX, topY, centerZ + rightZ);
			putDebugVertex(centerX - rightX, bottomY, centerZ - rightZ);
			putDebugVertex(centerX + rightX, topY, centerZ + rightZ);
			putDebugVertex(centerX - rightX, topY, centerZ - rightZ);
			vertexCount = 6;
		}
		grassDebugBuffer.flip();
		boolean green = config.grassDebugMode() == GrassDebugMode.GREEN_CLUMP
			|| config.grassDebugMode() == GrassDebugMode.GREEN_BLADE;
		drawGrassDebugBuffer(vertexCount,
			green ? 0.08f : 1.0f, green ? 1.0f : 0.0f, green ? 0.12f : 1.0f);
		if (!grassDebugDrawLogged)
		{
			log.info("Grass debug draw: mode={}, vertices={}, instances=1, playerScene=({}, {}, {})",
				config.grassDebugMode(), vertexCount, (int) px, (int) bottomY, (int) pz);
			grassDebugDrawLogged = true;
		}
	}

	/** Shared, proven non-instanced debug renderer used by the magenta quad and
	 * all-terrain root markers. Callers only populate grassDebugBuffer. */
	private void drawGrassDebugBuffer(int vertexCount, float red, float green, float blue)
	{
		if (vertexCount <= 0)
		{
			return;
		}
		glUseProgram(glGrassDebugProgram);
		glUniformMatrix4fv(uniGrassDebugProjection, false, weatherProjection);
		glUniform3f(uniGrassDebugBaseCenter, 0.0f, 0.0f, 0.0f);
		glUniform1f(uniGrassDebugInstanceSpacing, 0.0f);
		glUniform3f(uniGrassDebugColor, red, green, blue);
		glBindBuffer(GL_ARRAY_BUFFER, vboGrassDebugHandle);
		glBufferSubData(GL_ARRAY_BUFFER, 0, grassDebugBuffer);
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_GREATER);
		glDepthMask(true);
		glDisable(GL_CULL_FACE);
		glDisable(GL_BLEND);
		glBindVertexArray(vaoGrassDebugHandle);
		glDrawArrays(GL_TRIANGLES, 0, vertexCount);
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		restoreSceneRenderState();
	}

	private void drawGrassInstancedDebug()
	{
		if (glGrassDebugProgram == 0 || vaoGrassDebugHandle == 0
			|| vboGrassDebugHandle == 0)
		{
			return;
		}
		Player player = client.getLocalPlayer();
		if (player == null || player.getLocalLocation() == null)
		{
			return;
		}
		LocalPoint local = player.getLocalLocation();
		float px = local.getX();
		float pz = local.getY();
		float dx = weatherCameraX - px;
		float dz = weatherCameraZ - pz;
		float length = (float) Math.sqrt(dx * dx + dz * dz);
		if (length < 0.001f)
		{
			dx = 0.0f;
			dz = 1.0f;
			length = 1.0f;
		}
		dx /= length;
		dz /= length;
		float centerX = px - dz * 256.0f;
		float centerZ = pz + dx * 256.0f;
		float bottomY = Perspective.getTileHeight(client, local, client.getPlane());
		grassDebugBuffer.clear();
		putDebugBlade(0.0f, 0.0f, 0.0f, 64.0f, 0.0f);
		grassDebugBuffer.flip();
		glUseProgram(glGrassDebugProgram);
		glUniformMatrix4fv(uniGrassDebugProjection, false, weatherProjection);
		glUniform3f(uniGrassDebugBaseCenter, centerX, bottomY, centerZ);
		glUniform1f(uniGrassDebugInstanceSpacing, 96.0f);
		glUniform3f(uniGrassDebugColor, 0.08f, 1.0f, 0.12f);
		glBindBuffer(GL_ARRAY_BUFFER, vboGrassDebugHandle);
		glBufferSubData(GL_ARRAY_BUFFER, 0, grassDebugBuffer);
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_GREATER);
		glDepthMask(true);
		glDisable(GL_CULL_FACE);
		glDisable(GL_BLEND);
		glBindVertexArray(vaoGrassDebugHandle);
		glDrawArraysInstanced(GL_TRIANGLES, 0, 24, 10);
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		restoreSceneRenderState();
		if (!grassDebugDrawLogged)
		{
			log.info("Grass debug draw: mode={}, vertices=24, instances=10, playerScene=({}, {}, {})",
				config.grassDebugMode(), (int) px, (int) bottomY, (int) pz);
			grassDebugDrawLogged = true;
		}
	}

	private void drawTerrainGrassDebug(Scene scene)
	{
		// Reuse the normal instanced terrain path, but bypass density/radius LOD and
		// select only the exact grass material anchors generated by SceneUploader.
		if (!config.flowingGrass())
		{
			return;
		}
		if (glGrassProgram == 0 || vaoGrassHandle == 0 || vboGrassInstanceHandle == 0)
		{
			return;
		}
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}
		int offset = SCENE_OFFSET >> 3;
		int instanceCount = 0;
		grassInstanceBuffer.clear();
		gatherTerrainDebug:
		for (int zx = 0; zx < ctx.sizeX; ++zx)
		{
			for (int zz = 0; zz < ctx.sizeZ; ++zz)
			{
				if (instanceCount >= MAX_SURFACE_DETAIL_INSTANCES)
				{
					break gatherTerrainDebug;
				}
				Zone zone = ctx.zones[zx][zz];
				if (!zone.initialized
					|| zone.surfaceDetailVisibleFrame != grassVisibilityFrame)
				{
					continue;
				}
				float baseX = (zx - offset) << 10;
				float baseZ = (zz - offset) << 10;
				int end = Math.min(zone.surfaceDetailLevelOffsets[0],
					zone.surfaceDetailAnchors.length);
				for (int anchor = 0; anchor + 5 < end;
					anchor += SURFACE_DETAIL_INSTANCE_FLOATS)
				{
					if (Math.round(zone.surfaceDetailAnchors[anchor + 5]) != 0)
					{
						continue;
					}
					grassInstanceBuffer.put(baseX + zone.surfaceDetailAnchors[anchor]);
					grassInstanceBuffer.put(zone.surfaceDetailAnchors[anchor + 1]);
					grassInstanceBuffer.put(baseZ + zone.surfaceDetailAnchors[anchor + 2]);
					grassInstanceBuffer.put(zone.surfaceDetailAnchors[anchor + 3]);
					grassInstanceBuffer.put(zone.surfaceDetailAnchors[anchor + 4]);
					grassInstanceBuffer.put(0.0f);
					if (++instanceCount >= MAX_SURFACE_DETAIL_INSTANCES)
					{
						break gatherTerrainDebug;
					}
				}
			}
		}
		if (instanceCount == 0)
		{
			log.info("Grass terrain debug gathered no mapped grass-underlay anchors");
			return;
		}
		grassInstanceBuffer.flip();
		glBindBuffer(GL_ARRAY_BUFFER, vboGrassInstanceHandle);
		glBufferSubData(GL_ARRAY_BUFFER, 0, grassInstanceBuffer);
		glUseProgram(glGrassProgram);
		glUniformMatrix4fv(uniGrassProjection, false, weatherProjection);
		glUniform3f(uniGrassCamera, weatherCameraX, weatherCameraY, weatherCameraZ);
		glUniform3f(uniGrassFocus, weatherCameraX, weatherCameraY, weatherCameraZ);
		glUniform2f(uniGrassWorldOffset, 0.0f, 0.0f);
		glUniform1f(uniGrassTime, 0.0f);
		glUniform4f(uniGrassDrawRadius, 100000.0f, 100000.0f, 100000.0f, 100000.0f);
		glUniform1f(uniGrassHeightScale, 1.0f);
		glUniform1f(uniGrassWindStrength, 0.0f);
		glUniform1i(uniGrassWeatherModeVert, 0);
		glUniform3f(uniGrassLightDirection, 0.0f, -1.0f, 0.0f);
		glUniform1f(uniGrassLightIntensity, 1.0f);
		glUniform1f(uniGrassAmbientLight, 1.0f);
		glUniform1f(uniGrassLightningFlash, 0.0f);
		glUniform1i(uniGrassWeatherModeFrag, 0);
		glUniform1f(uniGrassNightFactor, 0.0f);
		glUniform1f(uniGrassBrightness, 1.0f);
		glUniform1i(uniGrassEnhancedColors, 0);
		glUniform1f(uniGrassSaturation, 1.0f);
		glUniform1f(uniGrassContrast, 1.0f);
		glUniform4f(uniGrassFogColor, 0.0f, 0.0f, 0.0f, 1.0f);
		glUniform1i(uniGrassShadowsEnabled, 0);
		glUniform1i(uniGrassMaterialDebugMode, 0);
		glUniform1i(uniGrassMaterialLightingEnabled, 0);
		glUniform1f(uniGrassMaterialLightingStrength, 0.0f);
		glUniform1i(uniGrassWetSurfacesEnabled, 0);
		glUniform1f(uniGrassWetSurfaceStrength, 0.0f);
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_GREATER);
		glDepthMask(true);
		glDisable(GL_CULL_FACE);
		glDisable(GL_BLEND);
		glBindVertexArray(vaoGrassHandle);
		glDrawArraysInstanced(GL_TRIANGLES, 0, SURFACE_DETAIL_VERTICES_PER_INSTANCE,
			instanceCount);
		glBindVertexArray(0);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		restoreSceneRenderState();
		log.info("Grass terrain debug draw: vertices={}, instances={}",
			SURFACE_DETAIL_VERTICES_PER_INSTANCE, instanceCount);
	}

	private void putDebugVertex(float x, float y, float z)
	{
		grassDebugBuffer.put(x).put(y).put(z);
	}

	private int putDebugBlade(float centerX, float centerZ, float bottomY,
		float rightX, float rightZ)
	{
		return putDebugBlade(centerX, centerZ, bottomY, rightX, rightZ,
			1.0f, 1.0f);
	}

	private int putDebugBlade(float centerX, float centerZ, float bottomY,
		float rightX, float rightZ, float heightScale, float widthScale)
	{
		float[] heights = {bottomY + 2.0f, bottomY - 64.0f,
			bottomY - 128.0f, bottomY - 190.0f, bottomY - 230.0f};
		float[] halfWidths = {24.0f, 20.0f, 14.0f, 6.0f, 0.0f};
		for (int i = 0; i < heights.length; ++i)
		{
			heights[i] = bottomY + (heights[i] - bottomY) * heightScale;
			halfWidths[i] *= widthScale;
		}
		int vertices = 0;
		for (int section = 0; section < 4; ++section)
		{
			float leftX = centerX - rightX * halfWidths[section] / 64.0f;
			float leftZ = centerZ - rightZ * halfWidths[section] / 64.0f;
			float rightPosX = centerX + rightX * halfWidths[section] / 64.0f;
			float rightPosZ = centerZ + rightZ * halfWidths[section] / 64.0f;
			float nextLeftX = centerX - rightX * halfWidths[section + 1] / 64.0f;
			float nextLeftZ = centerZ - rightZ * halfWidths[section + 1] / 64.0f;
			float nextRightX = centerX + rightX * halfWidths[section + 1] / 64.0f;
			float nextRightZ = centerZ + rightZ * halfWidths[section + 1] / 64.0f;
			putDebugVertex(leftX, heights[section], leftZ);
			putDebugVertex(rightPosX, heights[section], rightPosZ);
			putDebugVertex(nextRightX, heights[section + 1], nextRightZ);
			putDebugVertex(leftX, heights[section], leftZ);
			putDebugVertex(nextRightX, heights[section + 1], nextRightZ);
			putDebugVertex(nextLeftX, heights[section + 1], nextLeftZ);
			vertices += 6;
		}
		return vertices;
	}

	private int putDebugClump(float centerX, float centerZ, float bottomY)
	{
		int vertices = 0;
		for (int blade = 0; blade < 8; ++blade)
		{
			float angle = (float) (blade * Math.PI * 2.0 / 8.0);
			float heightScale = 0.88f + (blade % 4) * 0.06f;
			float widthScale = 0.82f + (blade % 3) * 0.08f;
			vertices += putDebugBlade(centerX, centerZ, bottomY,
				(float) Math.cos(angle) * 64.0f,
				(float) Math.sin(angle) * 64.0f,
				heightScale, widthScale);
		}
		return vertices;
	}

	private boolean advancedWaterEnabled(Scene scene)
	{
		return scene.getWorldViewId() == WorldView.TOPLEVEL
			&& config.enhancedWater()
			&& glWaterProgram != 0
			&& fboScene != -1
			&& fboSceneResolved != -1
			&& sceneColorTexture != 0
			&& sceneDepthTexture != 0
			&& sceneTargetWidth > 0
			&& sceneTargetHeight > 0;
	}

	/**
	 * Composite deferred terrain water over a resolved snapshot of the opaque
	 * scene. Water writes its own depth afterward so RuneLite's normal alpha pass
	 * continues to sort fences, foliage, and other transparent geometry correctly.
	 */
	private void drawAdvancedWater(Scene scene)
	{
		if (!advancedWaterEnabled(scene))
		{
			return;
		}

		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		boolean hasVisibleWater = false;
		for (int zx = 0; zx < ctx.sizeX && !hasVisibleWater; ++zx)
		{
			for (int zz = 0; zz < ctx.sizeZ; ++zz)
			{
				Zone zone = ctx.zones[zx][zz];
				if (zone.initialized
					&& zone.waterVisibleFrame == grassVisibilityFrame
					&& zone.hasWater())
				{
					hasVisibleWater = true;
					break;
				}
			}
		}
		if (!hasVisibleWater)
		{
			return;
		}

		glGetIntegerv(GL_VIEWPORT, sceneViewport);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fboScene);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboSceneResolved);
		glBlitFramebuffer(
			0, 0, sceneTargetWidth, sceneTargetHeight,
			0, 0, sceneTargetWidth, sceneTargetHeight,
			GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT,
			GL_NEAREST);
		glBindFramebuffer(GL_FRAMEBUFFER, fboScene);

		long now = frameEnvironment.timeMillis;
		WeatherMode weather = config.weatherMode();
		float nightFactor = frameEnvironment.nightFactor;
		float[] lightDirection = getActiveLightDirection();
		boolean shadowMapValid = surfaceShadowMapValid && shadowDepthTexture != 0;

		glUseProgram(glWaterProgram);
		glUniformMatrix4fv(uniWaterProjection, false, weatherProjection);
		glUniformMatrix4fv(uniWaterWorldProjection, false, weatherProjection);
		glUniformMatrix4fv(uniWaterShadowLightProj, false, currentShadowLightProj);
		glUniform4f(uniWaterUvTransform,
			sceneViewport[0] / (float) sceneTargetWidth,
			sceneViewport[1] / (float) sceneTargetHeight,
			sceneViewport[2] / (float) sceneTargetWidth,
			sceneViewport[3] / (float) sceneTargetHeight);
		glUniform2f(uniWaterTargetSize, sceneTargetWidth, sceneTargetHeight);
		glUniform3f(uniWaterCamera, weatherCameraX, weatherCameraY, weatherCameraZ);
		glUniform3f(uniWaterLightDirection,
			lightDirection[0], lightDirection[1], lightDirection[2]);
		glUniform3f(uniWaterFogColor, currentFogR, currentFogG, currentFogB);
		glUniform1f(uniWaterTime, (now % 600_000L) / 1000.0f);
		glUniform1f(uniWaterPassStrength, config.waterStrength() / 100.0f);
		glUniform1f(uniWaterPassOpacity, config.waterOpacity() / 100.0f);
		glUniform1f(uniWaterDrawDistance, currentDrawDistance);
		glUniform1f(uniWaterNightFactor, nightFactor);
		glUniform1f(uniWaterLightningFlash,
			weather == WeatherMode.STORM ? getLightningFlash(now) : 0.0f);
		glUniform1f(uniWaterWeatherDensity, config.weatherDensity() / 100.0f);
		glUniform1i(uniWaterWeatherMode, weather.ordinal());
		glUniform1i(uniWaterShadowMapValid, shadowMapValid ? 1 : 0);
		glUniform1i(uniWaterZeroToOneDepth, glCapabilities.OpenGL45 ? 1 : 0);
		glUniform1i(uniWaterSkyReflectionEnabled, activeSkyTexture != 0 ? 1 : 0);
		glUniform1i(uniWaterMaterialDebugMode, config.materialDebugMode().getId());

		glActiveTexture(GL_TEXTURE3);
		glBindTexture(GL_TEXTURE_2D, sceneColorTexture);
		glUniform1i(uniWaterSceneColor, 3);
		glActiveTexture(GL_TEXTURE4);
		glBindTexture(GL_TEXTURE_2D, sceneDepthTexture);
		glUniform1i(uniWaterSceneDepth, 4);
		glActiveTexture(GL_TEXTURE5);
		glBindTexture(GL_TEXTURE_2D, shadowDepthTexture);
		glUniform1i(uniWaterShadowMap, 5);
		glActiveTexture(GL_TEXTURE6);
		glBindTexture(GL_TEXTURE_CUBE_MAP, activeSkyTexture);
		glUniform1i(uniWaterSkyTexture, 6);

		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_GREATER);
		glDepthMask(true);
		glEnable(GL_CULL_FACE);
		glDisable(GL_BLEND);

		int offset = SCENE_OFFSET >> 3;
		for (int zx = 0; zx < ctx.sizeX; ++zx)
		{
			for (int zz = 0; zz < ctx.sizeZ; ++zz)
			{
				Zone zone = ctx.zones[zx][zz];
				if (!zone.initialized
					|| zone.waterVisibleFrame != grassVisibilityFrame
					|| !zone.hasWater())
				{
					continue;
				}

				zone.renderWater(zx - offset, zz - offset,
					ctx.minLevel, ctx.level, ctx.maxLevel,
					ctx.hideRoofIds, uniWaterBase);
			}
		}

		glBindVertexArray(0);
		glActiveTexture(GL_TEXTURE6);
		glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
		glActiveTexture(GL_TEXTURE5);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE4);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE3);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
		restoreSceneRenderState();
	}

	private void drawVolumetricLighting()
	{
		SkyMode sky = getEnvironmentSkyMode();
		float activeRayStrength = getActiveCelestialRayStrength();
		if (activeRayStrength <= 0.0f
			|| sky == SkyMode.OFF
			|| glVolumetricProgram == 0
			|| glVolumetricCompositeProgram == 0
			|| fboSceneResolved == -1
			|| sceneColorTexture == 0
			|| sceneDepthTexture == 0
			|| fboVolumetric == -1
			|| volumetricTexture == 0
			|| sceneTargetWidth <= 0
			|| sceneTargetHeight <= 0
			|| volumetricTargetWidth <= 0
			|| volumetricTargetHeight <= 0
			|| atmosphereFilteredShadowDepthTexture == 0)
		{
			return;
		}

		glGetIntegerv(GL_VIEWPORT, sceneViewport);

		// Snapshot the complete world after water and alpha geometry. Both celestial
		// profiles use the same independent half-resolution atmospheric target; the
		// source no longer needs to be visible on screen.
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fboScene);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboSceneResolved);
		glBlitFramebuffer(
			0, 0, sceneTargetWidth, sceneTargetHeight,
			0, 0, sceneTargetWidth, sceneTargetHeight,
			GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT,
			GL_NEAREST);
		// Stretched mode deliberately adds one pixel of negative viewport padding.
		// Java division truncates toward zero, so use floor/ceil division explicitly
		// to keep the half-resolution target aligned at those negative origins.
		int rayX = halfResolutionViewportOrigin(sceneViewport[0]);
		int rayY = halfResolutionViewportOrigin(sceneViewport[1]);
		int rayWidth = halfResolutionViewportExtent(
			sceneViewport[0], sceneViewport[2]);
		int rayHeight = halfResolutionViewportExtent(
			sceneViewport[1], sceneViewport[3]);

		glBindFramebuffer(GL_FRAMEBUFFER, fboVolumetric);
		glViewport(rayX, rayY, rayWidth, rayHeight);

		glUseProgram(glVolumetricProgram);
		glActiveTexture(GL_TEXTURE3);
		glBindTexture(GL_TEXTURE_2D, sceneColorTexture);
		glUniform1i(uniVolumetricSceneColor, 3);
		glActiveTexture(GL_TEXTURE4);
		glBindTexture(GL_TEXTURE_2D, sceneDepthTexture);
		glUniform1i(uniVolumetricSceneDepth, 4);
		glUniform4f(uniVolumetricUvTransform,
			sceneViewport[0] / (float) sceneTargetWidth,
			sceneViewport[1] / (float) sceneTargetHeight,
			sceneViewport[2] / (float) sceneTargetWidth,
			sceneViewport[3] / (float) sceneTargetHeight);
		glUniform2f(uniVolumetricCelestialRayStrength,
			getSunRayStrength(), getMoonRayStrength());
		glUniform1f(uniVolumetricMoonProfile,
			isMoonEnvironment(sky) ? 1.0f : 0.0f);
		glUniform1i(uniVolumetricWeatherMode, config.weatherMode().ordinal());
		glUniformMatrix4fv(uniVolumetricWorldProjection, false, weatherProjection);
		glUniform3f(uniVolumetricCamera,
			weatherCameraX, weatherCameraY, weatherCameraZ);
		float[] lightDirection = getActiveSceneLightDirection();
		glUniform3f(uniVolumetricLightDirection,
			lightDirection[0], lightDirection[1], lightDirection[2]);
		setEnvironmentRayColor(uniVolumetricRayColor);
		glUniformMatrix4fv(uniVolumetricShadowLightProj,
			false, currentAtmosphereLightProj);

		boolean shadowMapValid = atmosphereShadowMapValid
			&& atmosphereFilteredShadowDepthTexture != 0;
		glActiveTexture(GL_TEXTURE5);
		glBindTexture(GL_TEXTURE_2D, atmosphereFilteredShadowDepthTexture);
		glUniform1i(uniVolumetricShadowMap, 5);
		glUniform1i(uniVolumetricShadowMapValid, shadowMapValid ? 1 : 0);
		glUniform1i(uniVolumetricZeroToOneDepth,
			glCapabilities.OpenGL45 ? 1 : 0);

		glDisable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_CULL_FACE);
		glDisable(GL_BLEND);
		glBindVertexArray(vaoSkyHandle);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		glBindFramebuffer(GL_FRAMEBUFFER, fboScene);
		glViewport(
			sceneViewport[0], sceneViewport[1],
			sceneViewport[2], sceneViewport[3]);
		glUseProgram(glVolumetricCompositeProgram);
		glActiveTexture(GL_TEXTURE3);
		glBindTexture(GL_TEXTURE_2D, sceneColorTexture);
		glUniform1i(uniVolumetricCompositeSceneColor, 3);
		glUniform1i(uniVolumetricCompositeSceneDepth, 4);
		glUniform4f(uniVolumetricCompositeSceneUvTransform,
			sceneViewport[0] / (float) sceneTargetWidth,
			sceneViewport[1] / (float) sceneTargetHeight,
			sceneViewport[2] / (float) sceneTargetWidth,
			sceneViewport[3] / (float) sceneTargetHeight);
		glActiveTexture(GL_TEXTURE6);
		glBindTexture(GL_TEXTURE_2D, volumetricTexture);
		glUniform1i(uniVolumetricCompositeRays, 6);
		glUniform4f(uniVolumetricCompositeRayUvTransform,
			rayX / (float) volumetricTargetWidth,
			rayY / (float) volumetricTargetHeight,
			rayWidth / (float) volumetricTargetWidth,
			rayHeight / (float) volumetricTargetHeight);
		glUniform2f(uniVolumetricCompositeRayTexelSize,
			1.0f / volumetricTargetWidth,
			1.0f / volumetricTargetHeight);
		glBindVertexArray(vaoSkyHandle);
		glDrawArrays(GL_TRIANGLES, 0, 3);

		glActiveTexture(GL_TEXTURE6);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE5);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE4);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE3);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
		glViewport(
			sceneViewport[0], sceneViewport[1],
			sceneViewport[2], sceneViewport[3]);
		restoreSceneRenderState();
	}

	private void drawWeather()
	{
		WeatherMode mode = config.weatherMode();
		long now = frameEnvironment.timeMillis;
		updateWeatherAudio(mode, now);
		if (mode == WeatherMode.CLEAR || glWeatherProgram == 0)
		{
			return;
		}

		boolean snow = mode == WeatherMode.SNOW || mode == WeatherMode.BLIZZARD;
		boolean severe = mode == WeatherMode.STORM || mode == WeatherMode.BLIZZARD;
		boolean storm = mode == WeatherMode.STORM;
		int baseParticles = storm ? 96000
			: mode == WeatherMode.BLIZZARD ? 165000
				: snow ? 52000 : 28000;
		int particles = baseParticles * config.weatherDensity() / 100;
		glUseProgram(glWeatherProgram);
		glUniformMatrix4fv(uniWeatherProjection, false, weatherProjection);
		glUniform3f(uniWeatherCamera, weatherCameraX, weatherCameraY, weatherCameraZ);
		glUniform1f(uniWeatherTime, (now % 600_000L) / 1000.0f);
		glUniform1f(uniWeatherRadius,
			storm ? 1550.0f : mode == WeatherMode.BLIZZARD ? 1550.0f : 1950.0f);
		glUniform1f(uniWeatherFallSpeed,
			snow ? (mode == WeatherMode.BLIZZARD ? 295.0f : 150.0f)
				: (storm ? 2400.0f : 1250.0f));
		glUniform1f(uniWeatherWind, config.weatherWind()
			* (mode == WeatherMode.BLIZZARD ? 4.5f : severe ? 4.0f : 2.2f));
		glUniform1f(uniWeatherStreakLength, storm ? 255.0f : severe ? 210.0f : 145.0f);
		glUniform1i(uniWeatherSnow, snow ? 1 : 0);
		glUniform1i(uniWeatherStorm, storm ? 1 : 0);
		glUniform1i(uniWeatherSevere, severe ? 1 : 0);
		float flash = storm ? getLightningFlash(now) : 0.0f;
		float precipitationIntensity = storm ? 0.82f
			: mode == WeatherMode.BLIZZARD ? 0.88f : 0.48f;
		glUniform1f(uniWeatherIntensity,
			precipitationIntensity + flash * 0.28f);

		glEnable(GL_DEPTH_TEST);
		glDepthMask(false);
		glDisable(GL_CULL_FACE);
		glEnable(GL_BLEND);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		if (snow)
		{
			glEnable(GL_PROGRAM_POINT_SIZE);
		}
		glBindVertexArray(vaoSkyHandle);
		glDrawArrays(snow ? GL_POINTS : GL_LINES, 0, snow ? particles : particles * 2);
		if (snow)
		{
			glDisable(GL_PROGRAM_POINT_SIZE);
		}
		restoreSceneRenderState();
	}

	private void restoreSceneRenderState()
	{
		glBindVertexArray(0);
		glDepthMask(true);
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_GREATER);
		glEnable(GL_CULL_FACE);
		glEnable(GL_BLEND);
		glBlendFuncSeparate(
			GL_SRC_ALPHA,
			GL_ONE_MINUS_SRC_ALPHA,
			GL_ONE,
			GL_ONE
		);
		glUseProgram(glProgram);
	}

	private void updateWeatherAudio(WeatherMode mode, long now)
	{
		boolean enabled = config.weatherSounds();
		int volume = config.weatherVolume();
		weatherAudio.update(mode, enabled, volume);
		boolean lightningWeather = mode == WeatherMode.STORM;
		long cycle = now / 11_000L;
		long phase = now % 11_000L;
		if (enabled && lightningWeather && phase < 85L && cycle != lastThunderCycle)
		{
			lastThunderCycle = cycle;
			weatherAudio.playThunder(cycle, volume);
		}
		else if (!lightningWeather)
		{
			lastThunderCycle = Long.MIN_VALUE;
		}
	}

	private int getLightningTexture(long now)
	{
		long cycle = now / 11_000L;
		long phase = now % 11_000L;
		if (phase >= 260L)
		{
			return 0;
		}
		int pair = (int) (cycle & 1L) * 2;
		return lightningSkyTextures[pair + (phase < 85L ? 0 : 1)];
	}

	private float getLightningFlash(long now)
	{
		long phase = now % 11_000L;
		if (phase < 85L)
		{
			return 0.55f;
		}
		if (phase < 150L)
		{
			return 1.0f;
		}
		if (phase < 260L)
		{
			return 0.38f * (260L - phase) / 110.0f;
		}
		return 0.0f;
	}

	private void postDrawToplevel()
	{
		glDisable(GL_BLEND);
		glDisable(GL_CULL_FACE);
		glDisable(GL_DEPTH_TEST);

		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, awtContext.getFramebuffer(false));
		sceneFboValid = true;
	}

	private void blitSceneFbo()
	{
		int width = lastStretchedCanvasWidth;
		int height = lastStretchedCanvasHeight;

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform transform = graphicsConfiguration.getDefaultTransform();

		width = getScaledValue(transform.getScaleX(), width);
		height = getScaledValue(transform.getScaleY(), height);

		int defaultFbo = awtContext.getFramebuffer(false);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fboScene);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, defaultFbo);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height,
			GL_COLOR_BUFFER_BIT, GL_NEAREST);

		// Reset
		glBindFramebuffer(GL_READ_FRAMEBUFFER, defaultFbo);

		checkGLErrors();
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized)
		{
			return;
		}
		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			z.surfaceDetailVisibleFrame = grassVisibilityFrame;
			z.waterVisibleFrame = grassVisibilityFrame;
			z.treeReplacementVisibleFrame = grassVisibilityFrame;
		}
		// Static zone geometry is submitted immediately rather than through the
		// PASS_OPAQUE dynamic buffer. Render it by replacement: deferred water uses
		// the resolved scene alpha to distinguish its generated seabed, and blending
		// here would both erase that marker and mix opaque terrain with the sky.
		glDisable(GL_BLEND);
		z.renderOpaque(zx - offset, zz - offset, ctx.minLevel, ctx.level,
			ctx.maxLevel, ctx.hideRoofIds, advancedWaterEnabled(scene));

		checkGLErrors();
	}

	private static final int ALPHA_ZSORT_CLOSE = 2048;

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.initialized)
		{
			return;
		}

		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
		int dx = ctx.cameraX - ((zx - offset) << 10);
		int dz = ctx.cameraZ - ((zz - offset) << 10);
		boolean close = dx * dx + dz * dz < ALPHA_ZSORT_CLOSE * ALPHA_ZSORT_CLOSE;

		if (level == 0)
		{
			z.alphaSort(zx - offset, zz - offset, ctx.cameraX, ctx.cameraY, ctx.cameraZ);
			z.multizoneLocs(scene, zx - offset, zz - offset, ctx.cameraX, ctx.cameraZ, ctx.zones);
		}

		RenderThread rt = rts[0];
		z.renderAlpha(rt.modelUploader, zx - offset, zz - offset, cameraYaw, cameraPitch, ctx.minLevel, ctx.level, ctx.maxLevel, level, ctx.hideRoofIds, !close || (scene.getOverrideAmount() > 0));

		checkGLErrors();
	}

	@Override
	public void drawPass(Projection projection, Scene scene, int pass)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		if (pass == DrawCallbacks.PASS_OPAQUE)
		{
			if (scene.getWorldViewId() == WorldView.TOPLEVEL)
			{
				// Dynamic opaque geometry accumulated during scene traversal follows the
				// same replacement rule as the static zone draws above.
				glDisable(GL_BLEND);
				for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
				{
					rts[i].vaoO.draw();
				}
				drawTrees(scene);
				drawSurfaceDetails(scene);
				logTreeRenderProfile();
				drawAdvancedWater(scene);
				if (config.treeOcclusionDebug()
					&& config.treeOcclusion() != TreeOcclusionMode.OFF
					&& treeVisibilityInitialized)
				{
					drawTreeOcclusionDebug();
				}
				restoreSceneRenderState();
			}
			else
			{
				glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
			}
		}
		else if (pass == DrawCallbacks.PASS_ALPHA)
		{
			for (int x = 0; x < ctx.sizeX; ++x)
			{
				for (int z = 0; z < ctx.sizeZ; ++z)
				{
					Zone zone = ctx.zones[x][z];
					zone.removeTemp();
				}
			}
		}
		else if (pass == DrawCallbacks.PRE_PASS_ALPHA)
		{
			// Opaque zone submissions deliberately leave blending disabled. Reassert
			// the normal scene state before any nested or top-level alpha geometry.
			restoreSceneRenderState();
			for (int i = 0; i < rts.length; ++i)
			{
				rts[i].vaoA.unmap();
			}

			glUniformMatrix4fv(uniEntityProj, false, ctx.projection);
			glUniform4i(uniEntityTint, scene.getOverrideHue(), scene.getOverrideSaturation(), scene.getOverrideLuminance(), scene.getOverrideAmount());
		}

		checkGLErrors();
	}

	@Override
	public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject, Renderable r, Model m, int orient, int x, int y, int z)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		if (!renderCallbackManager.drawObject(scene, tileObject))
		{
			return;
		}

		var worldLocation = tileObject.getWorldLocation();
		if (scene.getWorldViewId() == WorldView.TOPLEVEL
			&& treeReplacementRegistry.resolve(tileObject.getId(),
				worldLocation.getX(), worldLocation.getY(), worldLocation.getPlane()) != null)
		{
			// The zone-resident replacement was registered during scene upload.
			// Suppress only this transient GPU copy; the TileObject remains intact.
			return;
		}
		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		if (m.getFaceTransparencies() == null)
		{
			RenderThread rt = rts[renderThreadId + 1];
			VAO o = rt.vaoO.get(size);
			if (o == null)
			{
				return;
			}

			rt.modelUploader.uploadTempModel(m, orient, x, y, z, o.vbo.vb,
				tileObject.getId(), worldLocation.getX(), worldLocation.getY(),
				worldLocation.getPlane());
			o.addRange(ctx.projection, scene, 0);
		}
		else
		{
			m.calculateBoundsCylinder();

			RenderThread rt = rts[renderThreadId + 1];
			VAO o = rt.vaoO.get(size);
			VAO a = rt.vaoA.get(size);
			if (o == null || a == null)
			{
				return;
			}

			ModelUploader sorter = rt.modelUploader;

			int start = a.vbo.vb.position();
			try
			{
				sorter.uploadSortedModel(rt, worldProjection, m, orient, x, y, z,
					o.vbo.vb, a.vbo.vb, false, tileObject.getId(),
					worldLocation.getX(), worldLocation.getY(), worldLocation.getPlane());
			}
			catch (Exception ex)
			{
				log.debug("error drawing entity", ex);
			}
			int end = a.vbo.vb.position();

			o.addRange(ctx.projection, scene, 0);

			if (end > start)
			{
				int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? SCENE_OFFSET : 0;
				int zx = (x >> 10) + (offset >> 3);
				int zz = (z >> 10) + (offset >> 3);
				Zone zone = ctx.zones[zx][zz];

				// level is checked prior to this callback being run, in order to cull clickboxes, but
				// tileObject.getPlane()>maxLevel if visbelow is set - lower the object to the max level
				int plane = Math.min(ctx.maxLevel, tileObject.getPlane());
				// renderable modelheight is typically not set here because DynamicObject doesn't compute it on the returned model
				zone.addTempAlphaModel(a.vao, start, end, plane, x & 1023, y, z & 1023);
			}
		}
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x, int y, int z)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		if (!renderCallbackManager.drawObject(scene, gameObject))
		{
			return;
		}

		Renderable renderable = gameObject.getRenderable();
		var worldLocation = gameObject.getWorldLocation();
		if (scene.getWorldViewId() == WorldView.TOPLEVEL
			&& treeReplacementRegistry.resolve(gameObject.getId(),
				worldLocation.getX(), worldLocation.getY(), worldLocation.getPlane()) != null)
		{
			return;
		}
		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		int renderMode = renderable.getRenderMode();
		if (renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH || m.getFaceTransparencies() != null || m.getTransparency() != 0)
		{
			RenderThread rt = rts[0];
			VAO o = rt.vaoO.get(size);
			VAO a = rt.vaoA.get(size);
			ModelUploader uploader = rt.modelUploader;

			int start = a.vbo.vb.position();
			m.calculateBoundsCylinder();
			try
			{
				uploader.uploadSortedModel(rt, worldProjection, m, orient, x, y, z,
					o.vbo.vb, a.vbo.vb,
					renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH,
					gameObject.getId(), worldLocation.getX(), worldLocation.getY(),
					worldLocation.getPlane());
			}
			catch (Exception ex)
			{
				log.debug("error drawing entity", ex);
			}
			int end = a.vbo.vb.position();

			o.addRange(ctx.projection, scene, renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH ? renderMode : 0);

			if (end > start)
			{
				int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? (SCENE_OFFSET >> 3) : 0;
				int zx = (gameObject.getX() >> 10) + offset;
				int zz = (gameObject.getY() >> 10) + offset;
				Zone zone = ctx.zones[zx][zz];
				int plane = Math.min(ctx.maxLevel, gameObject.getPlane());
				zone.addTempAlphaModel(a.vao, start, end, plane, x & 1023, y - renderable.getModelHeight() /* to render players over locs */, z & 1023);
			}
		}
		else
		{
			RenderThread rt = rts[0];
			VAO o = rt.vaoO.get(size);
			ModelUploader uploader = rt.modelUploader;
			uploader.uploadTempModel(m, orient, x, y, z, o.vbo.vb,
				gameObject.getId(), worldLocation.getX(), worldLocation.getY(),
				worldLocation.getPlane());
			o.addRange(ctx.projection, scene, 0);
		}
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.invalidate)
		{
			z.invalidate = true;
			log.debug("Zone invalidated: wx={} x={} z={}", scene.getWorldViewId(), zx, zz);
		}
	}

	@Subscribe
	public void onPostClientTick(PostClientTick event)
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		rebuild(wv);
		for (WorldEntity we : wv.worldEntities())
		{
			wv = we.getWorldView();
			rebuild(wv);
		}
	}

	private void rebuild(WorldView wv)
	{
		SceneContext ctx = context(wv);
		if (ctx == null)
		{
			return;
		}

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];
				if (!zone.invalidate)
				{
					continue;
				}

				assert zone.initialized;
				zone.free();
				zone = ctx.zones[x][z] = new Zone();

				Scene scene = wv.getScene();
				clientUploader.zoneSize(scene, zone, x, z);

				VBO o = null, a = null;
				int sz = zone.sizeO * Zone.VERT_SIZE * 3;
				if (sz > 0)
				{
					o = new VBO(sz);
					o.init(GL_STATIC_DRAW);
					o.map();
				}

				sz = zone.sizeA * Zone.VERT_SIZE * 3;
				if (sz > 0)
				{
					a = new VBO(sz);
					a.init(GL_STATIC_DRAW);
					a.map();
				}

				zone.init(o, a);

				clientUploader.uploadZone(scene, zone, x, z);

				zone.unmap();
				zone.initialized = true;
				zone.dirty = true;

				log.debug("Rebuilt zone wv={} x={} z={}", wv.getId(), x, z);
			}
		}
	}

	private void prepareInterfaceTexture(int canvasWidth, int canvasHeight)
	{
		if (canvasWidth != lastCanvasWidth || canvasHeight != lastCanvasHeight)
		{
			lastCanvasWidth = canvasWidth;
			lastCanvasHeight = canvasHeight;

			glBindTexture(GL_TEXTURE_2D, interfaceTexture);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, canvasWidth, canvasHeight, 0, GL_BGRA, GL_UNSIGNED_BYTE, 0);
			glBindTexture(GL_TEXTURE_2D, 0);
		}

		final BufferProvider bufferProvider = client.getBufferProvider();
		final int[] pixels = bufferProvider.getPixels();
		final int width = bufferProvider.getWidth();
		final int height = bufferProvider.getHeight();

		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, interfacePbo);
		glBufferData(GL_PIXEL_UNPACK_BUFFER, (long) width * height * Integer.BYTES, GL_STREAM_DRAW);
		ByteBuffer interfaceBuf = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
		if (interfaceBuf != null)
		{
			interfaceBuf
				.asIntBuffer()
				.put(pixels, 0, width * height);
			glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER);
		}
		else
		{
			glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
			return;
		}
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);
		glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	@Override
	public void draw(int overlayColor)
	{
		final GameState gameState = client.getGameState();
		if (gameState == GameState.STARTING)
		{
			return;
		}

		final TextureProvider textureProvider = client.getTextureProvider();
		if (textureArrayId == -1 && textureProvider != null)
		{
			// lazy init textures as they may not be loaded at plugin start.
			// this will return -1 and retry if not all textures are loaded yet, too.
			textureArrayId = textureManager.initTextureArray(textureProvider);
			if (textureArrayId > -1)
			{
				// if texture upload is successful, compute and set texture animations
				float[] texAnims = textureManager.computeTextureAnimations(textureProvider);
				glUseProgram(glProgram);
				glUniform2fv(uniTextureAnimations, texAnims);
				glUseProgram(0);
			}
		}

		final int canvasHeight = client.getCanvasHeight();
		final int canvasWidth = client.getCanvasWidth();

		prepareInterfaceTexture(canvasWidth, canvasHeight);

		glClearColor(0, 0, 0, 1);
		glClear(GL_COLOR_BUFFER_BIT);

		if (sceneFboValid)
		{
			blitSceneFbo();
		}



		// Texture on UI
		drawUi(overlayColor, canvasHeight, canvasWidth);

		if (config.materialDebugMode() == MaterialDebugMode.OFF
			&& config.shadowDebug() && surfaceShadowMapValid)
		{
			drawShadowDebug();
		}

		try
		{
			awtContext.swapBuffers();
		}
		catch (RuntimeException ex)
		{
			// this is always fatal
			if (!canvas.isValid())
			{
				// this might be AWT shutting down on VM shutdown, ignore it
				return;
			}

			log.error("error swapping buffers", ex);

			// try to stop the plugin
			SwingUtilities.invokeLater(() ->
			{
				try
				{
					pluginManager.stopPlugin(this);
				}
				catch (PluginInstantiationException ex2)
				{
					log.error("error stopping plugin", ex2);
				}
			});
			return;
		}

		drawManager.processDrawComplete(this::screenshot);

		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));

		checkGLErrors();
	}

	private void drawShadowDebug()
	{
		// Draw over the normal framebuffer.
		glBindFramebuffer(
				GL_DRAW_FRAMEBUFFER,
				awtContext.getFramebuffer(false)
		);

		int canvasWidth =
				client.getCanvasWidth();

		int canvasHeight =
				client.getCanvasHeight();

		/*
		 * Debug window size.
		 *
		 * Bottom-right quarter of the client.
		 */
		int width =
				canvasWidth / 3;

		int height =
				canvasHeight / 3;

		glViewport(
				canvasWidth - width,
				0,
				width,
				height
		);

		glUseProgram(
				glShadowDebugProgram
		);

		glActiveTexture(
				GL_TEXTURE0
		);

		glBindTexture(
				GL_TEXTURE_2D,
				shadowDepthTexture
		);

		glUniform1i(
				uniShadowDebugMap,
				0
		);

		// We don't want the quad interacting with scene depth.
		glDisable(GL_DEPTH_TEST);
		glDisable(GL_CULL_FACE);
		glDisable(GL_BLEND);

		glBindVertexArray(
				vaoUiHandle
		);

		glDrawArrays(
				GL_TRIANGLE_FAN,
				0,
				4
		);

		glBindVertexArray(0);

		glBindTexture(
				GL_TEXTURE_2D,
				0
		);

		glUseProgram(0);

		glEnable(GL_DEPTH_TEST);
		glEnable(GL_CULL_FACE);
	}

	private void drawUi(final int overlayColor, final int canvasHeight, final int canvasWidth)
	{
		glEnable(GL_BLEND);
		glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);
		glBindTexture(GL_TEXTURE_2D, interfaceTexture);

		// Use the texture bound in the first pass
		final UIScalingMode uiScalingMode = config.uiScalingMode();
		glUseProgram(glUiProgram);
		glUniform1i(uniTex, 0);
		glUniform2i(uniTexSourceDimensions, canvasWidth, canvasHeight);
		glUniform4f(uniUiAlphaOverlay,
			(overlayColor >> 16 & 0xFF) / 255f,
			(overlayColor >> 8 & 0xFF) / 255f,
			(overlayColor & 0xFF) / 255f,
			(overlayColor >>> 24) / 255f
		);
		glUniform1f(uniUiColorblindIntensity, config.colorBlindIntensity());

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			glDpiAwareViewport(0, 0, dim.width, dim.height);
			glUniform2i(uniTexTargetDimensions, getScaledValue(t.getScaleX(), dim.width), getScaledValue(t.getScaleY(), dim.height));
		}
		else
		{
			glDpiAwareViewport(0, 0, canvasWidth, canvasHeight);
			glUniform2i(uniTexTargetDimensions, getScaledValue(t.getScaleX(), canvasWidth), getScaledValue(t.getScaleY(), canvasHeight));
		}

		// Set the sampling function used when stretching the UI.
		// This is probably better done with sampler objects instead of texture parameters, but this is easier and likely more portable.
		// See https://www.khronos.org/opengl/wiki/Sampler_Object for details.
		// GL_NEAREST makes sampling for bicubic/xBR simpler, so it should be used whenever linear/hybrid isn't
		final int function = uiScalingMode == UIScalingMode.LINEAR || uiScalingMode == UIScalingMode.HYBRID ? GL_LINEAR : GL_NEAREST;
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, function);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, function);

		// Texture on UI
		glBindVertexArray(vaoUiHandle);
		glDrawArrays(GL_TRIANGLE_FAN, 0, 4);

		// Reset
		glBindTexture(GL_TEXTURE_2D, 0);
		glBindVertexArray(0);
		glUseProgram(0);
		glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
		glDisable(GL_BLEND);
	}

	/**
	 * Convert the front framebuffer to an Image
	 *
	 * @return
	 */
	private Image screenshot()
	{
		int width = client.getCanvasWidth();
		int height = client.getCanvasHeight();

		if (client.isStretchedEnabled())
		{
			Dimension dim = client.getStretchedDimensions();
			width = dim.width;
			height = dim.height;
		}

		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		width = getScaledValue(t.getScaleX(), width);
		height = getScaledValue(t.getScaleY(), height);

		BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
		int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

		glReadBuffer(awtContext.getBufferMode());
		glReadPixels(0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, pixels);

		// glReadPixels returns rows bottom-up, flip them to top-down
		int[] row = new int[width];
		for (int y0 = 0, y1 = height - 1; y0 < y1; ++y0, --y1)
		{
			System.arraycopy(pixels, y0 * width, row, 0, width);
			System.arraycopy(pixels, y1 * width, pixels, y0 * width, width);
			System.arraycopy(row, 0, pixels, y1 * width, width);
		}

		return image;
	}

	private static final SkyAtlasLayout SUNSET_LAYOUT =
			new SkyAtlasLayout(
					SkyAtlasLayout.Slot.BOTTOM_LEFT,   // north = A
					SkyAtlasLayout.Slot.BOTTOM_MIDDLE, // east  = B
					SkyAtlasLayout.Slot.BOTTOM_RIGHT,  // south = C
					SkyAtlasLayout.Slot.TOP_RIGHT      // west  = D
			);

	private void shutdownSkyTextures()
	{
		if (cosmicSkyTexture != 0)
		{
			glDeleteTextures(cosmicSkyTexture);
			cosmicSkyTexture = 0;
		}

		if (nightSkyTexture != 0)
		{
			glDeleteTextures(nightSkyTexture);
			nightSkyTexture = 0;
		}

		if (daySkyTexture != 0)
		{
			glDeleteTextures(daySkyTexture);
			daySkyTexture = 0;
		}

		if (sunsetSkyTexture != 0)
		{
			glDeleteTextures(sunsetSkyTexture);
			sunsetSkyTexture = 0;
		}

		for (int i = 0; i < rainSkyTextures.length; ++i)
		{
			if (rainSkyTextures[i] != 0)
			{
				glDeleteTextures(rainSkyTextures[i]);
				rainSkyTextures[i] = 0;
			}
		}
		if (snowSkyTexture != 0)
		{
			glDeleteTextures(snowSkyTexture);
			snowSkyTexture = 0;
		}
		for (int i = 0; i < lightningSkyTextures.length; ++i)
		{
			if (lightningSkyTextures[i] != 0)
			{
				glDeleteTextures(lightningSkyTextures[i]);
				lightningSkyTextures[i] = 0;
			}
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		GameState state = gameStateChanged.getGameState();
		if (state.getState() < GameState.LOADING.getState())
		{
			// this is to avoid scene fbo blit when going from <loading to >=loading,
			// but keep it when doing >loading to loading
			sceneFboValid = false;
		}
		if (state == GameState.STARTING)
		{
			if (textureArrayId != -1)
			{
				textureManager.freeTextureArray(textureArrayId);
			}
			textureArrayId = -1;
			lastAnisotropicFilteringLevel = -1;
		}
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			loadSubScene(worldView, scene);
			return;
		}

		if (nextZones != null)
		{
			log.debug("Double zone load!");
			// The previous scene load just gets dropped, this is uncommon and requires a back to back map build packet
			// while having the first load take more than a full server cycle to complete
			CountDownLatch latch = new CountDownLatch(1);
			clientThread.invoke(() ->
			{
				for (int x = 0; x < NUM_ZONES; ++x)
				{
					for (int z = 0; z < NUM_ZONES; ++z)
					{
						Zone zone = nextZones[x][z];
						assert !zone.cull;
						// anything initialized is a reused zone and so shouldn't be freed
						if (!zone.initialized)
						{
							zone.unmap();
							zone.initialized = true;
							zone.free();
						}
					}
				}
				latch.countDown();
			});
			try
			{
				latch.await();
			}
			catch (InterruptedException e)
			{
				throw new RuntimeException(e);
			}
			nextZones = null;
			nextRoofChanges = null;
		}

		SceneContext ctx = root;
		Scene prev = client.getTopLevelWorldView().getScene();

		regionManager.prepare(scene);

		int dx = scene.getBaseX() - prev.getBaseX() >> 3;
		int dy = scene.getBaseY() - prev.getBaseY() >> 3;

		final int SCENE_ZONES = NUM_ZONES;

		// initially mark every zone as needing culled
		for (int x = 0; x < SCENE_ZONES; ++x)
		{
			for (int z = 0; z < SCENE_ZONES; ++z)
			{
				ctx.zones[x][z].cull = true;
			}
		}

		Map<Integer, Integer> roofChanges = new HashMap<>();

		// find zones which overlap and copy them
		Zone[][] newZones = new Zone[SCENE_ZONES][SCENE_ZONES];
		final GameState gameState = client.getGameState();
		if (prev.isInstance() == scene.isInstance()
			&& gameState == GameState.LOGGED_IN)
		{
			int[][][] prevTemplates = prev.getInstanceTemplateChunks();
			int[][][] curTemplates = scene.getInstanceTemplateChunks();

			int[][][] prids = prev.getRoofs();
			int[][][] nrids = scene.getRoofs();

			for (int x = 0; x < SCENE_ZONES; ++x)
			{
				next:
				for (int z = 0; z < SCENE_ZONES; ++z)
				{
					int ox = x + dx;
					int oz = z + dy;

					// Reused the old zone if it is also in the new scene, except for the edges, to work around
					// tile blending, (edge) shadows, sharelight, etc.
					if (canReuse(ctx.zones, ox, oz))
					{
						if (scene.isInstance())
						{
							// Convert from modified chunk coordinates to Jagex chunk coordinates
							int jx = x - (SCENE_OFFSET / 8);
							int jz = z - (SCENE_OFFSET / 8);
							int jox = ox - (SCENE_OFFSET / 8);
							int joz = oz - (SCENE_OFFSET / 8);
							// Check Jagex chunk coordinates are within the Jagex scene
							if (jx >= 0 && jx < Constants.SCENE_SIZE / 8 && jz >= 0 && jz < Constants.SCENE_SIZE / 8)
							{
								if (jox >= 0 && jox < Constants.SCENE_SIZE / 8 && joz >= 0 && joz < Constants.SCENE_SIZE / 8)
								{
									for (int level = 0; level < 4; ++level)
									{
										int prevTemplate = prevTemplates[level][jox][joz];
										int curTemplate = curTemplates[level][jx][jz];
										if (prevTemplate != curTemplate)
										{
											log.error("Instance template reuse mismatch! prev={} cur={}", prevTemplate, curTemplate);
											continue next;
										}
									}
								}
							}
						}

						Zone old = ctx.zones[ox][oz];
						assert old.initialized;

						if (old.dirty)
						{
							continue;
						}

						assert old.sizeO > 0 || old.sizeA > 0;

						// Roof ids aren't consistent between scenes, so build a mapping of old -> new roof ids
						// Sometimes groups split or merge, so we can't copy the zone in that case
						for (int level = 0; level < 4; level++)
						{
							for (int tx = 0; tx < 8; tx++)
							{
								for (int tz = 0; tz < 8; tz++)
								{
									int prid = prids[level][(ox << 3) + tx][(oz << 3) + tz];
									int nrid = nrids[level][(x << 3) + tx][(z << 3) + tz];

									if (prid != nrid && (prid == 0 || nrid == 0))
									{
										log.trace("Roof mismatch: {} -> {}", prid, nrid);
										continue next;
									}

									Integer orid = roofChanges.putIfAbsent(prid, nrid);
									if (orid == null)
									{
										log.trace("Roof change: {} -> {}", prid, nrid);
									}
									else if (orid != nrid)
									{
										log.trace("Roof mismatch: {} -> {} vs {}", prid, nrid, orid);
										continue next;
									}
								}
							}
						}

						assert old.cull;
						old.cull = false;

						newZones[x][z] = old;
					}
				}
			}
		}

		// Fill out any zones that weren't copied
		for (int x = 0; x < SCENE_ZONES; ++x)
		{
			for (int z = 0; z < SCENE_ZONES; ++z)
			{
				if (newZones[x][z] == null)
				{
					newZones[x][z] = new Zone();
				}
			}
		}

		// size the zones which require upload
		Stopwatch sw = Stopwatch.createStarted();
		int len = 0, lena = 0;
		int reused = 0, newzones = 0;
		for (int x = 0; x < NUM_ZONES; ++x)
		{
			for (int z = 0; z < NUM_ZONES; ++z)
			{
				Zone zone = newZones[x][z];
				if (!zone.initialized)
				{
					assert zone.glVao == 0;
					assert zone.glVaoA == 0;
					mapUploader.zoneSize(scene, zone, x, z);
					len += zone.sizeO;
					lena += zone.sizeA;
					newzones++;
				}
				else
				{
					reused++;
				}
			}
		}
		log.debug("Scene size time {} reused {} new {} len opaque {} size opaque {}kb len alpha {} size alpha {}kb",
			sw, reused, newzones,
			len, (len * Zone.VERT_SIZE * 3) / 1024,
			lena, (lena * Zone.VERT_SIZE * 3) / 1024);

		// allocate buffers for zones which require upload
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE >> 3; ++x)
			{
				for (int z = 0; z < Constants.EXTENDED_SCENE_SIZE >> 3; ++z)
				{
					Zone zone = newZones[x][z];

					if (zone.initialized)
					{
						continue;
					}

					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						o = new VBO(sz);
						o.init(GL_STATIC_DRAW);
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						a = new VBO(sz);
						a.init(GL_STATIC_DRAW);
						a.map();
					}

					zone.init(o, a);
				}
			}

			latch.countDown();
		});
		try
		{
			latch.await();
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException(e);
		}

		// upload zones
		sw = Stopwatch.createStarted();
		for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE >> 3; ++x)
		{
			for (int z = 0; z < Constants.EXTENDED_SCENE_SIZE >> 3; ++z)
			{
				Zone zone = newZones[x][z];

				if (!zone.initialized)
				{
					mapUploader.uploadZone(scene, zone, x, z);
				}
			}
		}
		log.debug("Scene upload time {}", sw);

		nextZones = newZones;
		nextRoofChanges = roofChanges;
	}

	private static boolean canReuse(Zone[][] zones, int zx, int zz)
	{
		// For tile blending, sharelight, and shadows to work correctly, the zones surrounding
		// the zone must be valid.
		for (int x = zx - 1; x <= zx + 1; ++x)
		{
			if (x < 0 || x >= NUM_ZONES)
			{
				return false;
			}
			for (int z = zz - 1; z <= zz + 1; ++z)
			{
				if (z < 0 || z >= NUM_ZONES)
				{
					return false;
				}
				Zone zone = zones[x][z];
				if (!zone.initialized)
				{
					return false;
				}
				if (zone.sizeO == 0 && zone.sizeA == 0)
				{
					return false;
				}
			}
		}
		return true;
	}

	private void loadSubScene(WorldView worldView, Scene scene)
	{
		int worldViewId = scene.getWorldViewId();
		assert worldViewId != -1;

		log.debug("Loading world view {}", worldViewId);

		SceneContext ctx0 = subs[worldViewId];
		if (ctx0 != null)
		{
			log.info("Reload of an already loaded worldview?");
			return;
		}

		final SceneContext ctx = new SceneContext(worldView.getSizeX() >> 3, worldView.getSizeY() >> 3);
		subs[worldViewId] = ctx;

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];
				mapUploader.zoneSize(scene, zone, x, z);
			}
		}

		// allocate buffers for zones which require upload
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			for (int x = 0; x < ctx.sizeX; ++x)
			{
				for (int z = 0; z < ctx.sizeZ; ++z)
				{
					Zone zone = ctx.zones[x][z];

					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						o = new VBO(sz);
						o.init(GL_STATIC_DRAW);
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						a = new VBO(sz);
						a.init(GL_STATIC_DRAW);
						a.map();
					}

					zone.init(o, a);
				}
			}

			latch.countDown();
		});
		try
		{
			latch.await();
		}
		catch (InterruptedException e)
		{
			throw new RuntimeException(e);
		}

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				mapUploader.uploadZone(scene, zone, x, z);
			}
		}
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		int worldViewId = worldView.getId();
		if (worldViewId != WorldView.TOPLEVEL)
		{
			log.debug("WorldView despawn: {}", worldViewId);
			var sub = subs[worldViewId];
			if (sub == null)
			{
				return;
			}

			sub.free();
			subs[worldViewId] = null;
		}
	}

	@Override
	public void swapScene(Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			swapSub(scene);
			return;
		}

		SceneContext ctx = root;
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (zone.cull)
				{
					zone.free();
				}
				else
				{
					// reused zone
					zone.updateRoofs(nextRoofChanges);
				}
			}
		}
		nextRoofChanges = null;

		ctx.zones = nextZones;
		nextZones = null;
		grassDiagnosticsGeneration++;
		grassDiagnosticsPending = true;
		grassPocStatusLogged = false;

		// setup vaos
		for (int x = 0; x < ctx.zones.length; ++x) // NOPMD: ForLoopCanBeForeach
		{
			for (int z = 0; z < ctx.zones[0].length; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized)
				{
					zone.unmap();
					zone.initialized = true;
				}
			}
		}

		checkGLErrors();
	}

	private void swapSub(Scene scene)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		// setup vaos
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized)
				{
					zone.unmap();
					zone.initialized = true;
				}
			}
		}
		log.debug("WorldView ready: {}", scene.getWorldViewId());
	}

	private int getScaledValue(final double scale, final int value)
	{
		return (int) (value * scale);
	}

	private void glDpiAwareViewport(final int x, final int y, final int width, final int height)
	{
		final GraphicsConfiguration graphicsConfiguration = clientUI.getGraphicsConfiguration();
		final AffineTransform t = graphicsConfiguration.getDefaultTransform();
		glViewport(
			getScaledValue(t.getScaleX(), x),
			getScaledValue(t.getScaleY(), y),
			getScaledValue(t.getScaleX(), width),
			getScaledValue(t.getScaleY(), height));
	}

	private int getDrawDistance()
	{
		return Ints.constrainToRange(config.drawDistance(), 0, MAX_DISTANCE);
	}

	private void checkGLErrors()
	{
		if (!log.isDebugEnabled())
		{
			return;
		}

		for (; ; )
		{
			int err = glGetError();
			if (err == GL_NO_ERROR)
			{
				return;
			}

			String errStr;
			switch (err)
			{
				case GL_INVALID_ENUM:
					errStr = "INVALID_ENUM";
					break;
				case GL_INVALID_VALUE:
					errStr = "INVALID_VALUE";
					break;
				case GL_INVALID_OPERATION:
					errStr = "INVALID_OPERATION";
					break;
				case GL_INVALID_FRAMEBUFFER_OPERATION:
					errStr = "INVALID_FRAMEBUFFER_OPERATION";
					break;
				default:
					errStr = "" + err;
					break;
			}

			log.debug("glGetError:", new Exception(errStr));
		}
	}

	@Subscribe
	private void onCommandExecuted(CommandExecuted event)
	{
		if (event.getCommand().equals("gpumaterialreload"))
		{
			try
			{
				SurfaceMaterialRuleCatalog.reloadBundled();
				log.info("Reloaded GPU surface material rules");
				if (client.getGameState() == GameState.LOGGED_IN)
				{
					client.setGameState(GameState.LOADING);
				}
			}
			catch (RuntimeException ex)
			{
				log.error("Unable to reload GPU surface material rules", ex);
			}
		}
		else if (event.getCommand().equals("gpumem"))
		{
			int totalSzKb = 0;
			for (int i = 0; i < rts.length; ++i)
			{
				RenderThread rt = rts[i];
				int szKb = rt.vaoO.size() + rt.vaoA.size();
				totalSzKb += szKb;
				log.info("RenderThread{}: {}kb", i, szKb);
			}
			log.info("Total: {}kb", totalSzKb);
		}
	}

}
