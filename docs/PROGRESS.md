# Implementation progress

The goal remains the full initial foundation in [PLAN.md](PLAN.md). A successful build alone is not foundation completion.

## Cycle 1: Forge build and headless QA bootstrap

- Added the Java 17 / Forge 1.20.1 project, pinned ForgeGradle/Gradle versions, required client/server mod metadata, and configurable 4/64 avatar limits.
- Added an isolated GameTest source set and a test using an actual Minecraft chest and Forge inventory capability.
- Added the windowless QA command. No graphical client or authentication browser has been launched.
- Validation: `./gradlew build` succeeded and produced the reobfuscated JAR; `./gradlew runGameTestServer` passed its one required test, placing and modifying a real chest. There are no ordinary unit tests yet. GameTest fixtures are excluded from the mod JAR. No client/UI or companion behavior has been verified.

## Remaining implementation

| Area | Required work |
| --- | --- |
| Avatar | Client rendering verification, actor compatibility beyond the tested vanilla interactions, validated capacity limits |
| Lifecycle | Real client login/logout verification, death/bed respawn, further command interruption and item-drop edge cases; pending execution now survives tested orderly process restart |
| Observations | Native client delivery verification, block queries, further inventory compatibility; server snapshots/events and Forge transport are implemented |
| Execution | Following, additional gameplay operations and broader terrain recovery; structured programs and persisted execution/request results are implemented |
| Harness | Native client login/logout/UI verification and broader runtime recovery; Forge client runtime/storage/UI, packets, and controller expiry are implemented and checked headlessly where possible |
| Authority | Actual connected-client instruction/control verification, normal player command execution and delayed outcomes; authoritative chat/delegation/receipt service is tested |
| Gameplay | Mining, farming, combat/reflexes, equipment, placement, chunk traversal, explicit dimension travel, idle routines |
| QA | Native client TCP/rendering checks, further edge cases, capacity evidence; combined actual-world interruption/resumption passes, and the client checklist is documented |
| Account UI | Visual verification of the implemented disconnected status; real sign-in/live inference remain follow-up scope |

Live authentication and actual AI usage are outside this foundation goal and remain unverified.

## Cycle 2: Persistent survival-player prototype

- Added a distinct-profile `ServerPlayer` actor with a local packet listener and normal player tick/survival paths, avoiding Forge FakePlayer's invulnerability and disabled ticking.
- Added owner-only raw lifecycle commands, instruction delegation data, revocable control sessions, saved physical NBT, periodic checkpoints, disconnect removal, reconnect restoration, and offline destruction drops.
- Container closure occurs before saving/destroying so items carried on a menu cursor are returned/accounted for.
- `./scripts/qa.sh` passed: one JUnit authority test and all four required Forge GameTests. The world tests cover real chest capability mutation, actor damage/ticking, disconnect/restore with 14 metadata-bearing diamonds including menu cursor items, and destruction of a saved avatar dropping nine real diamonds exactly once. Saved-state serialization is also round-tripped through compressed NBT.
- These tests invoke the same manager paths used by login/logout events, but do not simulate real client connections or prove client rendering. The current prototype is registered in its level rather than the server's global PlayerList; compatibility with commands/mods requiring that list remains to be addressed. Automatic death respawn, bed seeking, navigation, reflexes, and action execution are not implemented yet.

## Cycle 3: Physical inventory work

- Added fixed nine-chunk searches with per-container timestamps, explicit coverage, double-chest identity normalization, vanilla inventories, and sided Forge item-handler access. Divergent generic face views report conservative counts and uncertainty instead of invented backing-slot identity.
- Added incremental search, travel, collection, deposit, and delivery jobs with progress, cancellation, pause/resume, live-session checks, and in-memory request deduplication across subsequent jobs. A collection freezes its initial scope and observed quantities; delivery accepts an exact acquired-stack ledger, preserving pre-existing belongings.
- Added bounded-segment vanilla path computation through an unspawned geometry proxy and real player physics for movement. Chunk-map movement updates now follow the actor. Native interaction hooks, locked/obstructed chests, real capacity, and actual transfer results govern inventory access.
- Added manual search/collect/travel/deposit/deliver/job/cancel commands. Custom item/tag argument serialization is registered for client command-tree synchronization.
- Headless QA now resets only its disposable `run/gametest/world` before each suite; the development server workspace is separate. Added original generated arena fixtures and their reproducible generator.
- Validation: `./scripts/qa.sh` succeeded with two JUnit tests and all eleven required Forge GameTests passing. The delivery test verified twelve acquired diamonds reached the recipient while three pre-existing diamonds stayed with the actor, over more than twenty blocks of measured movement and chunk-boundary tracking. Freshness, pause/resume, source conservation, server-hook denial, request deduplication, and command serialization passed. The 67,113-byte mod JAR excludes test code and fixtures. No graphical client was launched.
- This cycle does not yet supply the client inference harness, a full action-program interpreter, persisted job recovery, universal mod compatibility, or terrain construction/navigation recovery.

