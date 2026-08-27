# GPU Renderer Foundation Roadmap

This roadmap turns the renderer direction in `AGENTS.md` into bounded,
acceptance-gated work. The goal is to compete with 117 HD through reliable
materials, authored content coverage, real geometry, and coherent environment
response before adding more post-processing or light effects.

## Working Method

Each checkbox is a separately authorized work package. "Next phase" means the
first unchecked package only. A package may be split further when investigation
shows that its acceptance criteria cannot be met safely in one patch.

Every package must define:

- owned render pass and data path
- expected files or systems
- performance budget
- focused automated checks
- visual acceptance scene
- explicit non-goals

Do not combine asset creation, metadata plumbing, shader response, and regional
content coverage in one patch. Those are different failure domains.

## Current Foundations

Treat these as working unless a focused investigation proves otherwise:

- stock RuneLite GPU world and UI rendering
- blended vanilla terrain textures
- shared frame-environment state
- sky, celestial bodies, fog, weather, and audio
- subtractive cast shadows
- dedicated advanced-water pass and generated shoreline substrate
- selective sun/moon shaft pass
- broad surface-material tags and debug visualization
- material-aware dry/wet response
- instanced grass, stone, sand, and dirt details

## Phase 1 — Material Truth and Inspection

Objective: make material decisions explainable and data-driven before adding
new texture assets or stronger shading.

### 1A — Exact Texture Rule Catalog (complete)

- [x] Move verified live-cache texture classifications into a versioned data
      resource.
- [x] Preserve current classification output exactly.
- [x] Return rule provenance with exact matches for diagnostics and tests.
- [x] Validate duplicate IDs, invalid IDs, unknown material names, and resource
      loading.

Acceptance:

- Existing water/material boundary tests remain unchanged and pass.
- Every exact texture classification identifies its catalog rule.
- Unknown texture IDs remain `UNKNOWN` without heuristic promotion.
- No shader, VBO layout, water-range, or visual behavior changes.

Non-goals: cursor inspector UI, area rules, object rules, replacement textures,
normal maps, or new material lighting.

### 1B — Material Inspector (complete)

- [x] Add a developer-facing tile/object inspector.
- [x] Report texture, overlay, underlay, object ID, area/plane, resolved material,
      rule source, confidence, and fallback reason.
- [x] Keep existing full-scene material-color debug modes.

Acceptance: Port Sarim surfaces can be audited without adding logging or editing
shaders. Inspector state must have zero render cost while disabled.

### 1C — Area, Tile, and Object Rules (complete)

- [x] Add ordered data rules for texture, overlay, underlay, object ID,
      world-area, and plane selectors.
- [x] Define deterministic precedence and `UNKNOWN` fallback.
- [x] Add development reload without per-frame file access.

Acceptance: the rule engine can express and test all selectors without changing
the VBO layout or reading rule files per frame. Initial verified texture and
terrain rules distinguish water, turf, carpet vetoes, grass, sand, wood, stone,
metal, and foliage. Region-specific Port Sarim object-ID curation belongs to the
first Phase 4 slice and is driven by the inspector rather than guessed IDs.

Development command: after rebuilding resources, `::gpumaterialreload` reloads
the catalog once and rebuilds the scene so new tags take effect.

## Phase 2 — Authored Material Atlas

### 2A — Atlas and Material Definition Contract (complete)

- [x] Define authored material properties and texture-array ownership.
- [x] Add neutral fallback normal/property layers.
- [x] Audit vertex metadata capacity before changing the VBO contract.

Acceptance: `authored_materials.json` validates every semantic fallback,
`AuthoredMaterialAtlas` exclusively owns neutral normal/property arrays, and
the existing signed-short vertex lane carries a tested three-bit authored
variant without changing the VBO. See `MATERIAL_ATLAS.md`.

Non-goals: image-backed material assets, shader sampling, stronger lighting,
parallax, or changing the current rendered image.

### 2B — First Material Pack (complete)

- [x] Add a small coherent pack for grass, dirt, sand, cobble, masonry, dock
      wood, painted wood, roof tile, metal, and foliage.
- [x] Preserve RuneLite vertex color as regional tint and lighting information.

Acceptance:

- The atlas contains neutral layer zero plus ten validated 128-pixel normal and
  property layers generated from a checked-in authored source sheet.
- Exact and heuristic material matches carry deterministic authored metadata in
  the existing signed-short vertex lane.
- The material inspector reports the selected authored slot, and focused tests load
  every image-backed layer at its declared resolution.
- No world shader samples the atlas yet, so RuneLite vertex color and the Phase
  1 lighting baseline remain pixel-authoritative until Phase 3.

Non-goals: normal/property sampling, tangent generation, stronger surface
lighting, parallax, broad world-rule curation, or a new render pass.

Non-goals for Phase 2: parallax, dynamic lights, or global model replacement.

## Phase 3 — Stable Material Shading

### 3A — Shared Terrain Normals

- [ ] Generate stable normals across shared terrain vertices and shaped tiles.
- [ ] Preserve stock shading for untagged geometry.

### 3B — Authored Surface Response

- [ ] Apply normal and roughness response only to resolved authored materials.
- [ ] Use the shared frame environment and existing shadow visibility.
- [ ] Keep diffuse, sky contribution, rough specular, metal, and wet response
      energy-bounded.

### 3C — Optional Close Height Detail

- [ ] Evaluate parallax only on a small allowlist and close distance.
- [ ] Ship only if motion, edges, and cost remain stable.

## Phase 4 — Curated World Coverage

Implement one vertical slice at a time:

1. Port Sarim and ships
2. Karamja shoreline
3. Lumbridge
4. Varrock
5. Falador
6. one dungeon/interior scene

Each slice receives material rules, object rules, environment review, fixed
comparison cameras, and a performance capture. The baseline comparison disables
celestial rays and active weather so foundational quality is measurable.

## Phase 5 — Living Geometry

### 5A — Surface Detail Visual QA

- [ ] Tune existing grass, stones, sand fragments, and dirt clods.
- [ ] Verify slopes, shaped boundaries, shores, interiors, roofs, and distance
      transitions.

### 5B — Existing-Model Wind Masks

- [ ] Add curated wind behavior for foliage, shrubs, flags, sails, and cloth.
- [ ] Keep buildings, rigid props, characters, and unknown objects unchanged.

Details receive shadows by default. Adding them to shadow-caster passes requires
a separate measured package.

## Phase 6 — Environment and Weather Integration

- [ ] Drive wetness, snow, drying, and foliage response from material definitions.
- [ ] Resolve shoreline substrate through the same material system.
- [ ] Add regional environment profiles only where they improve coherence.
- [ ] Keep selective rays secondary and independently configurable.

## Deferred Features

Do not pull these into a foundation package:

- general dynamic/local-light system
- screen-space or planar reflections
- broad parallax coverage
- detail-geometry shadow casting
- heavyweight deferred rendering
- ray tracing or path tracing

## Quality and Performance Gates

The target modes are:

- **Stock+** — rules, authored color, blending, environment response
- **Enhanced** — normal/roughness maps and current 3D details
- **Ultra** — limited close height detail, denser details, extended wind

Prefer existing passes and arrays. A new fullscreen pass, framebuffer copy,
per-frame allocation, CPU/GPU synchronization point, or VBO layout expansion
requires an explicit package justification and measured benefit.
