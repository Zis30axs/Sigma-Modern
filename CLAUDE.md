# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Sigma-Modern is a Minecraft 26.2 client built from MCP-decompiled source. Sodium, Iris, ViaFabricPlus,
SodiumExtra, and Lithium are ported into it as **direct source modifications** — there is no Fabric/NeoForge
loader and no Mixin runtime. Every former `@Mixin` injection point has been inlined into the vanilla
`net.minecraft` / `com.mojang` source it targeted.

`src/main/java` also carries a first-party client layer at `com.mentalfrostbyte.jello` (module/setting/event
framework, GUI, account management) — this is original Sigma code, not a port.

## Build / run / test

Building requires JDK 25+ on `JAVA_HOME`. The `java` on `PATH` may be an older JDK (e.g. 17) — if so, `mvn`
fails with a misleading `release 25 not supported` error rather than a JDK-not-found error. Set `JAVA_HOME`
explicitly:

```bash
JAVA_HOME=<path-to-jdk25+> mvn -o -DskipTests compile   # offline incremental build
JAVA_HOME=<path-to-jdk25+> mvn compile                  # online, resolves deps on first run
```

Do not modify `pom.xml`'s `release` setting to work around a wrong `JAVA_HOME` — fix the environment instead.

Run tests (JUnit 5, `src/test/java`, mirrors `com.mentalfrostbyte.jello.*`):

```bash
JAVA_HOME=<path-to-jdk25+> mvn -o test
JAVA_HOME=<path-to-jdk25+> mvn -o test -Dtest=ModuleManagerTest
```

CI/shipping builds always pass `-DskipTests`; the JUnit dependency is `test`-scope only and never reaches the
shipped jar.

Run the game: the entry point is `Start` (`src/main/java/Start.java`), which resolves/downloads vanilla
assets, resolves the active Sigma account, then delegates to `net.minecraft.client.main.Main`. In IntelliJ,
run the `Start` main class. Game directory is `run/` at the repo root; VFP protocol configs live under
`run/config/viafabricplus/`.

`javac`/Maven sometimes reports a bare `BUILD FAILURE` with no file:line diagnostic for implicit-compile
errors (a source file pulled in transitively that doesn't itself appear in the failing module's explicit
sources). When that happens, don't guess — compile the suspect file directly with the Java Compiler API (or
`javac`) to surface the real error.

## Porting conventions (read before touching ported-mod code)

These apply to anything under `net.caffeinemc.mods.sodium`, `net.caffeinemc.mods.lithium`, `malte0811.ferritecore`,
`me.flashyreese.mods.sodiumextra`, `net.irisshaders.iris`, and `com.viaversion.viafabricplus`:

- Former Mixin injection points are inlined as direct edits to the vanilla target class, marked
  `// MODIFIED for porting` (or `// MODIFIED for porting: <detail>`) at the change site. Don't remove these
  markers when editing nearby code — they're what makes the diff against upstream mixins auditable.
  `@Overwrite`/`@Redirect`/`@WrapOperation` become the literal replacement logic at the target site.
  For VFP `@Inject(at = @At("HEAD"))` hooks on packet handlers specifically: if the hook needs netty-thread
  timing (it touches the channel, the pipeline, or auto-read), it must go **above**
  `PacketUtils.ensureRunningOnSameThread` — that call reschedules onto the main thread and throws on the
  netty thread, so anything placed after it runs one main-thread hop later than real Mixin HEAD would have.
  Hooks that only inspect game state are fine (and often required) after that guard.
- A mod's own package names, class names, and directory structure are preserved as-is (e.g.
  `malte0811.ferritecore.*` stays under that package, not moved into `com.mentalfrostbyte`).
- `@Mixin` accessor/invoker interfaces (`*.mixin.accessors.*`, `*Accessor`/`*Access`/`*Invoker` interfaces)
  are kept as plain interfaces; the real vanilla class is made to `implement` them directly instead of using
  a Mixin-injected implementation. This preserves the mod's own `instanceof`/cast logic unchanged.
  Duplicate accessor declarations across mixin packages are all kept and satisfied by one implementation.