## Cycle 4: Structured programs and durable recovery

- Added a Minecraft-independent interpreter with bounded work, deterministic action IDs, named/partial results, nested try/catch, rethrow, and cancellation that bypasses recovery. Syntax is validated before execution; dynamic world conditions remain operation-time checks.
- Connected structured programs to the native search, collection, delivery, deposit, travel, report/chat, and wait jobs. Search observations feed collection without a second scan; collection ledgers feed delivery without including pre-existing belongings.
- Added append, replace, interrupt, and explicit resume-by-ID. Failed execution does not advance appended work. Interrupted requests remain selectable, and existing IDs acknowledge persisted results instead of replaying operations.
- Added execution checkpoints to avatar NBT, including pending operation progress, queues, error scopes, and named results. Disconnect pauses/saves/unbinds jobs; restoration binds new runtime objects to the new physical body and awaits explicit resumption.
- Added `/paracraft program`, [program documentation](PROGRAMS.md), and a separate two-process headless restart runner. The restart workspace is distinct from normal GameTests and the development world.
- Validation: the normal suite passed seventeen required Minecraft GameTests and eight JUnit tests. The restart runner passed one actual writer assertion in JVM 61984 and one actual reader assertion in JVM 62147: pending collection resumed from disk without repeating extraction, old control tokens were invalid, and the accepted request ID was retained. These are orderly-restart/service-path checks, not proof of real client connections or arbitrary cross-mod crash transactions.
- Client inference routing, snapshots/transport/UI, tasks/memory, remaining gameplay/reflexes, and broader avatar compatibility/capacity validation remain required before foundation completion.

## Cycle 5: Durable decision-loop core and deterministic scripts

- Added the Minecraft-independent client inference boundary, copied explicit context, configurable Light/planning routes, and tool restrictions checked before effects and when loading saved decisions.
- Added a single serialized pipeline with five-second inference-start cadence (including escalation/observation continuations), fresh snapshot replacement, event coalescing, inactive-task scheduling, event/timer waits, idle suppression, and cancellation that rejects late results even if a provider ignores it.
- Added durable task priorities/statuses, personality, keyed memory, bounded rolling history, local pause/error state, and world/owner/avatar-scoped atomic JSON storage. Pending turns and accepted tool batches survive restart, and unacknowledged effects keep stable request IDs for authoritative server deduplication.
- Added the visibly labeled deterministic simulator. Its component scenarios cover diamond collection/delivery programs, progress without replacement, interruption with inactive gathering and resumption of its existing program, task outcomes, memory, and delegation requests. A new provider instance is used for each fixture call to prove that context is explicit.
- The first full QA run hit an intermittent timeout in the existing nested-recovery fixture. Its diamond search could overlap concurrent fixtures, and its floorless delivery recipient was exactly at the reach cutoff. The fixture now selects a unique item, keeps a reach margin, and asserts exactly three recovered items reach the recipient. Two subsequent suites passed with this correction.
- Validation: final `./scripts/qa.sh` passed 25 JUnit tests and all 17 required actual Minecraft GameTests, producing the mod JAR with no QA classes/fixtures included. The new decision-loop tests use virtual time and stubbed service replies; they do not verify a real client connection, in-game chat routing, account UI, or live AI. No graphical client or sign-in browser was launched.
- Next: connect authoritative snapshots/event journals, chat/delegation, observation receipts, server deduplication, Forge packets, the client runtime/storage, and disconnected account-status controls. [HARNESS.md](HARNESS.md) documents the current core and integration obligations. Broader gameplay/lifecycle/capacity requirements remain open; the foundation goal is not complete.

