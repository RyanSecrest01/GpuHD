# RuneLite GPU Experimental Renderer

This repository is a lightweight extension of RuneLite's stock GPU plugin. The visual target is clean, smooth, colorful OSRS—not a wholesale 117 HD clone.

## Start here

1. Run `git status --short --branch` before reading code or editing. The worktree may contain user changes.
2. Read only the subsystem document relevant to the task:
   - Core renderer, shadows, materials, color, textures, sky: `docs/RENDERER.md`
   - Water: `docs/WATER.md`
   - Fog, weather mist, celestial rays: `docs/VOLUMETRICS.md`
3. Use the file/anchor map in that document. Do not begin with a full-repository scan.
4. If architecture or a verified limitation changes, update the relevant document in the same change.

## Current baseline

- Active development branch: `feature/stone-cleanup`.
- If `git status` reports another branch, treat this as the documented baseline and verify that branch's diff before assuming a subsystem is present.
- `mac-dev` contains the rejected camera-space celestial-ray prototype. Do not merge it wholesale.
- `origin/master` contains larger experimental systems, including deferred water. Treat it as a research source and port features incrementally.
- Celestial rays are intentionally absent from the active branch. The visible sun/moon glow is not a volumetric-ray system.
- Directional shadows, roof-dominant shadow casters, material tags, material palettes, polygon definition, weather, skyboxes, and inline enhanced water are active.

## Non-negotiable renderer rules

- Preserve stock RuneLite color and geometry flow wherever possible.
- Never reintroduce blanket per-triangle diffuse/dynamic lighting. It exposed terrain seams and darkened the map.
- Cast shadows may darken stock color only inside a validated shadow mask. Unoccluded pixels must remain stock before independent effects.
- Reuse existing zone VAOs/VBOs. Avoid duplicating world geometry.
- RuneLite scene depth is reversed (`GL_GREATER`, clear depth `0`). The shadow map uses conventional depth (`GL_LESS`, clear depth `1`). Never mix those conventions.
- Restore every OpenGL state changed by a custom pass, including framebuffer bindings, viewport, program, VAO, depth state, blend state, culling, active texture, and clip-control mode where relevant.
- Do not alter UI rendering or login rendering unless the task explicitly requires it.
- Keep custom world effects gated to `GameState.LOGGED_IN` or provide a deterministic login fallback.
- Avoid temporal history on macOS; prior history/billboard experiments produced ghosting and screen cutoffs.
- Do not launch the client repeatedly for diagnostics. The user's Mac is resource-constrained. Prefer static inspection, focused tests, and compilation; request one controlled visual run only when it can answer a specific question.

## Efficient workflow

Before editing:

- Read the relevant subsystem document completely.
- Inspect only its listed files and the current diff.
- State the render path and the minimum intended change.

After a meaningful change:

```bash
./gradlew :client:compileJava :client:processResources --offline
./gradlew :client:test \
  --tests net.runelite.client.plugins.gpu.GpuPluginLightMatrixTest \
  --tests net.runelite.client.plugins.gpu.SurfaceMaterialClassifierTest \
  --offline
git diff --check
```

`ShaderTest` skips unless `glslangValidator` is supplied. When available:

```bash
./gradlew :client:test \
  --tests net.runelite.client.plugins.gpu.ShaderTest \
  -PglslangPath="$(command -v glslangValidator)" --offline
```

Report exactly which files changed, what was verified, and what still needs an in-game check.

## Art direction

- Prefer restrained, material-specific response over global effects.
- Enhanced Colors must remain independent and neutral at `100`.
- Polygon Definition should add bounded light-facing definition, never outlines or global darkness.
- Architecture should be matte and readable; vegetation may carry more saturation.
- Texture quality should improve through conservative material tags and sparse curated replacements, not global sharpening.
- Sky preset/weather is the master environment state for fog, sun/moon, reflections, shadows, and weather visibility.
