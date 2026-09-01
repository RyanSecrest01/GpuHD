# Skybox port notes

## Behavior to preserve

- Four 3x2 cubemap atlases provide DAY, SUNSET, NIGHT, and COSMIC skies.
- Atlas wall mapping is calibrated to RuneLite coordinates: +Z north, +X east,
  -Z south, and -X west.
- RuneLite height points toward -Y. The atlas top is uploaded to cubemap -Y and
  receives the one required counter-clockwise rotation. Do not add a second
  flip/rotation in the shader.
- Cubemap faces use clamp-to-edge and linear filtering to avoid seams.
- The sky projection uses the same camera pitch/yaw and projection convention as
  the world but deliberately removes camera translation. Walking does not move
  the sky; camera rotation does.
- Preserve stretched viewport/aspect handling and the existing reversed-depth
  world clear. Translate the compact sun/moon discs into 117HD's environment
  state instead of importing GpuHD's broader celestial-ray system.

Weather sky atlases were deliberately not copied. 117HD remains authoritative
for weather and can later select these clear cubemaps through its own profiles.

## Current implementation references

- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/GpuPlugin.java`
  - `initSkyVao`
  - `loadSkyCubemap`
  - `uploadCubemapFace`
  - `uploadCubemapFaceRotatedCounterClockwise`
  - `initSkyTextures`
  - `drawCustomSky`
  - `drawSkybox`
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/SkyMode.java`
  defines OFF/DAY/SUNSET/NIGHT/COSMIC selection.
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/SkyAtlasLayout.java`
  records atlas face-layout semantics used by validation.
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/GpuPluginConfig.java`
  owns the custom-sky and environment-cycle controls.
- `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/sky_vert.glsl`
  and `sky_frag.glsl` are the copied shader sources.

During the port, keep 117HD's lighting, water, fog, weather, and environment
rendering authoritative. Expose the selected 117HD cubemap to those systems; do
not copy GpuHD water/fog code simply because it consumes `activeSkyTexture`.

