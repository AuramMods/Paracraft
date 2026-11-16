# Foundation QA

The default QA entry point is windowless. No client is launched automatically, and no authentication or real model usage is exercised yet.

## Run

Select a Java 17 JDK using `JAVA_HOME`, then run from the repository root:

```sh
./scripts/qa.sh
```

The command runs `test`, `build`, and `runGameTestServer`. Its exit code is nonzero if the build or required GameTests fail. Runtime logs are in `run/gametest/logs/latest.log`; the combined output is captured in `build/qa/headless.log`. The disposable test world is inside `run/gametest`.

The GameTest server terminates on completion. To interrupt a foreground run, use Ctrl-C and allow Gradle to terminate the child JVM. No persistent background server is created by this script. A retained interactive QA server will be added with the gameplay scenario.

## Implemented scenario

`chestCapabilityUsesRealWorld` places a real chest in the test world, inserts seven diamonds through its Forge item capability, extracts three, and checks that four remain. Expected outcome: the test passes and emits `QA_EVT` with event `real_chest_capability`.

This validates the Forge runner and real inventory API, not an avatar transfer. Companion behavior, model routing, and the complete diamond-delivery scenario are upcoming implementation cycles.

## Visual checks

Client rendering, screens, animations, and any focus-preserving graphical launch remain unverified. Do not infer their success from headless results. Once the client features exist, the manual checklist will describe the exact controls, expected outcomes, and logs to return.

## Local development runtime

The initial development run uses a verified Temurin 17.0.21+9 ARM64 JDK cached at `~/.cache/paracraft/java17/jdk-17.0.21+9/Contents/Home`. That path is local tooling, not a requirement for users. Any compatible Java 17 JDK can be selected instead. No credentials or external account sessions are needed for these tests.
