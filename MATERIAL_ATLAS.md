# Authored Material Atlas Contract

This document defines the authored surface-data contract. Phase 2A established
storage and ownership. Phase 2B supplies the first authored assets and
deterministic variants; Phase 3 consumes them for stable shading.

## Ownership and Lifecycle

`AuthoredMaterialAtlas` exclusively owns two `GL_TEXTURE_2D_ARRAY` objects:

- tangent-space normal layers
- packed material-property layers

They are created after the GPU context and shader programs initialize, and are
deleted before that context is destroyed. Initialization restores the previous
active texture, array binding, and unpack alignment. The arrays are not bound
or sampled by world shaders through Phase 2B, so the authored pack remains
visually inert and adds no per-frame work.

`authored_materials.json` is the versioned source of layer indices and bounded
material factors. It is loaded and validated once. RuneLite's existing texture
and vertex color remain the authoritative base color via
`VANILLA_MULTIPLY`; authored albedo replacement is not part of this contract.

## Layer Encodings

All layers are 128×128 `GL_RGBA8` with generated mipmaps.

- Normal RGBA: unsigned tangent XYZ in RGB; A reserved. Decode RGB as
  `sample * 2 - 1` before normalization.
- Property RGBA: R roughness, G metallic, B ambient occlusion, A height.

Layer zero is generated rather than stored as an image:

- normal `(128, 128, 255, 255)`
- properties `(255, 0, 255, 128)`

Fallback definitions use normal strength and height scale zero. Therefore an
unknown, missing, or not-yet-authored material preserves current rendering.

## Vertex Metadata Audit

No VAO, VBO, stride, or draw-call change is required.

The existing signed 16-bit `tex.x` lane is allocated as:

| Bits | Meaning |
| --- | --- |
| 0–8 | RuneLite texture code (0–256) |
| 9–12 | semantic `SurfaceMaterial` ID |
| 13–15 | authored variant (0–7 per semantic material) |

Signed values are safe because shaders mask after integer promotion. Variant
zero is the semantic fallback. Later rules can distinguish, for example,
cobble from masonry or dock wood from painted wood without widening vertices.

`tex.w` and `abhsl` remain untouched because their bits are already committed
to terrain/water edges, alpha, bias, and generated-water-bed markers.

## First Pack

The checked-in source sheet and reproducible generator produce ten paired map
layers:

| Material | Semantic / variant | Layer |
| --- | --- | --- |
| grass | `GRASS / 1` | 1 |
| dirt | `DIRT / 1` | 2 |
| sand | `SAND / 1` | 3 |
| cobble | `STONE / 1` | 4 |
| masonry | `STONE / 2` | 5 |
| dock wood | `WOOD / 1` | 6 |
| painted wood | `WOOD / 2` | 7 |
| roof tile | `STONE / 3` | 8 |
| metal | `METAL / 1` | 9 |
| foliage | `FOLIAGE / 1` | 10 |

`scripts/generate-authored-material-maps.ps1` deterministically derives the
128-pixel tangent normals and packed property maps from
`materials/authored_height_source.png`. Exact catalog rules may select a
variant explicitly; heuristic material matches use the semantic default.

## Phase Boundaries

Phase 3 owns normal/property sampling and must keep untagged and fallback
surfaces visually identical to the current renderer. It also owns stable
tangent-space terrain normals and all lighting response.
