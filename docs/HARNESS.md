# Client decision-loop foundation

This describes the Minecraft-independent harness core in `core/agent` and its authoritative Forge service in `forge/control`. Unit tests use virtual time and service fixtures; headless Forge GameTests connect the same loop/coordinator to actual inventories and player movement. The Forge client runtime, transport, and account/model screens are wired. [AUTH.md](AUTH.md) describes client-owned sign-in and strict typed decisions; [LIVE_QA.md](LIVE_QA.md) describes actual Luna gameplay checks. Native connected-client/login/rendering checks remain unverified. See [NETWORK.md](NETWORK.md).

## Provider and routing

`InferenceProvider` accepts a complete request containing a route, configurable model/effort labels, physical snapshot, original trigger, and explicit observation results. Each call is independent. The request owns copies of its JSON; a provider cannot mutate live harness state. Provider futures are polled on the owner's client thread.

The configurable defaults are `gpt-6-luna / none` and `gpt-6-luna / xhigh`. The simulator never calls an external model service. The optional [ChatGPT provider](AUTH.md) sends configured routes unchanged to the public Responses API after client-side sign-in; actual account models and request errors determine availability. No model is silently substituted.

[Continuity](CONTINUITY.md) preserves a short Planning handoff note, each new task's original request and structured decision/tool-result history. Both live providers replay recent assistant decisions and actual results before the current snapshot, keeping world data separate from system instructions. K exposes the current note as Next step. Empty actionable Planning responses retain unresolved work and receive corrective context.

The [OpenRouter provider](OPENROUTER.md) uses separately configured client-wide exact model IDs for each route, with an API key held only in local account storage. `InferenceProvider.model` resolves its route before building a request or output trace; saved per-avatar ChatGPT routes remain intact. Both providers decode the same strict typed decision schema and share the harness's scheduling, task memory, action validation and cancellation behavior.

| Route | Available tools |
| --- | --- |
| Light | `chat`, `think_harder`, or an empty decision |
| Planning | `chat`, `observe`, `program`, `resume_program`, `pause_program`, `cancel_program`, `task_upsert`, `task_remove`, `remember`, `forget`, `personality`, `wait`, `trust`, `untrust`, `configure_behavior` |

A continuation (`think_harder` or `observe`) must be the sole tool call in a reply. The whole reply is checked before even an otherwise permitted call is applied. Persisted decisions are checked again on load. The server must independently enforce instruction authority and operation permissions.

## Scheduling and recovery

`HarnessLoop` receives the latest snapshot through `accept` and advances through `tick`. It keeps one inference/effect pipeline per avatar. Every inference start, including escalation and observation continuations, observes the five-second minimum. A slow response holds that pipeline; snapshots arriving meanwhile replace physical state instead of queueing inference requests.

Fresh chat starts on Light; known task/program/timer continuations start on Planning. Acknowledged running/advancing queued programs suppress routine model polling while native work proceeds. New events/status changes still wake the loop. Chat takes priority over coalesced background notifications; spawn/self-response events are history-only. Explicit program waits compare actual state changes without repeatedly waking on an unchanged pause. See [CONTINUITY.md](CONTINUITY.md).

Planning can send a single program with no task entry and include its completion reply as the final server-side step. With `wakeOnSuccess: false`, its success goes into history and the avatar can return to idle without another inference turn. Programs that need additional decisions can keep the standard success wake and can link a durable task.

Snapshots are bound to persistent world, owner, and avatar UUIDs. Session changes or a server pause cancel pending futures. Results from an old future cannot be applied, including when a provider ignores cancellation. The turn remains saved for resumption under a valid lease.

Respawn advances a persisted life revision. An unfinished decision from the previous body is discarded instead of submitting its remaining effects under the new lease; durable tasks and an unfinished original chat request remain for fresh planning. Ordinary reconnect within the same life preserves retry/deduplication behavior.

Events have monotonically increasing journal IDs. Repeated/unsorted snapshots do not replay IDs. Pending damage and pickup notifications coalesce by type; they retain the first/latest IDs and occurrence counts, and damage totals where supplied. The newest physical snapshot supplies actual inventory/health. Authorized chat events remain separate with their original text and sender UUID. History-only events (`wake: false`), including outgoing responses, advance history/cursor without waking the model.

