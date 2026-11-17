# Structured action programs

Programs execute server-side through the same jobs used by manual controls. They contain finite sequences and nested recovery blocks, not executable Java, shell commands, or scripts in another language. The simulated provider produces this schema through the wired client harness; native client connection/visual checks remain unverified.

## Submission and identity

Use `/paracraft program <companion> <json>` as the owner. `JobManager.submitProgram` checks owner and current control-session ID. The client-facing `ServerControl` service additionally checks instruction provenance/revocation, request content identity, and server observation receipts. Forge transport derives its owner from the native connection; actual TCP/client verification remains pending.

Programs can also compose `search_recipes` and `craft_item` with collection, placement, delivery, and deposit. [CRAFTING.md](CRAFTING.md) documents internal 2×2/table 3×3 syntax, quantity semantics, and native ingredient/remainder behavior.

An optional `schema` is currently `1`. Supply a stable UUID in `id` to retry a request safely; if omitted, the manual command generates one and reports it. Reusing an accepted ID acknowledges its existing state/result and does not execute another copy, including after save/reconnect or a clean server restart. `taskId` associates the program with an intention; the server does not mark that intention complete automatically.

Submission modes:

| Mode | Behavior |
| --- | --- |
| `replace` | Cancel current and queued execution, then start the new program; manual default |
| `append` | Queue after current work; automatic advancement requires success |
| `interrupt` | Pause current work, retain prior requests, and start the new program; old work awaits explicit selection |
| `resume` | Resume the prior request identified by `id`; no `steps` is required in this command |

An appended program stays queued after an unhandled failure. Interrupted requests remain accessible by ID, including prior queued requests. Resume explicitly chooses one request and parks other current work. `/paracraft pause` and `/paracraft resume` stop/continue current execution and rotate the control session; `/paracraft cancel` cancels current and queued execution. Cancellation never invokes a recovery block.

Example of a harmless manual program:

```text
/paracraft program Josh {"schema":1,"taskId":"inspection","steps":[{"op":"wait_ticks","ticks":20},{"op":"report_result","saveAs":"summary"}]}
```

Read progress through `/paracraft job Josh`. For a saved request, use:

```text
/paracraft program Josh {"id":"<the-request-UUID>","mode":"resume"}
```

## Operations

Arguments live beside `op` on the step object. `saveAs` stores its result under a name for later steps. Coordinates are absolute `[x,y,z]` arrays in the actor's current dimension.

| Operation | Arguments and result |
| --- | --- |
| `travel` | `destination`; physical route to coordinates |
| `search_containers` | `item`; fixed nine-chunk inventory observation |
| `collect_items` | `item`, optional `quantity` (integer or `"all"`), optional `containersFrom`; acquired count and exact stack ledger |
| `deposit_items` | `destination`, `item`, integer `quantity`; actual deposited count |
| `deliver_items` | `itemsFrom`, `recipient` (UUID string or `{ "playerUuid": "..." }`); drops that named collection ledger at the recipient's feet |
| `wait_ticks` | Nonnegative `ticks`; server wait with remaining time |
| `chat` | `text`; avatar chat message |
| `report_result` | Returns the current named results |
| `report_failure` | Returns the current caught failure, including partial results |
| `fail` | Optional `message`; explicit ordinary failure, useful for QA/recovery paths |
| `rethrow` | Propagates the current caught failure to an outer handler |
| `mine_blocks` | `block`, `blocks`, or `cuboid`; native tool selection, timed breaking, pickup ledger |
| `place_blocks` | `placements` with `position` and `item`; physical placement and actual consumption |
| `farm` | Same target forms as mining, optional `plant`; mature crop harvest/pickup/replant |
| `equip` | `item`, `slot`; native armor/offhand rules or main-hand selection |
| `unequip` | `slot`; move worn/held items to main inventory, returning an exact `acquiredStacks` ledger |
| `interact_block` | `position`, optional `hand`/`sneak`/`face`/local `hit`/`holdTicks`; physical native block/item right-click |
| `interact_entity` | Entity UUID `target`, optional `hand`/`sneak`/local `hit`/`holdTicks`; native precise/ordinary entity interaction |
| `use_item` | Optional `hand`/`sneak`/absolute `lookAt`/`holdTicks`; native held-item use and optional timed release |
| `drop_items` | `item`, integer quantity or `"all"`, optional equipment `slot` and player `recipient`; drop existing belongings with native hooks and partial results |
| `follow` | `target` entity UUID, optional `distance`/`ticks`; moving-target following |
| `attack_entity` | Living `target` UUID, optional `distance`/`ticks`; basic native melee |
| `set_spawn` | `bed` block position; native spawn setting and brief sleep when permitted |
| `discover_commands` | Optional `prefix`; native usage forms and completions for this avatar |
| `execute_command` | `command`, optional `await: "teleport"` and outcome predicates; native dispatch and actual observed arrival |
| `search_blocks` | `block` ID/tag, optional nearest-result `limit` and spherical `radius`; loaded nine-chunk observation with distances and explicit coverage |
| `use_portal` | Selected `position`, optional expected `dimension`/`timeoutTicks`; physical approach and native portal transition |
| `open_inventory` | `position`, optional inspected-slot `limit`; native interaction and bounded slot/view observation |
| `take_items` | `position`, `item`, exact integer `quantity`; fixed-source extraction and acquired-stack ledger |

