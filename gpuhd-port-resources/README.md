# GpuHD selective port resources

This directory is a migration/reference package for moving four GpuHD features
into a new 117HD fork in this order:

1. Skybox
2. Grass
3. Trees
4. Ray tracing

The copied files are snapshots. The existing GpuHD source files remain
authoritative and were not moved, renamed, or modified to create this package.

117HD must remain authoritative for terrain textures and materials, base
lighting, shadows, water, fog, weather, and its existing environment renderer.
Port behavior into those native systems; do not install GpuHD's renderer around
117HD or use semantic material classes to select replacement appearances.

## Contents

- `skybox/`: four clear-environment cubemap atlases and the working sky shaders.
- `grass/`: the production GLB clump, production shaders, diagnostic shaders,
  attribution, and implementation notes.
- `trees/`: current common/oak GLBs, used oak textures, exact-ID mapping, color
  and alpha-cutout shadow shaders, and implementation notes.
- `ray-tracing/`: the working occlusion-aware celestial light-shaft shaders and
  implementation notes. The directory retains the requested migration-stage
  name, but the effect is rasterized volumetric integration, not ray tracing.

Each `reference/PORT_NOTES.md` identifies the current Java classes and methods
which own the behavior. Whole Java renderer classes are deliberately not copied.

## Source manifest

Every copied file and its original repository path is listed below.

| Package file | Original source |
|---|---|
| `skybox/textures/cosmic_test.png` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/cosmic_test.png` |
| `skybox/textures/day_test.png` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/day_test.png` |
| `skybox/textures/night_test.png` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/night_test.png` |
| `skybox/textures/sunset_test.png` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/sunset_test.png` |
| `skybox/shaders/sky_vert.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/sky_vert.glsl` |
| `skybox/shaders/sky_frag.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/sky_frag.glsl` |
| `grass/models/grass green by Steve B - 8q6D0D_SuBE.glb` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/glb/grass green by Steve B - 8q6D0D_SuBE.glb` |
| `grass/shaders/grass_glb_vert.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/grass_glb_vert.glsl` |
| `grass/shaders/grass_glb_frag.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/grass_glb_frag.glsl` |
| `grass/shaders/grass_debug_vert.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/grass_debug_vert.glsl` |
| `grass/shaders/grass_debug_frag.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/grass_debug_frag.glsl` |
| `grass/shaders/grass_glb_debug_vert.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/grass_glb_debug_vert.glsl` |
| `grass/shaders/grass_root_debug_vert.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/grass_root_debug_vert.glsl` |
| `grass/shaders/grass_root_debug_frag.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/grass_root_debug_frag.glsl` |
| `grass/reference/THIRD_PARTY_ASSETS.md` | `THIRD_PARTY_ASSETS.md` |
| `trees/models/common-tree/tree_01.glb` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/vegetation/trees/common-tree/tree_01.glb` |
| `trees/models/common-tree/tree_02.glb` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/vegetation/trees/common-tree/tree_02.glb` |
| `trees/models/common-tree/tree_03.glb` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/vegetation/trees/common-tree/tree_03.glb` |
| `trees/models/oak/oak_lod0.glb` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/vegetation/trees/oak/oak_lod0.glb` |
| `trees/textures/oak/oak_bark.png` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/vegetation/trees/oak/oak_bark.png` |
| `trees/textures/oak/oak_leaf.png` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/vegetation/trees/oak/oak_leaf.png` |
| `trees/textures/oak/oak_twig.png` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/vegetation/trees/oak/oak_twig.png` |
| `trees/mappings/tree-replacements.json` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/vegetation/trees/tree-replacements.json` |
| `trees/shaders/tree_vert.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/tree_vert.glsl` |
| `trees/shaders/tree_frag.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/tree_frag.glsl` |
| `trees/shaders/tree_shadow_vert.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/tree_shadow_vert.glsl` |
| `trees/shaders/tree_shadow_frag.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/tree_shadow_frag.glsl` |
| `ray-tracing/shaders/volumetric_vert.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/volumetric_vert.glsl` |
| `ray-tracing/shaders/volumetric_frag.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/volumetric_frag.glsl` |
| `ray-tracing/shaders/volumetric_composite_vert.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/volumetric_composite_vert.glsl` |
| `ray-tracing/shaders/volumetric_composite_frag.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/volumetric_composite_frag.glsl` |
| `ray-tracing/shaders/atmosphere_shadow_filter_vert.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/atmosphere_shadow_filter_vert.glsl` |
| `ray-tracing/shaders/atmosphere_shadow_filter_frag.glsl` | `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/atmosphere_shadow_filter_frag.glsl` |

Intentionally omitted: weather sky atlases, authored terrain/underlay/overlay
assets, water shaders, GpuHD base-lighting and shadow systems, general fog, the
unused `oak_leaf_2.png`, and the legacy/unmapped `common-tree/tree_lod0.glb`.
