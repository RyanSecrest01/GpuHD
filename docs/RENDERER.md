# Core Renderer, Environment, Lighting, and Shadows

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
| Material-owned surface behavior | `docs/MATERIALS.md` |
| Zone draw ranges and roof-dominant casters | `Zone.java` |
| Sky rendering | `sky_vert.glsl`, `sky_frag.glsl`, `SkyMode.java` |
| Shadow depth pass | `shadow_vert.glsl`, `shadow_frag.glsl`, `shadow_debug_*` |
| Matrix correctness tests | `GpuPluginLightMatrixTest.java` |

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

## Material and Color Boundary

Material tags, palettes, Polygon Definition, texture overrides, normal mapping, stone cleanup, and ground materials are owned by `docs/MATERIALS.md`.

Renderer-level invariants that still apply:

- material effects are layered around a stock-color fallback;
- material normals must never recreate blanket world diffuse lighting;
- Enhanced Colors remains an independent world-only grade and is neutral at saturation/contrast `100`;
- fog is applied after material/color response;
- material passes must preserve login/UI isolation and GL state.

## HDR status

There is no true HDR output or internal HDR scene today.

Possible future internal-HDR path:

1. Render scene color into RGBA16F.
2. Resolve to a single-sample FP16 texture.
3. Apply exposure/white balance/filmic tone mapping in a fullscreen pass.
4. Draw UI afterward at reference white.

This can remain cross-platform SDR output and still improve sun, lightning, specular, and bloom. True Windows HDR/macOS EDR requires native presentation changes outside the GPU shader alone and is a separate project.

## Near-term priorities

1. Keep shadow behavior stable; do not mix material work with matrix/bias changes.
2. Maintain one resolved environment state for sky, fog, reflections, weather, and shadows.
3. Keep custom passes deterministic and state-safe on Apple OpenGL 4.1.
4. Continue material work through `MATERIALS.md`.
5. Port deferred water incrementally only after the core renderer branch is stable; see `WATER.md`.

## Focused validation

- Shadow direction: fixed camera, compare Morning/Noon/Evening.
- Roof transition: enter/leave a building without moving camera; exterior shadow silhouette should not pop.
- Material/color changes: follow the controlled comparisons in `MATERIALS.md`.
- Login/UI: custom world uniforms must not recolor either.
