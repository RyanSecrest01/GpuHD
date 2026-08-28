# GpuHD master source dataset

Generated from raw RuneLite exports with:

```text
python3 tools/gpuhd_export/merge_exports.py \
  ~/.runelite/gpuhd-object-dumps \
  data/gpuhd-source-dataset
```

The raw exports remain outside the repository. Regenerate this directory after
collecting more chunks. Do not hand-edit generated CSV/JSON files; curate
future exact mappings separately.

Files:

- `terrain.csv`: canonical world-tile observations with exact terrain IDs,
  texture IDs, colors, observation counts, and source exports;
- `objects.csv`: canonical object placements with exact object/texture IDs and
  coordinates;
- `terrain-id-usage.csv`: exact underlay/overlay occurrence and location index;
- `texture-sources.csv`: referenced texture IDs and available reference PNGs;
- `textures/`: copied native RuneLite reference images when available;
- `conflicts.csv`: differing authoritative or appearance metadata for the same
  identity; this must be reviewed before authoring mappings;
- `manifest.json`: deterministic counts and schema version.

Semantic material fields are retained only as diagnostics. Nothing in this
dataset assigns an authored appearance.
