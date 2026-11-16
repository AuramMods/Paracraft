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
| Observations | Full client snapshots/events, block/entity queries, further inventory compatibility; basic nine-chunk inventory search is implemented |
| Execution | Following, additional gameplay operations and broader terrain recovery; structured programs and persisted execution/request results are implemented |
| Harness | Client transport, simulated provider, Light/planning routes, cadence, tasks, waits, memory/personality/history |
| Authority | Owner/trusted chat instructions, delegation, normal player command execution and delayed outcomes |
| Gameplay | Mining, farming, combat/reflexes, equipment, placement, chunk traversal, explicit dimension travel, idle routines |
| QA | Client-driven end-to-end diamond retrieval, task interruption/resumption, further edge cases, capacity evidence, client checklist |
| Account UI | Clearly disconnected status and extension point for later sign-in/live inference |

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
