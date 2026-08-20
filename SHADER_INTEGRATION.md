# Shader integration status

Sigma-Modern keeps shader-pack loading separate from graphics-backend execution so OpenGL and Vulkan can share pack parsing while retaining independent renderer implementations.

## Current milestone

- Shader packs can be discovered from the `shaderpacks` directory as folders or ZIP archives.
- Pack paths are constrained to the shader-pack root.
- GLSL stages are discovered and `#include` directives are expanded with cycle and root-escape protection.
- OpenGL and Vulkan are both recognized as custom-shader-capable backends.
- Terrain rendering has a backend-neutral shader bridge. When a pack is selected, Sigma creates vanilla-equivalent solid, cutout, and translucent terrain pipelines and precompiles them through the active `GpuDevice` with a custom `ShaderSource`.
- The bridge is fail-safe: if any bridge pipeline cannot compile, terrain immediately continues using the vanilla pipeline. Selecting a shader pack must not make the Vulkan backend unusable.

The bridge currently uses Minecraft's preprocessed vanilla `core/terrain` GLSL. This is intentionally a renderer takeover/probe, not yet a claim that OptiFine/Iris shader-pack GLSL is being rendered.

## Next renderer stages

1. Transform shader-pack vertex/fragment programs to Minecraft 26.2 terrain inputs, bind groups, and uniforms.
2. Route transformed terrain programs through the existing backend-neutral bridge.
3. Add shader-pack render targets (colortex/depth targets) and framegraph ownership.
4. Add composite/final passes and target flipping.
5. Add shadow rendering.
6. Extend stage support beyond the current Minecraft `ShaderType` vertex/fragment model where required by geometry, tessellation, and compute shader packs.

## Compatibility policy

- Vulkan vanilla rendering is a hard fallback path and must remain usable even when shader-pack initialization fails.
- Backend-specific state must stay out of shared shader-pack parsing code.
- OpenGL-specific Iris behavior is adapted rather than pasted into shared renderer code.
- Vulkan work should use Minecraft 26.2's native Vulkan abstractions and SPIR-V compilation path instead of emulating OpenGL state.

Design references include the public Iris 26.2 source and the experimental public `fangbm/iris4vulkan` 26.2 port. These are used as implementation references; Sigma-Modern's integration is written around its own directly maintained Minecraft source architecture.
