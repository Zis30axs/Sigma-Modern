# Third-party optimization provenance

Sigma-Modern includes small, native adaptations of optimization techniques from the following open-source Minecraft projects. The adaptations are integrated directly into the Minecraft 26.2 source tree rather than shipping the original mods or their loader/mixin infrastructure.

This file records provenance and upstream licensing for maintainers. Redistribution should continue to preserve all applicable upstream notices and license obligations.

## Lithium

- Upstream: https://github.com/CaffeineMC/lithium
- Reference branch: `26.1.x`
- License: GNU LGPL v3
- Adaptations: cache `Direction.values()` results used by `RedStoneWireBlock` neighbor-update hot paths and `PistonStructureResolver` branching checks, avoiding repeated enum-array allocations while preserving vanilla iteration order and behavior; replace `Optional.map` in `EntityBasedExplosionDamageCalculator` with an explicit fast path that reuses the original `Optional` when explosion resistance is unchanged.

## FerriteCore

- Upstream: https://github.com/malte0811/FerriteCore
- Reference branch: `26.1`
- License: MIT
- Adaptation: when `PatchedDataComponentMap` becomes empty after mutation, release the allocated mutable patch map and return to FastUtil's singleton empty map with copy-on-write semantics.

## ImmediatelyFast

- Upstream: https://github.com/RaphiMC/ImmediatelyFast
- Reference branch: `26.2`
- License: GNU LGPL v3
- Adaptations: avoid unbinding the framebuffer after every render pass and restore the default framebuffer immediately before presentation; cache consecutive text and name-tag `RenderType` to `VertexConsumer` lookups to avoid redundant vertex-builder resolution.

## ModernFix

- Upstream: https://github.com/embeddedt/ModernFix
- Reference branch: `26.1`
- License: GNU LGPL v3 or later
- Adaptations: lazily cache `ExtraCodecs.TagOrElementLocation` in `TagEntry`; use a compact FastUtil map as the persistent `AttributeSupplier` backing map instead of retaining the ImmutableMap representation produced by its builder; cache `MinecraftProfileTexture#getHash()` results briefly inside each `SkinManager.TextureCache` to avoid recomputing profile texture hashes during lookup and registration.

## Video settings and shader UI

- Reese's Sodium Options: https://github.com/FlashyReese/reeses-sodium-options
- Reference branch: `26.2/dev`
- License: MIT
- Iris: https://github.com/IrisShaders/Iris
- Reference branch: `26.2`
- License: GNU LGPL v3
- Adaptation: provide a native Minecraft 26.2 tabbed/searchable video settings layout inspired by modern Sodium option screens, plus an Iris-style shader-pack entry. The shader-pack manager scans the standard `shaderpacks` directory and persists pack selection without bundling Fabric/NeoForge, Mixin, Sodium, Reese's Sodium Options, or Iris runtime code. Actual shader rendering integration is intentionally separate and is not claimed by this UI pass.

## Scope

Only the optimization and UI techniques listed above are included by these integration passes. No Fabric/NeoForge loader code, Mixin configuration, compatibility plugin, or unrelated optimization from the upstream projects is bundled here.
