# Shader integration status

Sigma-Modern keeps shader-pack loading and compatibility transformation separate from graphics-backend execution so OpenGL and Vulkan can share pack semantics while retaining independent backend compilation.

## Current milestone

- Shader packs can be discovered from the `shaderpacks` directory as folders or ZIP archives.
- Pack paths are constrained to the shader-pack root.
- GLSL stages are discovered and `#include` directives are expanded with cycle and root-escape protection.
- OpenGL and Vulkan are both recognized as custom-shader-capable backends.
- Terrain rendering resolves real shader-pack programs with Iris-style fallbacks and compiles transformed `gbuffers_terrain_solid`, `gbuffers_terrain_cutout`, and `gbuffers_water` sources through Minecraft 26.2's native `GpuDevice` pipeline path.
- Terrain `DRAWBUFFERS` / `RENDERTARGETS` can route up to eight logical `colortex` targets. Logical target numbers are kept separate from fragment-output locations, matching shader-pack semantics.
- Terrain activation remains per layer. Unsupported solid/cutout/water programs fall back independently, and selecting a shader pack must not make the Vulkan backend unusable.
- Ordered `composite`, `composite1`, `composite2`, ... fragment passes now have a first backend-neutral execution path on OpenGL and Vulkan.
- Composite passes use main/alternate render-target pairs. A pass samples the current side, writes the opposite side, then flips every logical target it wrote. This avoids reading from and writing to the same texture in one screen pass.
- Before the existing `final.fsh` stage runs, Sigma resolves the current logical `colortex0` side back to Minecraft's main world target. Extra `colortex` samplers keep their current ping-pong side, so the final stage can consume the composite chain without changing its existing world-end hook.
- Shader-pack `final.fsh` continues to execute as a real full-screen pass after composite resolution.

## Terrain program resolution

The terrain resolver follows the useful portion of Iris 26.2's `ProgramId` fallback model:

- solid: `gbuffers_terrain_solid` -> `gbuffers_terrain` -> `gbuffers_textured_lit` -> `gbuffers_textured` -> `gbuffers_basic`
- cutout: `gbuffers_terrain_cutout` -> `gbuffers_terrain` -> `gbuffers_textured_lit` -> `gbuffers_textured` -> `gbuffers_basic`
- translucent: `gbuffers_water` -> `gbuffers_terrain` -> `gbuffers_textured_lit` -> `gbuffers_textured` -> `gbuffers_basic`

The current terrain compatibility transformer maps common fixed-function and OptiFine-era interfaces onto Sigma's Minecraft 26.2 block mesh contract: `Position`, `Color`, `UV0`, `UV2`, `Globals`, `Fog`, `Projection`, `ChunkSection`, the block atlas, and the lightmap. Common supported forms include `ftransform`, `gl_Vertex`, `gl_Color`, `gl_MultiTexCoord0/1/2`, `gl_TextureMatrix`, model/projection matrices, legacy `varying`, legacy texture functions, and target-indexed fragment outputs.

The block mesh still does not contain Iris/Sodium extended attributes such as block/entity IDs, normals, mid texture coordinates, tangents, or mid-block data. Programs requiring those attributes, arbitrary shader-pack uniforms, geometry/tessellation/compute stages, or unsupported backend capabilities fall back per terrain layer.

## Composite-pass subset

The first composite implementation is deliberately fragment-oriented. Sigma discovers root-level `composite`, `composite1`, `composite2`, ... in numeric order, preprocesses their fragment source, and synthesizes a full-screen triangle vertex stage. The pack-provided composite vertex stage is not executed yet, so packs that depend on meaningful custom composite vertex work are outside the guaranteed compatibility subset.

The current subset supports:

- `DRAWBUFFERS` and `RENDERTARGETS` using logical targets 0 through 7;
- `colortex0` through `colortex7` and the legacy aliases `gcolor`, `gdepth`, `gnormal`, `composite`, and `gaux1` through `gaux4`;
- `depthtex0` / `gdepthtex`;
- one `vec2` fragment input, including legacy `gl_TexCoord[0]`;
- legacy `texture2D`, `texture2DLod`, `texture2DProj`, and `texture2DGrad` forms;
- `gl_FragColor` for a single draw buffer and numeric `gl_FragData[n]` mapped to explicit modern output locations;
- `viewWidth`, `viewHeight`, and `aspectRatio` derived from the current `colortex0` size.

`depthtex1`, deferred passes, explicit flip directives, mipmap directives, viewport scaling, requested colortex formats, custom uniforms/textures, shadow samplers, image/SSBO resources, begin/prepare passes, and compute programs are not active yet. The transformer already rejects unsupported resources conservatively instead of submitting undefined bindings to Vulkan.

## Final-pass subset

The current `final.fsh` path supports `colortex0` through `colortex7` plus the same legacy color aliases, `depthtex0` / `gdepthtex`, one `vec2` fragment input, legacy texture functions, `gl_FragColor` / `gl_FragData[0]`, and the screen-size uniforms `viewWidth`, `viewHeight`, and `aspectRatio`. Composite resolves the current `colortex0` side back to the Minecraft main target first; the existing final pass then keeps its normal copy-before-write protection for `colortex0`, while extra color samplers read the current ping-pong side.

## Next renderer stages

1. Add an exact pre-translucent world-stage hook, capture `depthtex1`, and execute `deferred*` before translucent rendering instead of incorrectly treating deferred as an end-of-world composite.
2. Add explicit buffer flips, mipmap generation, viewport scaling, and requested `colortex` formats from shader-pack directives.
3. Extend the shared uniform/texture contract for camera, time, environment, material/block IDs, normals, mid texture coordinates, tangents, and other commonly requested shader-pack data.
4. Add shadow target ownership and shadow terrain/entity rendering phases.
5. Extend backend stage support for geometry, tessellation, and compute programs where Minecraft's active backend can provide them safely.

## Compatibility policy

- Vulkan vanilla rendering is a hard fallback path and must remain usable even when shader-pack transformation, pipeline compilation, or a screen pass fails.
- Vulkan shader support is capability-based. The same transformed source is submitted through Minecraft 26.2's backend-neutral pipeline API; Vulkan uses the native GLSL-to-SPIR-V route instead of emulating OpenGL state.
- Backend-specific state stays out of shared shader-pack parsing, fallback resolution, render-target planning, and compatibility transformation code.
- Screen-pass ping-pong state is reset every world frame and is not allowed to leak into vanilla rendering after a failed or missing final stage.
- OpenGL-specific Iris behavior is adapted rather than pasted into shared renderer code.

Design references include the public Iris 26.2 source and the experimental public `fangbm/iris4vulkan` 26.2 port. They are implementation references; Sigma-Modern's integration is written around its own directly maintained Minecraft source architecture.
