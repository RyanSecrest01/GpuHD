# RuneLite GPU Experimental Renderer

This is a custom fork of RuneLite's built-in GPU plugin.

## Current goals

We are extending the stock GPU renderer while keeping it lightweight.

Features already added:
- custom cubemap skyboxes
- DAY / SUNSET / NIGHT / COSMIC SkyMode
- sky-aware fog
- enhanced saturation and contrast
- experimental directional lighting
- experimental shadow-map infrastructure

## Important rules

- Preserve the existing RuneLite GPU rendering pipeline whenever possible.
- Make small incremental changes.
- Do not rewrite large rendering systems without explaining why.
- Keep RuneLite launching after each step.
- Prefer reusing existing VAOs/VBOs over duplicating world geometry.
- Always restore OpenGL state after custom passes.
- Avoid breaking login screen rendering.
- Avoid changing UI rendering unless required.
- Run the client/Gradle compile after meaningful changes.
- When debugging graphics, isolate one subsystem at a time.

## Rendering direction

The sky preset should eventually act as the master environment state.

DAY:
- bright neutral ambient
- warm directional sunlight
- high sun angle

SUNSET:
- warm orange directional light
- low sun angle
- cooler/darker ambient

NIGHT:
- dark blue ambient
- weak cool moonlight

COSMIC:
- dark violet ambient
- very subtle directional light

Enhanced Colors should remain independent and responsive.

## Shadow work

There is currently experimental shadow-map code.
Do not assume it is correct.
Validate coordinate spaces, matrices, and depth conventions before using it to darken the visible scene.

RuneLite normal rendering uses reversed depth.
The experimental shadow framebuffer currently uses conventional depth.

Before modifying celestial rays, read `CELESTIAL_RAYS_HANDOFF.md` for the
current prototype, known visual failure, and renderer invariants to preserve.

## Workflow

Before editing:
1. inspect all relevant Java and GLSL files
2. explain the render path being modified
3. make the minimum viable change
4. compile/run
5. report exactly which files changed
