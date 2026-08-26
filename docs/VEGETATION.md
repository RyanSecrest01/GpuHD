# Vegetation Roadmap

Last updated: 2026-08-26. Procedural vegetation is future work and is not implemented on the active branch.

Procedural vegetation is future work.

Grass should be generated only on materials explicitly marked vegetation eligible.

Initial implementation should use inexpensive instanced grass clumps rather than dense individual blades.

Future grass should support:

- deterministic placement
- distance-based density
- distance fade
- wind animation
- weather-dependent wind strength
- terrain orientation
- configurable quality

Do not begin vegetation work until material classification can reliably identify valid grass terrain.

## Current Prerequisites

The material system currently provides:

- a Grass material ID;
- a terrain eligibility bit;
- conservative terrain HSL/texture classification;
- Material Debug for visual inspection;
- world-space position and terrain geometry in the existing renderer.

This is not yet sufficient proof for procedural placement. Before vegetation work begins, validate Grass classification across several biomes and explicitly exclude:

- indoor green floors and carpets;
- green roofs and scenery;
- swamp/water surfaces;
- farm plots where generated wild grass is inappropriate;
- paths, roads, tile overlays, and quest-specific surfaces;
- hidden/unrelated maps and nested world views.

Vegetation eligibility should become an explicit material property rather than assuming every Grass-tagged face is eligible.

## Focused File Map

Only inspect these areas for vegetation planning:

| Concern | Files |
| --- | --- |
| Material/terrain eligibility | `SurfaceMaterial.java`, `SurfaceMaterialClassifier.java`, `docs/MATERIALS.md` |
| Terrain upload and tile metadata | `SceneUploader.java`, `Zone.java` |
| World projection/environment | `GpuPlugin.java` (`FrameEnvironment`, camera/world projection) |
| Weather wind | `GpuPluginConfig.java`, `WeatherMode.java` |
| Candidate shaders/pass | new vegetation vertex/fragment shader only after the design is approved |

Do not read water or volumetric implementation for the first vegetation task.

## Recommended First Architecture

Use deterministic, upload-time or zone-time placement metadata plus a small instanced draw:

1. Identify eligible terrain triangles from explicit material metadata.
2. Generate a deterministic seed from world/tile coordinates and plane.
3. Place a small number of grass clumps using barycentric positions inside the eligible triangle.
4. Reuse a tiny static clump mesh/VAO and draw instances; do not upload individual blades into the world VBO.
5. Orient clump roots to the terrain plane while keeping blades mostly upright.
6. Animate only upper vertices with world-space wind.
7. Fade density and alpha before the draw-distance boundary.
8. Regenerate only when zone/material data changes, not every frame.

The initial clump should use crossed cards or a very small tapered mesh. Start opaque/alpha-tested and avoid expensive transparency sorting.

## Wind and Weather

Wind must be world-anchored and deterministic.

- CLEAR: restrained ambient motion.
- RAIN: moderate direction/speed increase.
- STORM: stronger gusts, but clamp displacement to avoid rubbery blades.
- SNOW/BLIZZARD: slower weight or stronger directional bend depending on art direction.

Reuse the resolved frame/weather state. Do not compute an unrelated time or wind direction in each shader/pass.

## Quality Levels

A future quality control may vary:

- clumps per tile/triangle;
- clump geometry complexity;
- maximum distance;
- shadow participation;
- wind complexity.

Quality must scale placement count, not merely make every blade more transparent. Low quality should remain stable and readable rather than sparse flickering noise.

## Shadow and Lighting Policy

For the first version:

- vegetation receives environment color and restrained directional response;
- grass does not enter the 4096 shadow-caster pass;
- existing world shadows may darken it using the same sampled mask only if the pass ordering makes that inexpensive;
- alpha cards do not cast detailed shadow-map silhouettes.

Later, a coarse vegetation blocker may be considered for volumetrics, but do not inject fine blades into celestial-ray occlusion.

## Performance Constraints

- Reuse one small clump VAO/VBO.
- Keep placement metadata compact and zone-owned.
- Avoid per-frame CPU traversal of every terrain face.
- Frustum/distance reject by zone or instance range before shading.
- Avoid geometry shaders, tessellation, compute-only requirements, or temporal buffers; Apple OpenGL 4.1 remains a target.
- Limit alpha overdraw, especially in dense fields and storms.
- Do not issue synchronous `glGet*` calls per vegetation frame.

## Non-Negotiable Invariants

- Vegetation off renders exact current world output.
- Placement is deterministic across camera movement and restarts.
- Grass never appears on unverified materials.
- No camera-facing screen-space swimming.
- No hard circular density boundary around the player.
- No UI/login rendering impact.
- All GL state is restored after the pass.
- Zone reuse/loading does not leak or duplicate instances.
- Weather wind changes motion, not eligibility.

## Development Phases

### Phase 0: classification audit

- Add/verify explicit vegetation eligibility.
- Capture Material Debug samples in multiple regions.
- Create exclusion evidence before rendering geometry.

### Phase 1: one static clump

- One eligible terrain type.
- Deterministic placement.
- No wind, no shadows, short distance.
- Compile, then stop for visual testing.

### Phase 2: density and fade

- Zone/instance batching.
- Distance density reduction and soft fade.
- Configurable quality.

### Phase 3: wind/weather

- World-space bend.
- Resolved environment/weather state.
- Stable gust variation.

### Phase 4: material and lighting polish

- Material palette integration.
- restrained light/shadow reception;
- biome-specific clump variants only if classification supports them.

## Focused Validation

- Same tile after restart: identical placement.
- Rotate/zoom camera: no movement except wind.
- Walk through zone boundaries: no pop-in duplicates or gaps.
- Grass field, road edge, building interior, farm, swamp, snow region, and nested scene.
- CLEAR/RAIN/STORM/SNOW wind comparison.
- Quality low/high and feature off.
- Mac frame time and alpha overdraw in the densest test field.
