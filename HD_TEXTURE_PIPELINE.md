# GpuHD Texture Authoring

## Workflow

1. Explore RuneScape and press the GPU **Export surface catalog** hotkey.
2. Inspect `data/master-surface-catalog.json`.
3. Remaster a source image without flattening its alpha mask.
4. Name the PNG with its exact source ID and place it in `hd-textures`.
5. Launch and test. No Java, GLSL, slot, CSV, or rule edit is needed.

```text
runelite-client/src/main/resources/net/runelite/client/plugins/gpu/hd-textures/
  vanilla/texture-<textureId>.png
  objects/object-<objectId>.png
  terrain/underlay-<underlayId>.png
  terrain/overlay-<overlayId>.png
```

An object PNG may have an optional `object-<id>.json` sidecar:

```json
{ "uvMode": "PLANAR", "uvScale": 1.0 }
```

`VANILLA` is the default. `PLANAR` uses X/Z for horizontal faces, Z/Y for
walls primarily facing +/-X, and X/Y for walls primarily facing +/-Z. Scale 1.0
repeats once per 128 world units.

## Appearance precedence

1. Exact object ID
2. Exact RuneLite texture ID
3. Exact underlay/overlay ID
4. Vanilla RuneLite appearance

Semantic materials such as STONE, WOOD, and GRASS describe behavior only.
They never select replacement albedo.

## Master catalog

`data/master-surface-catalog.json` is the sole persistent discovery database.
Exports merge observations in place with deterministic ID ordering and retain
existing observations. Its four namespaces are `textures`, `objects`,
`underlays`, and `overlays`. Texture entries retain native dimensions and alpha
coverage; object entries retain type, model texture IDs, orientations, and
compact sightings; terrain entries retain packed-HSL color observations,
observed texture IDs, and compact sightings. The exporter searches upward from
`user.dir` for the
repository. Launches elsewhere can set `-Dgpuhd.repoRoot=C:\path\to\GpuHD`.
Failure to resolve a repository is explicit, so distributions do not silently
write beside packaged resources.

Vanilla remasters inherit the original texture's alpha mask. Object and terrain
PNGs retain authored RGBA. Source/final PNGs and the master catalog are Git
assets; previews, chunk dumps, caches, and intermediate AI files are not.
