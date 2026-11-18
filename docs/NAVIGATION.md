# Physical navigation foundation

Travel and other jobs move the actual survival player through normal world physics. Vanilla ground path computation uses an unspawned geometry proxy; it is not another avatar. Long destinations are planned in bounded nearby segments. Genuine progress through a partial segment is distinguished from a repeated path that goes nowhere.

The player participates in native chunk tracking independently of its owner. Native player view/simulation distances govern the surrounding load/generation area. Movement updates that tracking position, and removal releases membership. There is no remote-owner leash and no teleport fallback when a route fails. Native generation still has its normal server cost.

```json
{"steps":[{"op":"travel","destination":[100,64,100],"saveAs":"journey"}]}
```

## Movement and local recovery

The current adapter supports:

- Walking, stairs, sprinting, physical following, and native interaction with hand-openable doors, fence gates and trapdoors. Running uses the native speed attribute when fed (food above six), grounded, and neither crouching nor using an item. It stops near arrival or when the job pauses or stops.
- Local three-dimensional swimming from shore or at depth. Underwater destinations and interaction approaches require the avatar to enter the water and reach the target's depth. The server uses the Forge swim-speed attribute and retains the oxygen-escape routine.
- Ladder routes across multiple levels, including approaches from nearby rooms, ascent, descent, and opening a hand-operable hatch aligned with the ladder.
- Walking around fences and walls, including winding routes through an existing maze. Ordinary navigation preserves blocks by default. A sealed or unreachable route fails with its actual position and a useful error instead of breaking through a base.

The server plans nearby destinations in segments of at most 48 blocks, with a spatial search radius of 64 plus the native eight-block margin, up to 256 blocks of cumulative walking within that region, and a 4,096-node search budget. This separates the length of a winding walk from its straight-line distance. Long journeys re-plan as they progress; this is bounded local routing, not an unlimited global maze solver.

[Ambient idling](BEHAVIOR.md#ambient-idling-default-on) uses a separate walking-only navigation policy. Its path nodes and proposed motion remain inside the fixed resting sphere, it avoids fluid nodes, and terrain recovery stays disabled even when opted in for ordinary work. Explicit waypoint patrols also walk without sprinting. Fleeing/combat and ordinary requested travel keep their existing speed behavior.

Explicit `mine_blocks` and `place_blocks` operations remain available. A server may opt into the earlier automatic terrain recovery by setting `navigationTerrainChanges = true` in `<world>/serverconfig/paracraft-server.toml`. Only this opt-in permits:

- Breaking a local obstruction after ordinary routing cannot make progress, using carried tool selection, timed native destruction, Forge hooks, loot, and durability.
- Bridging a missing local floor with carried solid blocks, physically edging outward while crouched and using normal placement.
- Pillaring upward with actual jumps, clearance, support, native placement, and consumed carried blocks.

Recovery is mechanical server work and consumes no inference. It is incremental; mining yields across ticks. Available materials, placement geometry, reach, game mode, protection hooks, and actual results decide whether it succeeds. It does not grant blocks, durability, flight, invulnerability, or movement teleports. Tight unsupported geometry and absent supplies fail and report the current physical result for planning.

Automatic support selection considers carried full-collision block items without block entities or falling-block behavior. It can use registered mod blocks through those native properties, but unusual block placement needs adapters/testing. Navigation does not automatically mine block entities or portal blocks; those obstructions require an explicit action. With terrain recovery explicitly enabled, it can alter ordinary terrain while recovering a route, subject to native interaction rules. The agent cannot toggle this server setting.

`navigation` progress exposes current mode, position, replans, recovery block/type, and native break progress. Pausing or cancelling stops velocity, aborts active break cracks/hooks, and restores the preceding crouch state. Completed world changes and item consumption remain completed. Restoring a saved job re-plans from its actual saved position, inventory, and world; transient path/jump/break progress is reconstructed rather than treated as already finished. Arrival during a pillar jump waits until its support placement completes, and land destinations require ground contact.

Use [explicit portal actions](PORTALS.md) for dimension transitions. Movement searches are local and bounded, including ladder and water routes.

## Native fall damage

A normal network player's movement listener performs native fall checks. The local avatar has no socket listener, so its physics invokes that same native check on actual movement/landing. Normal spawn protection and landing/damage rules still apply. Falling is not converted to a snapshot-only warning or suppressed to make navigation succeed.

## Verification

Native GameTests cover a winding fence maze, closed doors and gates, a staircase to a raised floor, a ladder and trapdoor in both directions, a swim around a submerged wall and a dive to open a chest. Other fixtures verify sprint cleanup, terrain-preserving failures, optional terrain recovery, native fall damage, and independent chunk loading during a 216-block journey.

The long-route test uses a narrow road supplied only four blocks ahead in already-full chunks. The destination must be initially absent and become full through avatar loading before the road fixture touches it. The GameTest world's distant flat terrain was observed to be void; this fixture does not pretend that it verified a natural overworld landscape. General terrain/modpack navigation and distributed 64-avatar performance remain further compatibility/load work.
