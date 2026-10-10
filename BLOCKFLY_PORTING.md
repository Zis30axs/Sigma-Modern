# BlockFly

Source: `E:\.sigma\SigmaClient\src\main\java\com\mentalfrostbyte\jello\module\impl\movement\BlockFly.java`
and its `blockfly/` directory. Target: Minecraft 26.2, Java 25, direct vanilla integration.

## Modes

All eight modes registered by the source are available in Movement → BlockFly → Mode.
The source's unregistered `BlockFlyAACMode.java.bak` is not an additional mode.

| Mode | Behavior retained |
| --- | --- |
| NCP | Extend, downward bridging, retained rotation, None/Jump/AAC/Slow/Sneak movement |
| Smooth | Side-face targeting, Constant speed and Cubecraft movement in addition to the shared movement choices |
| Hypixel | Downward-facing placement when the ray actually hits, retained rotations, downward bridging and its movement choices |
| Custom | Normal/Telly Bridge/Keep Y, Eagle, sneak pulses, advanced search, gradual rotation, snap presentation, explicit rotation and held-item presentation |
| LCustom | Physical right-button activation, six-layer search, Normal/Telly Bridge/Keep Y, Eagle, sneak pulses, snap and held-item presentation |
| SouthSide | Telly/Snap/Normal, air prediction and clutch input pause, rotation timing, Eagle, block selection, bounded packet hold, block marker, FOV and both counter styles |
| Legit | Predictive Eagle with a support-loss deadline, signed reaction delays, direction lock, shape-aware targeting, gradual rotation, interpolated aim noise and tremor, placement delays |
| LegitTelly | Original activation gate, 12-tick setup and 21-step movement curves, straight bridge lane, adaptive placement, manual takeover, automatic slot selection, packet arbitration and activation overlay |

Mode-specific settings have a mode-name prefix to keep their persisted keys unique in the modern typed setting system.
Settings retain their source defaults. Parent inventory, tower, sprint, swing, counter and FOV options are also present;
Custom/LCustom, SouthSide and LegitTelly retain their own inventory policies. LCustom, SouthSide and LegitTelly
use their own movement policies; LCustom remains inactive until its right-button gate is satisfied.

## Integration

- `BlockFly` owns one active policy and its event subscription. Changing modes, player/world changes, death,
  riding/spectator states and disabling release the previous policy's inventory, rotation and timer state.
- `BlockFlyPlacement` separates the destination, supporting block, clicked face, hit point and eye origin.
  It samples actual outline shapes and checks block **and** face with the real ray before placement.
- Movement correction is requested before player physics. `EventMotion` PRE reports the placement rotation;
  POST uses the final reported position/rotation to revalidate and interact. Cancelled motion cannot place.
- Native `MultiPlayerGameMode.useItemOn` retains prediction sequences and ViaFabricPlus protocol behavior.
  Slot restoration uses `syncCarriedItem`; temporary prediction angles are restored in `finally`.
- LocalPlayer's `EventSafeWalk` reuses vanilla edge collision, and `EventGetFovModifier` only affects the local view.
  `EventMovementInput` carries both keys and the movement vector, preserving fractional scripted movement.
- `Camera` and `ItemInHandRenderer` apply optional visual overrides without changing physical camera input.
  The legacy tower/speed timer scales the client tick duration only during an active session.
- Rendering uses the 26.2 HUD extraction and gizmo APIs; no immediate OpenGL, loader or Mixin dependency was added.

## Version differences

- Legacy fabricated block hits and placement-before-rotation paths are replaced with real hit validation and
  post-movement interaction. A candidate that is obstructed, out of reach or on a different face is skipped.
- On modern protocols `FakeInv` uses the normal player-inventory click path, like `Basic`: these protocols have
  no old `OPEN_INVENTORY` client-status packet. `OpenInv` still requires the inventory screen. Container menus
  are never mistaken for player inventory slot numbering.
- `SouthSide GodBridge` was declared but never read by the supplied source. Its compatibility setting is kept
  with that limitation stated in its description; it does not invent an extra algorithm.
- Mode names inherited from older server-specific implementations describe those algorithms. Their behavior
  on individual servers requires game testing; compilation and unit tests do not establish server compatibility.

## Validation

Verified: full offline Maven compilation of the project, followed by compilation of the final changed sources
with `--release 25` and **45 passing tests (0 failures, 0 errors)**. The final pass includes all mode regressions
plus module lifecycle, event bus and movement-correction checks. Output is isolated under `target/blockfly-build`.
Game/server testing has not been performed.

Regression coverage lives under `src/test/java/com/mentalfrostbyte/jello/module/impl/movement/` and
`src/test/java/com/mentalfrostbyte/jello/event/EventMovementInputTest.java`. It covers real shaped-block raycasts,
obstruction/reach/face rejection, deterministic search, inventory filtering, Eagle deadlines, scripted input,
Telly angle/phase boundaries, SouthSide prediction, and registration of all eight modes.

Build in a separate output directory while a game may be running:

```powershell
$env:JAVA_HOME = 'C:\Users\Administrator\.jdks\openjdk-26.0.2.1'
mvn -o -Dsigma.buildDirectory=target/blockfly-build test '-Dtest=BlockFly*Test,TellyModeTest,EventMovementInputTest,ModuleManagerTest,ModuleLifecycleTest,EventBusTest,MovementCorrectorTest'
```

Game checks still needed: straight/diagonal bridging, slabs/stairs, inventory exhaustion and offhand blocks,
each tower mode, releasing LCustom's right button, Telly activation/manual takeover, mode changes while moving,
disconnect/rejoin, and representative ViaFabricPlus target versions.
