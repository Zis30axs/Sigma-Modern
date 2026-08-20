# Shader integration status

Sigma-Modern keeps shader-pack loading and compatibility transformation separate from graphics-backend execution so OpenGL and Vulkan can share pack semantics while retaining independent backend compilation.

## Current milestone

- Shader packs can be discovered from the `shaderpacks` directory as folders or ZIP archives.
- Pack paths are constrained to the shader-pack root.
- GLSL stages are discovered and `#include` directives are expanded with cycle and root-escape protection.
- OpenGL and Vulkan are both recognized as custom-shader-capable backends.
- Shader-pack `final.fsh` has a real full-screen rendering path on both OpenGL and Vulkan. Sigma copies the completed world color target, transforms a conservative OptiFine/Iris-compatible fragment-shader subset, compiles it through the active backend, and draws it back into the world target.
- Terrain rendering now resolves real shader-pack programs with Iris-style fallbacks and can compile transformed `gbuffers_terrain_solid`, `gbuffers_terrain_cutout`, and `gbuffers_water` sources through Minecraft 26.2's native `GpuDevice` pipeline path.
- Terrain activation is per layer. A supported solid program can run even if cutout or water requires features outside the current subset; unsupported layers keep their vanilla pipeline instead of disabling shader rendering or the Vulkan backend.

## Terrain program resolution

The current terrain resolver follows the useful portion of Iris 26.2's `ProgramId` fallback model:

- solid: `gbuffers_terrain_solid` -> `gbuffers_terrain` -> `gbuffers_textured_lit` -> `gbuffers_textured` -> `gbuffers_basic`
- cutout: `gbuffers_terrain_cutout` -> `gbuffers_terrain` -> `gbuffers_textured_lit` -> `gbuffers_textured` -> `gbuffers_basic`
- translucent: `gbuffers_water` -> `gbuffers_terrain` -> `gbuffers_textured_lit` -> `gbuffers_textured` -> `gbuffers_basic`

The first terrain compatibility transformer maps common fixed-function and OptiFine-era interfaces onto Sigma's current Minecraft 26.2 block mesh contract: `Position`, `Color`, `UV0`, `UV2`, `Globals`, `Fog`, `Projection`, `ChunkSection`, the block atlas, and the lightmap. This includes common uses of `ftransform`, `gl_Vertex`, `gl_Color`, `gl_MultiTexCoord0/1/2`, `gl_TextureMatrix`, model/projection matrices, legacy `varying`, legacy texture functions, and target-0 fragment outputs.

This is intentionally conservative. The current block mesh does not contain Iris/Sodium extended attributes such as block/entity IDs, normals, mid texture coordinates, tangents, or mid-block data. Programs requiring those attributes, arbitrary shader-pack uniforms, geometry/tessellation/compute stages, or more than color target 0 are rejected for this terrain pass and fall back to vanilla per layer.

## Final-pass subset

The initial `final.fsh` compatibility subset supports `colortex0`/`gcolor`, one `vec2` fragment input (including legacy `gl_TexCoord[0]`), legacy `texture2D`/`texture2DLod`, `gl_FragColor`/`gl_FragData[0]`, and the screen-size uniforms `viewWidth`, `viewHeight`, and `aspectRatio`. Additional color targets, shadow samplers, arbitrary shader-pack uniforms, depth writes, geometry/tessellation stages, and compute shaders remain outside this pass. A rejected or failed final shader leaves the world output intact.

## Next renderer stages

1. Add shader-pack-owned `colortex` and depth targets and connect their lifetime to the world frame graph so terrain programs can use real multi-render-target gbuffer outputs.
2. Extend the terrain mesh/uniform contract for commonly requested shader-pack data such as normals, material/block IDs, mid texture coordinates, tangents, camera/time/environment values, and related samplers.
3. Add deferred/composite pass planning, target flipping, multiple color/depth samplers, and broader final-pass compatibility.
4. Add shadow target ownership and shadow terrain/entity rendering phases.
5. Extend backend stage support for geometry, tessellation, and compute programs where Minecraft's active backend can provide them safely.

## Compatibility policy

- Vulkan vanilla rendering is a hard fallback path and must remain usable even when shader-pack initialization or compilation fails.
- Vulkan shader support is capability-based. The same transformed source is submitted through Minecraft 26.2's backend-neutral pipeline API; Vulkan uses the native GLSL-to-SPIR-V route instead of emulating OpenGL state.
- Terrain compatibility is enabled per layer and only after the transformed pipeline successfully compiles on the active backend.
- Backend-specific state stays out of shared shader-pack parsing, fallback resolution, and compatibility transformation code.
- OpenGL-specific Iris behavior is adapted rather than pasted into shared renderer code.

Design references include the public Iris 26.2 source and the experimental public `fangbm/iris4vulkan` 26.2 port. They are implementation references; Sigma-Modern's integration is written around its own directly maintained Minecraft source architecture.
