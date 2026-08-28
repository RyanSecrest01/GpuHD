# GpuHD export curator

Merge raw `ChunkObjectExporter` output into a deterministic master dataset:

```text
python3 tools/gpuhd_export/merge_exports.py \
  ~/.runelite/gpuhd-object-dumps \
  data/gpuhd-source-dataset
```

The tool reads `chunk.json` when available and falls back to the CSV files. It
deduplicates terrain by `world_x/world_y/plane` and object placements by
`object_id/world_x/world_y/plane/type/orientation`. It retains observation
counts and source export names, and reports every differing field in
`conflicts.csv` rather than choosing a semantic replacement.
