# RuneLite GPU Experimental Renderer

## Project Identity

This repository is a lightweight extension of RuneLite's stock GPU plugin.

The target is clean, smooth, colorful OSRS with stronger atmosphere, materials, water, and directional depth—not a wholesale 117 HD clone and not a replacement renderer.

Preserve RuneLite's visual identity, existing scene upload path, UI rendering, login rendering, and performance characteristics wherever possible.

## Current Working Features

The documented baseline is `feature/stone-cleanup`. Always confirm the current branch and worktree with `git status --short --branch`.

Working systems include:

- DAY / SUNSET / NIGHT / COSMIC cubemap skies;
- continuous or fixed sun/moon environment directions;
- sky-aware custom fog;
- rain, storm, snow, blizzard, lightning, mist, and weather audio;
- directional 4096² cast shadows aligned with the visible sun or moon;
- roof-dominant shadow casters that remain stable when RuneLite hides roofs;
- CPU material tags packed into the existing vertex format;
- Classic / Natural / Lush material palettes and material debug view;
- bounded Polygon Definition without blanket dynamic lighting;
- targeted vertical-stone cleanup and matte response;
- inline enhanced water with waves, shoreline effects, sky reflection, and celestial highlights;
- Enhanced Colors with independent saturation and contrast.

Celestial rays are intentionally absent from this branch. `mac-dev` contains a rejected camera-space prototype. `origin/master` contains larger experimental systems and is a research source, not a wholesale merge target.

## Important OpenGL and RuneLite Invariants

- Stock RuneLite color is authoritative. Never restore blanket per-triangle diffuse/dynamic lighting.
- Unoccluded pixels must remain stock before independent palette, reflection, weather, and color effects.
- RuneLite scene depth is reversed: `GL_GREATER`, clear depth `0`, and `[0,1]` clip depth where supported.
- The custom shadow framebuffer uses conventional depth: `GL_LESS`, clear depth `1`, and `[-1,1]` clip depth remapped in GLSL.
- Never compare or reconstruct one depth convention as though it were the other.
- RuneLite render-world elevation uses negative Y. Do not negate an environment/light vector without tracing its coordinate space.
- `makeLightViewRotation` must remain orthonormal; movement along the light direction must preserve shadow UV.
- Reuse existing zone VAOs/VBOs and compact draw ranges. Do not duplicate the world mesh without an explicit design reason.
- Restore every modified GL state: draw/read framebuffer, viewport, program, VAO, depth mask/function, clear depth, blending/equation, culling, active texture, sampler bindings, and clip-control mode.
- Keep custom world effects gated to `GameState.LOGGED_IN` or supply a deterministic login fallback.
- Do not change UI rendering unless the task explicitly requires it.
- Avoid temporal history on macOS until a deterministic current-frame version is proven stable.

## Agent Autonomy

- Make reasonable, reversible, in-scope implementation decisions without repeatedly asking for permission.
- Inspect current code and documentation before assuming an old chat conclusion is still true.
- Preserve unrelated user changes in a dirty worktree.
- Prefer the smallest complete change that tests one subsystem or hypothesis.
- Do not broaden a task into a renderer rewrite, new pass architecture, dependency change, asset-license decision, destructive Git action, or external publication without clear authorization.
- If a required choice would materially change visual direction, performance, compatibility, or project scope, stop and ask the user.
- When architecture or a verified limitation changes, update the relevant design document in the same change.

## Efficiency Rules

1. Read `AGENTS.md`, then only the design document relevant to the task.
2. Run `git status --short --branch` before editing.
3. Use the focused file/anchor map in the selected document and `rg` for symbols. Do not begin with a full-repository scan.
4. Inspect the current diff before rereading unchanged implementation.
5. Isolate one subsystem per iteration. Do not tune shadows, materials, water, and volumetrics together.
6. Reuse existing tests, shaders, VAOs, buffers, assets, and helpers before creating new infrastructure.
7. Do not repeatedly launch RuneLite for diagnosis. The user's Mac is resource-constrained.
8. Do not browse the internet unless the task needs current external documentation, licensing, or a referenced source not available locally.
9. Report exact files changed, validation performed, and the single remaining visual question.

## Three-Attempt Limit

For the same visual defect or technical hypothesis, make at most three evidence-driven implementation attempts.

Each attempt must change one identified cause and must be followed by compilation or a focused test. Do not make three blind parameter tweaks.

After the third unsuccessful attempt:

- stop editing that subsystem;
- summarize what was tried and what evidence was learned;
- identify the unresolved variable or architectural limitation;
- request one controlled screenshot, runtime observation, debugger trace, or user decision before continuing.

A new attempt cycle begins only when new evidence changes the hypothesis.

## Compile, Then Stop for Visual Testing

After a meaningful visual change:

```bash
./gradlew :client:compileJava :client:processResources --offline
git diff --check
```

Run only focused tests relevant to the subsystem. Common renderer tests are:

```bash
./gradlew :client:test \
  --tests net.runelite.client.plugins.gpu.GpuPluginLightMatrixTest \
  --tests net.runelite.client.plugins.gpu.SurfaceMaterialClassifierTest \
  --offline
```

`ShaderTest` skips unless `glslangValidator` is supplied:

```bash
./gradlew :client:test \
  --tests net.runelite.client.plugins.gpu.ShaderTest \
  -PglslangPath="$(command -v glslangValidator)" --offline
```

Once compilation and focused tests pass, STOP. Do not continue artistic tuning without new in-game evidence. Ask the user to run one controlled visual comparison and specify the camera, location, settings, and toggles needed.

## Design Documentation

Subsystem architecture is documented under `docs/`.

Read only the document relevant to the current task.

- `docs/RENDERER.md` — core renderer, environment, lighting and shadows
- `docs/MATERIALS.md` — material system, texture overrides, normal mapping and ground materials
- `docs/WATER.md` — advanced water roadmap
- `docs/VOLUMETRICS.md` — celestial rays, fog and atmospheric effects
- `docs/VEGETATION.md` — procedural grass and future vegetation

Do not load every design document for unrelated tasks.
