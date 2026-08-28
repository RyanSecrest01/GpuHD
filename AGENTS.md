# GpuHD agent instructions

GpuHD is a lightweight extension of RuneLite's stock GPU plugin. Preserve OSRS
recognizability, the existing scene upload path, UI/login rendering, and Mac
performance. Do not implement grass or vegetation unless the user explicitly
reopens the planned work in `docs/vegetation.md`.

## Source of truth

Use this precedence, in order:

1. Current branch code and checked-in mapping/export data.
2. Exact imported mappings.
3. Current scoped architecture documents under `docs/`.
4. Legacy semantic/material fallback systems.
5. Old comments, chat conclusions, commits, and other branches.

When documentation disagrees with code, inspect the implementation and update
the documentation rather than silently coding from stale text.

Exact mappings are authoritative. Never replace object ID, RuneLite texture ID,
overlay ID, or underlay ID mappings with broad assumptions such as `STONE ->
masonry` or `GRASS -> grass`. Semantic classes describe behavior and fallback;
they do not override explicit appearance assignments.

## Scoped reading

- Renderer/OpenGL/framebuffer task: `docs/renderer.md`, then owning Java/GLSL.
- Texture task: `docs/textures.md`, then owning Java/GLSL/data.
- Terrain task: `docs/terrain.md`, `docs/export-pipeline.md`, then owning files.
- Vegetation task: `docs/vegetation.md`, `docs/terrain.md`, then owning files.
- Water task: `docs/water.md`, then owning files.
- Atmosphere/fog/rays task: `docs/volumetrics.md`, then owning files.

Do not read or rewrite the entire repository for a scoped task. `README.md`
contains general RuneLite project information only.

## Change discipline

Before changing rendering, inspect the owning Java and GLSL path, make the
smallest useful change, compile, and stop for user visual testing. Do not
repeatedly launch or tune RuneLite without new user evidence. Do not rewrite
unrelated renderer systems.

Preserve existing skyboxes, day/night and celestial environment, directional
lighting, shadow maps, weather/audio/mist, water, texture overrides,
export/debug tooling, and UI/login behavior. Restore all modified OpenGL state,
including framebuffer, viewport, program, VAO, depth, blend, culling, active
texture, sampler, and clip-control state.

Main-scene depth is reversed (`GL_GREATER`, clear `0`, `[0,1]` clip where
available). The custom shadow map uses conventional depth (`GL_LESS`, clear `1`,
`[-1,1]` clip remapped in GLSL). RuneLite render-world elevation uses negative
Y; trace coordinate space before changing signs.

Before editing, run `git status --short --branch` and preserve unrelated user
changes. After code changes, use:

```text
./gradlew :client:compileJava :client:processResources --offline
git diff --check
```

Stop after successful compilation when appearance still needs manual testing.
