# Renderer architecture

This document describes the current `feature/stone-cleanup` branch. Read it
before changing renderer, OpenGL, framebuffer, lighting, shadow, sky, weather,
or UI code. Current Java and GLSL are authoritative when this document differs
from implementation.

## Art direction

GpuHD is a lightweight extension of RuneLite's GPU renderer, not a wholesale
117 HD clone or a photorealistic replacement. Preserve OSRS proportions,
recognizable layouts, major color families, stylized geometry, and readability.
Improve material definition, resolution, surface variation, vegetation,
atmospheric depth, and lighting integration without introducing noisy
hyperreal textures, inconsistent scale, or excessive contrast.

## Current render path

```text
RuneScape scene
  -> SceneUploader / ModelUploader
  -> reusable zone VAOs/VBOs and draw ranges
  -> world vertex/fragment shaders
  -> stock textures, material tags, palettes, water, shadows
  -> sky/environment, fog, weather and storm mist
  -> scene resolve/blit
  -> RuneLite UI
```

`GpuPlugin` owns frame setup and pass ordering. `SceneUploader` uploads terrain,
static objects, roofs, and their compact metadata. `ModelUploader` handles
dynamic and temporary models. `Zone` owns scene buffers, draw ranges, and roof
shadow coverage metadata.

The top-level frame currently performs these major operations:

1. Resolve frame environment, camera, projections, and world uniforms.
2. Render the static shadow map when enabled.
3. Draw the selected sky cubemap and celestial bodies.
4. Draw the ordinary RuneLite world through the existing zone buffers.
5. Apply world fragment effects including material response, water, fog, and
   color controls.
6. Draw precipitation and storm mist in `postSceneDraw`.
7. Blit the scene target, then let stock RuneLite draw UI.

## Current systems and ownership

| System | Current owner | Status |
| --- | --- | --- |
| Main frame and GL state | `GpuPlugin.java` | Authoritative current path |
| Terrain/static upload | `SceneUploader.java` | Authoritative current path |
| Dynamic model upload | `ModelUploader.java` | Authoritative current path |
| Main world shading | `vert.glsl`, `frag.glsl` | Authoritative current path |
| Stock texture array | `TextureManager.java` | Authoritative fallback |
| Exact authored albedo overrides | `AuthoredTextureOverrideAtlas.java`, `authored_texture_overrides.json` | Sparse current path; empty until mapped assets exist |
| Semantic material tags/palettes | `SurfaceMaterial*.java`, `surface_material_rules.json` | Diagnostic/behavior fallback; not an appearance authority |
| Sky and celestial environment | `GpuPlugin.java`, `sky_*.glsl`, `SunPosition.java`, `MoonPosition.java` | Current |
| Directional shadows | `GpuPlugin.java`, `Zone.java`, `shadow_*.glsl` | Current |
| Weather and storm mist | `GpuPlugin.java`, `weather_*.glsl`, `WeatherAudioController.java` | Current |
| Water | inline section of `frag.glsl`, `SceneUploader.java` | Current lightweight path |
| ID debug/export tools | `SurfaceIdDebugOverlay.java`, `SurfaceIdDebugColors.java`, `ChunkObjectExporter.java` | Current discovery tools |
| Celestial rays | none on this branch | Planned only; see `volumetrics.md` |
| 3D vegetation | none | Planned only; see `vegetation.md` |

## Depth, framebuffer, and texture conventions

The main RuneLite scene uses reversed depth: `GL_GREATER`, clear depth `0`,
and `[0,1]` clip depth where supported. The custom shadow map uses conventional
depth: `GL_LESS`, clear depth `1`, and `[-1,1]` clip depth remapped in GLSL.
Never compare or reconstruct one convention as the other.

The renderer uses the existing scene target and zone buffers; it does not own a
general HDR pipeline. The current scene color target is normalized/SDR.

| Texture unit | Current binding |
| ---: | --- |
| 1 | RuneLite stock `sampler2DArray textures` |
| 2 | conventional-depth directional shadow map |
| 3 | active sky/environment cubemap |
| 4 | stock texture array through linear sampling for stone cleanup |
| 5 | sparse authored albedo texture array when loaded |

The stock texture array is 128×128 layers. Authored albedo replacements are a
sparse standalone array loaded at 256×256 and stored as `GL_SRGB8_ALPHA8` when
immutable storage is available. Mipmaps are generated. The exact replacement
path is documented in `textures.md`.

## Packed world metadata

`tex.x` is the existing packed integer vertex field:

- low 9 bits: stock texture code (`0` untextured, `1..256` cache texture + 1);
- next 4 bits: `SurfaceMaterial` ID;
- next 3 bits: authored diagnostic slot;
- terrain upload uses the remaining high bytes for underlay/overlay source
  codes (`stored ID + 1`, zero means absent).

`tex.w` contains low water shoreline/corner bits and eligibility flags:

- `0x100`: terrain;
- `0x200`: world scenery;
- `0x800`: roof marker used by current roof/material behavior.

`vert.glsl` unpacks these values as flat metadata. Do not change the packed
contract without auditing every uploader, shader, shadow pass, and test.

## Shadows and atmosphere

The shadow framebuffer is a 4096² conventional-depth map rendered from static
opaque zone geometry with roof-dominant caster policy. `makeLightViewRotation`
must remain orthonormal, and movement along the light direction must preserve
shadow UV. Actors and alpha foliage are not detailed shadow casters.

`FrameEnvironment` is the single current source for sky mode, day/night factor,
sun/moon directions, light direction, and weather coupling. RuneLite render
world elevation uses negative Y; trace coordinate space before changing signs.

Current atmosphere consists of sky cubemaps, distance/custom fog, precipitation,
storm mist, lightning response, and weather audio. There is no celestial-ray
pass. Atmospherics-style layered volumetrics are inspiration only; see
`volumetrics.md` for the separate plan.

## Legacy, fallback, and research boundaries

The stock texture array and stock RuneLite color are authoritative fallback
behavior. Semantic material classifications and Classic/Natural/Lush palettes
are behavior/diagnostic layers. Exact imported authored mappings, when present,
are appearance authorities. Old branches and `origin/master` are research
sources, not merge targets. Do not casually rewrite sky, water, shadows,
weather, volumetrics, or UI while working on textures or terrain.

Every custom pass must restore framebuffer, viewport, program, VAO, depth state,
blend state, culling, active texture, sampler bindings, and clip-control state.
Login and UI rendering must remain isolated.

## Scoped reading and roadmap

- Texture task: `textures.md`, then its owning Java/GLSL/data files.
- Terrain task: `terrain.md`, `export-pipeline.md`, then owning files.
- Vegetation task: `vegetation.md`, `terrain.md`, then owning files.
- Renderer task: this document, then the focused owner files.

The development order is source discovery, exact texture architecture, terrain
materials, vegetation, terrain polish, then atmospheric overhaul. Do not skip
to later phases because they are visually exciting.