## Cycle 6: Authoritative observations and actual-world harness integration

- Added a persistent world UUID, server snapshots of native physical state/equipment/nearby entities/programs, bounded wake journals, native damage/pickup hooks, authorized name mentions, and terminal program events. Outgoing responses remain history-only so they cannot wake their own loop.
- Added a server-thread control service with live owner/session checks, instruction revocation checks, owner-only delegation administration, persistent request fingerprints/acknowledgments, and independent incremental inventory queries. Actual packets/client runtime remain pending; the transport must supply the real connected sender.
- Observation receipts persist with the avatar. Programs bind named results from those server-owned receipts; invented receipt IDs and client-provided initial results do not supply inventory data. Actual transfers re-read changed contents and preserve partial results.
- Added a headless in-process bridge from the real simulated harness to Minecraft. An owner mention escalates, searches real chests, physically collects/delivers twelve diamonds, answers progress without replacing the request, marks its task complete, and stays idle across multiple decision intervals. The actor retains three pre-existing diamonds and walks more than twenty blocks. The clock is explicitly virtual and no TCP/client/sign-in/live model use is claimed.
- Added service authority, NBT receipt recovery, query disconnect/retry, frozen observation/changing-source, native health/equipment snapshot, and native damage-event assertions. An initial new damage assertion ran during normal spawn protection; it was moved into the established delayed survival test.
- Validation: `./scripts/qa.sh` passed 26 JUnit tests and all 20 required Minecraft GameTests. `./scripts/restart-qa.sh` passed writer JVM 85566 and reader JVM 85981, retaining world identity, a chat acknowledgment without repeating its effect, and the pending real inventory transfer. The 160,847-byte mod JAR excludes QA classes/fixtures. QA events were collected into the ignored local session. No graphical client was launched.
- Next: Forge networking, client runtime/local persistence, disconnected account/status controls, controller liveness, then the remaining gameplay/reflex/lifecycle/capacity work. The goal remains active and incomplete.

## Cycle 7: Forge client runtime, packets, controls, and controller liveness

- Added direction-specific Forge packets with bounded gzip/UTF-8 fragmentation, native-connection sender checks, controller generations, snapshot revisions, and transport-attempt IDs that reject late replies while preserving logical request deduplication. Snapshot revisions remain monotonic when the same stalled client reacquires an expired lease.
- Added the Minecraft-independent client coordinator and Forge client tick/logout adapter. The owner client now polls/saves local memory, runs the labeled simulator, reconstructs known unfinished server work when local memory is absent, preserves corrupt files, and pauses/releases failed control. A server heartbeat timeout independently stops jobs and revokes leases.
- Added explicit K-key/Mods-menu companion controls, disconnected account status, per-companion model/effort settings, spawn, pause/resume, destruction with drops, status/tasks/personality, and harness reconnect. No screen opens automatically. UI and raw server pause/resume stay consistent. Client memory uses the game directory's `paracraft/memory`.
- Added headless native-payload codec integration for the actual coordinator: large valid item metadata forces fragmentation; real gathering is set inactive for another travel request and resumes the same program before delivery/idle. Native-sender authority and missing-controller expiry also run against actual Minecraft jobs.
- QA exposed two fixture issues: a stress tag exceeded native NBT string size, and normal random terrain generated a shipwreck chest above an arena within its legitimate nine-chunk search. The metadata now uses separate valid tags. QA-only server properties now create seed-zero flat worlds without structures, with the actual running generator/seed asserted. Production/development worlds are unaffected. Two full suites passed after the world fix.
- Validation: final `./scripts/qa.sh` passed 37 JUnit tests and all 23 required Minecraft GameTests. Fresh-process restart checks passed writer JVM 5465 and reader JVM 5695 under the corrected QA settings. The 208,881-byte JAR excludes QA classes/fixtures.
- Native TCP login/signed chat, graphical screens/rendering, and actual client logout remain owner-check items in [QA.md](QA.md); the integration bridge uses native payload codecs with in-process endpoint delivery and virtual time. No graphical client, browser, sign-in, or live AI was used. [NETWORK.md](NETWORK.md) documents the implemented runtime.
- Next: remaining gameplay operations, survival/reflex/death/bed handling, normal avatar commands/dimension travel, broader navigation/compatibility, and capacity checks. The full foundation goal remains active.
