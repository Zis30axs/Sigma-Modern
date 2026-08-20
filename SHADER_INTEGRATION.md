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
- Ordered `deferred`, `deferred1`, `deferred2`, ... fragment passes execute at the pre-translucent world boundary on OpenGL and Vulkan.
- `depthtex1` is captured every active shader world frame immediately before deferred/translucent rendering, after opaque terrain and solid features but before translucent features and water.
- `depthtex2` is captured at the end of world rendering after translucent world content and before Minecraft clears depth for its vanilla first-person hand pass.
- Deferred passes reuse the screen-pass transformer and the shared colortex ping-pong targets. Because Sigma's remaining translucent entity/particle paths are not fully shader-gbuffer-aware yet, deferred output is temporarily normalized back to each logical target's main side before vanilla translucent rendering continues.
- Ordered `composite`, `composite1`, `composite2`, ... fragment passes execute at world end on OpenGL and Vulkan and can sample `depthtex0`, `depthtex1`, and `depthtex2`.
- Literal `flip.<pass>.<buffer>=true|false` directives from `shaders.properties` are applied to `deferred_pre`, individual deferred passes, `composite_pre`, and individual composite passes with Iris-compatible ping-pong semantics.
- Composite passes use main/alternate render-target pairs. A pass samples the current side, writes the opposite side, then applies its default and explicit flip rules without reading from and writing to the same texture in one screen pass.
- Before the existing `final.fsh` stage runs, Sigma resolves the current logical `colortex0` side back to Minecraft's main world target. Extra `colortex` samplers keep their current ping-pong side, and final shaders can sample `depthtex0`, `depthtex1`, and `depthtex2` simultaneously.
- Shader-pack `final.fsh` executes as a real full-screen pass after composite resolution. Sigma does not yet replace Minecraft's later vanilla first-person hand renderer with an Iris-style shader-aware hand path, so the vanilla hand is still drawn after the shader-pack final stage.

## Terrain program resolution

The terrain resolver follows the useful portion of Iris 26.2's `ProgramId` fallback model:

- solid: `gbuffers_terrain_solid` -> `gbuffers_terrain` -> `gbuffers_textured_lit` -> `gbuffers_textured` -> `gbuffers_basic`
- cutout: `gbuffers_terrain_cutout` -> `gbuffers_terrain` -> `gbuffers_textured_lit` -> `gbuffers_textured` -> `gbuffers_basic`
- translucent: `gbuffers_water` -> `gbuffers_terrain` -> `gbuffers_textured_lit` -> `gbuffers_textured` -> `gbuffers_basic`

The current terrain compatibility transformer maps common fixed-function and OptiFine-era interfaces onto Sigma's Minecraft 26.2 block mesh contract: `Position`, `Color`, `UV0`, `UV2`, `Globals`, `Fog`, `Projection`, `ChunkSection`, the block atlas, and the lightmap. Common supported forms include `ftransform`, `gl_Vertex`, `gl_Color`, `gl_MultiTexCoord0/1/2`, `gl_TextureMatrix`, model/projection matrices, legacy `varying`, legacy texture functions, and target-indexed fragment outputs.

The block mesh still does not contain Iris/Sodium extended attributes such as block/entity IDs, normals, mid texture coordinates, tangents, or mid-block data. Programs requiring those attributes, arbitrary shader-pack uniforms, geometry/tessellation/compute stages, or unsupported backend capabilities fall back per terrain layer.

## Deferred-pass subset

Sigma discovers root-level `deferred`, `deferred1`, `deferred2`, ... programs in numeric order. The current implementation is deliberately fragment-oriented: it preprocesses and transforms the pack fragment stage and synthesizes a full-screen triangle vertex stage. Pack-provided deferred vertex stages that perform meaningful custom work remain outside the guaranteed compatibility subset.

The deferred stage runs from the first world `PreparedFrame.executeTranslucent()` boundary, guarded by an opaque-terrain world-frame marker so later hand/screen feature rendering cannot accidentally execute the deferred chain again. `depthtex1` is captured at that boundary on every active shader world frame, whether or not a deferred pass itself samples it.

The current deferred subset supports:

- `DRAWBUFFERS` and `RENDERTARGETS` using logical targets 0 through 7;
- `colortex0` through `colortex7` and the legacy aliases `gcolor`, `gdepth`, `gnormal`, `composite`, and `gaux1` through `gaux4`;
- `depthtex0` / `gdepthtex` and the captured pre-translucent `depthtex1`;
- literal `flip.deferred_pre.<buffer>` and `flip.deferred[N].<buffer>` properties for `colortex0` through `colortex7` and their legacy aliases;
- the same fragment compatibility forms currently supported by the composite transformer, including one `vec2` input, legacy texture functions, numeric `gl_FragData[n]`, and screen-size uniforms.

