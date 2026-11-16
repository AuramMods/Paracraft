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
| Lifecycle | Real client login/logout verification, restart restoration, death/bed respawn, command interruption and item-drop edge cases |
| Observations | Nine-chunk search, coverage/freshness, inventory adapters, snapshots, events |
| Execution | Navigation, real collection/deposit/delivery, nested recovery interpreter, IDs, checkpoints, safe cancellation |
| Harness | Client transport, simulated provider, Light/planning routes, cadence, tasks, waits, memory/personality/history |
| Authority | Owner/trusted chat instructions, delegation, normal player command execution and delayed outcomes |
| Gameplay | Mining, farming, combat/reflexes, equipment, placement, chunk traversal, explicit dimension travel, idle routines |
| QA | End-to-end diamond retrieval, progress/interruption/resumption, edge cases, capacity evidence, client checklist |
| Account UI | Clearly disconnected status and extension point for later sign-in/live inference |

Live authentication and actual AI usage are outside this foundation goal and remain unverified.

## Cycle 2: Persistent survival-player prototype

- Added a distinct-profile `ServerPlayer` actor with a local packet listener and normal player tick/survival paths, avoiding Forge FakePlayer's invulnerability and disabled ticking.
- Added owner-only raw lifecycle commands, instruction delegation data, revocable control sessions, saved physical NBT, periodic checkpoints, disconnect removal, reconnect restoration, and offline destruction drops.
- Container closure occurs before saving/destroying so items carried on a menu cursor are returned/accounted for.
- `./scripts/qa.sh` passed: one JUnit authority test and all four required Forge GameTests. The world tests cover real chest capability mutation, actor damage/ticking, disconnect/restore with 14 metadata-bearing diamonds including menu cursor items, and destruction of a saved avatar dropping nine real diamonds exactly once. Saved-state serialization is also round-tripped through compressed NBT.
- These tests invoke the same manager paths used by login/logout events, but do not simulate real client connections or prove client rendering. The current prototype is registered in its level rather than the server's global PlayerList; compatibility with commands/mods requiring that list remains to be addressed. Automatic death respawn, bed seeking, navigation, reflexes, and action execution are not implemented yet.
