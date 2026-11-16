# Explicit portals and block observations

Ordinary navigation does not grant portal travel. A selected `use_portal` program temporarily permits native entry at one chosen portal, approaches it physically, and waits for Minecraft's actual dimension transition. No destination teleport is fabricated by the operation.

```json
{"steps":[
  {"op":"search_blocks","block":"#minecraft:portals","saveAs":"portals"},
  {"op":"report_result"}
]}
```

The planner can inspect those positions and select a reachable entry block in a later program:

```json
{"steps":[
  {"op":"use_portal","position":[100,64,100],"dimension":"minecraft:the_nether","timeoutTicks":600,"saveAs":"arrival"},
  {"op":"report_result"}
]}
```

## Portal lifecycle

The initial adapter supports actual Nether and End portal blocks. Select an entry block the avatar can physically occupy, generally the lowest accessible portal block. The optional `dimension` is an outcome constraint, not an instruction to create a portal to an arbitrary dimension. Nether survival timing, cooldown, destination portal search/creation, server Nether settings, Forge travel hooks, and native End platform placement still apply.

`timeoutTicks` counts active operation ticks including approach, defaulting to 600; zero waits indefinitely. A missing/unsupported block fails with actual state. Native refusal or a transition that never arrives eventually times out. Results retain the selected block, actual position/dimension, elapsed ticks, and observed entry/transition. Native generation or portal search can still have their ordinary server cost.

Pause, cancel, disconnect, and terminal outcomes revoke the runtime entry grant. Ordinary paused contact cannot accumulate permission to teleport later. Resumption reconstructs the selected action and native waiting can begin again; elapsed operation time is retained. Death and cancellation escape ordinary recovery. An ordinary failure can enter a program catch. A completed transition adopts the actual destination before subsequent instructions execute.

The End exit normally removes a player while waiting for its client to dismiss credits. A local avatar has no such graphical client. Its lifecycle acknowledges that native transition through the return-spawn path: a fresh body uses native keep-everything restoration, saved bed/forced/fallback spawn resolution, nondeath clone/respawn events, and destination roster membership. Inventory/equipment/experience and applicable native survival state survive; this is distinct from death respawn and does not advance the life revision. Pending programs rebind to that body. The credits-wait flag is saved so an interrupted return can be recognized after restoration.

Native portal adapters beyond Nether/End, unusual mod teleporters, and graphical transitions remain unverified. Ordinary commands remain a separate permitted dimension-travel route; see [COMMANDS.md](COMMANDS.md).

## Block query scope and freshness

`search_blocks` accepts an exact registered block ID or `#block_tag`. It freezes the avatar's current chunk and eight neighbors, including diagonals, and inspects their full build height. It reads loaded chunks without generating missing chunks merely to answer the query. Results contain:

- `blocks`: positions, actual block IDs, and observation ticks.
- `total`: all matching blocks counted, including positions omitted from the response.
- `coveredChunks`, `missingChunks`, and `complete`: explicit loaded coverage.
- `observedAt`, the fixed center chunk, selector, and dimension.
- `truncated`: whether the position list omits counted matches.

The default position limit is 256 and can be set from 1 through 4,096 using `limit`. This bounds response size; the query still counts all matches in covered chunks. Palette checks skip nonmatching sections and a bounded block budget yields between ticks. Saved cursors/results resume without recounting earlier entries. Completed observation timestamps stay frozen. Concurrent world changes are observed when each position is read; this is not an atomic whole-world snapshot. A new query is needed for fresh contents.

Block queries currently run as program operations, with their results visible in program snapshots. The standalone `observe` continuation remains inventory-specific. The deterministic simulator accepts `Josh, search blocks #minecraft:portals` and `Josh, use portal X Y Z`; both escalate from Light and create planning tasks. It does not automatically choose a portal from a query or claim general language understanding.

## Verified behavior

Seven actual-world GameTests cover walking into an End portal, full-height nine-chunk block counts/result truncation/freshness/checkpoint recovery, native Nether delay and destination follow-up, paused contact and body restoration, actual Forge travel refusal with timeout/recovery, End entry/credits return with item/health/hunger/XP/roster/program assertions, and cancellation preventing a later transition. These are headless native checks; no client rendering or credits screen has been observed. Existing cold-process inventory/command recovery remains a separate suite; a cold-process portal transition is not claimed as tested.
