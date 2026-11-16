# Native avatar commands

Programs can discover and execute Minecraft commands using the avatar's own profile, entity, position, dimension, and permissions. Owner operator privileges are not inherited. Paracraft does not install home/TPA commands or provide free teleportation; those commands must exist on the server and permit that avatar to use them.

```json
{"steps":[
  {"op":"discover_commands","prefix":"home","saveAs":"available"},
  {"op":"execute_command","command":"home base","await":"teleport","timeoutTicks":400,"saveAs":"arrival"},
  {"op":"report_result"}
]}
```

Discovery returns native Brigadier usage forms and completions, filtered through the avatar's command source. `prefix` is optional. Forms and suggestions each retain their actual total count and at most 128 entries. Suggestions can be asynchronous; the job yields while waiting. Discovery is advisory. Execution attempts the actual command without first treating a command list, guessed permissions, or a predicted destination as an execution gate.

## Command return and delayed outcomes

`execute_command` accepts `command` with or without a leading slash. Its default `await: "command"` completes when native dispatch reports success. A successful command returning integer zero is still successful when Brigadier's callback says so. Native failure reports captured output and stops dependent steps unless an ordinary program catch handles it.

`await: "teleport"` distinguishes acceptance of a request from actual teleportation. It waits for a subsequent native teleport serial change, optionally constrained by:

| Field | Meaning |
| --- | --- |
| `dimension` | Exact destination dimension registry ID |
| `destination` | Destination `[x,y,z]` within tolerance |
| `playerUuid` | Arrive near this currently online native player, in their dimension |
| `tolerance` | Nonnegative distance, default 2 blocks |
| `timeoutTicks` | Active operation ticks, default 400; zero waits indefinitely |

Results expose the dispatched actor identity, native result/callback state, current position/dimension, elapsed ticks, observed teleport, and output. Command feedback and direct player messages are captured, bounded to 64 messages of 2,048 characters with truncation/omission indicators. A request acknowledgment is not reported as arrival. Timeout preserves the actual partial result and stops dependents. An observed teleport is evidence of a native transition satisfying the predicates; it cannot universally prove which third-party request caused it.

The issued flag, origin serial, elapsed ticks, and output survive pause/body restoration and orderly process restart. Resuming an issued request observes its outcome without dispatching it again. External mod requests may expire across disconnect/restart. Cancelling this job does not universally cancel a request already stored by another mod, and abrupt cross-file crash transactions are not claimed.

## Dimensions and native identity

A command may perform a normal permitted native dimension teleport. Its pending program adopts the actual new dimension before subsequent operations bind world objects. Other unfinished jobs from a previous dimension remain paused and expose their required dimension. Returning there and explicitly resuming rebinds their checkpoint. A resume acknowledgment while in the wrong dimension reports the paused state rather than pretending work ran or discarding its checkpoint.

Avatars are registered in the native global player roster as well as their world. Name/UUID command lookups, permission checks, statistics, advancements, boss connections, and Forge login/logout paths therefore see the actual avatar. Their local connection has an in-memory channel for native Forge hooks; it opens no socket and is not an independently authenticated network client. Native disconnect requests save/despawn the body and pause work; the owner can explicitly resume to restore it.

Native roster membership also counts toward Minecraft's online-player count and `max-players` admission behavior. Set that server limit with companions in mind; Paracraft's configurable four-saved-per-owner and 64-active limits are separate. General cross-mod login assumptions, future real-player name collisions, and arbitrary permission/storage mods remain compatibility work.

Normal logout hooks run before the final canonical physical-state snapshot. Inventory/location changes made by those hooks are preserved. Destruction then drops retained items once and updates the native player-data mirror with the emptied inventory. Respawn replaces the roster body without firing a second ordinary login/logout pair. The [Forge access transformer](https://docs.minecraftforge.net/en/1.20.1/advanced/accesstransformers/) exposes the required native roster fields; its resource is included in the mod JAR.

Explicit portal use is still being implemented. Ordinary navigation does not automatically enter portals. These command checks do not establish arbitrary home/TPA mod compatibility or visual client behavior.

## Verification

Headless GameTests exercise real native roster lookups and dispatch, avatar permissions, output and zero-return success, failed-spawn rollback, delayed teleport acceptance versus arrival, timeout/dependent-step suppression, reconnect without reissue, retired-body protection, cross-dimension teleport followed by actual block placement, UUID-based home state, foreign-dimension checkpoint return/rebind, native disconnect recovery, simulated Light/planning command routing, and logout mutation/item conservation.

Home/TPA commands in these tests are explicitly test-only native command fixtures, excluded from the JAR. A separate pair of JVMs verifies an issued pending command restores without reissue and honestly times out when the test-only external request state is absent after restart. See [QA.md](QA.md).