See [GAMEPLAY.md](GAMEPLAY.md) for the block target forms, detailed examples, result semantics, protection/persistence checks, and current adapter limits.
See [INTERACTIONS.md](INTERACTIONS.md) for right-click gestures, native hooks, held use and interruption behavior.

See [COMMANDS.md](COMMANDS.md) for command acknowledgment versus delayed completion, permissions, output, and dimension checkpoint behavior.
See [PORTALS.md](PORTALS.md) for block query coverage and explicit Nether/End travel.
See [NAVIGATION.md](NAVIGATION.md) for chunk tracking, physical movement, and local terrain recovery with carried supplies.
See [CONTAINERS.md](CONTAINERS.md) for selected inventories, stack/view results, and capability compatibility.

Items accept registry IDs or `#tags`. A supplied inventory `scope` must currently be `{ "kind": "neighbor_chunks", "radius": 1 }`. `containersFrom` refers to a prior search result, avoiding another scan; its dimension, selector, original coverage, and timestamps stay explicit. Collection and transfer operations still resolve the real inventory and its rules when executing.

The control service can also bind `observationRefs: { "found": "<server-observation-receipt-UUID>" }` at submission, allowing `containersFrom: "found"` without a new search step. Receipts belong to the avatar and persist server-side. Client-invented initial inventory results are ignored. The manual command does not resolve external receipt references; its example below performs an explicit search inside the program.

The program below is a template: replace the recipient UUID before use. It performs search, collection, and delivery with no model decisions between individual containers:

```json
{
  "schema": 1,
  "taskId": "retrieve-diamonds",
  "mode": "replace",
  "steps": [
    { "op": "search_containers", "item": "minecraft:diamond", "saveAs": "found" },
    { "op": "collect_items", "item": "minecraft:diamond", "quantity": "all", "containersFrom": "found", "saveAs": "loot" },
    { "op": "deliver_items", "itemsFrom": "loot", "recipient": { "playerUuid": "00000000-0000-0000-0000-000000000000" } },
    { "op": "report_result", "saveAs": "summary" }
  ]
}
```

## Recovery

A `try` and its `catch` each contain arrays of steps. Both can contain nested recovery blocks. On ordinary failure, skip the remaining failed body and enter the nearest catch. A successful catch resumes after its construct; a failing catch or `rethrow` propagates outward. The caught record contains message, operation, stable action ID, path, and actual partial result. `saveAs` on a failed operation also preserves its partial result so recovery can use an acquired-stack ledger.

```json
{
  "steps": [
    {
      "try": [
        { "op": "collect_items", "item": "minecraft:diamond", "quantity": 100, "saveAs": "partial" }
      ],
      "catch": [
        { "op": "report_failure", "saveAs": "shortfall" }
      ]
    },
    { "op": "report_result" }
  ]
}
```

Completed movements and inventory mutations remain completed. Neither recovery nor restart rolls them back. Control cancellation, avatar removal/death, or an unexpected dimension change bypass ordinary catches. Each server tick performs a bounded amount of interpreter work; running physical jobs yield to later ticks.

## Persistence and verification

Checkpoints retain program position, recovery stacks, named results, stable action/request IDs, queues, and a pending job's actual progress. Disconnect pauses execution, closes interactions, snapshots physical/cognitive execution data, removes the body, and releases its runtime references. Reconnect binds saved operations to the new body in a paused state; the agent or owner explicitly resumes the chosen request.

Execution JSON is stored as UTF-8 bytes inside avatar NBT, avoiding NBT string-size limits. Completed acknowledgments remain readable without reopening inventories or revisiting a former dimension. Operations re-resolve current world objects and apply actual transfer results after restoration. Orderly restart is tested; cross-file saves and arbitrary third-party handlers do not form a universal transaction across abrupt crashes.

Run `./scripts/qa.sh` for the normal headless suite. Run `./scripts/restart-qa.sh` for the two-process checkpoint test; it resets only `run/restart-qa`, launches a writer then a separate reader, and verifies actual world assertions in both. Logs are `build/qa/restart/write.log` and `read.log`. Both processes terminate after their tests, and neither starts a game window.
