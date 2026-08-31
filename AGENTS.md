# RuneLite GPU Experimental Renderer

This repository is a custom fork of RuneLite's built-in GPU plugin.

The goal is to evolve the stock RuneLite GPU renderer into a more modern,
atmospheric renderer while preserving RuneLite's lightweight performance,
gameplay readability, and existing rendering architecture wherever practical.

This is an incremental renderer project, not a ground-up engine rewrite.

---

## Current Renderer State

The project has progressed significantly beyond the stock RuneLite GPU renderer.

### Working / Baseline Features

The following systems currently exist and should be treated as working
foundations unless investigation proves otherwise:

- custom cubemap skyboxes
- DAY / SUNSET / NIGHT / COSMIC SkyMode
- sky-aware atmospheric fog
- enhanced saturation and contrast controls
- directional environment lighting
- dynamic directional shadows
- shadow-map rendering infrastructure
- rendered sun
- rendered moon
- configurable/environment-dependent sun and moon positioning
- day/night environment cycle
- baseline weather system
- rain
- snow
- lightning
- rain sound effects
- lightning/thunder sound effects
- a dedicated deferred water pass with scene-color/depth transparency
- depth-aware tropical shoreline substrate generation
- selective sun and moon light shafts
- explicit CPU-classified surface material tags
- versioned texture/terrain/object material rules and opt-in inspector
- validated authored-material contract with neutral fallbacks and the first
  image-backed grass/dirt/sand/stone/wood/metal/foliage normal-property pack
- material-aware dry and rain response
- instanced 3D grass, stone, sand, and dirt surface details

Snow currently has no associated sound effect, intentionally or otherwise.

Directional lighting and shadows are now functional baseline systems.
Do not replace or substantially redesign them merely because older comments
or experimental code describe them as unfinished.

Investigate the current implementation before making assumptions.

---

## Important Remaining Gaps

The renderer does NOT currently have:

- ray tracing
- path tracing
- hardware ray-traced shadows
- physically based authored material maps across the world
- stable generated terrain normals shared across tile boundaries
- general normal, roughness, height, or ambient-occlusion maps
- broad object and foliage wind-displacement coverage
- robust indoor/portal volumetric lighting
- general volumetric fog
- screen-space reflections
- planar reflections
- general world reflections

Do not describe existing shadow mapping as ray tracing.

Current dynamic shadows are rasterized shadow-map-based shadows.

The existing selective celestial-ray pass is a lightweight shadow-volume
approximation, not ray tracing or a general volumetric-lighting solution.

The existing advanced water and material systems are working foundations.
Improve them incrementally rather than restarting their architecture.

---

## Current Development Direction

The sky/environment system should increasingly act as the master environment
state for the renderer.

Lighting, shadows, fog, celestial objects, weather, water, and future
atmospheric effects should visually agree with the active environment.

Avoid implementing these systems as unrelated visual effects when they can
share a coherent environment state.

The active renderer roadmap is maintained in `RENDERER_ROADMAP.md`. Treat that
file as the source of truth for phase order, current work-package scope,
acceptance criteria, and explicit non-goals.

The atlas layout, channel semantics, ownership, and vertex-capacity decision are
recorded in `MATERIAL_ATLAS.md`. Do not reinterpret those channels or widen the
world VBO without a later package demonstrating that the existing semantic plus
authored-slot encoding is insufficient.

The repository-native appearance workflow is defined in
`HD_TEXTURE_PIPELINE.md`. Exact source IDs select appearances. Semantic material
classes describe behavior. Never use a semantic class as a shortcut for
authored asset identity when an exact object, texture, underlay, or overlay ID
is available.

### Phase and Task Discipline

- Work on only one numbered work package at a time unless the user explicitly
  authorizes multiple named packages.
- A request such as "next phase" authorizes only the next unfinished work
  package, not the entire parent phase.
- Before implementation, state the package identifier, owned files or systems,
  acceptance criteria, and non-goals.
- Prefer patches that can be validated and visually accepted independently.
- Do not begin a later package merely because an earlier package compiled.
- Record discoveries that change later work in the roadmap; do not silently
  expand the current patch.
