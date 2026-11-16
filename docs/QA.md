# Foundation QA

The default QA entry point is windowless. No client is launched automatically, and no authentication or real model usage is exercised yet.

## Run

Select a Java 17 JDK using `JAVA_HOME`, then run from the repository root:

```sh
./scripts/qa.sh
```

The command runs `test`, `build`, and `runGameTestServer`. Its exit code is nonzero if the build or required GameTests fail. Runtime logs are in `run/gametest/logs/latest.log`; the combined output is captured in `build/qa/headless.log`. Each headless run deletes and rebuilds its disposable `run/gametest/world` and writes QA-only `server.properties` for seed zero, flat terrain, and no generated structures. The running generator/seed are asserted by a GameTest. This matters because searches include every height in the nine chunks; normal random terrain can otherwise add unrelated loot chests above an arena. The QA-only server-start adapter also sets the actual minimal GameTest player list's view/simulation distances to eight, with runtime assertions, and enables native PvP for player-interaction tests. Its zero distance defaults otherwise leave targets outside the template's forced chunks frozen even though properties specify a distance. Keep personal worlds outside that directory. The ordinary development server uses the separate `run/development` workspace.

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

Real client login/logout, rendering, and the in-game client runtime still require native client verification. Server scenarios submit through the same execution service exposed by manual controls. See [PROGRAMS.md](PROGRAMS.md) for program syntax.

The decision-loop core also has deterministic JUnit scenarios with a virtual clock and stubbed server replies. They check five-second routing/continuations, slow-response serialization, forbidden Light tools, session cancellation, coalescing, event/timer waits, on-disk memory, durable pending turns and request IDs, progress without replacement, task interruption/resumption, and returning to idle. See [HARNESS.md](HARNESS.md) for the boundary and scripted grammar.

The harness GameTests additionally exercise:

- A service-level owner mention through Light, planning, real inventory observation, physical collection/delivery, in-flight progress, task completion, and idle suppression. Exactly twelve new diamonds are delivered while three pre-existing diamonds remain carried. `QA_EVT` identifies the virtual clock, in-process transport, and absence of live AI usage.
- Native damage events and snapshots of native health/equipment durability; unauthorized mentions, owner delegation, revoked late results, trusted administration denial, and control-lease separation.
- Request receipt content conflicts, repeat acknowledgment after control NBT reload/session rotation, and persistent world binding.
- Disconnect during observation, retry under a new lease, immutable completed observation receipts after reconnect, rejection of invented receipts, and actual partial collection when a five-item search becomes a three-item source.

These checks use actual Minecraft state through an in-process client/service bridge. Signed client chat, native TCP delivery, client UI, and live model usage are not exercised.

The client transport suite additionally runs the actual coordinator with local JSON files through native Minecraft custom-payload constructors and Forge buffer codecs. It forces multi-fragment snapshots with valid large item metadata, asks for progress, marks gathering inactive for a real travel program, resumes the same gathering request, delivers the items, and remains idle across heartbeat polls. It verifies native-sender authority and controller expiry after a missed heartbeat. Its clock is virtual and its endpoint delivery is in-process; it does not simulate a real TCP login.

The gameplay suite uses actual Minecraft operations for:

- Single/list/cuboid mining, effective tool selection and wear, real drop pickup/ledger delivery, and disconnect/NBT/body restoration between blocks.
- Pause/resume/cancel with the Forge start/abort lifecycle and no delayed destruction; wrong tool tiers, canceled left clicks/breaks, and full inventories preserving world drops.
- Death state raised inside a native break hook canceling the program before catches or later instructions.
- Placement cursor restoration and native canceled-place rollback of world and item changes.
- Mature-crop harvest/pickup/replant with actual seed conservation and skipped immature crops.
- Native armor/offhand slots, invalid-slot rejection, binding curses, already-equipped acknowledgment, and protected attacks.
- Following a moved entity, native melee cooldown/damage/durability, and bare-hand combat.

See [GAMEPLAY.md](GAMEPLAY.md) for program examples and current adapter limits. These checks run headlessly; no visual mining animation, client control, or arbitrary modpack compatibility is inferred from them.

The survival suite adds actual native death/drop/vanishing/experience checks, repeated-death protection, cancellation of current/parked/queued work, normal bed respawn, keep-inventory and broken-bed fallback, saved dead-body restoration, Forge-cancelled rescue, clone/respawn hooks, native brief sleeping/occupancy, initial daytime bed setting/persistence, spawn-hook denial, food use/remainder, rejected food use, disconnect during eating, and owner pause surviving eating completion. The client harness also verifies that a saved unfinished old-body decision is discarded on respawn while tasks/request context remain. See [SURVIVAL.md](SURVIVAL.md) for behavior and verification limits.

