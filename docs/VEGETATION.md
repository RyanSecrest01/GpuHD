# Vegetation architecture — planned only

No grass or vegetation renderer exists on the current branch. This document is
architecture planning, not an implementation contract. Do not begin it until
the source/export and terrain-material data are approved.

## Goal and art direction

Build high-quality 3D grass and vegetation over terrain materials that opt in.
Keep the result recognizable as OSRS with stylized geometry, readable regions,
and restrained realism. Ground textures provide soil, moss, dirt, or sparse
grass substrate; 3D vegetation provides the visible grassy character.

Grass placement must be driven by resolved terrain material metadata:

```text
terrain mapping -> TerrainMaterial -> vegetation enabled/configuration
```

Never use `if green -> spawn grass`. Avoid grass through buildings, stone paths,
water, authored nonvegetated terrain, or other explicit exclusions.

## Planned geometry and placement

Use GPU-instanced reusable clump/blade meshes. A clump should contain multiple
tapered blades with multiple vertical segments for curvature, randomized
rotation, height, width, and restrained color variation. Do not use a
Minecraft-style three-triangle grass shortcut.

Placement must be deterministic from world position, terrain identity, and a
stable seed. It should be generated or updated only when zone/material data
changes, not traversed from scratch every frame. Density, height, variation,
and UV scale come from the resolved terrain material rather than pixel color.

Plan for distance-based density and LOD, soft fades, zone/frustum rejection,
and compact instance metadata. Apple OpenGL 4.1 remains a target: avoid making
geometry shaders, tessellation, compute-only paths, or temporal buffers
required.

## Lighting, wind, and weather

Vegetation should receive the existing directional/environment lighting in a
bounded material-local response. Shadow reception may reuse the established
shadow mask where practical; detailed grass should not initially enter the
4096² shadow-caster pass.

Wind must be world-anchored and deterministic, using the resolved weather/frame
state. Animate upper blade segments with restrained bending. Weather may later
change wind strength and motion, but must not change material eligibility.

## Current versus planned

Current: no vegetation geometry, instances, placement pass, wind shader, or
vegetation shadow integration.

Planned: one vegetation-enabled terrain material, instanced clumps, deterministic
placement, density/height variation, wind, LOD, edge-aware density, and later
weather/shadow integration.

## Development phases

- Phase A — source discovery: exporter, deterministic ID visualization, master
  source dataset.
- Phase B — texture architecture: exact precedence and reviewed assets.
- Phase C — terrain materials: exact underlay/overlay selectors, substrate,
  and initial transitions.
- Phase D — vegetation: one eligible terrain material, instanced clumps,
  deterministic placement, density/height variation, wind, and LOD.
- Phase E — terrain polish: edge-aware vegetation, texture blending, and path
  borders.
- Phase F — atmospheric overhaul: height fog, distance haze, local mist, and
  improved volumetric integration.

Do not skip directly to Phase D because it is visually exciting.

## Acceptance constraints for a future implementation

- feature off produces the current world output;
- placement is unchanged by camera movement or restart;
- no density circle, swimming, duplicate instances, or zone-boundary pops;
- no vegetation on unverified or excluded terrain;
- no UI/login impact and full GL state restoration;
- quality settings change instance count/LOD, not just transparency;
- performance and alpha overdraw are measured on macOS.