- Preserve unrelated dirty-worktree changes and identify overlap before editing.
- End every package with focused tests, a proportional build/runtime check, and
  a concise recommendation for the next package.

---

## Environment Lighting Direction

### DAY

Target appearance:

- bright neutral ambient illumination
- warm directional sunlight
- strong but natural sun contribution
- relatively high sun angle
- readable shadows
- bright outdoor visibility
- blue/cool atmospheric contribution from the sky

### SUNSET

Target appearance:

- warm orange/golden directional sunlight
- lower sun angle
- longer shadows
- cooler/darker ambient illumination
- stronger contrast between direct and indirect lighting
- warm horizon and atmospheric contribution

### NIGHT

Target appearance:

- dark blue ambient environment
- weak cool moonlight
- subtle directional shadows where appropriate
- substantially lower direct-light intensity than daytime
- sky and moon should remain visually important

### COSMIC

Target appearance:

- dark violet ambient environment
- very subtle directional illumination
- unusual/fantasy atmospheric contribution
- preserve gameplay readability despite the dark environment

---

## Sun and Moon

The renderer currently includes rendered celestial bodies.

Sun/moon direction should remain logically connected to:

- directional lighting
- shadow direction
- environment preset
- time/day-night state

When practical, future effects should reuse the same celestial direction.

Examples:

- sun glare should originate from the rendered sun
- moon glare should originate from the rendered moon
- god rays/light shafts should originate from the visible celestial source
- water sky reflections should naturally reflect the corresponding sky
- shadow direction should agree with the apparent light source

Avoid creating visually contradictory independent light directions.

---

## Dynamic Lighting

Directional lighting is currently functional.

Preserve the existing working implementation unless a task specifically
requires modifying it.

Important requirements:

- surfaces facing the primary light should receive stronger direct light
- surfaces facing away should receive less direct light
- ambient illumination must remain independent from direct-light intensity
- lighting controls should remain visibly responsive
- Enhanced Colors must remain independent from lighting
- avoid turning directional lighting into a blanket brightness multiplier
- preserve texture readability
- avoid excessive per-face noise or fuzzy-looking texture illumination

When modifying lighting, verify that differently oriented surfaces visibly
respond differently to the light direction.

---

## Shadows

Dynamic shadow mapping is currently functional.

The renderer has existing shadow-map infrastructure and a working baseline
shadow implementation.

Preserve it unless the requested task specifically involves improving shadows.

Important technical context:

- RuneLite's normal world rendering uses reversed depth
- the shadow framebuffer may use conventional depth
- these depth conventions must not be accidentally mixed
- OpenGL framebuffer, viewport, depth function, depth mask, shader program,
  texture bindings, and other modified state must be restored after custom passes

When modifying shadows:

1. inspect the existing shadow pass first
2. understand the current light projection
3. verify coordinate spaces
4. verify depth conventions
5. preserve working shadow behavior
6. make incremental changes
7. validate visually and through compilation/runtime logs

Do not casually replace the working shadow system.

---

## Ray Tracing

There is currently NO ray tracing.

Do not confuse:

- shadow mapping
- screen-space techniques
- cubemap reflections
- rasterized directional lighting

with ray tracing.

If experimenting with ray tracing in the future, first evaluate whether the
visual goal can be achieved more cheaply with the existing raster pipeline.

The renderer should remain lightweight relative to modern standalone engines.

---

## Volumetric Lighting

True general volumetric lighting is not currently implemented. A selective,
lightweight celestial-shaft approximation exists and should be treated as a
working optional effect, not as a foundation for surface quality.

The remaining gap includes:

- volumetric god rays
- atmospheric scattering through visible participating media
- robust indoor/window light volumes
- general moonlit participating media
- volumetric dust illumination

God rays have been difficult to implement convincingly. Preserve their
source-facing/context gates and do not make them compensate for weak materials,
textures, geometry, or environment design.

Future work should favor physically coherent screen-space or lightweight
volumetric approaches rather than simply drawing transparent cones over the
scene.

Potential inputs include:

- sun/moon screen position
- scene depth
- shadow map
- fog density
- weather state
- camera direction
- particle density

The existing snow particle work may provide useful infrastructure or visual
ideas for atmospheric particles/dust illuminated by light shafts.