The reflex suite exercises an actual roofed water pool with a side exit, sealed-pool search failure and retry spacing, native water-breathing effects, neutral retaliation, passive stop/explicit resume, player fleeing, separate aggressive categories and authority exclusions, and canceled native attacks leaving ordinary work paused. It also connects an actual simulated harness to behavior changes and a repeated physical idle patrol: routine arrivals consume no inference, ordinary travel interrupts it, reconnect preserves progress, and owner pause stops movement. See [BEHAVIOR.md](BEHAVIOR.md) for exact configuration and limits.

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
/paracraft behavior Josh
/paracraft behavior Josh {"players":"flee","mobs":"neutral"}
/paracraft program Josh {"steps":[{"op":"wait_ticks","ticks":20},{"op":"report_result"}]}
```

Names use Minecraft player-name syntax and must be unique among saved companions and connected players. A companion UUID can replace its name in commands other than spawn. Only the owner can use these management operations. Trusted players may instruct through chat but do not gain management control. Pausing revokes the control lease while survival ticking continues.

For a later manual session: spawn Josh, inspect status/list output, disconnect/reconnect to check the saved location, and destroy Josh to inspect inventory drops. Native player-info packets are sent, but appearance and animations remain unverified until a client session is deliberately tested. Automatic initial bed selection and death respawn run on the server; use a disposable world when exploring the prototype.

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

The writer stops a program after collecting three of six diamonds but before depositing them. The reader must load the avatar/program from world storage, observe three diamonds remaining in the source and three carried, reject the old lease, acknowledge the same request ID, then resume to leave three in the source, three in the destination, and none carried. It also verifies the persisted world UUID and a control-service chat receipt, acknowledging that receipt under the fresh lease without emitting another response event. Nondefault player/mob reactions and idle settings must already exist in loaded data before body reconstruction. The marker file stores expected identifiers and the first process ID; it is not used as replacement inventory, receipt, behavior, or program state.

Expected events: `cold_restart_write` and `cold_restart_read`, each with pass status and different process IDs. A zero-test run cannot satisfy the script. Logs to return are `build/qa/restart/write.log` and `build/qa/restart/read.log`; the native second-process log is `run/restart-qa/logs/latest.log`.

## Visual checks

Client rendering, screens, animations, actual TCP/signed chat, and client login/logout remain unverified. No verified focus-preserving graphical-client launcher has been set up, so automated checks have remained headless. No window or sign-in browser is launched by the QA scripts.

For an owner-initiated graphical session:

1. Build/install the JAR on Forge 47.4.28 clients and server, or start your development client yourself with `./gradlew runClient`. Use a disposable flat world. This command opens Minecraft; it is not part of automated QA.
2. Press **K**, or use Mods → Paracraft → Config. Expect an explicit simulated-provider banner and disconnected account status. Inspect layout at your preferred GUI scale. The screen should not pause world simulation.
3. Spawn Josh through the screen or `/paracraft spawn Josh`. Check visible player appearance, movement, and status. No authentication should be requested.
4. Place five and seven diamonds in two reachable nearby chests within Josh's current nine-chunk neighborhood. Say `Josh, collect all the diamonds nearby and give them to me.` Allow the five-second route/observation cadence. Expect physical collection and delivery with simulated chat labels.
5. During walking, say `Josh, progress?`; expect a reply without restarting collection. Then ask `Josh, travel to X Y Z` using a reachable nearby floor position. Expect the gathering task to become inactive while travel runs, then resume the existing work. Confirm twelve collected items arrive and task status becomes completed.
6. Click Pause, then resume through either the screen or `/paracraft resume Josh`. Expect execution to stop/continue. Edit model/effort labels through Model routes, save, and reconnect to confirm persistence. These labels do not exercise real model access.
7. From a second client, mention Josh before/after the owner grants trust, then revoke trust. Expect only authorized instructions to wake the owner's harness; the other client should not gain owner controls or memory.
8. Disconnect while Josh is traveling. Expect the body to disappear, then restore at its saved location on reconnect with inventory/task state retained. Confirm it is not moved to the owner's new position and completed transfers are not repeated.
9. Destroy through the owner controls in a disposable scenario and inspect carried/equipped item drops. Check that the body does not return on reconnect.
10. In a separate disposable scenario, create an available bed near a newly spawned companion. Expect an initial attempt to set spawn; daytime can set spawn without sleep. Use a `set_spawn` program to select another bed and inspect the reported result. Check occupied/obstructed beds as well as visible sleeping/waking.
11. Supply food, let hunger fall, and expect actual eating without inference controlling every tick. Pause ordinary work during eating and confirm eating completion does not resume it. Then deliberately cause a normal death: check drops, bed/fallback respawn, interrupted work, and the new body. Repeat with your server's keep-inventory rules. Do not infer client animations from the automated server tests.
12. Inspect `/paracraft behavior Josh`, then say `Josh, behavior players passive`. Expect Light escalation and a persisted category change visible in the screen/snapshot. Use a disposable scenario to compare passive, flee, and neutral responses; your server's PvP/claim rules still apply.
13. Configure an idle patrol using the example in [BEHAVIOR.md](BEHAVIOR.md), replacing coordinates with your actual floor positions. Expect repeated movement without growing inference counts from routine arrivals. Interrupt it with work, pause, and reconnect. Test a safe pool with a surface/side exit to observe oxygen escape; sealed pools can fail and cause death normally.

Return the client and server `logs/latest.log` files, screenshots at problematic GUI scales, and a note for each failed step. Default development-client logs are `run/development/logs/latest.log`; local cognitive files are under that game directory's `paracraft/memory`. Never include credentials. Native client observations have not been supplied yet; passing headless tests does not substitute for these checks.

## Local development runtime

The initial development run uses a verified Temurin 17.0.21+9 ARM64 JDK cached at `~/.cache/paracraft/java17/jdk-17.0.21+9/Contents/Home`. That path is local tooling, not a requirement for users. Any compatible Java 17 JDK can be selected instead. No credentials or external account sessions are needed for these tests.
