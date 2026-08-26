# Material System

Last updated: 2026-08-26. This document owns material tags, palettes, texture overrides, normal mapping, stone cleanup, and future ground materials.

## Goal

Introduce a lightweight material layer between RuneLite texture IDs and the custom renderer.

The initial material system must preserve stock RuneLite rendering when no override exists.

A material may eventually define:

- base/albedo texture override
- normal map
- normal strength
- roughness
- specular strength
- water classification
- vegetation eligibility
- ground classification
- future emissive properties

Full physically based rendering is NOT an initial requirement.

## Current State

The first metadata layer is working and does not widen the vertex format.

### Packing contract

- `tex.x` low 9 bits: stock texture code (`0` untextured, `1..256` cache texture + 1).
- `tex.x` next 4 bits: `SurfaceMaterial` ID.
- `tex.w` bit `0x100`: terrain eligibility.
- `tex.w` bit `0x200`: world-scenery eligibility.
- `tex.w` low eight bits remain water shoreline/corner flags.
- `vert.glsl` masks the stock texture code before texture animation or array lookup and transports material ID as `flat`.

Current material IDs:

| ID | Material |
| ---: | --- |
| 0 | Unknown |
| 1 | Grass |
| 2 | Stone |
| 3 | Sand |
| 4 | Dirt |
| 5 | Wood |
| 6 | Metal |
| 7 | Foliage |
| 8 | Water |

### Classification

`SurfaceMaterialClassifier` is the authoritative classifier.

- Terrain paint/model faces use exact texture identities first, then conservative HSL/overlay/underlay evidence.
- Static and dynamic world scenery use exact texture IDs only.
- Arbitrary untextured actors are not HSL-classified, preventing green armor or gray characters from becoming Grass/Stone.
- Unknown is the required fallback.
- `SurfaceMaterialClassifierTest` covers texture-code packing, water boundaries, terrain shapes, and conservative classification.

Do not widen classification ranges based on appearance alone. Use Material Debug and identify the actual texture/object path first.

### Material palettes

Classic, Natural, and Lush are implemented.

- Classic is a true shader bypass.
- Natural is the intended balanced default.
- Lush is a stronger stylized palette.
- Palette transforms preserve Rec.709 luminance, then adjust material-specific hue/chroma.
- Material grading runs before shadows and later Enhanced Colors.
- Water is excluded when Enhanced Water owns its color.
- Eligibility flags keep login/UI/actors out of the palette path.

### Polygon definition

The current renderer has no uploaded smooth vertex-normal stream. Polygon Definition uses the derivative geometric face normal to add a bounded, headroom-limited lift to light-facing opaque geometry.

- It never darkens stock color.
- It does not draw outlines.
- Shadow blockers suppress the lift.
- Tagged vertical stone cleanup suppresses it.
- High values expose terrain triangles; future work should reduce it on `TERRAIN_FLAG` while retaining definition on props/models.

### Stone cleanup

The active branch has a targeted fallback for old masonry textures:

- textured Stone + World Scenery + near-vertical face gate;
- a second binding of the stock texture array through a linear/trilinear sampler;
- a five-tap edge-aware cross filter that reduces low-contrast grain while preserving mortar;
- mip-footprint fade so distant textures rely on existing mipmaps;
- strong suppression of environment reflection, direct specular, and Polygon Definition;
- slider zero returns exact stock behavior.

Verified mappings:

- Edgeville `BRICKWALL` object ID 1902 uses cache texture ID 2 on most visible faces.
- `DRYSTONEWALL` object ID 979 uses cache texture ID 11.
- Some masonry models are untextured and cannot be corrected by a texture-ID override alone.

The current filter improves matte response but cannot remove baked highlights or invent missing source detail. Sparse authored replacement albedo is the correct next stone step.

## Focused File Map

| Concern | Files |
| --- | --- |
| Material IDs, packing, flags | `SurfaceMaterial.java` |
| Texture/HSL classification | `SurfaceMaterialClassifier.java` |
| Static terrain/scenery tags | `SceneUploader.java` |
| Dynamic/temp scenery tags | `ModelUploader.java` |
| Vertex unpack/transport | `vert.glsl` |
| Palette, cleanup, material response | `frag.glsl` |
| Settings and debug controls | `GpuPluginConfig.java`, `MaterialPalette.java` |
| Texture arrays and filtering | `TextureManager.java`, `GpuPlugin.java` |
| Classification tests | `SurfaceMaterialClassifierTest.java` |

Use `rg` for `fMaterialId`, `TERRAIN_FLAG`, `WORLD_SCENERY_FLAG`, `applyMaterialPalette`, and `stoneWallCleanup`.

## Stock Fallback Invariants

