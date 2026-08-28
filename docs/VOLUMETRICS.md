# Fog, Weather Mist, and Celestial Rays

Last updated: 2026-08-26. The active branch has weather precipitation and storm mist, but no celestial-ray pass.

## Goal

Atmosphere should be world-anchored, clean, and readable:

- storm fog surrounds the player and limits visibility without whitening all distant textures;
- rain/snow live in world space and do not stick to the camera;
- lightning briefly illuminates mist and produces directional shadow response;
- future sun/moon rays should be broad, stable shafts shaped by large blockers—not painted spokes, full-screen blur, or camera-tethered overlays.

## Read only these files first

| Concern | Files/anchors |
| --- | --- |
| Weather configuration and draw call | `GpuPluginConfig.java`, `GpuPlugin.java` (`drawWeather`) |
| Precipitation/mist geometry | `weather_vert.glsl` |
| Mist color/light response | `weather_frag.glsl` |
| Distance fog and wet/rain impacts | `frag.glsl` |
| Sky/environment visibility | `GpuPlugin.java` (`FrameEnvironment`, `getCelestialVisibility`), `sky_frag.glsl` |
| Working directional depth map | `GpuPlugin.java` (`renderShadowMap`), `shadow_*`, `Zone.java` |
| Rejected ray prototype | branch `mac-dev`, commit `929e42b49` |

Do not read `water.md` for a fog/ray task unless water-weather coupling is explicitly in scope.

## Current atmosphere path

### Distance fog

The main fragment shader applies ordinary RuneLite/custom fog after world color, shadows, material effects, Enhanced Colors, and weather surface response.

- Custom sky modes can provide sky-aware fog color.
- Fog Brightness controls the selected fog color.
- Fog Thickness controls how aggressively distant geometry closes into the environment.
- STORM uses camera-distance fog rather than only the normal scene-edge fog.
- Storm day/night selects a brighter or darker overcast environment.

The desired look is colored atmospheric closure, not white/gray desaturation.

### Precipitation

`drawWeather` runs in `postSceneDraw` for the top-level world, before UI.

- CLEAR: no weather pass.
- RAIN: camera-centered world-space line streaks.
- STORM: denser/longer/faster rain plus storm mist and lightning.
- SNOW: point sprites with slow drift.
- BLIZZARD: denser/faster snow; no storm-mist billboard volume currently.

The particle field wraps around the camera in world coordinates. It uses the same world projection cached during scene setup, so stretched/Retina viewport transforms remain aligned.

### Storm mist

Storm mist is the only current volumetric-style effect.

- It draws thousands of camera-centered world billboards using the weather VAO and procedural `gl_VertexID`; no duplicated world geometry.
- Puffs have fixed world-space sizes, so perspective changes their screen size naturally.
- Near/far, vertical, and camera-plane fades hide the cylinder/near-plane boundary.
- Fragment noise is radial and world-seeded, so billboard facing does not appear to rotate its texture.
- It is depth-tested against the scene with depth writes disabled.
- It samples the working conventional shadow map once per puff fragment for lightning visibility.
- Lightning adds cool-blue shaft variation and alpha; it does not maintain temporal history.

Current storm density default is 78. Particle count scales from that value; the storm path is intentionally expensive and should be profiled before increasing it.

### Lightning

Lightning uses synchronized sky textures, a short flash envelope, weather audio, main-scene surface response, shadow strength lift, and mist illumination.

The flash timing is frame-environment visual time; thunder playback uses wall-clock time. Keep visual and audio ownership explicit.

## Celestial-ray status

There are no sun/moon shafts on `feature/stone-cleanup`.

What remains:

- radial sun glare and a moon aura in `sky_frag.glsl`;
- a working 4096 directional shadow map;
- roof-dominant static caster metadata;
- environment sun/moon directions.

These are useful inputs for a future ray system, but glare is not volumetric scattering.

### Rejected implementations and lessons

#### Screen/camera-space low-resolution prototype (`mac-dev`)

The prototype rendered a low-resolution camera-space blocker mask, blurred/scattered it, and composited the result over the scene.