Unfinished `QUEUED`, `ACTIVE`, `INACTIVE`, and `WAITING` tasks remain schedulable. `COMPLETED` and `FAILED` tasks do not create polling. A `wait` tool with an event and/or nonnegative integer duration suspends ordinary task polling. A matching event, timer, chat, damage, or pickup wakes it. A timer can wake an avatar even without a task. With no pending turn, events, unfinished tasks, or elapsed wait, inference stays off.

Every turn stores its original trigger, planning continuation results, route/round, accepted tool batch, and acknowledgment cursor. State is saved before inference starts and before effects are sent. Restarting during inference reruns the pending route with the original trigger and fresh physical context. Restarting during a tool submission replays the recorded call using the same request UUID, without inferring another batch.

`HarnessLoop.Sink` routes effects through `ServerControl`, which persists authoritative acknowledgments and deduplicates server effects, including chat and observation receipts. IDs identify a turn/round/call rather than an ephemeral connection. The Forge adapter stamps the current lease and derives the owner from the native connection. The service checks ownership, lease validity, conflicting request content, and revoked instruction senders. GameTests exercise the native payload codecs/in-process endpoint; real client TCP transport remains unverified.

Provider, storage, authority, transport, and conflicting-request faults pause the local loop and expose `lastError` in the UI. An authoritative ordinary `TOOL_FAILED`/`OBSERVATION_FAILED` stops dependent calls and returns the actual failure to planning through `observations.tool_failures`, preserving the original trigger and earlier acknowledged effects. Replanning advances the round so it cannot replay the same permanently failed receipt. This recovery survives local storage/restart. The runtime sends an owner pause on local faults and releases control on link failure. The server independently pauses/revokes an expired controller. Native client logout saves/cancels local work, and server logout independently saves/despawns bodies.

## Tasks and local storage

Tasks have stable IDs, goals, priorities, statuses, and optional program IDs/other explicit metadata. Updates preserve unspecified fields. Smaller priority numbers sort first. The agent can reorder/remove tasks, write/delete keyed memory, and replace the personality text through planning tools.

History retains at most 128 entries and 65,536 JSON characters. Removed history is represented by counts by kind; active tasks, memory, personality, and a pending turn are kept separately. This is history trimming, not semantic memory summarization.

`AgentStore` stores JSON beneath a supplied local root as:

```text
<root>/<world UUID>/<owner UUID>/<avatar UUID>.json
```

It writes a forced temporary file and replaces the destination atomically when the filesystem supports it, with a replacement fallback otherwise. The record repeats its binding and schema; malformed or mismatched data raises an error and is left available for diagnosis. Tests cover actual temporary files, state replacement, and world isolation. The Forge client selects `<game directory>/paracraft/memory` as its root.

## Deterministic QA scripts

`SimulatedProvider` is a finite script interpreter. Its status label says **SIMULATED — deterministic QA scripts, no AI usage**, and every chat response starts with `[SIMULATED]`. It has no account integration or hidden conversation state.

| Example instruction | Scripted behavior |
| --- | --- |
| `Hey Josh, how's it going?` | Light chat |
| `Josh, collect all the diamonds nearby and give them to me.` | Escalate, request inventory observation, create a task, submit collection/delivery |
| `Josh, progress?` | Light reports current task/program state; no replacement |
| `Josh, travel to 12 64 30` | Escalate, mark earlier active work inactive, interrupt with a travel task |
| `Josh, pause work` / `Josh, resume work` | Planning task/program state changes |
| `Josh, wait for weather` | Planning event wait |
| `Josh, remember base=South door` | Planning memory write |
| `Josh, personality Brief and cheerful` | Planning personality update |
| `Josh, trust <UUID>` / `Josh, untrust <UUID>` | Planning delegation request; the server must verify owner permission |
| `Josh, behavior players passive` / `Josh, behavior mobs neutral` | Escalate and update the selected server reaction category; all four documented reactions are supported |
| `Josh, discover commands home` | Escalate, create a command-discovery task/program, and report its actual outcome |
| `Josh, execute command list` | Escalate and dispatch as the avatar; native command return completes the task |
| `Josh, teleport command home base` | Escalate, dispatch once, and wait for an actual native teleport or timeout |
| `Josh, search blocks #minecraft:portals` | Escalate and run a nearby block query task; inspect actual positions in its program result |
| `Josh, search blocks minecraft:crafting_table nearest 3 radius 16` | Return up to three nearest matching positions within sixteen blocks and the loaded nine-chunk scope |
| `Josh, use portal 100 64 100` | Escalate and physically approach/use that selected native portal |

