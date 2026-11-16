# Foundation QA

The default QA entry point is windowless. No client is launched automatically, and no authentication or real model usage is exercised yet.

## Run

Select a Java 17 JDK using `JAVA_HOME`, then run from the repository root:

```sh
./scripts/qa.sh
```

The command runs `test`, `build`, and `runGameTestServer`. Its exit code is nonzero if the build or required GameTests fail. Runtime logs are in `run/gametest/logs/latest.log`; the combined output is captured in `build/qa/headless.log`. Each headless run deletes and rebuilds only its disposable `run/gametest/world`, preventing stale drops/avatars from earlier failures. Keep personal worlds outside that directory. The ordinary development server uses the separate `run/development` workspace.

The GameTest server terminates on completion. To interrupt a foreground run, use Ctrl-C and allow Gradle to terminate the child JVM. No persistent background server is created by this script. A retained interactive QA server will be added with the gameplay scenario.

## Implemented scenarios

`chestCapabilityUsesRealWorld` places a real chest in the test world, inserts seven diamonds through its Forge item capability, extracts three, and checks that four remain. Expected outcome: the test passes and emits `QA_EVT` with event `real_chest_capability`.

The three avatar GameTests additionally verify survival ticking/damage, save/despawn/restore with stack metadata and menu cursor items preserved, compressed-NBT serialization, control-session rotation, and destroying a saved avatar with exactly one set of world item drops. The JUnit test checks that trusted-player instruction rights never grant the owner's control lease and that reconnect rejects an earlier lease.

The inventory-job suite additionally covers:

- Walking between two chests, collecting twelve diamonds, delivering only those twelve to a recipient's feet, and retaining three pre-existing diamonds. Assertions measure actual per-tick movement and total traveled distance, world item entities/recipient inventory, and server chunk tracking.
- Exactly nine searched chunks, exclusion of a tenth chunk's contents, double-chest deduplication, and stable timestamps after a search completes.
- Furnace output-side extraction and refusal to remove source items when the avatar is full.
- A Forge interaction hook denying access without modifying protected contents.
- Pausing and resuming the same travel job.
- A retried old collection request acknowledging its existing result after a newer job, without extracting twice or replacing the newer job.
- Native command-tree serialization/deserialization including Paracraft's custom argument type.

The program suite executes search-to-collection-to-delivery through named results, nested partial-result recovery, interrupt/append/explicit-resume, cancellation bypassing catches, syntax rejection before mutation, and reconnect with a pending collection preserved through NBT. The separate restart suite verifies pending transfers, native inventory/chest state, stable request IDs, and stale-session rejection across two real Forge JVMs.

Real client login/logout, rendering, automatic death respawn, the client decision loop, and the chat-to-Light-to-planning diamond workflow remain unverified or incomplete. Server scenarios submit through the same execution service exposed by manual controls. See [PROGRAMS.md](PROGRAMS.md) for program syntax.

## Current manual controls

The following player commands exist in the foundation build:

```text
/paracraft spawn Josh
/paracraft list
/paracraft status Josh
/paracraft pause Josh
/paracraft resume Josh
/paracraft trust Josh <online-player>
/paracraft untrust Josh <online-player>
/paracraft destroy Josh
/paracraft search Josh minecraft:diamond
/paracraft collect Josh minecraft:diamond
/paracraft travel Josh 100 64 100
/paracraft deposit Josh 100 64 100 minecraft:diamond 12
/paracraft deliver Josh <online-player> minecraft:diamond
/paracraft job Josh
/paracraft cancel Josh
/paracraft program Josh {"steps":[{"op":"wait_ticks","ticks":20},{"op":"report_result"}]}
```

Names use Minecraft player-name syntax and must be unique among saved companions and connected players. A companion UUID can replace its name in commands other than spawn. Only the owner can use these management operations. Trust data is persisted for the upcoming chat harness; it does not give another player management control now. Pausing revokes the control lease while survival ticking continues.

For a later manual session: spawn Josh, inspect status/list output, disconnect/reconnect to check the saved location, and destroy Josh to inspect inventory drops. Native player-info packets are sent, but appearance and animations remain unverified until a client session is deliberately tested. Automatic bed selection and death respawn remain incomplete; use a disposable world when exploring this prototype.

To inspect physical inventory work in a later manual session:

1. Put diamonds in nearby chests and spawn Josh on safe ground.
2. Run `/paracraft search Josh minecraft:diamond`, then `/paracraft job Josh`. Expect fixed-scope positions, counts, coverage, and observation timestamps.
3. Run `/paracraft collect Josh minecraft:diamond`. Expect physical travel and collection, with progress visible through `/paracraft job Josh`.
4. Use pause/resume during a trip; expect movement to stop and the same job to continue on resume. An explicit new manual job replaces the previous one; reading status does not.
5. Run the deliver command to drop all currently carried matching items at that player's feet, or deposit a quantity in a chosen chest. The lower-level delivery API also accepts a precise collection ledger, as exercised by the automated scenario.
6. Cancel an operation or disconnect. Expect current movement to stop and inventory to be conserved. Disconnect saves pending execution and restores it paused on reconnect; explicitly resume it using its request ID or the resume command. Cancelling is terminal and never runs a catch.

Return `run/development/logs/latest.log` and notes/screenshots for visual behavior. Do not infer UI/rendering success from the headless checks. To regenerate original NBT fixtures after editing their dimensions, run `python3 scripts/generate-test-templates.py`; the empty fixture has no floor and the arena's template floor sits one block above its structure-block origin.

## Fresh-process recovery QA

With Java 17 selected, run `./scripts/restart-qa.sh`. It deletes only its generated `run/restart-qa` workspace, starts one headless writer process, waits for its actual completion, then starts a different reader process against the saved world. It does not reset the world between those two processes.

The writer stops a program after collecting three of six diamonds but before depositing them. The reader must load the avatar/program from world storage, observe three diamonds remaining in the source and three carried, reject the old lease, acknowledge the same request ID, then resume to leave three in the source, three in the destination, and none carried. The marker file stores expected identifiers and the first process ID; it is not used as replacement inventory or program state.

Expected events: `cold_restart_write` and `cold_restart_read`, each with pass status and different process IDs. A zero-test run cannot satisfy the script. Logs to return are `build/qa/restart/write.log` and `build/qa/restart/read.log`; the native second-process log is `run/restart-qa/logs/latest.log`.

## Visual checks

Client rendering, screens, animations, and any focus-preserving graphical launch remain unverified. Do not infer their success from headless results. Once the client features exist, the manual checklist will describe the exact controls, expected outcomes, and logs to return.

## Local development runtime

The initial development run uses a verified Temurin 17.0.21+9 ARM64 JDK cached at `~/.cache/paracraft/java17/jdk-17.0.21+9/Contents/Home`. That path is local tooling, not a requirement for users. Any compatible Java 17 JDK can be selected instead. No credentials or external account sessions are needed for these tests.
