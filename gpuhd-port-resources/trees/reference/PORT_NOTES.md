# Tree replacement port notes

Tree occlusion/player visibility is a required migration feature, not optional
polish. Reproduce the behavior using 117HD's native scene, shader, lighting, and
shadow architecture rather than copying the monolithic GpuHD renderer class.

## Identity and scene behavior

- Exact RuneScape object ID is the normal authoritative replacement key.
  Coordinate overrides exist only for rare landmarks.
- Suppress only the original object's visual submission after its mapped GLB
  has loaded successfully. Keep RuneScape collision, interaction, animation,
  click targets, and game state intact. Missing/invalid replacement assets must
  leave the stock model visible.
- Auxiliary foliage IDs are suppressed only within the mapped primary tree's
  configured radius. Never globally suppress IDs 4735/4736/4738.
- Preserve exact object position/orientation. Apply deterministic variant,
  modest scale variation, and optional small yaw without moving the root.

## GLB, materials, instancing, and LOD

- `VegetationGlbMesh` applies the full node hierarchy to positions and normals,
  preserves UVs/winding/indices, performs the one Y-up to RuneLite Y-down
  conversion, centers horizontally, and anchors the lowest trunk/root at local
  height zero.
- Preserve multiple glTF materials, embedded/external RGBA textures, base-color
  factors, double-sided flags, alpha modes/cutoffs, and data-driven exact
  material-name texture overrides.
- Batch compatible source primitives by material without simplifying or
  spatially merging foliage. Current oak collapses thousands of source
  primitives into bark/twig/foliage draw groups while keeping appearance.
- Upload each unique mesh/material set once. Identical tree instances share
  VBO/IBO/textures and differ only by the streamed root/yaw/scale/seed record.
- Keep the mapping's LOD0-LOD3 slots and deterministic shared placement. Missing
  LODs fall back to the nearest loaded asset. Visible geometry and shadow LOD
  ranges are independent; distant foliage stops casting before trees vanish.
- The mapping is an exact snapshot and its model paths were originally relative
  to `vegetation/trees/`. In this package, resolve those paths beneath
  `trees/models/` (and material-override PNGs beneath `trees/textures/`) when
  adapting the loader; do not rewrite the snapshot just to suit the package.
- Foliage uses alpha cutout plus two-sided wrapped/transmission lighting. Bark
  retains stronger normal-based directional shading. Integrate both with
  117HD's native sun, sky fill, night factor, and shadow visibility.

## Required foliage visibility behavior

Only foliage fades. Trunks and major branches remain opaque.

- `TreeOcclusionMode` supplies OFF, BALANCED, STRONG, and PLAYER_PRIORITY
  presets. Current preset values are bubble radius / sight width / maximum fade
  / smoothing speed: BALANCED 384/224/0.90/10, STRONG 512/320/0.97/13, and
  PLAYER_PRIORITY 640/448/1.0/16 RuneLite units.
- Java calculates player position from `Player.getLocalLocation()` and terrain
  height, camera position from the active frame, and a continuous top-down
  factor from camera pitch. State moves toward new positions and preset values
  exponentially so camera rotation does not pop foliage.
- At high/top-down pitch, the shader primarily evaluates an asymmetric
  player-centered ellipsoid: tall above the player, shallow below ground.
- At lower pitch, it increasingly evaluates a camera-to-player corridor/cone,
  extended slightly beyond the player. A separate guard around the camera
  removes canopy cards immediately in front of the lens. Player Priority may
  fully dither an obstruction away.
- Preserve leaf alpha cutout first, then apply a stable 4x4 Bayer screen-door
  threshold to the additional visibility. Do not use blended leaf transparency
  or fade entire trees.
- Keep the debug bubble/corridor wire visualization for validating camera pitch,
  corridor coverage, and player priority.

## Current implementation references

- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/TreeReplacementRegistry.java`
  - catalog validation, exact ID/coordinate precedence, auxiliary candidates,
    material overrides, LOD paths, and active/fail-safe definitions
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/VegetationGlbMesh.java`
  - node transform baking, shared geometry decode, RGBA materials, exact
    material overrides, and `batchPrimitivesByMaterial`
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/TreeGpuAsset.java`
  - one reusable VAO/VBO/IBO/texture set and streamed instance buffer
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/SceneUploader.java`
  - `uploadZoneRenderable` and `hasNearbyTreePrimary`
  - fail-safe stock visual suppression and replacement registration
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/Zone.java`
  - `addTreeReplacement` and `TreeReplacementInstance`
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/GpuPlugin.java`
  - `initTreeAssets`, `prepareTreeInstances`, `vegetationDistanceBands`,
    `vegetationLod`, `drawTrees`, `bindTreeMaterial`, and `drawTreeShadows`
  - `drawDynamic` and `drawTemp` suppress transient duplicate visuals only
  - `updateTreeVisibilityState`, `treeTopDownFactor`,
    `drawTreeOcclusionDebug`, and `drawTreeOcclusionDebugLines`
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/TreeOcclusionMode.java`
  owns visibility presets and fade response.
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/GpuPluginConfig.java`
  owns tree occlusion/debug, foliage/trunk/shadow profiler toggles, and shared
  vegetation LOD bands.
- `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/tree_frag.glsl`
  contains foliage classification input, player/camera volume math, alpha
  cutout, Bayer dither, and foliage-only fade.
- `tree_vert.glsl`, `tree_shadow_vert.glsl`, and `tree_shadow_frag.glsl` preserve
  instancing, wind, and alpha-cutout shadow behavior. Translate the shadow
  behavior into 117HD's native shadow system rather than porting GpuHD's shadow
  infrastructure.
- Tests: `TreeReplacementRegistryTest.java`, `VegetationGlbMeshTest.java`,
  `VegetationLodTest.java`, and `TreeOcclusionTest.java`.