`depthtex2` is intentionally rejected in deferred programs because the pre-hand snapshot does not exist until after translucent world rendering. After the deferred chain, flipped logical color targets are copied back to their main sides and the flip state is reset. This normalization is intentionally temporary: it preserves correct deferred results while existing vanilla translucent feature renderers still assume Minecraft's normal render targets.

## Composite-pass subset

Sigma discovers root-level `composite`, `composite1`, `composite2`, ... in numeric order, preprocesses their fragment source, and synthesizes a full-screen triangle vertex stage. The pack-provided composite vertex stage is not executed yet, so packs that depend on meaningful custom composite vertex work remain outside the guaranteed compatibility subset.

The current subset supports:

- `DRAWBUFFERS` and `RENDERTARGETS` using logical targets 0 through 7;
- `colortex0` through `colortex7` and the legacy aliases `gcolor`, `gdepth`, `gnormal`, `composite`, and `gaux1` through `gaux4`;
- `depthtex0` / `gdepthtex`, pre-translucent `depthtex1`, and pre-hand `depthtex2`;
- literal `flip.composite_pre.<buffer>` and `flip.composite[N].<buffer>` properties;
- one `vec2` fragment input, including legacy `gl_TexCoord[0]`;
- legacy `texture2D`, `texture2DLod`, `texture2DProj`, and `texture2DGrad` forms;
- `gl_FragColor` for a single draw buffer and numeric `gl_FragData[n]` mapped to explicit modern output locations;
- `viewWidth`, `viewHeight`, and `aspectRatio` derived from the current `colortex0` size.

Flip behavior follows Iris's useful screen-pass rule: a draw buffer flips after a pass unless the corresponding explicit property is `false`, then every explicit `true` entry performs an additional flip. `*_pre` directives only perform entries explicitly set to `true`. Flip targets are included in Sigma's per-frame render-target preparation even when a pass does not otherwise sample or write them.

Sigma currently reads literal `shaders.properties` values only. If property preprocessor conditionals such as `#if`, `#ifdef`, or `#else` are detected, explicit flip directives are ignored rather than evaluating inactive branches incorrectly. Mipmap directives, viewport scaling, requested colortex formats, custom uniforms/textures, shadow samplers, image/SSBO resources, begin/prepare passes, and compute programs are not active yet. Unsupported resources are rejected rather than submitted as undefined Vulkan bindings.

## Final-pass subset

The current `final.fsh` path supports `colortex0` through `colortex7` plus the same legacy color aliases, `depthtex0` / `gdepthtex`, `depthtex1`, `depthtex2`, one `vec2` fragment input, legacy texture functions, `gl_FragColor` / `gl_FragData[0]`, and the screen-size uniforms `viewWidth`, `viewHeight`, and `aspectRatio`. A final shader can request any combination of the three depth samplers. Composite resolves the current `colortex0` side back to the Minecraft main target first; the final pass then keeps its normal copy-before-write protection for `colortex0`, while extra color samplers read the current ping-pong side.

`depthtex2` represents world depth immediately before Minecraft's vanilla hand depth clear. Sigma does not yet run the first-person hand through shader-pack gbuffers, so this milestone exposes the correct pre-hand depth resource without claiming full Iris hand-rendering compatibility.

## Next renderer stages

1. Make translucent terrain/entities/particles fully flip-aware so deferred output can remain on the Iris-style current side without the temporary normalize copies, while preserving Minecraft 26.2's Improved Transparency target routing.
2. Add an Iris-style shader-aware hand path so first-person hands participate in shader-pack gbuffers and the final-stage ordering can match Iris end-to-end.
3. Add mipmap generation, viewport scaling, requested `colortex` formats, and eventually the shader-properties preprocessor needed for conditional directives.
4. Extend the shared uniform/texture contract for camera, time, environment, material/block IDs, normals, mid texture coordinates, tangents, and other commonly requested shader-pack data.
5. Add shadow target ownership and shadow terrain/entity rendering phases.
6. Extend backend stage support for geometry, tessellation, and compute programs where Minecraft's active backend can provide them safely.

## Compatibility policy

- Vulkan vanilla rendering is a hard fallback path and must remain usable even when shader-pack transformation, directive parsing, pipeline compilation, a depth snapshot, or a screen pass fails.
- Vulkan shader support is capability-based. The same transformed source is submitted through Minecraft 26.2's backend-neutral pipeline API; Vulkan uses the native GLSL-to-SPIR-V route instead of emulating OpenGL state.
- Backend-specific state stays out of shared shader-pack parsing, fallback resolution, render-target planning, and compatibility transformation code.
- Screen-pass ping-pong and depth-snapshot readiness are reset every world frame and are not allowed to leak into vanilla rendering after a failed or missing final stage.
- OpenGL-specific Iris behavior is adapted rather than pasted into shared renderer code.

Design references include the public Iris 26.2 source and the experimental public `fangbm/iris4vulkan` 26.2 port. They are implementation references; Sigma-Modern's integration is written around its own directly maintained Minecraft source architecture.