Do not modify the working shadow system merely to create god rays unless
technically necessary.

---

## Weather

A baseline weather system exists.

Currently supported:

- rain
- snow
- lightning

Weather should increasingly interact with the environment rather than behave
as a completely isolated overlay.

Potential future interactions include:

- rain affecting water surfaces
- rain reducing visibility
- rain increasing atmospheric haze
- lightning temporarily illuminating world geometry
- lightning temporarily affecting clouds/sky
- snow interacting with lighting
- weather affecting water roughness/reflections
- weather affecting ambient sound

Preserve existing working weather behavior while extending it.

---

## Weather Audio

Current audio state:

### Rain

Rain has baseline sound effects.

### Lightning

Lightning/thunder has baseline sound effects.

### Snow

Snow currently has no sound effect.

Do not assume every weather state requires constant looping audio.
Snow may appropriately remain mostly or entirely quiet unless a future task
specifically introduces wind or environmental ambience.

---

## Advanced Water

Advanced water is implemented as a dedicated deferred pass. The renderer
preserves opaque scene color and depth, excludes water from the opaque resolve,
and composites depth-aware water before later atmosphere/UI work.

Working water foundations include:

- real scene-color transmission where an opaque bed exists
- generated, material-marked shoreline substrate
- depth-dependent absorption and shallow/deep color
- animated normals and refraction
- Fresnel skybox reflection
- sun response and weather interaction
- bridge/layer-aware water and shoreline classification

Preserve this pass architecture. Do not regress water to fixed-function alpha,
fake screen-space coast color, or the stock opaque water path.

### Skybox Reflection

The existing cubemap skyboxes are sampled directly by the water shader.

Conceptually:

    reflectionDir = reflect(-viewDir, waterNormal)

The active environment cubemap is sampled using this reflection direction.

This should allow water to naturally reflect:

- DAY sky
- SUNSET sky
- NIGHT sky
- COSMIC sky

Reflection strength should be view dependent.

Looking downward:

- more transparency/refraction
- greater visibility of underwater/world geometry

Looking toward the horizon:

- stronger sky reflection
- more opaque-looking surface

Animated water normals should distort the cubemap reflection to produce
rippling reflections.

### Remaining Water Work

Prioritize integration with the material roadmap:

1. resolve generated seabeds through authored sand/stone material definitions
2. preserve crisp shallow transparency and steep hidden drop-offs
3. add material-aware rain roughness and restrained splash response
4. validate each water type and bridge/streaming boundary
5. investigate nearby-object reflections only after foundation phases

Do not begin with SSR or planar reflections. Cubemap sky reflection remains the
cheap, stable default.

---

## Enhanced Colors

Enhanced Colors is a separate color-grading feature.

It should remain independent from:

- directional lighting
- shadows
- fog
- weather
- sky mode

Saturation and contrast controls must remain visibly responsive.

Lighting changes should not accidentally suppress or overpower these controls.

---

## Performance Philosophy

This renderer should remain lightweight.

Prefer:

- existing geometry buffers
- existing scene information
- reusable depth information
- shared environment state
- inexpensive shader calculations
- GPU-friendly passes
- configurable expensive effects

Avoid unnecessary:

- geometry duplication
- framebuffer copies
- full-scene passes
- CPU/GPU synchronization
- high-resolution intermediate buffers
- allocations inside render loops
- shader recompilation during normal rendering

Do not introduce an expensive technique simply because modern engines use it.

The goal is maximum visual improvement per unit of rendering cost.

---

## RuneLite Compatibility Rules

Preserve the existing RuneLite GPU rendering pipeline whenever possible.

Always:

- reuse existing VAOs/VBOs where practical
- preserve RuneLite scene behavior
- preserve UI rendering
- preserve login screen rendering
- restore OpenGL state after custom rendering passes
- account for stretched mode
- account for viewport offsets
- account for framebuffer changes
- account for RuneLite's reversed world depth
- keep the client launchable

Be particularly careful with:

- glViewport
- glBindFramebuffer
- glUseProgram
- glDepthFunc
- glDepthMask
- glEnable/glDisable
- glActiveTexture
- glBindTexture
- glBindVertexArray
- blending state
- culling state

