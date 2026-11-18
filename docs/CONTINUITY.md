# Agent continuity and fewer decision cycles

The reported log contained 71 Light escalations and 27 Planning waits. Previously, decision history usually contained tool names without successful arguments/replies, routine task updates started on Light again, and spawn could wake inference before the owner's queued chat. These could cause repeated inspection/waiting without retaining the whole requested goal.

## Context

Each fresh request now includes a short saved `workingNote`, the original requester/message attached to new tasks, and recent assistant decisions followed by acknowledged execution results. The current `continuity` block contains tasks, memory, personality and the saved handoff before physical snapshot data. Up to three decision batches are replayed. Actual current state/results override prior intentions; historical messages do not execute effects.

The response is `{"note":"brief goal, verified progress and next step","calls":[...]}`. This is a concise handoff, not hidden reasoning or public chat. Planning updates it; Light greetings/progress checks leave the Planning note intact. K shows it as **Next step**. Old saved memory and calls-only responses remain readable.

Snapshots/world text stay outside system instructions. Explicit history follows the [manual conversation-state approach](https://developers.openai.com/api/docs/guides/conversation-state) while preserving independent snapshot requests, local memory and both providers. No server-retained model conversation is required.

The 128-entry/64-KiB journal now contains structured tool arguments, actual replies and local acknowledgments. Large history arrays/text carry explicit preview omissions; execution uses original data. All nonterminal programs, unfinished-task-linked results, the acknowledged program and four recent terminal programs remain in model context; unrelated older terminal records are omitted there. Server persistence/control state is unchanged. Dropped entities now expose actual item stacks and pickup-delay state, and snapshots identify the server's current program. Missing old information is not invented.

## Scheduling and recovery

New chat starts on Light. Known task/program/timer continuations start directly on Planning. Configured models/efforts and the five-second minimum remain unchanged. An acknowledged RUNNING program, or QUEUED work with a running predecessor, proceeds without periodic model inference. Fresh snapshots still update locally. Chat, damage, pickup, completion/failure and changed execution status can wake the loop.

Chat takes priority over background notifications, preserving chat order. Background wakes share one decision via `original_trigger.coalescedEvents`, preferring a terminal-program event as the primary trigger. Spawn, automatic spawn-point updates and self-responses are history-only. Debug output includes trigger type/event ID.

Explicit waits remain supported. Program waits notice an actual paused/failed/missing-state change without repeatedly waking on an unchanged pause. An indefinite `any`/`chat` wait with no unfinished work appears as Idle and still wakes for new events. Planning guidance calls out queued work stuck behind failure, reuse of known results, whole-goal completion, and container collection versus loose item pickup.

Empty Planning replies retain unresolved player requests as queued tasks when necessary, preserve their context, and receive corrective feedback. One immediate continuation retains the original trigger; repeated empty replies yield to normal scheduling so newer chat can run. The agent still chooses actions, explicit waits, clarification and terminal task status. This does not invent success, repeat side effects, impose a usage budget or change models.

## Selective context

Provider requests begin with fixed instructions and the companion profile. Recent conversation, the current request, health and location, relevant task and program summaries, and a small set of historical decisions and results follow. Requests about items or nearby entities include short previews when the request mentions them. Inventory NBT, memory values, and complete job results are available on demand.

Light and Planning can call `get_context` with one or more sections. Use `query` to filter records by item, entity, task or program ID, name, memory key, or text. Choose `detail: "full"` for exact records and use `nextOffset` to fetch another page. The latest selection per section stays available during that turn and refreshes from the client snapshot before inference. `observe` can perform a fresh world container search on Planning. Its server receipt is still available for action programs.

## Action history

Each avatar saves a rolling plain-text journal of observed gameplay effects. Placement and mining record the actual block ID and coordinates when the world changes. This captures individual blocks even when a later step in the same batch fails. Other requested jobs record short outcomes from their actual results. The oldest whole lines are evicted at approximately 10,000 tokens.

```text
placed minecraft:stone 145 64 -29
mined minecraft:oak_log 145 65 -29
deposited 12 minecraft:oak_log 151 64 -31
```

Coordinates use `X Y Z`. Entries in other dimensions end with `in <dimension>`. Both model routes receive a small recent excerpt, with the newest action last. Use `get_context` with `action_history` to search or page older lines, newest first. The rest of the saved journal stays out of routine inference.

Fixed instructions and tool definitions give each route a stable prefix for repeated requests. Cache reuse depends on the provider, model, and matching rendered tokens. A switch between Light and Planning can change the prefix.

## Verification

Tests cover saved notes/goals/replies across restart, direct continuation routing, five virtual minutes of mechanical work without extra inference, Light progress checks, empty-decision recovery, changed-state waits without spinning, chat priority/background batching, idle wake behavior, escaped large history, typed notes, both providers' replayed messages, relevant-program retention and separation of world text from system instructions.

Native GameTests remain windowless. The separate [live Luna suite](LIVE_QA.md) checks actual model decisions with delivery/crafting. In-game Next step layout and connected-client interaction remain manual checks; automated validation launches no graphical client or browser.
