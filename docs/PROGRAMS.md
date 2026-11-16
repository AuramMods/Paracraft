# Structured action programs

Programs execute server-side through the same jobs used by manual controls. They contain finite sequences and nested recovery blocks, not executable Java, shell commands, or scripts in another language. The simulated provider already produces this schema in headless service scenarios; Forge client transport is pending.

## Submission and identity

Use `/paracraft program <companion> <json>` as the owner. `JobManager.submitProgram` checks owner and current control-session ID. The client-facing `ServerControl` service additionally checks instruction provenance/revocation, request content identity, and server observation receipts. Trusted-player service routing is implemented; connected-client transport remains pending.

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