- Unknown material must render stock.
- A missing override texture/normal/property must render stock.
- Classic palette must bypass material color grading.
- Stone Cleanup zero must bypass smoothing and matte suppression.
- Material Debug is diagnostic only and off by default.
- Stock alpha discard remains authoritative even when an albedo override is sampled.
- Material metadata must never change texture animation indexing.
- Login, UI, actors, projectiles, and unrelated models must not inherit world-material effects accidentally.

## Texture Override Architecture

Do not replace a layer inside the entire stock 256-layer array. That would affect every use of the texture ID and force all replacements into the stock resolution.

Preferred sparse path:

1. Keep the 128² stock texture array on unit 1 untouched.
2. Bind only authored replacements in a small standalone texture or sparse array on an unused unit.
3. Map exact RuneLite texture IDs to replacement layers from one Java metadata source.
4. Gate the override with material and terrain/scenery eligibility when necessary.
5. Preserve stock UVs, alpha test, brightness, animation, and fallback.
6. Generate mipmaps and mirror configured anisotropic filtering.
7. Log and fall back instead of disabling the plugin when an optional asset is missing.

The first candidate is a clean 256² replacement for cache texture ID 2. Validate all wall orientations, UV rotation, scale, corners, and repetition before expanding coverage.

Do not enlarge all 256 stock layers to 256² or 512². A single 256² RGBA8 texture with mipmaps is about 341 KiB; a full 256-layer 256² array is roughly 85 MiB.

Third-party assets require adjacent provenance/license documentation. Commit only assets actually used by the runtime mapping.

## Normal Mapping Plan

Normal mapping must begin as a material-local response, not a new blanket world-lighting pass.

### First test

1. Choose one exact, clearly tagged world material.
2. Add one optional normal map and normal-strength property.
3. Derive a stable tangent basis from UV/world derivatives or add the minimum verified vertex metadata needed by that material.
4. Keep the geometric normal as fallback when the UV Jacobian is degenerate.
5. Use the mapped normal first for additive direct highlight/specular and roughness response.
6. Do not multiply the entire stock base color by N·L in the first implementation.
7. Keep shadows driven by the established light-depth mask, not the normal map.

Normal maps must be authored without baked directional lighting. They need mipmaps, conservative strength, correct green-channel convention, and validation on every model UV orientation.

### Directional-lighting boundary

The project previously rejected global derivative-normal diffuse lighting because it exposed every RuneLite triangle. Any normal-mapped diffuse term must therefore be opt-in per material, bounded around stock, and visually tested on one surface before expansion.

## Ground Materials and Blending

Ground blending is separate from flat shading/Polygon Definition.

Future ground work may include:

- explicit underlay/overlay material identity;
- detail albedo or normal overlays at a world-stable scale;
- material-aware transition masks between adjacent tiles;
- reduced Polygon Definition on terrain;
- vegetation eligibility;
- optional macro color variation without visible tile repetition.

Do not attempt ground blending by flattening a provoking vertex color or globally blurring the scene. That creates diagonal quad seams and destroys authored tile boundaries.

Start with one terrain material and prove:

- deterministic classification;
- stock fallback;
- no diagonal triangle seam;
- no camera-relative texture motion;
- stable mip/anisotropic behavior;
- no actor/model contamination.

## Development Order

1. trace existing RuneLite texture ID pipeline
2. create material metadata representation
3. map RuneLite texture IDs to materials
4. preserve stock appearance as fallback
5. support one test normal map
6. implement normal-mapped directional lighting
7. expand material coverage
8. introduce ground detail textures
9. introduce ground blending
10. add specular/roughness response
11. add optional albedo replacements only where visually justified

Steps 1–4 are substantially implemented. The immediate practical work is to stabilize sparse albedo replacement metadata, then perform Step 5 on one controlled material. Do not skip directly to broad normal-mapped lighting.

## Philosophy

Do not replace RuneLite's entire visual identity with generic HD textures.

Prefer enhancing existing RuneLite surfaces with:

- better lighting response
- subtle detail
- surface normals
- material properties

before wholesale texture replacement.

Architecture should stay matte/readable. Vegetation can carry more saturation. Reflection, roughness, and specular response must be material properties, not guesses based only on low color chroma.

## Focused Validation

- Material Debug before and after a classifier change.
- Classic/Natural/Lush at identical camera and neutral Enhanced Colors.
- Stock fallback with override resource removed or unavailable.
- Login screen, actors, animated objects, alpha-cutout textures, and nested scenes.
- Edgeville texture ID 2 at Stone Cleanup 0/100 and all wall orientations.
- Normal-map test with strength 0/100, light Morning/Noon/Evening, and shadowed/unshadowed faces.
- Ground test across tile, overlay, underlay, and shaped-tile boundaries.
