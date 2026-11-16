# Survival and respawn

The server runs these mechanics without inference. The owner's client still controls intentions and ordinary action programs. Account linking and live AI usage remain deferred.

## Eating

At food level 14 or below, the server selects a carried food with positive native nutrition and no declared harmful effects. It considers main inventory and offhand food through Forge food properties, preferring higher nutrition. The normal item-use duration, item hooks, nutrition, effects, and remainder stack apply. Mushroom stew, for example, is eaten over actual ticks and returns a bowl rather than deleting the stack and directly adding hunger.

Eating holds a running program at its safe pause boundary. After use ends, the same program may continue if its owner/session are still active. Owner pause, explicit program pause/cancellation, disconnect, or death takes precedence over automatic resumption. Survival eating can finish while the owner has paused ordinary work. A refused use does not consume food or grant nutrition, and retries are spaced by 100 server ticks.

Disconnect stops incomplete use before saving. The actual unconsumed stack/hunger are restored; native eating can begin again after reconnect. This does not automatically resume programs that disconnect left paused.

Snapshots expose `survival.mode`, food status, and observed food after use. Selection uses the actual item behavior and properties; it is not a guarantee that every custom consumable or hidden effect has been integrated. Potions, milk, and broader supply planning are separate concerns.

## Bed spawn

An explicit program can establish spawn using a reachable bed:

```json
{"steps":[{"op":"set_spawn","bed":[100,64,100],"saveAs":"bed"}]}
```

The avatar approaches, normalizes a bed foot to its head, and uses native bed interaction with Forge interaction/spawn/sleep hooks. Occupied beds, broken beds, restricted interactions, invalid bed dimensions, and denied spawn changes report actual failure. Invalid-dimension attempts do not deliberately explode a bed.

Successful native spawn setting and successful sleep are distinct. Daytime or a nearby threat may prevent sleeping after spawn has been set. Results include `spawnSet`, `sleepEntered`, `sleepProblem`, and `sleepTicks`. When sleep is permitted, the avatar briefly sleeps for up to 20 active operation ticks, then wakes and clears occupancy. Pause/cancel also wakes it. This initial operation does not implement full-night cooperative sleeping.

On first creation, a server routine incrementally scans a loaded 17-by-9-by-17 block neighborhood centered at the original spawn position. It seeks an unoccupied bed that can set spawn and attempts the same bed operation when ordinary work permits. It yields to owner work/pause. Missing or unavailable beds are recorded in `survival.initialBed`, with normal fallback spawn remaining in effect. It does not generate terrain or search the world for a bed. The initial attempt/status and explicit bed spawn survive save/restore. A later request can use `set_spawn` to select another bed; acquiring or crafting one remains a separate task.

## Death and respawn

Lethal damage enters the native `ServerPlayer` death path, including Forge cancellation/drop hooks, normal item and equipment drops, vanishing curses, experience, death statistics, and server gamerules. Returning menu cursor/crafting items before death prevents them from being saved separately from the death inventory. A completed native death is recorded and persisted; repeated notifications do not repeat loot.

A Forge-cancelled death is not treated as a completed death. A rescue mod that cancels death and restores health retains the existing body and control session.

An accepted death cancels every outstanding current, queued, and parked program. Catch blocks cannot resume those programs. Control is revoked during death. After the native death animation ticks, the server creates a fresh companion with the same profile using the native respawn-copy path and Forge clone/respawn hooks.

The native spawn-block resolver supplies a valid bed/anchor/forced spawn when available; otherwise the new body's normal overworld spawn behavior is used. Invalid bed spawn is reported. Keep-inventory and experience retention follow native respawn rules. Normal death resets health/hunger. Hardcore respawn produces a spectator; spectator state suppresses survival routines and new gameplay submissions. Native respawn-anchor resolution and the actual hardcore death transition are implemented but have not yet received dedicated integration scenarios.

`lifecycle` snapshots report death/respawn and whether a spawn block was actually used. A persisted `lifeRevision` advances on respawn. The client discards an unfinished decision from the previous body, cancels its futures, retains durable tasks and an unfinished original chat request, and begins fresh planning from current physical state. Ordinary reconnect preserves its durable request retry behavior within the same life.

Disconnecting while dead saves the completed-death state and post-loot inventory. Restoring that body finishes respawn without executing death loot again. Explicit avatar destruction remains separate: it drops any currently retained belongings and removes the saved avatar permanently.

## Verification and remaining work

Actual headless Minecraft checks cover lethal damage, native drops/vanishing/XP, repeated death notification, current/parked/queued cancellation, bed respawn, keep-inventory, broken-bed fallback, dead-body reconnect, cancelled native death, Forge clone/respawn events, brief sleep/occupancy, daytime initial bed setting, denied spawn changes, native food duration/remainder, food hook refusal, disconnect during eating, owner pause during eating, and suppression of spectator survival/gameplay. A deterministic harness test covers persisted old-body decision invalidation after respawn.

The keep-inventory/fallback check advances the death timer explicitly; the ordinary death and dead-reconnect checks wait for actual entity ticks. These are native server/service tests, not visual client checks. See [QA.md](QA.md) for owner checks, [BEHAVIOR.md](BEHAVIOR.md) for oxygen escape, reaction profiles, and idle patrols, and [COMMANDS.md](COMMANDS.md) for implemented native command/dimension teleports. Explicit portals and broader navigation remain foundation work.
