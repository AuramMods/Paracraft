# Foundation QA

The default QA entry point is windowless. No client is launched automatically, and no authentication or real model usage is exercised yet.

## Run

Select a Java 17 JDK using `JAVA_HOME`, then run from the repository root:

```sh
./scripts/qa.sh
```

The command runs `test`, `build`, and `runGameTestServer`. Its exit code is nonzero if the build or required GameTests fail. Runtime logs are in `run/gametest/logs/latest.log`; the combined output is captured in `build/qa/headless.log`. The disposable test world is inside `run/gametest`.

The GameTest server terminates on completion. To interrupt a foreground run, use Ctrl-C and allow Gradle to terminate the child JVM. No persistent background server is created by this script. A retained interactive QA server will be added with the gameplay scenario.

## Implemented scenarios

`chestCapabilityUsesRealWorld` places a real chest in the test world, inserts seven diamonds through its Forge item capability, extracts three, and checks that four remain. Expected outcome: the test passes and emits `QA_EVT` with event `real_chest_capability`.

The three avatar GameTests additionally verify survival ticking/damage, save/despawn/restore with stack metadata and menu cursor items preserved, compressed-NBT serialization, control-session rotation, and destroying a saved avatar with exactly one set of world item drops. The JUnit test checks that trusted-player instruction rights never grant the owner's control lease and that reconnect rejects an earlier lease.

Current automated result: one JUnit test and four required GameTests passed. This verifies service paths and actual world entities/items; real network login/logout, client rendering, automatic death respawn, model routing, and diamond collection/delivery are not yet verified or implemented as complete features.

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
```

Names use Minecraft player-name syntax and must be unique among saved companions and connected players. A companion UUID can replace its name in commands other than spawn. Only the owner can use these management operations. Trust data is persisted for the upcoming chat harness; it does not give another player management control now. Pausing revokes the control lease while survival ticking continues.

For a later manual session: spawn Josh, inspect status/list output, disconnect/reconnect to check the saved location, and destroy Josh to inspect inventory drops. Native player-info packets are sent, but appearance and animations remain unverified until a client session is deliberately tested. Automatic bed selection and death respawn remain incomplete; use a disposable world when exploring this prototype.

## Visual checks

Client rendering, screens, animations, and any focus-preserving graphical launch remain unverified. Do not infer their success from headless results. Once the client features exist, the manual checklist will describe the exact controls, expected outcomes, and logs to return.

## Local development runtime

The initial development run uses a verified Temurin 17.0.21+9 ARM64 JDK cached at `~/.cache/paracraft/java17/jdk-17.0.21+9/Contents/Home`. That path is local tooling, not a requirement for users. Any compatible Java 17 JDK can be selected instead. No credentials or external account sessions are needed for these tests.
