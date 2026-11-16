# Client decision-loop foundation

This describes the Minecraft-independent harness core in `core/agent`. It is implemented and tested with virtual time and a stubbed service. The Forge client transport, actual server snapshot service, chat event wiring, and account/status screen are still required. The current mod does not yet respond to in-game chat through this harness.

## Provider and routing

`InferenceProvider` accepts a complete request containing a route, configurable model/effort labels, physical snapshot, original trigger, and explicit observation results. Each call is independent. The request owns copies of its JSON; a provider cannot mutate live harness state. Provider futures are polled on the owner's client thread.

The configurable defaults are `gpt-6-luna / none` and `gpt-6-luna / xhigh`. These are route settings supplied by the design, not evidence of model availability, authentication, or live inference. The current simulator never makes network/provider requests.

| Route | Available tools |
| --- | --- |
| Light | `chat`, `think_harder`, or an empty decision |
| Planning | `chat`, `observe`, `program`, `resume_program`, `pause_program`, `cancel_program`, `task_upsert`, `task_remove`, `remember`, `forget`, `personality`, `wait`, `trust`, `untrust` |

A continuation (`think_harder` or `observe`) must be the sole tool call in a reply. The whole reply is checked before even an otherwise permitted call is applied. Persisted decisions are checked again on load. The server must independently enforce instruction authority and operation permissions.

## Scheduling and recovery

`HarnessLoop` receives the latest snapshot through `accept` and advances through `tick`. It keeps one inference/effect pipeline per avatar. Every inference start, including escalation and observation continuations, observes the five-second minimum. A slow response holds that pipeline; snapshots arriving meanwhile replace physical state instead of queueing inference requests.

Snapshots are bound to persistent world, owner, and avatar UUIDs. Session changes or a server pause cancel pending futures. Results from an old future cannot be applied, including when a provider ignores cancellation. The turn remains saved for resumption under a valid lease.

Events have monotonically increasing journal IDs. Repeated/unsorted snapshots do not replay IDs. Pending damage and pickup notifications coalesce by type; they retain the first/latest IDs and occurrence counts, and damage totals where supplied. The newest physical snapshot supplies actual inventory/health. Authorized chat events remain separate with their original text and sender UUID.

Unfinished `QUEUED`, `ACTIVE`, `INACTIVE`, and `WAITING` tasks remain schedulable. `COMPLETED` and `FAILED` tasks do not create polling. A `wait` tool with an event and/or nonnegative integer duration suspends ordinary task polling. A matching event, timer, chat, damage, or pickup wakes it. A timer can wake an avatar even without a task. With no pending turn, events, unfinished tasks, or elapsed wait, inference stays off.

Every turn stores its original trigger, planning continuation results, route/round, accepted tool batch, and acknowledgment cursor. State is saved before inference starts and before effects are sent. Restarting during inference reruns the pending route with the original trigger and fresh physical context. Restarting during a tool submission replays the recorded call using the same request UUID, without inferring another batch.

`HarnessLoop.Sink` must persist authoritative request acknowledgments and deduplicate all server effects, including chat and observation receipts. IDs identify a turn/round/call rather than an ephemeral connection. The runtime must stamp the current lease and actual connected owner, reject revoked instructions, and reconcile accepted programs. Core tests demonstrate the retry contract against a deduplicating stub; they do not prove server-side integration yet.

Provider/tool failures pause the local loop and expose `lastError` for UI and control-session handling. The runtime adapter must stop/revoke server execution when its harness fails or disconnects. This lifecycle wiring is pending.

## Tasks and local storage

Tasks have stable IDs, goals, priorities, statuses, and optional program IDs/other explicit metadata. Updates preserve unspecified fields. Smaller priority numbers sort first. The agent can reorder/remove tasks, write/delete keyed memory, and replace the personality text through planning tools.

History retains at most 128 entries and 65,536 JSON characters. Removed history is represented by counts by kind; active tasks, memory, personality, and a pending turn are kept separately. This is history trimming, not semantic memory summarization.

`AgentStore` stores JSON beneath a supplied local root as:

```text
<root>/<world UUID>/<owner UUID>/<avatar UUID>.json
```

It writes a forced temporary file and replaces the destination atomically when the filesystem supports it, with a replacement fallback otherwise. The record repeats its binding and schema; malformed or mismatched data raises an error and is left available for diagnosis. Tests cover actual temporary files, state replacement, and world isolation. No production Minecraft storage directory is selected until the client adapter is connected.

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

An inactive task remains eligible for automatic later resumption; `pause work` is not a permanent pause. Use an explicit wait or the runtime's owner pause control to suspend polling.

The inventory observation continuation expects a server receipt ID and observed search data. The program refers to that receipt through `observationRefs.found`; its collection consumes the named result and its delivery consumes the acquired-stack ledger. The server adapter must bind its own observation, never accept client-invented inventory data. Program IDs derive from the avatar, original event ID, and scripted operation, preventing duplicate program identity when the same event is reconstructed.

The simulator checks actual program snapshots to complete/fail tasks, reports real failure strings/delivered counts supplied there, and resumes existing program IDs. It does not implement general language understanding. Unknown/malformed script requests do not claim successful gameplay.

## Verification

Run `./gradlew test` with Java 17 for virtual-clock scheduling, routing, cancellation, restart/retry, storage, scripted diamond/progress/completion, interruption/resumption, and idle checks. These tests use service replies supplied by test fixtures. They do not exercise real containers, network connections, UI, credentials, or live model usage.

The separate Forge GameTests exercise the existing physical inventory/interpreter paths. Connecting these two verified components into one actual Minecraft snapshot-to-program scenario is the next cycle.
