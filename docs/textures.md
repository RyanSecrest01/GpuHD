# Texture architecture

This is the source of truth for texture identity, authored appearance, and
runtime replacement. Read `renderer.md` first for frame and GL invariants.

## Non-negotiable identity rule

Do not use semantic material classes as a shortcut for asset assignment.

```text
Bad:  STONE -> masonry.png
      GRASS -> grass.png

Good: exact source mapping -> authored appearance
      semantic class -> physical behavior or fallback only
```

An explicit object ID, RuneLite texture ID, underlay ID, or overlay ID mapping
always remains authoritative. `STONE` describes behavior; it does not identify
which masonry appearance an object uses. Multiple stone objects may require
different albedo, normal, and property assets.

## Three appearance tiers

The intended selection precedence is:

```text
exact object/surface mapping
  -> exact texture-ID replacement
  -> exact remastered vanilla asset
  -> RuneLite vanilla fallback
```

The current sparse runtime implementation is
`AuthoredTextureOverrideAtlas.java` with
`authored_texture_overrides.json`. It supports exact `textureId`, `underlayId`,
and `overlayId` entries. Overlay wins over underlay on terrain; an exact terrain
mapping wins over a generic texture mapping. The current mapping file is empty,
so vanilla remains active until an asset is deliberately added.

Object-specific `objectId + textureId` precedence is a planned extension. Until
it exists, object appearance can be selected by its exact model texture ID, not
by a semantic class.

## Asset workflow

```text
RuneLite TextureProvider
  -> exported native reference PNGs
  -> offline human/AI remastering
  -> reviewed final asset
  -> exact mapping JSON
  -> runtime sparse authored array
```

AI or other upscaling runs only during development. Runtime performs no AI
generation. Final assets ship with GpuHD and should preserve the source texture
ID in their filename where practical.

Recommended repository layout:

```text
textures/
  vanilla-remastered/texture-002.png
  authored/masonry/lumbridge-wall.png
  authored/ground/olive-grass.png
```

The current drop-in folder is
`runelite-client/src/main/resources/net/runelite/client/plugins/gpu/authored_textures/`.
Its mapping file is adjacent at
`authored_texture_overrides.json`. The standard authored target is currently
256×256, but target resolution should become configurable metadata rather than
an assumption in future atlas work.

Every committed third-party asset needs adjacent provenance/license notes.

## Color and sampling

The current stock array is 128×128 RGBA8 and remains untouched. The authored
array is uploaded as sRGB RGBA8 (`GL_SRGB8_ALPHA8` when available), so OpenGL
decodes sampled albedo into linear values before later shader operations.
Authored images are resized to 256×256 at load time, mipmaps are generated,
and linear minification/magnification is used. The current authored path does
not yet mirror the configurable stock anisotropic-filter setting.

Do not silently change encoding. Dark authored textures previously resulted
from implicit color-space assumptions. Albedo is color data; normal maps and
packed properties, when added, are data textures and must not use sRGB encoding.

## Current versus planned

Current: stock texture array, sparse authored albedo array, exact texture/
underlay/overlay mapping, stock UVs, stock animation indexing, stock fallback.

Planned: object-context mappings, configurable asset resolution, remastered
vanilla pack, material-local normal/property arrays, provenance validation, and
terrain-material appearance selection. None of those should be implemented as
a broad semantic replacement.

## Roadmap

- Phase A: extract and curate the master source dataset.
- Phase B: stabilize exact precedence, remastered vanilla assets, authored
  overrides, and color-space/filtering consistency.
- Phase C: let terrain mappings resolve to `TerrainMaterial` assets; see
  `terrain.md`.
- Phase D onward: vegetation and terrain polish consume resolved materials;
  see `vegetation.md`.
