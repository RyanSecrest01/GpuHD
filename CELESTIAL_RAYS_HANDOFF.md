# Celestial Rays Handoff

## Current status

The `mac-dev` branch contains a clean, current-frame celestial-ray prototype.
Its local silhouette detail and screen blend can look very good when the camera
is positioned inside a narrow shaft. It is not yet the final ray architecture.

Known visual failure:

- the ray field appears to move with the camera;
- zooming out can turn many local shafts into a large blurred overcast wedge;
- pinning an offscreen sun to the viewport edge keeps rays present, but does not
  make the volume world-anchored.

This is an architectural limitation, not a strength-slider problem. Do not try
to solve it only by reducing exposure or increasing blur.

## Current render path

1. `GpuPlugin.renderCelestialRayTexture` renders the existing
   roof-dominant static opaque Zone geometry through the gameplay camera into a
   small `GL_R8` mask. White is open visibility and black is a blocker.
2. Two five-tap passes blur and bias the mask toward large silhouettes.
3. `ray_scatter_frag.glsl` performs a 12-tap radial integration toward the
   projected sun or moon.
4. The normalized ray field is screen-blended into `fboScene` before weather and
   UI rendering.

The two ray targets preserve the scene aspect ratio and are capped at 512 pixels
on their longest edge. There is no temporal history, scene-color sampling, or
depth resolve.

## Why it moves and blooms on zoom-out

Both the blocker mask and radial source are camera-space constructs. The mask is
rebuilt from the camera projection, and an offscreen celestial body becomes a
virtual source 12–28 physical pixels beyond a screen edge. More geometry and
more mixed open/blocked paths enter the mask as the camera zooms out. The radial
transition response then covers a much larger part of the viewport, and the
screen blend reads as overcast glare instead of localized volume.

The favorable "camera inside the ray" view is useful evidence: the coarse
silhouette filtering, color, and final screen blend are promising. The unstable
radial field is the part to replace first.

## Recommended next experiment

Make one controlled change: retain the mask, low-frequency filtering, buffers,
and apply pass, but replace radial-to-source scattering with parallel
directional integration. The sun and moon are directional lights at practical
infinity, so samples should march a fixed physical distance along the projected
celestial direction rather than converge on a nearby virtual screen point.

Requirements for that experiment:

- use a constant pixel-space ray length so camera zoom does not change shaft
  energy or apparent width;
- keep the field normalized and guarantee all-white and all-black masks produce
  zero shafts;
- use the offscreen source only to determine the 2D direction, not radial scale;
- preserve the existing broad silhouette filter and one-channel full-range R8
  encoding;
- keep rays additive/screen blended only; never darken the base scene.

If directional screen integration still slides perceptibly, the next quality
tier is a small set of world-space, camera-facing volume slices rendered into
`fboScene` before weather. Depth-test those slices against RuneLite's existing
reversed scene depth and sample a separate coarse light-space blocker map. That
is more work, but it anchors the medium in world space without a scene-depth
texture resolve or temporal history.

## Invariants to preserve

- Do not modify the working 4096x4096 surface-shadow appearance or sampling.
- Keep roof-dominant ray blockers stable when RuneLite hides roofs.
- Do not reintroduce temporal accumulation; it previously caused Mac ghosting.
- Do not use billboard fog/ray cards; they produced seams and camera dragging.
- Do not restore angular sun cookies, repeated spokes, or a horizontal flare.
- Keep exact Retina/stretched gameplay viewport alignment and bottom-left FBO
  texture coordinates.
- Restore framebuffer, viewport, program, texture unit, VAO, depth, cull, and
  blend state after every custom pass.
- Keep login screen and UI rendering outside the ray composite.
- Avoid launching RuneLite repeatedly on the Mac; prefer Java/resource/tests,
  then request one focused visual check.

## Visual acceptance checks

- Rays remain readable with the sun just outside any viewport edge.
- Rotating the camera does not drag a large translucent wedge across the world.
- Zooming does not materially change global exposure or shaft thickness.
- Rays originate from broad roofs/buildings/trees, not shingles or tiny mesh
  details.
- Fully open and fully blocked masks add no blanket brightness.
- No horizontal cutoff, stale frame, ghosting, or UI contamination appears.

## Offline validation

Run:

```text
./gradlew :client:compileJava :client:processResources
./gradlew :client:test --tests net.runelite.client.plugins.gpu.GpuPluginRayTest
git diff --check
```

`ShaderTest` now lists the custom sky, ray, shadow, and weather programs, but it
requires `glslang.path` to perform external GLSL validation.
