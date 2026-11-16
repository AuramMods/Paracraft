# Foundation validation — 2026-11-16

The Forge 1.20.1 first pass is implemented and ready for owner QA with the labeled simulated provider. Authentication and live AI usage remain the next phase. No graphical client, browser sign-in, or account credentials were used for this validation.

## Build and automated results

| Check | Observed result |
| --- | --- |
| Toolchain/build | Java 17, Gradle 8.8, ForgeGradle 6.0.54, Forge 47.4.28; `test build runGameTestServer` exited zero |
| Unit tests | 46 tests, zero failures/errors |
| Native normal suite | All 86 required Forge GameTests passed |
| Cold-process suite | Writer and reader each passed their required world assertions in distinct Forge JVMs |
| Background execution | Normal worker 46188 and restart worker 47255 completed with real exit code zero and released locks |
| Stop/duplicate control | Actual held-lock rejection; actual Forge launch stopped gracefully through its owned group, recorded cancelled, and released ownership |
| Packaging | Reobfuscated JAR includes its access transformer; QA classes and structure fixtures are excluded |
| Process cleanup | No background QA worker, Forge GameTest server, or Gradle daemon remained after final checks |

Final artifact:

```text
build/libs/paracraft-forge-1.20.1-0.1.0-dev.jar
309,162 bytes
SHA-256: da7016ca31c0b9e300e323766e28c4d0cc1a54ff4b42db3768aa31592207ace9
```

The final cold writer JVM was 47463; reader JVM 47908 loaded that saved world. It verified world identity, physical inventory/chests, pending transfer recovery, stale-lease rejection, acknowledgment without duplicate effects, persisted behavior settings, and an already-issued command restored without reissue. The test-only external teleport request is absent after process restart, so that command honestly times out rather than claiming arrival.

## What the native tests establish

| Area | Evidence |
| --- | --- |
| Identity/lifecycle | Distinct survival players, native roster/name/UUID lookups, failed-spawn rollback, save/despawn/restore, menu cursor conservation, death/drop/bed/respawn, logout mutations and exactly-once destruction drops |
| Authority | Owner control leases, stale-session rejection, unauthorized mentions, trusted instructions, revocation, owner-only delegation administration, native-sender checks and controller expiry |
| Observations | Native health/equipment/events, fixed nine-chunk inventories/blocks, diagonal/full-height coverage, timestamps, conservative face counts, observation receipts and changed-source partial results |
| Inventory work | Actual physical collection/deposit/delivery, metadata, pre-existing belongings, sided capabilities, source/destination refusal/capacity, selected open/take jobs and saved transfer counts |
| Programs | Ordered/named results, nested catch/rethrow, partial results, interrupt/append/resume, cancellation/death outside recovery, checkpoints, request deduplication and foreign-dimension parking/rebind |
| Gameplay | Effective tools, timed breaking/loot/durability/pickup, farming/replant, placement rollback, armor/binding, following, native melee, beds/food/oxygen, reaction profiles and no-inference idle patrols |
| Navigation | Physical movement, 216-block owner-independent travel, native loading/generation before fixture contact, tracking release, doors/swim/ladder ascent, obstruction/bridge/pillar recovery, partial-height support and actual fall damage |
| Commands/dimensions | Native avatar permissions and feedback, zero-return success, delayed acceptance versus teleport, timeout, no reissue on restoration, destination follow-up, explicit Nether/End portals and nondeath End return |
| Capacity | Actual default 4/64 admission/refusal, changed config admission, roster membership and capacity-constrained reconnect with inventory retained |
| Combined scenario | Authorized mention → Light escalation → real inventory search → physical diamond collection → progress without replacement → inactive gathering during another request → resumption/delivery/task completion → idle suppression |

The combined harness/coordinator checks use native payload codecs and an in-process endpoint with a virtual decision clock. They operate on real Minecraft inventories/entities/world mutations, but do not simulate an actual network client login. The local simulator is finite scripted behavior, visibly labeled `[SIMULATED]`; its call counters are not live model usage. A separate unit check confirms short progress replies use actual counts without dumping snapshot/inventory metadata into chat.

## Scope of the evidence

- The capability fixture is a real attached serialized Forge interface on a non-Container block entity, excluded from production. It is representative API coverage, not an installed arbitrary modpack.
- The 64-avatar scenario validates actual admission/roster/configuration/reconnect behavior. It is not a benchmark of 64 simultaneous distributed navigation or inference workloads.
- Distant GameTest terrain was observed to be void. The long-route test explicitly supplies a narrow road four blocks ahead in already-full chunks and requires destination generation/loading before the road touches it. It does not claim natural-landscape coverage.
- Native home/TPA fixtures prove command identity, delayed outcomes, and recovery. Third-party command-mod state/cancellation compatibility is not universally established.
- Cold-process tests cover the listed orderly recovery paths. New fixed-source/mining/portal body checkpoints have additional in-process NBT checks; cold portal/death coverage and universal cross-mod crash transactions are not claimed.

## Owner checks and later work

Real TCP/signed chat, client login/logout, player rendering/animations, screen layout, and actual modpack interactions remain unverified. No verified focus-preserving graphical launcher was established; automation intentionally stayed headless. Follow the owner-initiated checklist in [QA.md](QA.md) using one persistent client session, and return client/server logs plus notes for failures.

Initial adapters cover vanilla/native and generic capability behavior. Special crops/weapons/accessories/virtual storage, unusual teleporters/movement, natural-terrain tuning, distributed performance, a reusable cross-server profile library, additional loaders/versions, advanced crafting/smelting/construction, and optional usage budgets remain expansion work. Cognitive state in this pass is correctly isolated by world/owner/avatar binding.

Real sign-in/live inference is not implemented or verified. The next step is a supported client-only account/provider integration behind `InferenceProvider`, preserving tool routing, snapshot reconstruction, cancellation, and server authority. The owner then explicitly tests account sign-in and one real request. Credentials must remain outside Minecraft server state/packets.

## Reproduce and inspect

Use [INSTALL.md](INSTALL.md) for installation/configuration, [BACKGROUND.md](BACKGROUND.md) for immediate-return start/status/stop commands, and [QA.md](QA.md) for foreground/cold runs and the manual checklist.

Local evidence is in `build/qa/final-manifest.json`, `final-normal.log`, `final-restart.log`, their `*-state.json` files, `headless.log`, `restart/write.log`, `restart/read.log`, and `build/test-results/test`. The ignored `.qa` session collects structured `QA_EVT` assertions separately from owner visual observations. Generated artifacts can be rebuilt from the committed source; do not treat an old state file alone as proof of a fresh run.
