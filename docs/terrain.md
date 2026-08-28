# Terrain materials

This document defines the terrain direction. The current branch has terrain
upload and semantic classification, but not the final `TerrainMaterial`
architecture.

## Current facts

`SceneUploader` uploads `SceneTilePaint` and `SceneTileModel` through the
existing zone buffers. RuneLite exposes underlay and overlay IDs separately;
the stored scene values are ID+1 with zero meaning absent. Terrain also carries
paint/model colors, shape, rotation, and face texture IDs. The exporter records
these values; see `export-pipeline.md`.

Underlays and overlays often describe color-driven terrain rather than actual
texture assets. Their IDs are world-design metadata. Their vanilla RGB values
are useful evidence but are not mandatory final HD appearance when an authored
mapping exists.

```text
underlay/overlay ID = selector
underlay/overlay RGB = source evidence, not final appearance authority
```

## Current fallback boundary

`SurfaceMaterialClassifier` and `surface_material_rules.json` provide semantic
material tags such as Grass, Stone, Sand, Dirt, Wood, Metal, Foliage, and Water.
They support palettes, water behavior, diagnostics, and conservative fallback.
They do not identify the final authored appearance. Exact texture/terrain
assignments remain authoritative.

Current selection principle:

```text
exact terrain mapping -> semantic fallback -> vanilla fallback
```

No example ID should be treated as universal. IDs must be confirmed from the
current export dataset and curated mapping data.

## Planned terrain-material resolution

```text
underlay/overlay ID
  -> exact terrain mapping
  -> TerrainMaterial
  -> ground albedo, normal, and properties
  -> vegetation configuration
```

A future `TerrainMaterial` should conceptually contain:

- source type and exact source ID;
- terrain material ID;
- albedo, normal, and packed properties assets;
- UV scale and world-stable sampling settings;
- vegetation enabled flag;
- vegetation density, height, and variation;
- transition/edge behavior.

For example only, a confirmed mapping could resolve an underlay to an olive
grass material whose substrate and vegetation settings are authored together.
That is a design example, not a current hardcoded rule.

Terrain materials should represent the substrate beneath vegetation: soil,
moss, dirt, sparse grass, or exposed rock. Actual 3D grass supplies much of
the grassy character, allowing natural transitions toward dirt and paths.

## Planned transitions

Terrain transitions should be material-aware and world-stable, not a global blur
or provoking-vertex color trick. Preserve tile shapes and avoid diagonal seams.
The eventual edge-aware progression is:

```text
dense grass -> sparse grass -> exposed substrate -> path/stone
```

Water, buildings, stone paths, authored nonvegetated terrain, and other
exclusions must be explicit. Pixel greenness is never a vegetation selector.

## Roadmap

- Phase A: collect and verify exact underlay/overlay evidence.
- Phase B: stabilize exact authored texture mapping and color-space behavior.
- Phase C: implement one exact terrain mapping and a minimal `TerrainMaterial`.
- Phase D: let one vegetation-enabled terrain material feed instanced clumps;
  see `vegetation.md`.
- Phase E: add edge-aware vegetation, substrate blending, and path borders.
