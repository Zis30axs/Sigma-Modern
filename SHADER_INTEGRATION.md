# Shader integration status

Sigma-Modern keeps shader-pack loading separate from graphics-backend execution so OpenGL and Vulkan can share pack parsing while retaining independent renderer implementations.

## Current milestone

- Shader packs can be discovered from the `shaderpacks` directory as folders or ZIP archives.
- Pack paths are constrained to the shader-pack root.
- GLSL stages are discovered and `#include` directives are expanded with cycle and root-escape protection.
- OpenGL and Vulkan are both recognized as custom-shader-capable backends.
- Terrain rendering has a backend-neutral shader bridge. When a pack is selected, Sigma creates vanilla-equivalent solid, cutout, and translucent terrain pipelines and precompiles them through the active `GpuDevice` with a custom `ShaderSource`.
- The terrain bridge is fail-safe: if any bridge pipeline cannot compile, terrain immediately continues using the vanilla pipeline. Selecting a shader pack must not make the Vulkan backend unusable.
- Shader-pack `final.fsh` now has a first real rendering path on both OpenGL and Vulkan. Sigma copies the completed world color target, transforms a conservative OptiFine/Iris-compatible fragment-shader subset, compiles it through the active backend, and draws a full-screen triangle back into the world target.

The initial `final.fsh` compatibility subset supports `colortex0`/`gcolor`, one `vec2` fragment input (including legacy `gl_TexCoord[0]`), legacy `texture2D`/`texture2DLod`, `gl_FragColor`/`gl_FragData[0]`, and the screen-size uniforms `viewWidth`, `viewHeight`, and `aspectRatio`. Additional color targets, shadow samplers, arbitrary shader-pack uniforms, depth writes, geometry/tessellation stages, and compute shaders are intentionally rejected for this pass. A rejected or failed final shader leaves the vanilla world output intact.

The terrain bridge still uses Minecraft's preprocessed vanilla `core/terrain` GLSL. It proves backend-neutral terrain pipeline takeover, but full OptiFine/Iris terrain transformation is not complete yet. The `final.fsh` path is the first stage that executes transformed shader-pack GLSL.

## Next renderer stages

1. Transform shader-pack vertex/fragment programs to Minecraft 26.2 terrain inputs, bind groups, and uniforms.
2. Route transformed `gbuffers_terrain`, cutout, and water programs through the existing backend-neutral bridge with Iris-style program fallback resolution.
3. Add shader-pack render targets (`colortex`, depth targets) and framegraph ownership.
4. Expand composite/final passes, target flipping, common time/camera uniforms, and multiple color samplers.
5. Add shadow rendering.
6. Extend stage support beyond the current Minecraft `ShaderType` vertex/fragment model where required by geometry, tessellation, and compute shader packs.

## Compatibility policy

- Vulkan vanilla rendering is a hard fallback path and must remain usable even when shader-pack initialization fails.
- Vulkan shader support is capability-based: the current `final.fsh` subset uses Minecraft 26.2's native GLSL-to-SPIR-V path; unsupported pack features fall back instead of disabling Vulkan.
- Backend-specific state must stay out of shared shader-pack parsing and transformation code.
- OpenGL-specific Iris behavior is adapted rather than pasted into shared renderer code.
- Vulkan work should use Minecraft 26.2's native Vulkan abstractions and SPIR-V compilation path instead of emulating OpenGL state.

Design references include the public Iris 26.2 source and the experimental public `fangbm/iris4vulkan` 26.2 port. These are used as implementation references; Sigma-Modern's integration is written around its own directly maintained Minecraft source architecture.