- Loader-specific machinery — mod metadata, entrypoints, `IMixinConfigPlugin`/`MixinService` config-file
  reading, per-platform config file I/O — is **not** ported. Only the runtime behavior it was gating gets
  wired to a real call site (a bootstrap call, a hardcoded default matching upstream's shipped default, etc).
- Never add a new Mixin, Fabric/NeoForge API dependency, or reflection-based workaround to make a port
  compile. If upstream used reflection to reach a private field, add a real accessor method to the target
  class instead (see `PORTING.md`'s `BlockStateCacheImpl` entry for the precedent).
- A module whose default-on upstream config is mutually exclusive with another already-ported module (e.g.
  FerriteCore's thread-detector vs. Lithium's chunk locking) is intentionally left unported (`SKIP`), not
  stubbed with a no-op. Check `PORTING.md` before assuming a missing class/field is an oversight.
- `PORTING.md` is the ledger for Sodium/Iris/Lithium/SodiumExtra/FerriteCore — every mixin's target class and
  DONE/SKIP/TODO status. `VFP_PORTING.md` is stage/history notes for ViaFabricPlus; **`VFP_AUDIT.md` is the
  only trustworthy source of VFP's actual per-hook status** — `VFP_PORTING.md` previously declared the VFP
  port complete and that was retracted after a full hook-by-hook audit found real gaps, so prefer
  `VFP_AUDIT.md` when the two disagree.
- `ANTIEXPLOIT.md` records which anti-exploit protections were deliberately implemented vs. rejected, with
  the packet-pipeline reasoning behind each decision (e.g. why protections sit at the handler/codec level
  rather than as a cancellable `EventReceivePacket` module).
- `GRIM_PORTING.md` records the client-side AntiCheat module (`ModuleAntiCheat`, `com.mentalfrostbyte.jello.anticheat`):
  a rewrite of Grim's judging ideas over the little a client can observe of other players, not a source port. It
  lists what the client can and cannot see (verified against `ServerEntity`), which Grim check maps to which local
  check, and the known false-positive/false-negative scenarios. The checks are pure functions of `Sample`s behind
  `WorldProbe`, so they are unit-tested without a game; the marked `// Sigma hook:` sites in `ClientPacketListener`
  are the only place the game feeds them.
- `SELFCHECK_PORTING.md` records `ModuleSelfDetection` (`com.mentalfrostbyte.jello.selfcheck`), which is the opposite
  of the above: it judges the *local* player with the real GrimAC (`ac.grim.grimac`, ported as source from Grim
  `8eb5f28`) and PacketEvents, fed by netty taps on the wire side of ViaFabricPlus. Rules that are easy to break:
  - Grim and PacketEvents are loaded per connection by `EngineLoader.Isolating` (Grim bakes the server version into
    `static final`s); never reference `ac.grim.grimac`/PacketEvents classes from client code outside
    `selfcheck.engine.*`, or you get the client's copy, not the connection's.
  - The Grim platform layer must never answer from live client state (inventory, world): a cheat would reach the
    judge. The `MODIFIED for porting` sites in `ac.grim.grimac` force this regardless of the copied-in config.
  - Grim's permissions are a whitelist (`SigmaPlayers.GRANTED`); `grim.disabled`/`grim.exempt*` must stay denied.
  - A module that holds back and re-sends pongs must re-send the *same packet object*; local pongs are recognised
    by type (`SelfCheckPong`) and a re-created plain pong would leak to the server.
  - "Grim is quiet" is not evidence: check the "N movements predicted" / "player state" lines in
    `run/sigma5/selfcheck/logs/latest.log`. `GrimReplayTest` replays a real recording (format 2, with per-packet
    markers) in real time and asserts predictions actually ran.

## The `com.mentalfrostbyte.jello` client framework

This is the module/settings/event system that ported and first-party features hook into.

- **`Module`** (`jello/module/Module.java`) — a toggleable feature. Settings are declared as typed fields via
  `register(new BooleanSetting(...))` etc., not looked up by string key — read `this.someSetting.get()`
  directly rather than a config map. A module is subscribed to the `EventBus` only while enabled, so
  `@EventTarget` handlers never need to check `isEnabled()` themselves. Toggling fires
  `EventModuleToggle`; anything cosmetic (module-list UI, sounds, notifications) subscribes to that event
  rather than being called directly from `Module`. `ClientMode`/gameplay-mode concerns are deliberately kept
  out of `Module` itself.
- **`Setting<T>`** (`jello/setting/`) — sealed-ish per-type settings (`BooleanSetting`, `NumberSetting`,
  `EnumSetting`, `ColorSetting`, `TextSetting`), not one generic settings type. Code that consumes a setting
  should fail fast on a bad in-code usage (wrong type, missing setting) but be tolerant of bad *persisted*
  config values (clamp/ignore rather than throw) when loading from disk.
  See `[[sigma-modern-module-layer-decisions]]`-style history in `PORTING.md`/git log for the reasoning if a
  setting type feels overly restrictive — it's usually intentional.
- **`ModuleManager`** / **`EventBus`** / `Module.setEnabled` — registration, listener add/remove, and enable
  transitions are transactional and failure-isolated: a listener exception during enable/disable or a
  registration failure doesn't leave the manager or bus in a half-updated state. `EventBus`'s subscriber list
  is an immutable snapshot per publish, so publishing is safe to interleave with register/unregister from
  other threads (notably Netty's event loop for network-triggered events). Regression tests for these
  invariants live in `src/test/java/.../module/ModuleManagerTest.java`,
  `.../module/ModuleLifecycleTest.java`, and `.../event/EventBusTest.java` — extend these rather than adding
  ad hoc concurrency tests elsewhere if you touch lifecycle code.
- Ported modules that need to affect 26.2 rendering (brightness, damage tilt, fire overlay, camera clip,
  weather, etc.) land their real logic at the vanilla render call site, with the `Module`/`Setting` only
  holding the on/off + parameters — same "vanilla source does the work, module holds policy" split used for
  every other port in this repo.
