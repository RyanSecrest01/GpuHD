# Master export and source discovery

This document defines the discovery data used to author future mappings. The
exporter is evidence collection, not a material resolver.

## Current pipeline

```text
RuneScape world
  -> ChunkObjectExporter
  -> raw CSV/JSON plus decoded texture references
  -> curated exact mapping
  -> runtime lookup/atlas
```

`ChunkObjectExporter` scans the player's current 8×8 world chunk across scene
planes. Trigger it with the configured GPU hotkey or the `gpuexport` client
command. Output is written to:

```text
RuneLite.RUNELITE_DIR/gpuhd-object-dumps/
chunk-<minWorldX>-<minWorldY>-<timestamp>/
```

On macOS this is normally `~/.runelite/gpuhd-object-dumps/`. Existing exports
are not rewritten because the directory includes a timestamp.

## Authoritative identities

Raw IDs are primary and must remain present in every curated dataset:

- object ID;
- RuneLite model texture ID(s);
- underlay ID;
- overlay ID;
- world X/Y, scene coordinates, and plane;
- object orientation and object type when available.

Semantic `SurfaceMaterial` and authored-slot values are optional diagnostics.
They must never replace or conceal these IDs. Export data must never directly
become a guessed `STONE` or `GRASS` replacement.

## Files and schemas

Each export contains:

- `terrain.csv`: one record per discovered terrain tile, including
  `world_x,world_y,plane,scene_x,scene_y,render_level,underlay_id,overlay_id,`
  `texture_ids,tile_type,shape,rotation`, raw paint/model color fields, and
  `average_rgb` when a direct RGB value exists;
- `objects.csv`: one row per object placement, including
  `object_id,name,type,world_x,world_y,plane,orientation,texture_ids`, debug
  colors, and optional semantic diagnostics;
- `chunk.json`: the combined detailed bundle with bounds, counts, terrain,
  objects, and sorted referenced texture IDs;
- `texture-manifest.csv` and `textures/texture-<id>.png`: decoded native
  RuneLite texture references available from the live `TextureProvider`.

Underlays and overlays are definitions/colors, not texture images. The exporter
does not invent an image for them. A missing direct RGB value is left empty
rather than reconstructed from packed HSL or a broad semantic class.

Records and ID lists use deterministic sorting. Multiple object placements are
kept as separate rows; identity deduplication only prevents the same placement
being emitted repeatedly while traversing multi-tile references.

## Debug correlation

`SurfaceIdDebugColors` is the shared deterministic ID-to-color helper used by
the overlay and exporter. Object, underlay, overlay, and texture IDs therefore
produce stable hexadecimal colors across runs. A screenshot with the matching
debug mode can be correlated to CSV/JSON by ID and hex color.

## RuneLite information boundary

RuneLite exposes paint RGB, packed HSL corner/face colors, terrain model colors,
object definitions, and model face texture arrays when a CPU-visible model is
available. It does not expose a canonical final lit RGB for every rendered
face. Some dynamic or unavailable renderables therefore have no reliable model
texture list. Treat those as known missing evidence, not permission to infer a
replacement.

## Roadmap

- Phase A: robust exporter, deterministic ID visualization, master source
  dataset.
- Phase B: exact mapping precedence and reviewed remastered vanilla/authored
  assets; see `textures.md`.
- Phase C: exact terrain IDs resolve to terrain materials; see `terrain.md`.

The source dataset should be curated and reviewed before broad appearance work.

## Master dataset curator

The offline curator is `tools/gpuhd_export/merge_exports.py`. It does not run
inside RuneLite and does not modify raw exports. Run it with the raw export root
and a checked-in output directory:

```text
python3 tools/gpuhd_export/merge_exports.py \
  ~/.runelite/gpuhd-object-dumps \
  data/gpuhd-source-dataset
```

The generated master dataset contains canonical `terrain.csv` and `objects.csv`,
`terrain-id-usage.csv`, `texture-sources.csv`, `conflicts.csv`, `manifest.json`,
and copied reference PNGs under `textures/` when raw exports contain them.
Canonical records retain observation counts and source export names. A conflict
means the same exact identity was observed with differing metadata; it requires
review and never triggers an automatic material assignment. Scene-local X/Y
values are intentionally retained but excluded from conflict detection because
the extended-scene origin can move between captures; world coordinates are the
stable terrain identity.
