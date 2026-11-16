# Paracraft

Server-owned Minecraft companions with a client-owned decision harness. Initial target: Forge 1.20.1, Java 17.

Implementation is in progress. See [the design](docs/PLAN.md), [cycle progress](docs/PROGRESS.md), [action programs](docs/PROGRAMS.md), and [QA instructions](docs/QA.md). Authentication and live AI usage are not connected; the planned foundation provider is explicitly simulated.

## Build

With a Java 17 JDK selected through `JAVA_HOME`:

```sh
./gradlew build
```

The reobfuscated mod is written to `build/libs/paracraft-forge-1.20.1-0.1.0-dev.jar`. Install it on Forge 47.4.28 or newer within the 47.x series, on both client and server. Java 17 is required to build and run this Minecraft version.

## Headless verification

```sh
./scripts/qa.sh
```

This runs ordinary tests, builds the mod, and starts a windowless Forge GameTest server in a disposable development world. It does not start a graphical Minecraft client or open authentication browsers. Test-only classes/resources belong to `src/gameTest` and are excluded from the distributable JAR.

Sources: [Forge setup](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/), [Forge GameTests](https://docs.minecraftforge.net/en/1.20.1/misc/gametest/). The Gradle wrapper was taken from the official Forge MDK; its script retains the Gradle Apache-2.0 notice.
