# GpuHD Vegetation Assets

## Tree replacement contract

Tree replacements are selected primarily by exact RuneScape object ID. World
coordinates are exceptions only. Original RuneScape objects remain authoritative
for game state, collision, interactions, animation state, and click targets while
GpuHD replaces only their visual representation.

The authoritative mapping is:

`runelite-client/src/main/resources/net/runelite/client/plugins/gpu/vegetation/trees/tree-replacements.json`

Tree assets live beside it:

```text
vegetation/trees/
├── common-tree/tree_lod0.glb (staged for a later package)
├── oak/oak_lod0.glb
├── willow/willow.glb
├── yew/yew.glb
└── dead/dead-tree.glb
```

To add a tree, place the GLB at the mapped path and add its exact object IDs to
the corresponding `objectIds` array. No Java or GLSL edit is required. Restart
the GPU plugin so the packaged resource is loaded and the scene is rebuilt.

The proven oak appearance maps exact oak ID `10820` and normal-tree ID `1278`.
Tune `trees.oak.scale` first.
`randomYaw` is disabled so the replacement preserves the RuneScape object's
exact orientation and interaction alignment.

Some stock trees include separate visual-only ground-decoration objects. The
optional `auxiliaryObjectIds` and `auxiliaryRadius` fields suppress those models
only near an active mapped tree. They do not remove the RuneScape objects or
alter collision/game state. Oak IDs `4735` and `4736` are currently scoped to
four tiles around an ID `10820` oak, avoiding global suppression near unmapped
normal trees.

The renderer suppresses vanilla triangles only after the mapped GLB has loaded
successfully. A missing or invalid asset therefore leaves the RuneScape model
visible rather than creating an invisible object.

## Mesh and material expectations

- glTF is Y-up; the shared vegetation loader performs the single conversion to
  RuneLite's Y-down world coordinates.
- The complete transformed mesh is centered horizontally and normalized so its
  lowest trunk/root point is local height zero. It is never centered vertically.
- Triangle primitives may retain separate bark and foliage materials.
- Base-color textures remain RGBA. `MASK`/`BLEND` material alpha is rendered as
  depth-writing alpha cutout for stable RuneLite scene integration.
- Positions, normals, UVs, and indices are required or synthesized by the shared
  GLB path. Embedded and packaged external base-color images are supported.
- The original object orientation is preserved. Small scale and yaw variation is
  deterministic and never changes the object's position.

Optional mapping fields are `scale`, `rotationOffset`, `randomYaw`,
`windStrength`, and `foliageTransmission`. Wind and LOD behavior are reserved for
later packages; the fields exist so asset identity does not need redesign later.

`materialOverrides` maps an exact glTF material name to a PNG beside that tree's
GLB. A mapping can optionally specify `alphaCutoff`, `doubleSided`, and
`windResponse`; omit them
to preserve the GLB material values. The special name `None` targets primitives
without an assigned glTF material. Replacement PNGs retain their RGBA channels,
and foliage cutouts use the same cutoff in the color and shadow passes.

`coordinateOverrides` is intentionally empty. Use it only for exceptional
landmarks that share an object ID but require a different visual asset. Exact
object-ID mapping remains the normal authoritative path.
