# Renderer, Shadows, Materials, Color, and Textures

Last updated: 2026-08-26. This describes the active `feature/stone-cleanup` worktree, not every experiment on other branches.

## Visual goal

Keep RuneLite's GPU renderer lightweight and recognizable while making the world cleaner and more expressive:

- stock RuneLite color is the baseline;
- directional cast shadows provide depth without blanket relighting;
- material tags allow small, deliberate material responses;
- palettes and Enhanced Colors remain independent;
- texture improvements are sparse and curated rather than a global HD pack;
- UI and login rendering remain stock.

The project explicitly rejected a global derivative-normal diffuse-lighting layer. It made low-poly terrain triangles and tile boundaries obvious, darkened roofs, and caused “shadows” that read as blanket darkness.

## Branch map

| Branch | Purpose | Important warning |
| --- | --- | --- |
| `feature/stone-cleanup` | Active baseline: shadows, material tags/palettes, polygon definition, targeted stone cleanup | Check the worktree before editing; refinements may be uncommitted |
| `feature/material-palette` | Material packing/classification and palette milestone | Useful history for palette regressions |
| `feature/polygon-definition` | Celestial rays removed; bounded polygon definition added | Basis for the clean renderer direction |
| `feature/texture-pop` | Texture clarity/perceptual color experiment | LOD bias and AF produced little visible improvement by themselves |
| `mac-dev` | Low-resolution camera-space celestial-ray prototype | Rejected because rays moved with the camera and became a broad blur |
| `origin/master` | Larger experimental renderer, including deferred water/material work | Research source only; do not merge wholesale |

## Focused file map

Read these files, not the entire repository:

| Concern | Primary files |
| --- | --- |
| Frame setup, environment, GL passes, texture bindings | `GpuPlugin.java` |
| User settings and defaults | `GpuPluginConfig.java` |
| Main world shading | `frag.glsl`, `vert.glsl` |
| Stock texture array/filtering | `TextureManager.java` |
| Static/dynamic geometry upload | `SceneUploader.java`, `ModelUploader.java` |
| Material packing/classification | `SurfaceMaterial.java`, `SurfaceMaterialClassifier.java` |
| Zone draw ranges and roof-dominant casters | `Zone.java` |
| Sky rendering | `sky_vert.glsl`, `sky_frag.glsl`, `SkyMode.java` |
| Shadow depth pass | `shadow_vert.glsl`, `shadow_frag.glsl`, `shadow_debug_*` |
| Matrix correctness tests | `GpuPluginLightMatrixTest.java` |
| Material tests | `SurfaceMaterialClassifierTest.java` |

Use `rg` for the method/uniform names in this document instead of browsing whole files.

## Current frame path

High-level top-level-world order:

1. `preSceneDraw`/top-level setup resolves one `FrameEnvironment` for the frame.
2. The RuneLite camera/world projection and main uniforms are uploaded.
3. If enabled, `renderShadowMap` renders static opaque zone geometry into a 4096² depth map.
4. `drawSkybox` clears the scene target and draws the selected cubemap plus procedural sun/moon.
5. RuneLite draws the ordinary world with the existing zone/model VAOs and `vert.glsl`/`frag.glsl`.
6. `postSceneDraw` draws precipitation and storm mist before leaving the scene FBO.
7. The scene FBO is blitted to the AWT framebuffer; UI is drawn afterward by the stock UI shader.

The current scene color target is SDR/normalized, not an internal HDR pipeline. Shaders generally clamp to `[0,1]`, and the scene is blitted without a tone-map pass.

### Texture units

| Unit | Current binding |
| --- | --- |
| 0 | transient/default operations |
| 1 | stock `sampler2DArray textures` |
| 2 | 4096 shadow depth texture |
| 3 | active sky/environment cubemap |
| 4 | the stock texture array again through a linear sampler for tagged stone cleanup |
| 5 | currently free; proposed for a sparse standalone replacement texture |

Do not place two sampler types on the same unit. Restore the active unit after custom texture work.

## Environment state

`GpuPlugin.FrameEnvironment` is the frame-owned source for:

