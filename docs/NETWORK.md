# Client runtime and transport

The Forge mod now starts its client-owned simulated harness automatically when a player joins a world. It shuts down local inference/futures and saves cognitive state on client logout. No companion screen opens automatically.

Press **K** in-game, or use **Mods → Paracraft → Config**, for companion status, tasks/personality, accounts, spawn, pause/resume, destruction with item drops, model routes, and harness reconnect controls. `/paracraft link chatgpt` opens the local account screen after joining. Unlinked players receive clickable reminders on join and avatar creation. The simulator is explicitly labeled; linked accounts can select the live provider described in [AUTH.md](AUTH.md). Authentication and credentials remain on the client.

ChatGPT model and effort settings are stored per companion. [OpenRouter](OPENROUTER.md) instead uses client-wide explicit Light/Planning model IDs and efforts configured through **K → Accounts → OpenRouter** or `/paracraft link openrouter`, preserving the per-companion routes. The provider resolves the actual route before inference and diagnostic tracing. Changing provider/settings restarts the local coordinator and invalidates prior inference; credentials remain in the protected local account record. The deterministic simulator does not verify live model availability or consume usage. The current screen/account path is compiled but has not been visually exercised in a Minecraft client.

## Local state and recovery

The client uses:

```text
<Minecraft game directory>/paracraft/memory/<world UUID>/<owner UUID>/<avatar UUID>.json
```

The server stores physical avatars, execution checkpoints, observations, event journals, and request acknowledgments in world data. Personality, memory, tasks, selected routes, and pending inference/tool turns remain local.

If no local file exists, the UI reports fresh memory. Unfinished server operations are represented as recovered tasks with their actual program IDs and an explicit note that the prior local intention is unavailable. Invalid memory files are preserved, their error is shown, and execution is paused. Repair/restore the file and reconnect the harness to reload it.

Pause/resume works through both raw commands and the screen. A server pause cancels local pending futures. An explicit server resume clears the corresponding local pause. Reconnect replaces the client controller generation, rotates avatar leases, and pauses prior execution for deliberate resumption. It does not teleport bodies or create another copy of inventory.

## Wire format

Forge `SimpleChannel` protocol `2` (JSON envelope version `1`) is required on both sides. Direction-specific registrations use `PLAY_TO_SERVER` and `PLAY_TO_CLIENT`; handlers enqueue work on the appropriate game thread. The server resolves the current native player/connection and ignores payload owner fields. Packets from a replaced native connection are dropped. See [Forge SimpleImpl](https://docs.minecraftforge.net/en/1.20.1/networking/simpleimpl/).

Logical JSON envelopes contain protocol, client-generation UUID, request UUID, and a message kind:

| Kind | Purpose |
| --- | --- |
| `poll` / `snapshot` | Baseline observations, controller heartbeat, and public aggregate activity |
| `effect` / `reply` | A routed harness tool request and authoritative acknowledgment |
| `control` / `reply` | Explicit owner spawn/pause/resume/destroy |
| `release` | Revoke a failed/stopped client controller |
| `agent_output` | Owner/session-bound visible output chunks when server debugging is enabled |

A separate server-to-client avatar-status packet supplies a bounded public label to tracking viewers. Changed labels are broadcast and newly tracking players receive the current label. Full task goals and memory stay local unless their tool arguments are emitted into deliberately enabled debug logs; see [DEBUG.md](DEBUG.md). Aggregate reports for an avatar deleted between heartbeats are ignored so its stale status cannot block the next baseline.

Effect requests also identify the avatar, current lease, original event, tool, and arguments. A transport attempt UUID distinguishes a retry from an earlier canceled submission. The stable request UUID still deduplicates the actual server effect. Late attempt replies cannot complete the retry. Monotonic server snapshot revisions and client poll sequences prevent older baselines replacing newer state.

Envelopes use UTF-8 JSON, gzip, and fragments of at most 24,000 bytes. Tests round-trip the fragments through actual Minecraft serverbound/clientbound custom-payload constructors and `FriendlyByteBuf` codecs. The serverbound engine limit is 32,767 bytes, so discriminator/header bytes still fit. Valid large item metadata is spread across multiple NBT tags in the QA fixture; a single native NBT string cannot hold the entire stress payload.

Transport bounds are 16 MiB per logical JSON/compressed message, 32 MiB buffered per connection, sixteen incomplete assemblies, and thirty seconds to finish an assembly. Bounds apply to actual encoded/received/decompressed bytes; oversize or malformed data reports an error. They are transport/processing bounds, not inference quotas. Duplicate identical parts are harmless; conflicting parts fail explicitly.

## Liveness and scheduling

The coordinator polls every two monotonic seconds independently of provider inference, and sends activity changes on the next client tick. It keeps at most one poll outstanding, coalescing activity changes until the response. A slow provider does not block heartbeats. Reports retain phase `THINKING` for both inference routes, with an additive `planning` boolean (absent means false for older clients). Thinking/Planning holds server-owned ambient navigation immediately on receipt; explicit operations and survival navigation are unaffected. Each avatar's inference starts still obey the five-second minimum and one-pipeline rule; idle snapshots consume no inference.

The server expires a controller after 200 server ticks without a poll, pauses its jobs, revokes leases, and leaves survival bodies present while their owner remains connected. Reacquiring an expired controller does not silently unpause it. The owner can resume explicitly. A local provider/tool/storage fault sends an owner pause; a link fault releases the controller. The heartbeat timeout is the independent fallback if the client/helper stops responding.

A snapshot/tool acknowledgment timeout is thirty monotonic seconds. The error is shown, pending work is retained, and the link pauses rather than continuously resubmitting old requests. **Reconnect** creates a fresh controller generation. Actual owner disconnect independently saves/despawns bodies and revokes authority on the server.

## Verification scope

ChatGPT sign-in, credentials, HTTP inference, and token/usage caching stay in a separate client-only account/provider layer described in [AUTH.md](AUTH.md). Account data is not added to these Minecraft envelopes, physical world state, or companion cognitive snapshots. Changing accounts/providers cancels/rebinds the client coordinator; the Minecraft wire schema remains unchanged.

Unit tests cover fragmentation, ordering, duplicate/conflicting parts, actual decompression limits, persistence, slow-provider heartbeats, route storage, raw/UI pause consistency, canceled-attempt retries, stale generations/world bindings, corrupted/missing memory, and acknowledgment timeouts.

The headless integration uses the actual coordinator and endpoint with native payload codecs, real chest inventories, walking, progress, an inactive gathering task during another real travel program, resumption, delivery, and idle suppression. It also verifies a forged owner payload is insufficient and a missing heartbeat pauses native execution.

The integration transport is in-process with a virtual clock. It does not prove a real TCP login, signed chat packet, client screen rendering, or native client logout. Those checks are listed in [QA.md](QA.md). No graphical client or browser has been launched by automated QA.