Observed failures:

- rays were visible mainly while looking toward the sun;
- attempts to keep an off-screen source active made the effect move with camera rotation;
- zooming out produced a massive overcast blur/blanket wash;
- the effect looked best only when the camera happened to sit inside the projected corridor;
- camera/viewport edge assumptions previously caused cutoff lines and ghost-like motion.

Do not merge or revive this path unchanged.

#### Sky-only ray tracing

Sampling blockers only in `sky_frag.glsl` cannot place shafts across visible world geometry because the world is drawn afterward and overwrites the sky. It can provide glare, but not convincing playable-scene volume.

#### World-fragment analytic cones/spokes

Analytic angular fans produced corny white ribbons. World-fragment cones only appear where opaque geometry exists and cannot fill empty air. Do not restore periodic `atan/cos` cookies, horizontal flares, or screen-space spoke patterns.

#### Temporal accumulation

Prior custom buffers on macOS produced retained-frame ghosting/cutoffs when state, viewport, or resolve behavior was wrong. The next implementation should be current-frame deterministic first. Temporal reprojection is out of scope until a non-temporal version is stable.

## Coordinate and depth rules

- RuneLite render-world elevation is negative Y.
- Use the same resolved `FrameEnvironment.activeSceneDirection` as the working shadow pass; do not independently negate it.
- Main scene depth is reversed.
- Shadow depth is conventional.
- A screen-space method needing scene depth must first resolve the multisampled depth renderbuffer into a sampleable texture with the correct convention.
- A low-resolution render target must use the exact physical gameplay viewport/aspect, not full canvas dimensions or logical pixels.
- FBO textures use bottom-left UV convention; RuneLite UI quad UVs are top-origin. Use a dedicated fullscreen vertex shader or an explicit Y correction.

## Recommended future direction

Do not begin until the user explicitly reopens celestial-ray work.

Preferred first production experiment: a small deterministic world-space volume/slice pass that reuses the working light direction and broad blocker information.

Requirements:

- no temporal history;
- additive lit-air only—occluded air must remain base color, never subtract or blanket-darken;
- large roofs/buildings/tree masses produce broad blockers;
- shingles and fine mesh detail are filtered out;
- source may be off-screen without turning into a full-screen wash;
- stable under yaw, pitch, zoom, stretch, and Retina scaling;
- storm/fog density controls visibility; clear air keeps the effect restrained;
- sky glare remains separate from shafts.

Candidate architecture:

1. Derive a 256–512 low-frequency blocker mask independent of the detailed surface-shadow appearance.
2. Bias/filter toward large silhouettes; do not blur raw depth as if it were linear visibility.
3. Integrate 4–8 fixed world-space samples or depth-tested view slices.
4. Composite before weather and UI with exact state restoration.
5. Start with static opaque/roof-dominant casters; document that actors/alpha foliage do not block until explicitly added.

Avoid forcing the 4096 detailed shadow map through a high-sample full-screen march every frame on Apple hardware.

## Non-negotiable invariants

- Custom volumetrics never alter the working 4096 surface-shadow appearance.
- No blanket darkening or global color desaturation.
- No screen-aligned card boundary, horizontal/vertical cutoff line, or retained-frame ghost.
- No dependence on UI dimensions.
- Login and SkyMode OFF must not sample stale textures.
- Every custom pass restores FBO, viewport, program, VAO, depth mask/function, blend mode, culling, active texture, and clip control.
- Weather particles/mist remain independently toggleable from future celestial rays.

## Focused validation

- Fixed world/camera, toggle effect off/on: only intended atmospheric energy changes.
- Rotate camera 360° and change pitch/zoom: volume remains world-anchored.
- Move sun Morning → Noon → Evening: shaft direction follows the same light used by shadows.
- Put the sun fully off-screen: no broad overcast wash.
- Test building corners, trees, hidden roofs, and open ground.
- Resize/stretch/Retina: no line or half-frame offset.
- Toggle GPU plugin and log in/out: no stale texture, ghost, or invalid FBO.
- Profile Mac frame time before increasing samples/resolution.
