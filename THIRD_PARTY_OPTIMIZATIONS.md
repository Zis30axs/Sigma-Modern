# Third-party optimization provenance

Sigma-Modern includes small, native adaptations of optimization techniques from the following open-source Minecraft projects. The adaptations are integrated directly into the Minecraft 26.2 source tree rather than shipping the original mods or their loader/mixin infrastructure.

This file records provenance and upstream licensing for maintainers. Redistribution should continue to preserve all applicable upstream notices and license obligations.

## Lithium

- Upstream: https://github.com/CaffeineMC/lithium
- Reference branch: `26.1.x`
- License: GNU LGPL v3
- Adaptation: cache the `Direction.values()` result used by `RedStoneWireBlock` neighbor-update hot paths, avoiding repeated enum-array allocations while preserving vanilla iteration order and behavior.

## FerriteCore

- Upstream: https://github.com/malte0811/FerriteCore
- Reference branch: `26.1`
- License: MIT
- Adaptation: when `PatchedDataComponentMap` becomes empty after mutation, release the allocated mutable patch map and return to FastUtil's singleton empty map with copy-on-write semantics.

## ImmediatelyFast

- Upstream: https://github.com/RaphiMC/ImmediatelyFast
- Reference branch: `26.2`
- License: GNU LGPL v3
- Adaptation: avoid unbinding the framebuffer after every render pass; restore the default framebuffer immediately before presenting the swapchain texture instead.

## ModernFix

- Upstream: https://github.com/embeddedt/ModernFix
- Reference branch: `26.1`
- License: GNU LGPL v3 or later
- Adaptation: lazily cache `ExtraCodecs.TagOrElementLocation` in `TagEntry` so repeated codec serialization does not allocate an equivalent wrapper object each time.

## Scope

Only the optimization techniques listed above are included by this integration pass. No Fabric/NeoForge loader code, Mixin configuration, compatibility plugin, settings UI, or unrelated optimization from the upstream projects is bundled here.
