# Water Subsystem

Last updated: 2026-08-26. The active branch uses a lightweight inline water shader. `origin/master` contains a more complete deferred-water reference, but it is not present here.

## Goal

Water should feel clear, reflective, animated, and integrated with the active sky/weather while remaining inexpensive on Apple OpenGL 4.1. It must not expose missing RuneLite underwater geometry, break alpha ordering, or require duplicated world VBOs.

## Read only these files first

| Concern | Files/anchors |
| --- | --- |
| Water settings/uniforms/pass ownership | `GpuPluginConfig.java` (`enhancedWater`, `waterStrength`, `waterOpacity`), `GpuPlugin.java` |
| Current shader | `frag.glsl`, section `Enhanced water` |
| Water classification and shore flags | `SurfaceMaterialClassifier.java`, `SceneUploader.java`, `SurfaceMaterial.java` |
| Cubemap/environment selection | `GpuPlugin.java` (`activeSkyTexture`, `drawSkybox`) |
| Deferred reference only | `git show origin/master:.../GpuPlugin.java`, `water_vert.glsl`, `water_frag.glsl`, `Zone.java` |

Do not scan unrelated weather/volumetric code unless the task explicitly couples water to weather.

## Current implementation

Water is still rendered in the ordinary world pass by `frag.glsl`.

### Identification

`SurfaceMaterialClassifier.isWaterTexture` is the authoritative Java list:

- texture IDs 1, 24, 25;
- 130 through 189;
- 208.

Texture 25 is treated as swamp water. `vert.glsl`/`frag.glsl` use the decoded stock texture ID; material packing must never corrupt this lookup.

### Shore metadata

`SceneUploader` packs water shoreline information into the low eight bits of `tex.w`:

- four cardinal edge bits;
- four corner bits.

Material eligibility uses higher bits (`0x100`, `0x200`) and must not overlap the shoreline mask.

### Shader behavior

The inline block currently provides:

- small UV motion on the stock water texture;
- layered world-space broad and soft wave normals;
- Schlick-like grazing response (currently a broad power-3 Fresnel approximation);
- one active cubemap reflection sample;
- clear/swamp tint separation;
- simulated shallow-water shelf near packed shoreline edges;
- procedural caustic color and a narrow shoreline ripple;
- sharp and broad celestial sparkle;
- fully opaque final output.

The active sky cubemap on texture unit 3 supplies the environment reflection, so DAY/SUNSET/NIGHT/COSMIC and weather skies automatically affect water.

### Current configuration

| Setting | Default | Meaning |
| --- | ---: | --- |
| Enhanced water | On | Enables the inline custom block |
| Water strength | 100 | Scales wave/tint/reflection/specular response; range 0–200 |
| Water opacity | 82 | Artistic clarity/bed blend control; range 40–100 |

Despite its name, Water Opacity is not fixed-function transparency. The inline path always outputs alpha 1.

## Known limitations

- No sampleable resolved scene color or scene depth is available to this fragment block.
- It cannot refract real geometry below the surface.
- “Clarity” is a visual blend toward a procedural shallow bed, not physical transmission.
- RuneLite scenes are not watertight; naïve transparency reveals holes and missing underwater floors.
- Reflection is sky-only. There is no SSR or local building reflection.
- The same shader must tolerate horizontal terrain water and model/vertical water, so physically horizontal assumptions must stay conservative.
- The cubemap currently has no mip chain; rough reflection uses a single sharp sample in this branch.
- The current sun highlight is not sampled from a dedicated water material/shadow pass.

Do not try to fix these limitations by simply lowering `c.a`. That will expose scene gaps and ordering artifacts.

## Reference architecture on `origin/master`

Commit `961163f17` (`Modernize GPU atmosphere water and materials`) contains a higher-quality deferred implementation. Useful ideas:

- keep water triangles in the existing zone VBO;
- record compact water draw ranges instead of duplicating geometry;
- omit eligible terrain water from the ordinary opaque draw;
- resolve opaque scene color and reversed depth only when visible water exists;
- render water after opaque geometry and before alpha geometry;
- depth-reject invalid refraction samples;
- use real substrate thickness where geometry exists and a stable deep-water fallback where it does not;
- use narrow Fresnel, world-space normals, cubemap reflection, weather roughness, shadowed sun specular, and fog closure.

That implementation also includes generated shoreline substrate geometry and a large shader. Do not cherry-pick it wholesale: it is coupled to unrelated main-branch materials/volumetrics and can be expensive on a Mac.

## Cost risks in the deferred design

- Full-resolution RGBA8 color plus DEPTH32F resolve costs 8 bytes per pixel of storage and significant per-frame bandwidth.
- At 2560×1600 that is roughly 33 MiB; at 4K roughly 66 MiB, before multisample storage.
- Any visible pond can trigger a full-screen color/depth resolve unless visibility is tracked carefully.
- The reference fragment path uses many texture reads and transcendental operations.
- Per-frame `glGet*` calls can serialize Apple drivers; use cached viewport/state.

## Recommended incremental port

### Phase 1: minimal deferred water

1. Reuse the existing zone VBO and port only compact terrain-water ranges.
2. Lazy-allocate a single-sample scene color/depth resolve only while Enhanced Water is enabled and eligible water is visible.
3. Add a small dedicated `water_vert.glsl`/`water_frag.glsl` pass.
4. Implement stable world waves, reversed-depth rejection, one cubemap sample, narrow Fresnel, and a deep-water fallback.
5. Keep the current inline shader as fallback for nested views, model water, waterfalls, and unsupported cases.

### Phase 2: optical depth and weather

- add restrained refraction;
- derive transmission from valid scene depth;
- add clear/swamp absorption;
- use 1–4 shadow samples for celestial sparkle;
- increase roughness during rain/storm/snow;
- integrate lightning without whitening the whole surface.

### Phase 3: shoreline substrate

Only after Phase 1 is stable:

- generate shallow substrate near connected banks;
- tag generated bed geometry explicitly;
- preserve real rocks/terrain when available;
- keep CPU searches bounded and cached.

## Non-negotiable invariants

- Stock water must remain available as fallback.
- No duplicated full-world VBO.
- Reversed scene depth and conventional shadow depth remain separate.
- Water pass state is fully restored.
- Alpha geometry must still draw in the correct order over water.
- UI and login are not affected.
- Enhanced Water off must recover the stock path.
- Do not combine the first deferred-water port with HDR, volumetrics, or new texture replacement work.

## Focused validation

- Clear pond and swamp, fixed camera: Enhanced Water off/on.
- Near shore and open water at multiple zoom levels.
- Roof-hidden water and nested world views.
- Fences/foliage/bridges over water for alpha ordering.
- DAY/SUNSET/NIGHT/COSMIC reflection orientation.
- Rain/storm/snow roughness and lightning response.
- Missing underwater geometry: no sudden holes or screen-sticker refraction.
- Mac GPU frame time with and without visible water.
