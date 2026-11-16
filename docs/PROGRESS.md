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
| Avatar | Simulated player prototype, ownership, rendering, inventory/equipment, survival, spawn/destroy/pause/resume |
| Lifecycle | Save/despawn, reconnect restore, death/bed respawn, control-session invalidation, exactly-once item drops |
| Observations | Nine-chunk search, coverage/freshness, inventory adapters, snapshots, events |
| Execution | Navigation, real collection/deposit/delivery, nested recovery interpreter, IDs, checkpoints, safe cancellation |
| Harness | Client transport, simulated provider, Light/planning routes, cadence, tasks, waits, memory/personality/history |
| Authority | Owner/trusted chat instructions, delegation, normal player command execution and delayed outcomes |
| Gameplay | Mining, farming, combat/reflexes, equipment, placement, chunk traversal, explicit dimension travel, idle routines |
| QA | End-to-end diamond retrieval, progress/interruption/resumption, edge cases, capacity evidence, client checklist |
| Account UI | Clearly disconnected status and extension point for later sign-in/live inference |

Live authentication and actual AI usage are outside this foundation goal and remain unverified.
