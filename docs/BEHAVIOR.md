# Server behavior

Oxygen escape, attacker reactions, eating, initial bed seeking, and idle patrols run on the server. They consume no inference themselves. Damage, pickups, and meaningful outcomes can still wake the client decision loop. The provider remains the labeled deterministic simulator; authentication/live AI are deferred.

Inspect settings and current mechanical state with `/paracraft behavior Josh`, the client snapshot, or the companion screen. Change settings through an owner command or the planning-only `configure_behavior` tool:

```text
/paracraft behavior Josh {"players":"flee","mobs":"neutral"}
```

The tool takes that same JSON object. Updates preserve unspecified settings. Invalid updates leave existing settings intact. Owner/session/instruction checks and persistent request receipts apply to the tool, including trusted instructions through the owner's harness. Trust does not give another player the owner's raw management controls. Settings persist with this physical server avatar and can be edited while it is saved/offline.

The simulator recognizes `Josh, behavior players passive` and `Josh, behavior mobs neutral` (any supported reaction). Light escalates; planning submits the authoritative tool and reports its acknowledgment. This finite grammar does not claim general natural-language behavior control.

## Reactions

Player and mob reactions are independent:

| Setting | Response |
| --- | --- |
| `passive` | Stop ordinary work without attacking; leave it paused for an explicit planning/owner decision |
| `flee` | Physically navigate away until the threat is gone or separation reaches eight blocks |
| `neutral` | Retaliate after actual damage, using normal carried weapons, cooldowns, reach, visibility, and native attack hooks |
| `aggressive` | Retaliate and proactively select nearby eligible targets |

Defaults are `players: flee` and `mobs: neutral`. Retaliation is queued only after native hurt actually reduces health or absorption. Damage notifications remain available to the harness. Reaction changes cancel an active combat/flee response so the new setting can take effect. They do not restart an unchanged patrol.

Proactive selection checks a loaded twelve-block neighborhood periodically. Mob aggression selects `Enemy` implementations, including compatible hostile mod mobs; it does not proactively attack ordinary animals. Player aggression excludes the owner, trusted instruction authorities, and their managed companions. Native PvP rules and protection hooks still govern actual damage. Neutral retaliation responds to an actual attacker in its configured category; proactive authority exclusions do not redefine an explicit retaliation setting.

Local defense continues without a task timer while an eligible target remains within 24 blocks. A defeated, missing, or retreated target ends the response. Fleeing likewise has no fixed duration. Actual blocked routes and repeated refused native attacks produce failures rather than imaginary movement/damage. Explicit `attack_entity` programs remain available for deliberate targets and longer pursuits.

Reflexes hold running programs at their safe pause boundary. Successful recovery releases only the program held by that reflex, subject to the current owner/session/pause state. Failed recovery leaves ordinary work paused and reports the actual reason. It does not run a program catch because the failure belongs to an external mechanical response. Planning can choose the recovery and explicitly resume its saved program. Owner pause/cancellation prevents automatic resumption. Survival reactions may continue while ordinary work is paused; passive settings and explicit behavior changes can stop combat movement.

## Oxygen

The server observes Forge's native breathing result, including water-breathing effects and fluid behavior. At low air (at most one third of maximum, capped at 100), it interrupts lower-priority mechanical work and attempts escape. Oxygen takes precedence over eating, fighting/fleeing, bed seeking, and idle patrol.

An incremental six-direction search checks up to 128 nodes per tick inside the loaded neighborhood, within eight blocks horizontally and sixteen vertically from the start. It uses the actor's collision volume, fluid properties, and actual block shapes; ordinary portal and lava routes are excluded. It can search sideways under a roof rather than assuming straight-up movement is possible. It does not load the world or create a breathing pocket.

Swimming applies player velocity and normal collision/fluid physics. It does not teleport, grant air, or restore health. Native breathing and air recovery determine completion. Breathable effects that prevent drowning without refilling air are honored. If a nominal destination remains unbreathable under the actual native result, the operation reports failure.

Sealed/unsupported routes leave ordinary work paused, report failure, and space retries by 100 server ticks. Later movement, changed blocks, effects, or a planning decision may permit recovery. Re-entering a hazard can trigger a later escape. Space/vacuum mods and unusual fluids may need adapters; projected local geometry cannot predict every mod's breathing policy. The snapshot includes `canBreathe`, `canRefillAir`, current/reflex results, and oxygen retry state.

## Idle patrol

Configure absolute waypoints in one dimension:

```text
/paracraft behavior Josh {"idle":{"enabled":true,"dimension":"minecraft:overworld","waypoints":[[100.5,64,100.5],[110.5,64,100.5],[110.5,64,110.5]],"waitTicks":40}}
/paracraft behavior Josh {"idle":{"enabled":false}}
```

Patrol walks the waypoints in order, waits the configured number of server ticks at each, and repeats. It yields to unfinished programs (including parked/queued work), owner pause, survival responses, and initial bed work. Reactions can be updated without resetting its cursor. Routine arrival/wait transitions emit no wake events. An actual failure emits a failure event and suspends that patrol until its idle settings are changed.

Settings, waypoint cursor, remaining wait, and physical position survive disconnect/reconnect. The new runtime navigates toward the saved waypoint from its actual restored location; it does not replay inventory mutations or teleport to a waypoint. A dimension mismatch fails rather than using the same coordinates in another world. This is a server routine, separate from a durable agent task; it does not keep inference active.

Snapshots expose `survival.mode`, behavior settings, active/last reflex, and idle progress. The companion screen displays the mode and reaction categories; raw commands expose the complete state. Rendering still requires owner verification.

## Verification

Headless Minecraft scenarios cover a roofed pool with a side exit, sealed-pool failure/retry spacing, native water breathing, neutral melee/resumption, passive stop/resume, player fleeing without damage, separate proactive categories/authority exclusions, native attack protection, planning-tool authority/persistence, and patrol yielding/reconnect/pause with a real harness remaining idle. Unit tests cover atomic schema updates, invalid input, Light restrictions, and scripted escalation. Fresh-process QA also checks nondefault reaction/idle settings loaded from world storage.

These establish initial native behavior rather than universal modpack, arbitrary terrain, or capacity guarantees. General navigation recovery, chunk lifecycle/capacity evidence, commands, and explicit dimension travel remain foundation work. Food and bed/death behavior are documented in [SURVIVAL.md](SURVIVAL.md).
