# Celestial light-shaft port notes

This is the effect previously described as "ray tracing": the sun/moon shafts
which appear through trees, windows, and around broad object silhouettes. It is
a working rasterized, occlusion-aware volumetric effect, not hardware or
software ray tracing. No BVH, ray query, or geometry intersection system exists
in the current repository refs.

## Behavior to preserve

- Render a separate low-resolution directional blocker depth map using the
  active celestial light direction. It must agree with visible roof state and
  include opaque world geometry plus alpha-cutout replacement-tree foliage.
- Stabilize the blocker projection in light-space around the camera focal point;
  camera orbit/zoom must not slide texels through the world.
- Run the copied 3x3 macro depth filter. It removes isolated roof seams and
  triangle noise while retaining broad silhouette corners and canopy openings.
  117HD may feed its own compatible light-depth map into this filter.
- Resolve scene color and reversed scene depth after opaque/alpha world geometry.
  Evaluate shafts at half resolution into RGBA16F. RGB stores signed light
  addition/extinction and alpha preserves source reversed depth for upsampling.
- Reconstruct a world view ray from the inverse world projection. March 40
  quadratic-distance stations through the filtered light-space depth map and
  accumulate stable visibility transitions rather than drawing transparent
  cones or adding fullscreen haze.
- Gate the response toward the actual sun/moon direction, reject top-down and
  away-facing pixels, fade finite blocker-map coverage, and preserve the special
  sustained dark-to-light doorway/window path test.
- Keep sun and moon profiles independent. Moon shafts are tighter, weaker, and
  disabled by default. Weather may attenuate transmission through 117HD's native
  environment state; do not import GpuHD weather rendering.
- Composite with a 3x3 depth-aware bilateral upscale. Do not mix sky and geometry
  samples across reversed-depth edges. Screen-like positive addition preserves
  stone, timber, foliage, and character detail beneath shafts.
- Restore framebuffer, viewport, program, active textures, depth, blending,
  culling, and VAO state after both passes.

The copied `atmosphere_shadow_filter_*` files belong to the celestial blocker
pipeline. The similarly named `atmosphere_vert.glsl` and `atmosphere_frag.glsl`
are weather ground-cloud/fog cards and remain intentionally excluded.

## Current implementation references

- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/GpuPlugin.java`
  - shader declarations `ATMOSPHERE_SHADOW_FILTER_PROGRAM`,
    `VOLUMETRIC_PROGRAM`, and `VOLUMETRIC_COMPOSITE_PROGRAM`
  - `renderLightDepthMap` with `atmosphereCasters=true`
  - `initAtmosphereShadowMap` and `filterAtmosphereShadowMap`
  - resolved-scene and half-resolution FBO setup in `initFbo`
  - `setEnvironmentRayColor`, `getSunRayStrength`, `getMoonRayStrength`, and
    `getActiveCelestialRayStrength`
  - `drawVolumetricLighting`
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/Zone.java`
  - `renderAtmosphereShadow` mirrors visible roof/range selection while allowing
    a dedicated lower-resolution blocker map.
- `runelite-client/src/main/java/net/runelite/client/plugins/gpu/GpuPluginConfig.java`
  - `godRays`, `godRaysStrength`, `moonRays`, `moonRaysStrength`, and compact
    sky-only `celestialGlareStrength` (glare is separate from shafts).
- `runelite-client/src/main/resources/net/runelite/client/plugins/gpu/tree_shadow_vert.glsl`
  and `tree_shadow_frag.glsl`, already copied under `trees/shaders/`, provide
  wind-consistent alpha-cutout tree blockers.

For 117HD, use its native scene color/depth, environment directions, roof state,
tree materials, and shadow/depth rendering. Port the shaft integration and
macro-blocker behavior without replacing 117HD's base lighting or shadow system.
