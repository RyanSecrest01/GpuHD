# 3D grass port notes

## Behavior to preserve

- Load the complete Steve B GLB as one reusable clump. Apply all glTF node
  transforms before calculating bounds; preserve positions, normals, UVs, and
  indices.
- Perform exactly one glTF Y-up to RuneLite Y-down conversion on the CPU.
  Center horizontally, never vertically. Normalize the lowest root to local
  Y=0 and grow upward toward negative RuneLite Y. The production shader must not
  convert axes, normalize roots, or calculate terrain height again.
- Generate deterministic roots from actual visible terrain triangles. Paint
  tiles and shaped models use triangle-area samples and barycentric height.
  Resolve a visible overlay before its hidden underlay; a non-vegetated path
  above grass produces no grass.
- Instance transform order is local normalized vertex -> uniform scale ->
  deterministic world-up yaw -> exact triangle root position -> 117HD view and
  projection. Keep grass globally upright until slope following is separately
  revalidated.
- Reuse one VAO/VBO/EBO and one streamed six-float instance record: root XYZ,
  stable seed, terrain HSL, detail type. Bases remain fixed while wind bends
  tips. Use reversed world depth, depth writes, double-sided geometry, and
  restore GL state afterward.
- Preserve deterministic density thinning and distance bands. Current grass is
  full near, thinned mid, and absent beyond the far vegetation boundary.
- Integrate through 117HD's native sun, sky fill, shadow visibility, fog, and
  night environment. The current fragment shader derives muted rustic color
  from terrain HSL and sharply reduces night/moon energy.

There is no external grass texture to copy: the current GLB appearance is
colored by `grass_glb_frag.glsl`. `textures/README.md` records this intentionally
empty directory.

## Current implementation references

- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/GlbGrassMesh.java`
  - `load`, `decode`, and `collectNode`
  - node-transform baking, bounds, one axis conversion, root normalization
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/SceneUploader.java`
  - `buildSurfaceDetailAnchors`
  - `appendModelSurfaceDetails`
  - `containingModelFace`, `modelFaceHeight`, and edge-clearance helpers
  - `surfaceDetailTileEligible`, `eligibleSurfaceDetailType`, and
    `putSurfaceDetailAnchor`
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/Zone.java`
  stores `surfaceDetailAnchors`, level offsets, visibility, and diagnostics.
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/GpuPlugin.java`
  - `initGrassGlbVao`
  - `drawSurfaceDetails`
  - `surfaceDetailSelection`
  - `grassDensityMultiplier`, `vegetationLod`, and distance-band helpers
  - `drawGrassGlb`
  - root/GLB/debug draw methods for proof-of-life validation
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/GpuPluginConfig.java`
  owns 3D grass enable, density, wind, debug mode, and shared vegetation bands.
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/GrassDebugMode.java`
  defines the staged diagnostic modes.
- `runelite-client/src/test/java/net/runelite/client/plugins/gpu/VegetationGlbMeshTest.java`
  and `VegetationLodTest.java` cover GLB and distance behavior.

The copied debug shaders are migration diagnostics, not alternate production
renderers. First prove one clump, then an instanced line, triangle root markers,
one known vegetation surface, and finally general eligible terrain.