- resolved `SkyMode`;
- day/night factor;
- sun and moon directions;
- active light direction;
- scene-space celestial direction.

The day/night cycle interpolates the environment when enabled. Fixed `SunPosition` and `MoonPosition` settings own the direction when it is disabled.

RuneLite render-world elevation uses negative Y. The shader's virtual material-lighting convention and the scene-space shadow direction are deliberately different representations. `activeSceneDirection` flips the environment Y exactly once for world/light-depth projection. Do not casually negate or “correct” these vectors.

The visible sun and moon are rendered in `sky_frag.glsl`. The sun is a stylized pale-gold body with radial glare and a small face. It has no angular spoke cookie and no celestial-ray sampling.

## Directional shadows

### What works

- One 4096² `GL_DEPTH_COMPONENT` shadow map.
- Orthographic directional-light projection centered and texel-snapped near the current camera.
- Conventional shadow depth: `GL_LESS`, clear depth `1`, clip depth `[-1,1]` remapped in the fragment shader.
- Main RuneLite scene restored to reversed depth: `GL_GREATER`, clear depth `0`, and `[0,1]` clip control on GL 4.5.
- 3×3 PCF in `frag.glsl`, constant receiver bias `0.00040`, distance-scaled sub-texel filter radius, and a map-edge confidence fade.
- Shadow color is a bounded day/night transmission multiplier. It does not compute a global N·L diffuse term.
- The fixed sun/moon direction and shadow displacement agree: Morning, Noon, and Evening visibly change cast direction and length.

### Critical matrix invariant

`makeLightViewRotation` must remain orthonormal. The correct up vector is `right × forward`. A former sign error in its Y component sheared light space and prevented coherent projected silhouettes.

For any world point `p` and scalar `t`, movement along the light direction must preserve shadow UV:

```text
uv(M * (p + tL)) == uv(M * p)
```

while depth changes monotonically. `GpuPluginLightMatrixTest` protects this behavior.

### Roof policy

The shadow pass calls `Zone.renderRoofDominantShadow`, not the visible-scene roof policy.

- Hidden roofs remain casters when the player enters a building, preventing exterior shadows from popping to the ground-floor outline.
- Upload-time `CoveredShadowRange` metadata subtracts covered lower wall/fully covered object ranges when an opaque replacement roof exists.
- Terrain, ground/decorative objects, bridges, and partially protruding objects remain conservative casters.

Known tradeoff: a retained hidden roof can shade an interior receiver. Avoid trying to solve that by switching the whole caster set back to visible roofs; an exterior-only result would need a receiver/roof-footprint mask.

### Shadow limitations

- Static opaque zone geometry only. Actors, temporary models, and alpha foliage do not currently cast.
- One orthographic map; no cascades.
- Shadow-map rendering is expensive at 4096² and runs every active frame.
- Bias tuning must remain isolated. Normal-dependent bias previously created triangle variation; too-small bias caused map-wide self-shadow acne.

Acceptance invariant: with Cast Shadows disabled, or where raw shadow occlusion is zero, the shadow subsystem must not change stock surface color.

## Material tagging

Material identity is computed once during upload and packed without changing the vertex stride.

### Packing contract

- `tex.x` low 9 bits: stock texture code (`0` untextured, `1..256` cache texture + 1).
- `tex.x` next 4 bits: `SurfaceMaterial` ID.
- `tex.w` bit `0x100`: terrain eligibility.
- `tex.w` bit `0x200`: world-scenery eligibility.
- `vert.glsl` masks the stock texture code before texture animation/array indexing and passes material ID as `flat`.

Materials: Unknown, Grass, Stone, Sand, Dirt, Wood, Metal, Foliage, Water.

`SceneUploader` classifies terrain from exact texture IDs or conservative HSL consensus. Ordinary models use exact texture IDs only. `ModelUploader` marks known world objects/scenery; actor/login buffers retain historical zero flags, preventing accidental palette recoloring.

Do not widen the VAO or HSL-classify arbitrary actors to solve a missing world tag. Add a conservative texture/object classification instead.

## Color and polygon definition

### Material palettes

`MaterialPalette` provides Classic, Natural, and Lush presets.