An inactive task remains eligible for automatic later resumption; `pause work` is not a permanent pause. Use an explicit wait or the runtime's owner pause control to suspend polling.

The inventory observation continuation receives a server receipt ID and observed search data. The program refers to that receipt through `observationRefs.found`; its collection consumes the named result and its delivery consumes the acquired-stack ledger. The server binds its own stored observation and ignores client-invented initial results. Program IDs derive from the avatar, original event ID, and scripted operation, preventing duplicate program identity when the same event is reconstructed.

The simulator checks actual program snapshots to complete/fail tasks, reports real failure strings/delivered counts supplied there, and resumes existing program IDs. It does not implement general language understanding. Unknown/malformed script requests do not claim successful gameplay.

Progress replies summarize actual current counts and phase without dumping the full snapshot, named results, or inventory metadata into public chat. Detailed observations remain in the explicit program/snapshot state.

## Verification

Run `./gradlew test` with Java 17 for virtual-clock scheduling, routing, cancellation, restart/retry, storage, scripted diamond/progress/completion, interruption/resumption, and idle checks. These tests use service replies supplied by test fixtures. They do not exercise real containers, network connections, UI, credentials, or live model usage.

The Forge GameTests also connect the loop and service into an actual Minecraft snapshot-to-program scenario: an authorized mention escalates, observes real chests, submits one collection/delivery program, answers progress during travel, completes the task, and remains idle for multiple virtual decision intervals. Source inventories, twelve delivered diamonds, three pre-existing diamonds, and more than twenty blocks of actual walking are asserted. The clock is virtual and transport is in-process; no connected client or live inference is claimed.

## Authoritative Forge service

`ServerControl` runs on the server thread. It records authorized owner/trusted mentions, damage, pickups, outgoing responses, and terminal program results. Name matching is case-insensitive and respects player-name boundaries. The server keeps 256 recent journal events with compacted type counts and a persistent sequence. New effects referring to expired or revoked instruction events fail explicitly. A previously accepted request can return its acknowledgment under a fresh valid owner lease without repeating its effect.

Chat without an explicit active-avatar name targets exactly one living avatar the sender may instruct within 24 blocks (inclusive, three-dimensional distance) in the same dimension. Other players' avatars do not count unless the sender has delegated instruction rights. Zero or multiple eligible nearby avatars cause no implicit wake. Explicit names retain priority and existing remote/multi-avatar addressing; naming an avatar without permission does not redirect the message to a different nearby avatar. The real Forge event passes its native player context through the server-thread handoff, and the wake journal retains the original text and sender UUID.

Snapshots include the world binding, session, identity/permissions, physical location/dimension, health, hunger, oxygen, experience, carried/equipped stack data and durability, effects, bed spawn, nearby entities with explicit truncation, execution progress, events, and recent inventory observation receipts. The client adds cognitive context before each provider invocation.

They also include native breathing status, survival mode, reaction/idle settings, current/last mechanical reflex, patrol progress, and `survival.ambient` with its fixed resting center, dimension, radius and actual idle activity. Planning can toggle `configure_behavior({ambientIdle:false})` or turn it back on with `true`; the strict tool schema includes this nullable optional boolean. Ambient behavior emits no task/wake events and keeps idle inference suppressed. `configure_behavior` uses the same authoritative request receipts as other server effects. See [BEHAVIOR.md](BEHAVIOR.md).

An inventory query advances incrementally over exactly the existing nine-chunk search scope, inspecting at most eight block entities per tick. Queries do not replace the running program. Disconnect interrupts an unfinished query; completed receipts survive in avatar NBT and can be acknowledged after reconnect. Search data retains its original timestamps and coverage. A program binds only receipts owned by that avatar; later transfers use actual changed contents and report partial results.

Permission administration through the agent service requires an owner-origin instruction. Trusted users can instruct through the owner's harness but cannot gain its lease or administer the trust list. Raw owner commands remain available.

World identity, journals, receipts, and published program states are saved with the existing avatar data. Current receipt/program histories are retained without pruning; long-running scale/storage optimization remains open. The two-process restart suite verifies world identity, recovery of a chat acknowledgment without repeating its effect, and the pending physical transfer. Completed observation receipt recovery is tested through NBT/body rebinds. These do not claim transactional crash recovery across arbitrary mod inventories. Native payload codecs and heartbeat loss are tested; actual TCP/client login/logout and account UI rendering still need direct verification.
