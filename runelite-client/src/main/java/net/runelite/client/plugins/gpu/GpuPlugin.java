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
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.HashMap;
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
import net.runelite.api.Perspective;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.TextureProvider;
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
import net.runelite.client.input.KeyManager;
import net.runelite.client.ui.ClientUI;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.HotkeyListener;
import net.runelite.rlawt.AWTContext;
import org.lwjgl.opengl.GL;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_SOURCE_API;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_OTHER;
import static org.lwjgl.opengl.GL43C.GL_DEBUG_TYPE_PERFORMANCE;
import static org.lwjgl.opengl.GL43C.glDebugMessageControl;
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
	private AuthoredTextureOverrideAtlas authoredTextureOverrideAtlas;

	@Inject
	private RegionManager regionManager;

	@Inject
	private DrawManager drawManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private SurfaceIdDebugOverlay surfaceIdDebugOverlay;

	@Inject
	private ChunkObjectExporter chunkObjectExporter;

	@Inject
	private KeyManager keyManager;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private RenderCallbackManager renderCallbackManager;

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
	static final Shader WEATHER_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "weather_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "weather_frag.glsl");

	static int glProgram;
	private int glUiProgram;

	private int glShadowProgram;
	private int glShadowDebugProgram;

	private int glSkyProgram;
	private int glWeatherProgram;
	private int uniWeatherProjection, uniWeatherCamera, uniWeatherTime;
	private int uniWeatherRadius, uniWeatherFallSpeed, uniWeatherWind, uniWeatherStreakLength;
	private int uniWeatherSnow, uniWeatherStorm, uniWeatherMist, uniWeatherIntensity;
	private int uniWeatherLightningFlash, uniWeatherShadowMap, uniWeatherShadowLightProj;
	private final float[] weatherProjection = Mat4.identity();
	private float weatherCameraX, weatherCameraY, weatherCameraZ;
	private final WeatherAudioController weatherAudio = new WeatherAudioController();
	private long lastThunderCycle = Long.MIN_VALUE;
	private int vaoSkyHandle;
	private int vboSkyHandle;
	private int uniSkyProj;
	private int uniSkyTexture;
	private int uniSkySunDirection;
	private int uniSkyCelestialGlowColor;
	private int uniSkyNightFactor;
	private int uniSkyMoonDirection;
	private int uniSkyCelestialVisibility;

	private int uniEnhancedColors;
	private int uniSaturation;
	private int uniContrast;
	private int uniPolygonDefinition;
	private int uniMaterialPalette;
	private int uniMaterialDebug;
	private int uniStoneWallCleanup;

	private int uniShadowLightProj;
	private int uniShadowBase;
	private int uniShadowDebugMap;

	private int uniLightDirection;
	private int uniCameraPosition;
	private int uniEnhancedWater;
	private int uniWaterStrength;
	private int uniWaterOpacity;
	private int uniEnvironmentMap;
	private int uniLightningFlash;
	private int uniWeatherModeMain;
	private int uniWeatherTimeMain;
	private int uniCelestialNightFactor;

	private int interfaceTexture;
	private int interfacePbo;

	private int cosmicSkyTexture;
	private int nightSkyTexture;
	private int daySkyTexture;
	private int sunsetSkyTexture;
	private final int[] rainSkyTextures = new int[3];
	private final int[] snowSkyTextures = new int[5];
	private final int[] lightningSkyTextures = new int[4];
	private int activeSkyTexture;

	private int vaoUiHandle;
	private int vboUiHandle;

	private int fboScene;
	private boolean sceneFboValid;
	private int rboColorBuffer;
	private int rboDepthBuffer;

	// =====================================================
	// Shadow map
	// =====================================================

	private static final int SHADOW_MAP_SIZE = 4096;
	private static final float[] MORNING_SUN = {0.65f, 0.55f, -0.52f};
	private static final float[] NOON_SUN = {0.035f, 1.0f, -0.025f};
	private static final float[] EVENING_SUN = {-0.65f, 0.48f, 0.52f};
	private static final float CELESTIAL_PEAK_ELEVATION = (float) Math.toRadians(78.0);

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

	private int textureArrayId;
	private int stoneTextureSampler;

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

	private int uniUseFog;
	private int uniFogColor;
	private int uniFogDepth;
	private int uniStormFogDensity;
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
	private int uniAuthoredTextures;
	private int uniAuthoredTextureLayers;
	private int uniAuthoredUnderlayLayers;
	private int uniAuthoredOverlayLayers;
	private int uniAuthoredTextureEnabled;
	private int uniBlockMain;
	private int uniTextureLightMode;
	private int uniTick;
	private int uniColorblindIntensity;
	private int uniUiColorblindIntensity;
	static int uniBase;

	static final float[] IDENTITY = Mat4.identity();

	private void exportCurrentChunk()
	{
		try
		{
			Path output = chunkObjectExporter.exportCurrentChunk();
			log.info("Chunk terrain/object export written to {}", output);
		}
		catch (RuntimeException ex)
		{
			log.warn("Unable to export the current terrain chunk", ex);
		}
	}

	private final HotkeyListener exportChunkHotkey = new HotkeyListener(
		() -> config.exportChunkHotkey())
	{
		@Override
		public void hotkeyPressed()
		{
			clientThread.invokeLater(GpuPlugin.this::exportCurrentChunk);
		}
	};

	@Override
	protected void startUp()
	{
		keyManager.registerKeyListener(exportChunkHotkey);
		overlayManager.add(surfaceIdDebugOverlay);
		root = new SceneContext(NUM_ZONES, NUM_ZONES);
		subs = new SceneContext[MAX_WORLDVIEWS];
		int numThreads = config.numThreads();
		rts = new RenderThread[numThreads + 1];
		for (int i = 0; i < rts.length; ++i)
		{
			var rt = rts[i] = new RenderThread();
			rt.modelUploader = new ModelUploader();
		}
		clientUploader = new SceneUploader(renderCallbackManager);
		mapUploader = new SceneUploader(renderCallbackManager);
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
				initProgram();
				initStoneTextureSampler();
				authoredTextureOverrideAtlas.initialize();
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
		keyManager.unregisterKeyListener(exportChunkHotkey);
		overlayManager.remove(surfaceIdDebugOverlay);
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
				shutdownSkyVao();
				shutdownInterfaceTexture();
				shutdownStoneTextureSampler();
				authoredTextureOverrideAtlas.shutdown();
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
			if (configChanged.getKey().equals("unlockFps")
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
				PROGRAM.compile(template, Map.of(
					"textures", 1,
					"shadowMap", 2,
					"environmentMap", 3,
					"smoothTextures", 4,
					"authoredTextures", 5));

		glUiProgram =
				UI_PROGRAM.compile(template);

		glSkyProgram =
					SKY_PROGRAM.compile(template);

		glShadowProgram =
				SHADOW_PROGRAM.compile(template);

		glShadowDebugProgram =
				SHADOW_DEBUG_PROGRAM.compile(template);
		glWeatherProgram = WEATHER_PROGRAM.compile(template, Map.of("shadowMap", 2));

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
		// up = right x forward. Reversing this Y component shears the light space.
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

	private void renderShadowMap(
			Scene scene,
			float cameraX,
			float cameraY,
			float cameraZ,
			float[] sceneLightDirection)
	{
		// RuneLite hides roofs for readability, not because they stop blocking
		// sunlight. Keep one roof-dominant caster set through that transition.
		renderLightDepthMap(
			scene,
			cameraX,
			cameraY,
			cameraZ,
			sceneLightDirection[0],
			sceneLightDirection[1],
			sceneLightDirection[2],
				shadowFbo,
				SHADOW_MAP_SIZE,
				currentShadowLightProj
		);
	}

	private void renderLightDepthMap(
			Scene scene,
			float cameraX,
			float cameraY,
			float cameraZ,
			float lightX,
			float lightY,
			float lightZ,
				int depthFbo,
				int depthMapSize,
				float[] lightProjectionTarget)
	{
		SceneContext ctx = context(scene);

		if (ctx == null)
		{
			return;
		}

		// =====================================================
		// Save current OpenGL state that we modify
		// =====================================================

		int[] previousViewport = new int[4];
		int previousDrawFramebuffer = glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
		int previousReadFramebuffer = glGetInteger(GL_READ_FRAMEBUFFER_BINDING);

		glGetIntegerv(
				GL_VIEWPORT,
				previousViewport
		);

		// =====================================================
		// Bind shadow framebuffer
		// =====================================================

		glBindFramebuffer(
				GL_FRAMEBUFFER,
				depthFbo
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
				depthMapSize,
				depthMapSize
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
		float shadowTexelSize = 2.0f * shadowRadius / depthMapSize;
		float shadowCenterX = Math.round(cameraX / shadowTexelSize) * shadowTexelSize;
		float shadowCenterY = Math.round(cameraY / shadowTexelSize) * shadowTexelSize;
		float shadowCenterZ = Math.round(cameraZ / shadowTexelSize) * shadowTexelSize;

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
				makeLightViewRotation(lightX, lightY, lightZ)
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

				zone.renderRoofDominantShadow(
					zx - offset,
					zz - offset,
					ctx.minLevel,
					ctx.maxLevel,
					uniShadowBase);
			}
		}

		// =====================================================
		// Restore normal RuneLite render state
		// =====================================================

		glBindVertexArray(0);

		// Restore both bindings exactly; depth-only passes must not leak their read
		// framebuffer into RuneLite's later scene resolve.
		glBindFramebuffer(
				GL_DRAW_FRAMEBUFFER,
				previousDrawFramebuffer
		);
		glBindFramebuffer(
				GL_READ_FRAMEBUFFER,
				previousReadFramebuffer
		);

		glUseProgram(
				glProgram
		);

		/*
		 * RuneLite's normal renderer uses reversed depth.
		 */
		glDepthFunc(
				GL_GREATER
		);

		glClearDepth(
				0.0
		);

		if (glCapabilities.OpenGL45)
		{
			glClipControl(GL_LOWER_LEFT, GL_ZERO_TO_ONE);
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
	}

	private void initUniforms()
	{
		uniShadowMap = glGetUniformLocation(glProgram, "shadowMap");
		uniShadowLightProjMain = glGetUniformLocation(glProgram, "shadowLightProj");
		uniShadowsEnabled = glGetUniformLocation(glProgram, "shadowsEnabled");
		uniShadowStrength = glGetUniformLocation(glProgram, "shadowStrength");
		uniSkyTexture = glGetUniformLocation(glSkyProgram, "skyTexture");
		uniSkyProj = glGetUniformLocation(glSkyProgram, "skyProj");
		uniSkySunDirection = glGetUniformLocation(glSkyProgram, "sunDirection");
		uniSkyCelestialGlowColor = glGetUniformLocation(glSkyProgram, "celestialGlowColor");
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
		uniPolygonDefinition = glGetUniformLocation(glProgram, "polygonDefinition");
		uniMaterialPalette = glGetUniformLocation(glProgram, "materialPalette");
		uniMaterialDebug = glGetUniformLocation(glProgram, "materialDebug");
		uniStoneWallCleanup = glGetUniformLocation(glProgram, "stoneWallCleanup");
		uniLightDirection = glGetUniformLocation(glProgram, "lightDirection");
		uniCameraPosition = glGetUniformLocation(glProgram, "cameraPosition");
		uniEnhancedWater = glGetUniformLocation(glProgram, "enhancedWater");
		uniWaterStrength = glGetUniformLocation(glProgram, "waterStrength");
		uniWaterOpacity = glGetUniformLocation(glProgram, "waterOpacity");
		uniEnvironmentMap = glGetUniformLocation(glProgram, "environmentMap");
		uniLightningFlash = glGetUniformLocation(glProgram, "lightningFlash");
		uniWeatherModeMain = glGetUniformLocation(glProgram, "weatherMode");
		uniWeatherTimeMain = glGetUniformLocation(glProgram, "weatherTime");
		uniCelestialNightFactor = glGetUniformLocation(glProgram, "celestialNightFactor");
		uniSmoothBanding = glGetUniformLocation(glProgram, "smoothBanding");
		uniBrightness = glGetUniformLocation(glProgram, "brightness");
		uniUseFog = glGetUniformLocation(glProgram, "useFog");
		uniFogColor = glGetUniformLocation(glProgram, "fogColor");
		uniFogDepth = glGetUniformLocation(glProgram, "fogDepth");
		uniStormFogDensity = glGetUniformLocation(glProgram, "stormFogDensity");
		uniDrawDistance = glGetUniformLocation(glProgram, "drawDistance");
		uniExpandedMapLoadingChunks = glGetUniformLocation(glProgram, "expandedMapLoadingChunks");
		uniTextureLightMode = glGetUniformLocation(glProgram, "textureLightMode");
		uniTick = glGetUniformLocation(glProgram, "tick");
		uniBlockMain = glGetUniformBlockIndex(glProgram, "uniforms");
		uniTextures = glGetUniformLocation(glProgram, "textures");
		uniTextureAnimations = glGetUniformLocation(glProgram, "textureAnimations");
		uniAuthoredTextures = glGetUniformLocation(glProgram, "authoredTextures");
		uniAuthoredTextureLayers = glGetUniformLocation(glProgram, "authoredTextureLayers");
		uniAuthoredUnderlayLayers = glGetUniformLocation(glProgram, "authoredUnderlayLayers");
		uniAuthoredOverlayLayers = glGetUniformLocation(glProgram, "authoredOverlayLayers");
		uniAuthoredTextureEnabled = glGetUniformLocation(glProgram, "authoredTextureEnabled");
		uniBase = glGetUniformLocation(glProgram, "base");
		uniColorblindIntensity = glGetUniformLocation(glProgram, "colorblindIntensity");

		uniTex = glGetUniformLocation(glUiProgram, "tex");
		uniTexTargetDimensions = glGetUniformLocation(glUiProgram, "targetDimensions");
		uniTexSourceDimensions = glGetUniformLocation(glUiProgram, "sourceDimensions");
		uniUiAlphaOverlay = glGetUniformLocation(glUiProgram, "alphaOverlay");
		uniUiColorblindIntensity = glGetUniformLocation(glUiProgram, "colorblindIntensity");
		uniShadowDebugMap = glGetUniformLocation(glShadowDebugProgram, "shadowMap");
		uniWeatherProjection = glGetUniformLocation(glWeatherProgram, "projection");
		uniWeatherCamera = glGetUniformLocation(glWeatherProgram, "cameraPosition");
		uniWeatherTime = glGetUniformLocation(glWeatherProgram, "time");
		uniWeatherRadius = glGetUniformLocation(glWeatherProgram, "radius");
		uniWeatherFallSpeed = glGetUniformLocation(glWeatherProgram, "fallSpeed");
		uniWeatherWind = glGetUniformLocation(glWeatherProgram, "wind");
		uniWeatherStreakLength = glGetUniformLocation(glWeatherProgram, "streakLength");
		uniWeatherSnow = glGetUniformLocation(glWeatherProgram, "snow");
		uniWeatherStorm = glGetUniformLocation(glWeatherProgram, "storm");
		uniWeatherMist = glGetUniformLocation(glWeatherProgram, "mist");
		uniWeatherIntensity = glGetUniformLocation(glWeatherProgram, "intensity");
		uniWeatherLightningFlash = glGetUniformLocation(glWeatherProgram, "lightningFlash");
		uniWeatherShadowMap = glGetUniformLocation(glWeatherProgram, "shadowMap");
		uniWeatherShadowLightProj = glGetUniformLocation(glWeatherProgram, "shadowLightProj");
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

	private void initStoneTextureSampler()
	{
		stoneTextureSampler = glGenSamplers();
		glSamplerParameteri(stoneTextureSampler, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
		glSamplerParameteri(stoneTextureSampler, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glSamplerParameteri(stoneTextureSampler, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glSamplerParameteri(stoneTextureSampler, GL_TEXTURE_WRAP_T, GL_REPEAT);
		glBindSampler(4, stoneTextureSampler);
	}

	private void bindStoneTextureArray()
	{
		glActiveTexture(GL_TEXTURE4);
		glBindTexture(GL_TEXTURE_2D_ARRAY, textureArrayId);
		glBindSampler(4, stoneTextureSampler);
		glActiveTexture(GL_TEXTURE0);
	}

	private void shutdownStoneTextureSampler()
	{
		if (stoneTextureSampler != 0)
		{
			glBindSampler(4, 0);
			glDeleteSamplers(stoneTextureSampler);
			stoneTextureSampler = 0;
		}
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
			 * RuneLite's visible zenith is cubemap -Y. Keep all four calibrated
			 * wall assignments above untouched and map only the clouded TOP atlas
			 * cell to the overhead cap.
			 */
			uploadCubemapFace(
					image,
					GL_TEXTURE_CUBE_MAP_POSITIVE_Y,
					0,
					0,
					faceWidth
			);

			uploadCubemapFace(
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

	private void initSkyTextures()
	{
		cosmicSkyTexture = loadSkyCubemap("cosmic_test.png");
		nightSkyTexture = loadSkyCubemap("night_test.png");
		daySkyTexture = loadSkyCubemap("day_test.png");
		sunsetSkyTexture = loadSkyCubemap("sunset_test.png");
		rainSkyTextures[0] = loadSkyCubemap("weather/skies/rain/sky283_day_rain.png");
		rainSkyTextures[1] = loadSkyCubemap("weather/skies/rain/sky280_sunset_rain.png");
		rainSkyTextures[2] = loadSkyCubemap("weather/skies/rain/sky282_night_rain.png");
		snowSkyTextures[0] = loadSkyCubemap("weather/skies/snow/sky273_day_snow.png");
		snowSkyTextures[1] = loadSkyCubemap("weather/skies/snow/sky271_sunrise_snow.png");
		snowSkyTextures[2] = loadSkyCubemap("weather/skies/snow/sky272_night_snow.png");
		snowSkyTextures[3] = loadSkyCubemap("weather/skies/snow/sky278_thunder_snow.png");
		snowSkyTextures[4] = loadSkyCubemap("weather/skies/snow/sky279_thunder_snow_high.png");
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

		log.info("Initialized {}x{} shadow map", SHADOW_MAP_SIZE, SHADOW_MAP_SIZE);
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

		// Reset
		glBindFramebuffer(GL_FRAMEBUFFER, awtContext.getFramebuffer(false));
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

	private void setEnvironmentCelestialGlowColor(int uniform)
	{
		ensureFrameEnvironment();
		SkyMode environmentSky = frameEnvironment.skyMode;
		float glowR = 1.0f;
		float glowG = 0.82f;
		float glowB = 0.55f;
		if (environmentSky == SkyMode.DAY)
		{
			glowR = 1.0f;
			glowG = 0.93f;
			glowB = 0.72f;
		}
		else if (environmentSky == SkyMode.NIGHT)
		{
			glowR = 0.42f;
			glowG = 0.55f;
			glowB = 0.85f;
		}
		else if (environmentSky == SkyMode.COSMIC)
		{
			glowR = 0.62f;
			glowG = 0.38f;
			glowB = 0.92f;
		}

		glUniform3f(uniform, glowR, glowG, glowB);
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
			// The sunset environment is the brighter of the two fully overcast
			// storm presets. The old day cubemap has visible clear-sky holes.
			return config.stormSkyMode() == StormSkyMode.DAY ? SkyMode.SUNSET : SkyMode.NIGHT;
		}
		if (weather == WeatherMode.BLIZZARD)
		{
			return SkyMode.NIGHT;
		}
		if (weather == WeatherMode.RAIN)
		{
			return SkyMode.SUNSET;
		}
		if (weather == WeatherMode.SNOW)
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
				setArcDirection(
					environment.sunDirection,
					MORNING_SUN,
					EVENING_SUN,
					(float) (phase / 0.50));
			}
			else if (phase < 0.85)
			{
				setArcDirection(
					environment.sunDirection,
					EVENING_SUN,
					MORNING_SUN,
					(float) ((phase - 0.50) / 0.35));
			}
			else
			{
				copyDirection(MORNING_SUN, environment.sunDirection);
			}
			// Only one celestial body is visible at a time. Sharing the continuous
			// orbit keeps weather-forced day/night modes free of hidden reset jumps.
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

	private static float getCelestialVisibility(WeatherMode weather)
	{
		return weather == WeatherMode.STORM || weather == WeatherMode.BLIZZARD
			? 0.08f
			: weather == WeatherMode.RAIN
				? 0.22f
				: weather == WeatherMode.SNOW ? 0.30f : 1.0f;
	}

	private void drawCustomSky(
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

		if (lightningTexture != 0 && (weather == WeatherMode.STORM || weather == WeatherMode.BLIZZARD))
		{
			selectedSkyTexture = lightningTexture;
		}
		else if (weather == WeatherMode.RAIN)
		{
			selectedSkyTexture = rainSkyTextures[0];
		}
		else if (weather == WeatherMode.STORM)
		{
			selectedSkyTexture = config.stormSkyMode() == StormSkyMode.DAY
				? rainSkyTextures[1] : rainSkyTextures[2];
		}
		else if (weather == WeatherMode.SNOW)
		{
			selectedSkyTexture = snowSkyTextures[0];
		}
		else if (weather == WeatherMode.BLIZZARD)
		{
			selectedSkyTexture = snowSkyTextures[3];
		}
		else switch (frameEnvironment.skyMode)
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
		float[] sun = frameEnvironment.sunDirection;
		// RuneLite model-space height and the sky cube's vertical axis are opposed.
		glUniform3f(uniSkySunDirection, sun[0], -sun[1], sun[2]);
		float[] moon = frameEnvironment.moonDirection;
		glUniform3f(uniSkyMoonDirection, moon[0], -moon[1], moon[2]);
		setEnvironmentCelestialGlowColor(uniSkyCelestialGlowColor);
		glUniform1f(uniSkyNightFactor, frameEnvironment.nightFactor);
		glUniform1f(uniSkyCelestialVisibility, getCelestialVisibility(weather));

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
		long frameNow = System.currentTimeMillis();
		FrameEnvironment environment = updateFrameEnvironment(frameNow);
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
			textureManager.setSamplerAnisotropicFilteringLevel(
					stoneTextureSampler,
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
		boolean stormFog = config.weatherMode() == WeatherMode.STORM;

		final int sky =
				client.getSkyboxColor();

		float fogR;
		float fogG;
		float fogB;

		// =====================================================
		// Custom sky-aware fog
		// =====================================================

		if (getEnvironmentSkyMode() != SkyMode.OFF
				&& config.customFog())
		{
			switch (getEnvironmentSkyMode())
			{
				case DAY:
					// Pale cool daylight haze
					fogR = 0.72f;
					fogG = 0.80f;
					fogB = 0.84f;
					break;

				case SUNSET:
					// Smoky blue/purple-gray
					fogR = 0.34f;
					fogG = 0.31f;
					fogB = 0.38f;
					break;

				case NIGHT:
					// Very dark blue atmospheric haze
					fogR = 0.055f;
					fogG = 0.070f;
					fogB = 0.105f;
					break;

				case COSMIC:
					// Almost-black violet
					fogR = 0.030f;
					fogG = 0.018f;
					fogB = 0.055f;
					break;

				default:
					fogR = 0.5f;
					fogG = 0.5f;
					fogB = 0.5f;
					break;
			}

			float fogBrightness =
					config.customFogBrightness() / 100.0f;

			fogR *= fogBrightness;
			fogG *= fogBrightness;
			fogB *= fogBrightness;

			fogDepth =
					config.customFogStrength();
		}

		else
		{
			// Normal RuneLite fog behavior
			fogR =
					(sky >> 16 & 0xFF) / 255f;

			fogG =
					(sky >> 8 & 0xFF) / 255f;

			fogB =
					(sky & 0xFF) / 255f;
		}

		// Storm fog uses the existing Fog thickness control, but unlike RuneLite's
		// normal edge-only fog it is measured outward from the camera.
		if (stormFog)
		{
			float stormBrightness = config.customFogBrightness() / 100.0f;
			boolean dayStorm = config.stormSkyMode() == StormSkyMode.DAY;
			fogR = (dayStorm ? 0.12f : 0.055f) * stormBrightness;
			fogG = (dayStorm ? 0.16f : 0.075f) * stormBrightness;
			fogB = (dayStorm ? 0.21f : 0.115f) * stormBrightness;
		}

		glUniform1i(
				uniUseFog,
				fogDepth > 0 || stormFog ? 1 : 0
		);

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

		glUniform1f(
				uniStormFogDensity,
				stormFog ? config.stormFogDensity() / 100.0f : 0.0f
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

		glUniform1f(
				uniPolygonDefinition,
				client.getGameState() == GameState.LOGGED_IN
					? Math.max(0f, Math.min(1f, config.polygonDefinition() / 100f))
					: 0f
		);

		glUniform1i(
				uniMaterialPalette,
				client.getGameState() == GameState.LOGGED_IN
					? config.materialPalette().getId()
					: MaterialPalette.CLASSIC.getId()
		);

		glUniform1i(
				uniMaterialDebug,
				client.getGameState() == GameState.LOGGED_IN && config.materialDebug()
					? 1
					: 0
		);

		glUniform1f(
				uniStoneWallCleanup,
				client.getGameState() == GameState.LOGGED_IN
					? Math.max(0f, Math.min(1f, config.stoneWallCleanup() / 100f))
					: 0f
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

		// =====================================================
		// Selective celestial effects. Stock RuneLite surface color remains the
		// base; only cast shadows, material highlights, and weather are layered on it.
		// =====================================================
		long weatherNow = frameNow;
		WeatherMode activeWeather = config.weatherMode();
		float lightningFlash = activeWeather == WeatherMode.STORM || activeWeather == WeatherMode.BLIZZARD
			? getLightningFlash(weatherNow) : 0.0f;
		boolean lightningShadow = lightningFlash > 0.0f;
		boolean surfaceShadowsActive = client.getGameState() == GameState.LOGGED_IN
			&& ((config.dynamicShadows()
				&& (config.shadowStrength() > 0 || config.shadowDebug()))
				|| lightningShadow);
		// One frame snapshot drives material highlights, sky bodies, and the shadow
		// pass. Highlights use virtual +Y-up; scene/sky depth uses RuneLite's
		// vertically inverted coordinate convention.
		float[] sun = environment.activeLightDirection;
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
		glUniform1f(uniCelestialNightFactor, environment.nightFactor);

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
		glUniform1f(uniLightningFlash, lightningFlash);
		glUniform1i(uniWeatherModeMain, activeWeather.ordinal());
		glUniform1f(uniWeatherTimeMain,
			(weatherNow % 600_000L) / 1000.0f);

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

		if (surfaceShadowsActive)
		{
			renderShadowMap(
					scene,
					cameraX,
					cameraY,
					cameraZ,
					environment.activeSceneDirection
			);
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
				surfaceShadowsActive ? 1 : 0
		);

		glUniform1f(
				uniShadowStrength,
				Math.max(config.shadowStrength() / 100.0f, lightningFlash * 0.72f)
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
				sky,
				cameraPitch,
				cameraYaw
		);

		// Reuse the selected sky cubemap as the wet-surface environment. Keep it
		// isolated on its own unit so RuneLite's texture array and shadow map stay
		// untouched.
		glUseProgram(glProgram);
		glActiveTexture(GL_TEXTURE3);
		glBindTexture(GL_TEXTURE_CUBE_MAP, activeSkyTexture);
		glUniform1i(uniEnvironmentMap, 3);
		glActiveTexture(GL_TEXTURE0);

		checkGLErrors();
	}

	private void drawSkybox(
			int sky,
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
				cameraPitch,
				cameraYaw);
		}
		else
		{
			// Keep reflections deterministic when custom sky rendering is disabled.
			activeSkyTexture = daySkyTexture;
		}
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			drawWeather();
			postDrawToplevel();
		}
		else
		{
			glUniform4i(uniEntityTint, 0, 0, 0, 0);
			glUniformMatrix4fv(uniEntityProj, false, IDENTITY);
		}
	}

	private void drawWeather()
	{
		WeatherMode mode = config.weatherMode();
		long visualNow = frameEnvironment.timeMillis;
		updateWeatherAudio(mode, System.currentTimeMillis());
		if (mode == WeatherMode.CLEAR || glWeatherProgram == 0)
		{
			return;
		}

		boolean snow = mode == WeatherMode.SNOW || mode == WeatherMode.BLIZZARD;
		boolean severe = mode == WeatherMode.STORM || mode == WeatherMode.BLIZZARD;
		boolean storm = mode == WeatherMode.STORM;
		int particles = (storm ? 96000 : severe ? 65000 : 28000) * config.weatherDensity() / 100;
		glUseProgram(glWeatherProgram);
		glUniformMatrix4fv(uniWeatherProjection, false, weatherProjection);
		glUniform3f(uniWeatherCamera, weatherCameraX, weatherCameraY, weatherCameraZ);
		glUniform1f(uniWeatherTime, (visualNow % 600_000L) / 1000.0f);
		glUniform1f(uniWeatherRadius, storm ? 1550.0f : severe ? 1750.0f : 1950.0f);
		glUniform1f(uniWeatherFallSpeed, snow ? (severe ? 210.0f : 135.0f) : (storm ? 2400.0f : 1250.0f));
		glUniform1f(uniWeatherWind, config.weatherWind() * (severe ? 4.0f : 2.2f));
		glUniform1f(uniWeatherStreakLength, storm ? 255.0f : severe ? 210.0f : 145.0f);
		glUniform1i(uniWeatherSnow, snow ? 1 : 0);
		glUniform1i(uniWeatherStorm, storm ? 1 : 0);
		glUniform1i(uniWeatherMist, 0);
		float flash = severe ? getLightningFlash(visualNow) : 0.0f;
		glUniform1f(uniWeatherLightningFlash, flash);
		glUniform1f(uniWeatherIntensity, (storm ? 0.82f : severe ? 0.72f : 0.48f) + flash * 0.28f);
		glUniformMatrix4fv(uniWeatherShadowLightProj, false, currentShadowLightProj);
		glActiveTexture(GL_TEXTURE2);
		glBindTexture(GL_TEXTURE_2D, shadowDepthTexture);
		glUniform1i(uniWeatherShadowMap, 2);
		glActiveTexture(GL_TEXTURE0);

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

		// A separate camera-centered mist volume puts atmosphere into empty air.
		// It reuses the weather VAO and remains depth-tested against the scene.
		if (storm && client.getGameState() == GameState.LOGGED_IN)
		{
			float mistDensity = config.stormFogDensity() / 100.0f;
			int mistParticles = 9000 * config.stormFogDensity() / 100;
			glUniform1i(uniWeatherMist, 1);
			glUniform1i(uniWeatherSnow, 0);
			// Keep the particle-volume boundary behind the distance fog. The old
			// 3,000-unit cylinder could project a visible cutoff into the scene.
			glUniform1f(uniWeatherRadius, 6800.0f);
			glUniform1f(uniWeatherIntensity, mistDensity);
			glDrawArrays(GL_TRIANGLES, 0, mistParticles * 6);
			glUniform1i(uniWeatherMist, 0);
		}
		glBindVertexArray(0);
		if (snow)
		{
			glDisable(GL_PROGRAM_POINT_SIZE);
		}
		glDepthMask(true);
		glEnable(GL_CULL_FACE);
		glUseProgram(glProgram);
	}

	private void updateWeatherAudio(WeatherMode mode, long now)
	{
		boolean enabled = config.weatherSounds();
		int volume = config.weatherVolume();
		weatherAudio.update(mode, enabled, volume);
		boolean lightningWeather = mode == WeatherMode.STORM || mode == WeatherMode.BLIZZARD;
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
		glActiveTexture(GL_TEXTURE3);
		glBindTexture(GL_TEXTURE_CUBE_MAP, 0);
		glActiveTexture(GL_TEXTURE0);
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
		z.renderOpaque(zx - offset, zz - offset, ctx.minLevel, ctx.level, ctx.maxLevel, ctx.hideRoofIds);

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
				for (int i = 0; i < rts.length; ++i) // NOPMD: ForLoopCanBeForeach
				{
					rts[i].vaoO.draw();
				}
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

		int size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		if (m.getFaceTransparencies() == null)
		{
			RenderThread rt = rts[renderThreadId + 1];
			VAO o = rt.vaoO.get(size);
			if (o == null)
			{
				return;
			}

			rt.modelUploader.uploadTempModel(m, orient, x, y, z, o.vbo.vb);
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
				sorter.uploadSortedModel(rt, worldProjection, m, orient, x, y, z, o.vbo.vb, a.vbo.vb, false);
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
				uploader.uploadSortedModel(rt, worldProjection, m, orient, x, y, z, o.vbo.vb, a.vbo.vb, renderMode == Renderable.RENDERMODE_SORTED_NO_DEPTH);
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
			uploader.uploadTempModel(m, orient, x, y, z, o.vbo.vb);
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
				bindStoneTextureArray();
				// if texture upload is successful, compute and set texture animations
				float[] texAnims = textureManager.computeTextureAnimations(textureProvider);
				glUseProgram(glProgram);
				glUniform2fv(uniTextureAnimations, texAnims);
				glUseProgram(0);
			}
		}

		if (authoredTextureOverrideAtlas.textureId() != 0)
		{
			glUseProgram(glProgram);
			glActiveTexture(GL_TEXTURE5);
			glBindTexture(GL_TEXTURE_2D_ARRAY, authoredTextureOverrideAtlas.textureId());
			glUniform1i(uniAuthoredTextures, 5);
			glUniform1iv(uniAuthoredTextureLayers, authoredTextureOverrideAtlas.textureLayers());
			glUniform1iv(uniAuthoredUnderlayLayers, authoredTextureOverrideAtlas.underlayLayers());
			glUniform1iv(uniAuthoredOverlayLayers, authoredTextureOverrideAtlas.overlayLayers());
			glUniform1i(uniAuthoredTextureEnabled, 1);
			glActiveTexture(GL_TEXTURE0);
		}
		else
		{
			glUseProgram(glProgram);
			glUniform1i(uniAuthoredTextureEnabled, 0);
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

		if (config.shadowDebug() && config.dynamicShadows())
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
		for (int i = 0; i < snowSkyTextures.length; ++i)
		{
			if (snowSkyTextures[i] != 0)
			{
				glDeleteTextures(snowSkyTextures[i]);
				snowSkyTextures[i] = 0;
			}
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
		if (event.getCommand().equals("gpuexport"))
		{
			clientThread.invokeLater(this::exportCurrentChunk);
			return;
		}
		if (event.getCommand().equals("gpumem"))
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