- Classic is a true bypass.
- Palette transforms preserve Rec.709 luminance and alter material chroma/hue only.
- Water is excluded from palette grading when its dedicated enhanced path owns the result.
- Material Debug is the first tool for diagnosing “this setting does nothing.”

Natural is the intended balanced default. Lush is deliberately stylized and can make stone cool/slate and terrain strongly saturated.

### Enhanced Colors

Enhanced Colors remains a later, independent world-only grade. Neutral values are saturation `100` and contrast `100`. Fog is applied after grading so atmospheric color is not regraded.

### Polygon Definition

This is inspired by the faceted read of 117 HD flat shading, but this renderer has no uploaded smooth vertex-normal stream. It uses a derivative face normal to add a small, headroom-limited lift to light-facing opaque geometry.

- It never darkens stock color.
- It does not draw polygon outlines.
- Shadow blockers suppress the lift.
- Tagged vertical stone cleanup suppresses it.

At high values it exposes terrain triangulation. Default `35` is a compromise; a future refinement should reduce/cap the effect on `TERRAIN_FLAG` while retaining it on props/models.

## Texture quality and stone cleanup

The stock texture array is 256 layers of 128×128 RGBA8 data with generated mipmaps. Anisotropic filtering improves oblique minification but cannot create missing detail or remove baked grain. Global negative LOD bias/“Texture Clarity” was visually ineffective and risked shimmer, so it is not the active strategy.

Current targeted stone path:

- Only textured `STONE` + `WORLD_SCENERY_FLAG` + near-vertical faces qualify.
- Unit 4 resamples the stock layer with linear/trilinear filtering.
- A 5-tap edge-aware cross filter reduces low-contrast intra-brick noise while preserving mortar edges.
- Extra taps fade out under minification, where mipmaps already low-pass.
- Stone also suppresses environment reflection, direct specular, and polygon definition so neutral gray is not mistaken for polished metal.
- Slider `0` is exact stock behavior.

Verified Edgeville mapping:

- Game object `BRICKWALL`/ID 1902 uses models 634/635/574/575/576.
- Most visible faces use cache texture ID `2`, classified as Stone and fully vertical.
- `DRYSTONEWALL`/ID 979 uses cache texture ID `11`.
- Some masonry is untextured and will not be fixed by a texture-ID override.

Candidate third-party textures currently exist under `resources/.../gpu/textures/`, but no replacement texture is wired into the renderer yet. The intended next step is a sparse standalone texture on unit 5, gated to cache texture ID 2 plus the existing vertical Stone/world-scenery mask. Keep the stock layer untouched and retain missing-resource fallback.

Do not enlarge the entire stock array to 256/512. A single 256² RGBA8 replacement with mipmaps is about 341 KiB; a full 256-layer 256² array is roughly 85 MiB.

## HDR status

There is no true HDR output or internal HDR scene today.

Possible future internal-HDR path:

1. Render scene color into RGBA16F.
2. Resolve to a single-sample FP16 texture.
3. Apply exposure/white balance/filmic tone mapping in a fullscreen pass.
4. Draw UI afterward at reference white.

This can remain cross-platform SDR output and still improve sun, lightning, specular, and bloom. True Windows HDR/macOS EDR requires native presentation changes outside the GPU shader alone and is a separate project.

## Near-term priorities

1. Finish and validate one sparse clean replacement for Edgeville cache texture ID 2.
2. Reduce Polygon Definition specifically on terrain without weakening props/models.
3. Extend material classification only from evidence gathered with Material Debug.
4. Keep shadow behavior stable; do not mix texture work with matrix/bias changes.
5. Port deferred water incrementally only after the core renderer branch is stable; see `WATER.md`.

## Focused validation

- Shadow direction: fixed camera, compare Morning/Noon/Evening.
- Roof transition: enter/leave a building without moving camera; exterior shadow silhouette should not pop.
- Material tags: use Material Debug before changing classifier ranges.
- Stone: fixed Edgeville camera, compare cleanup `0` and `100` under Classic palette and neutral Enhanced Colors.
- Polygon definition: compare terrain and props separately; do not judge at `100` only.
- Login/UI: custom world uniforms must not recolor either.