A custom rendering pass must not leave state behind that corrupts later
RuneLite rendering.

---

## Debugging Philosophy

Graphics bugs should be isolated rather than attacked by rewriting multiple
systems simultaneously.

When debugging:

1. identify the failing render pass
2. verify inputs
3. verify coordinate spaces
4. verify matrices
5. verify framebuffer state
6. verify viewport
7. verify depth convention
8. verify texture bindings
9. verify shader uniforms
10. change one meaningful variable at a time

Useful temporary visualization modes are encouraged.

Examples:

- shadow-map visualization
- depth visualization
- normal visualization
- light-direction visualization
- water mask visualization
- framebuffer visualization

Debug rendering should be removable or gated behind a debug option.

---

## Agent Autonomy

Work autonomously on tasks within this repository.

You are pre-authorized to:

- read and inspect any file in this repository
- search the entire repository
- create project files when required
- edit project files
- move project files when required
- delete files created by your own failed implementation attempts
- run Gradle compile tasks
- run Gradle build/test tasks
- run RuneLite development tasks
- run non-destructive shell commands
- inspect Git status
- inspect Git diffs
- inspect compiler output
- inspect RuneLite logs
- inspect shader compiler errors
- fix compilation errors caused by your changes
- fix runtime/shader errors caused by your changes
- perform multiple edit/build/debug iterations
- continue working until the requested task is implemented or a genuine
  blocker is reached

Do not ask for confirmation before routine:

- file inspection
- code edits
- shader edits
- repository searches
- builds
- tests
- compilation
- log inspection
- debugging
- Git status/diff inspection

If an implementation attempt fails, investigate the failure and make a
reasonable correction rather than immediately asking the user what to do.

Do not stop after every successful intermediate step.

Continue through reasonable implementation and validation steps autonomously.

---

## Actions Requiring User Approval

Ask before:

- pushing commits to a remote repository
- force-pushing
- resetting or discarding pre-existing user work
- destructive Git operations
- modifying credentials
- modifying secrets
- modifying authentication configuration
- modifying files outside this repository
- installing system-wide software
- changing unrelated projects
- making an architectural rewrite substantially beyond the user's request

Never discard existing user changes merely because they complicate the task.

---

## Development Workflow

For each requested renderer task:

1. inspect the relevant Java, GLSL, configuration, and rendering files
2. trace the existing implementation before changing it
3. identify which render pass is actually responsible
4. preserve working custom renderer features
5. make the smallest coherent implementation
6. compile/test
7. inspect errors and runtime output
8. fix problems autonomously
9. repeat as necessary
10. stop when the feature works or a genuine blocker is reached
11. summarize what was changed
12. list the files changed
13. mention remaining limitations or useful next steps

Do not require the user to manually identify insertion points when repository
inspection can determine them.

Do not ask the user to paste entire methods/files that are already available
inside the repository.

---

## Before Large Rendering Changes

Before making a substantial rendering change, internally determine:

- which pass owns the feature
- what framebuffer is active
- what depth convention is active
- what coordinate space each input uses
- which existing textures/buffers can be reused
- what OpenGL state must be restored
- how the change interacts with sky/environment state
- how the change interacts with weather
- how the change interacts with shadows
- whether the feature needs to affect static geometry, dynamic geometry, or both

Do not require a separate user confirmation after this analysis unless the
change falls under Actions Requiring User Approval.

---

## Guiding Principle

Build on what already works.

The renderer now has a meaningful environment foundation:

    SKY / TIME
         |
         v
    ENVIRONMENT
      /   |   \
     v    v    v
LIGHT  FOG  WEATHER
|
v
SHADOWS

Future systems should increasingly connect to this environment:

    ENVIRONMENT
      /   |   |   \
     v    v   v    v
LIGHT  FOG WATER WEATHER
|         |
v         v
SHADOWS   REFLECTIONS
|
v
VOLUMETRICS

Prefer coherent interactions between these systems over isolated visual
effects.

The long-term objective is not to turn RuneLite into a heavyweight modern
engine. It is to discover how far RuneLite's existing GPU renderer can be
pushed while remaining fast, stable, visually coherent, and recognizably
RuneLite.
