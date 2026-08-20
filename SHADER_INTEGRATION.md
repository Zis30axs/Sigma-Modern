# Shader integration status

Sigma-Modern keeps shader-pack loading and compatibility transformation separate from graphics-backend execution so OpenGL and Vulkan can share pack semantics while retaining independent backend compilation.

## Current milestone

- Shader packs can be discovered from the `shaderpacks` directory as folders or ZIP archives.
- Pack paths are constrained to the shader-pack root.
- GLSL stages are discovered and `#include` directives are expanded with cycle and root-escape protection.
- OpenGL and Vulkan are both recognized as custom-shader-capable backends.
- Terrain rendering resolves real shader-pack programs with Iris-style fallbacks and can compile transformed `gbuffers_terrain_solid`, `gbuffers_terrain_cutout`, and `gbuffers_water` sources through Minecraft 26.2's native `GpuDevice` pipeline path.
- Terrain programs can now use multiple color outputs through `DRAWBUFFERS` or `RENDERTARGETS`. Sigma owns reusable `colortex1` through `colortex7` textures while draw-buffer position 0 continues writing into Minecraft's current terrain color target.
- MRT activation is per terrain layer. If one layer requests unsupported stages, attributes, uniforms, formats, or more color attachments than the active backend exposes, that layer keeps its vanilla pipeline without disabling other shader layers or Vulkan.
- Shader-pack `final.fsh` has a real full-screen rendering path on OpenGL and Vulkan. It can sample current-frame `colortex0` through `colortex7` targets that were produced by the supported terrain gbuffer path, plus the current main `depthtex0` depth texture.

## Terrain program resolution

The terrain resolver follows the useful portion of Iris 26.2's `ProgramId` fallback model:

- solid: `gbuffers_terrain_solid` -> `gbuffers_terrain` -> `gbuffers_textured_lit` -> `gbuffers_textured` -> `gbuffers_basic`
- cutout: `gbuffers_terrain_cutout` -> `gbuffers_terrain` -> `gbuffers_textured_lit` -> `gbuffers_textured` -> `gbuffers_basic`
- translucent: `gbuffers_water` -> `gbuffers_terrain` -> `gbuffers_textured_lit` -> `gbuffers_textured` -> `gbuffers_basic`

The terrain compatibility transformer maps common fixed-function and OptiFine-era interfaces onto Sigma's current Minecraft 26.2 block mesh contract: `Position`, `Color`, `UV0`, `UV2`, `Globals`, `Fog`, `Projection`, `ChunkSection`, the block atlas, and the lightmap. This includes common uses of `ftransform`, `gl_Vertex`, `gl_Color`, `gl_MultiTexCoord0/1/2`, `gl_TextureMatrix`, model/projection matrices, legacy `varying`, legacy texture functions, and fixed-index `gl_FragData` outputs.

`DRAWBUFFERS` and `RENDERTARGETS` are interpreted like shader-pack framebuffer directives: fragment output location N maps to the Nth logical colortex entry from the directive. The current implementation supports up to eight RGBA8 color targets and requires logical `colortex0` as the first terrain draw buffer so Minecraft's normal world-color composition remains intact.

This remains intentionally conservative. The current block mesh does not contain Iris/Sodium extended attributes such as block/entity IDs, normals, mid texture coordinates, tangents, or mid-block data. Programs requiring those attributes, arbitrary shader-pack uniforms, custom render-target formats, geometry/tessellation/compute stages, or dynamic fragment-output indices fall back per layer.

## Render-target lifetime

- `colortex1` through `colortex7` are allocated lazily at the current terrain target size and reused across frames.
- Extra gbuffer targets are cleared once when first needed in a world frame, then shared by solid/cutout/translucent terrain passes so later passes can accumulate into the same logical buffers.
- The vanilla no-shader and single-color terrain path keeps the existing combined section-layer RenderPass. Separate per-layer passes are only introduced when MRT attachment counts require them.
- Resizing recreates the extra targets at the new dimensions. Changing shader pack or graphics backend releases the shader-owned targets.

## Final-pass subset

The final transformer currently supports:

- `colortex0` through `colortex7`
- legacy aliases `gcolor`, `gdepth`, `gnormal`, `composite`, `gaux1` through `gaux4`
- current main depth as `depthtex0` / `gdepthtex`
- one `vec2` fragment input, including legacy `gl_TexCoord[0]`
- legacy `texture2D` / `texture2DLod`
- `gl_FragColor` / `gl_FragData[0]`
- `viewWidth`, `viewHeight`, and `aspectRatio`

A final pass that asks for a colortex buffer not produced in the current frame falls back instead of sampling stale data. `depthtex1` and `depthtex2`, shadow samplers, arbitrary shader-pack uniforms, and compute/geometry/tessellation stages remain outside the current subset.

## Next renderer stages

1. Add deferred/composite pass planning and ping-pong target flipping so `composite*.fsh` can consume and update shader-owned colortex targets before `final.fsh`.
2. Add depth snapshots for `depthtex1` (before translucent) and `depthtex2` (before hand), then expose them to composite/final passes.
3. Extend the terrain mesh/uniform contract for commonly requested data such as normals, material/block IDs, mid texture coordinates, tangents, camera/time/environment values, and related samplers.
4. Parse shader-pack render-target format directives and support non-RGBA8 colortex formats where the active backend supports them.
5. Add shadow target ownership and shadow terrain/entity rendering phases.
6. Extend backend stage support for geometry, tessellation, and compute programs where Minecraft's active backend can provide them safely.

## Compatibility policy

- Vulkan vanilla rendering is a hard fallback path and must remain usable even when shader-pack initialization, transformation, render-target allocation, or pipeline compilation fails.
- Vulkan shader support is capability-based. The same transformed source and MRT attachment model are submitted through Minecraft 26.2's backend-neutral pipeline API; Vulkan uses the native GLSL-to-SPIR-V route instead of emulating OpenGL framebuffer state.
- Terrain compatibility is enabled per layer and only after the transformed pipeline successfully compiles on the active backend.
- Backend-specific state stays out of shared shader-pack parsing, fallback resolution, and compatibility transformation code.
- OpenGL-specific Iris behavior is adapted rather than pasted into shared renderer code.

Design references include the public Iris 26.2 source and the experimental public `fangbm/iris4vulkan` 26.2 port. They are implementation references; Sigma-Modern's integration is written around its own directly maintained Minecraft source architecture.
