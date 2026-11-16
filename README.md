# Paracraft

Server-owned Minecraft companions with a client-owned decision harness. Initial target: Forge 1.20.1, Java 17.

Implementation is in progress. See [the design](docs/PLAN.md), [cycle progress](docs/PROGRESS.md), [action programs](docs/PROGRAMS.md), [gameplay actions](docs/GAMEPLAY.md), [survival and respawn](docs/SURVIVAL.md), [decision harness](docs/HARNESS.md), [client runtime](docs/NETWORK.md), and [QA instructions](docs/QA.md). The mod now wires the simulated client harness, Forge packets, controls screen, native eating, and bed/death/respawn lifecycle. Authentication and live AI usage are not connected; client rendering/login checks await manual verification.

## Build

With a Java 17 JDK selected through `JAVA_HOME`:

```sh
./gradlew build
```

The reobfuscated mod is written to `build/libs/paracraft-forge-1.20.1-0.1.0-dev.jar`. Install it on Forge 47.4.28 or newer within the 47.x series, on both client and server. Java 17 is required to build and run this Minecraft version.

In a disposable world, press **K** for companion controls or run `/paracraft spawn Josh`, then mention Josh in chat. The initial provider is a labeled deterministic simulator. Try `Josh, collect the diamonds nearby and give them to me.` Model routes and local pause state are saved in the game directory; no credentials are required. Broader gameplay remains in progress.

## Headless verification

```sh
./scripts/qa.sh
```

This runs ordinary tests, builds the mod, and starts a windowless Forge GameTest server in a disposable development world. It does not start a graphical Minecraft client or open authentication browsers. Test-only classes/resources belong to `src/gameTest` and are excluded from the distributable JAR.

Sources: [Forge setup](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/), [Forge GameTests](https://docs.minecraftforge.net/en/1.20.1/misc/gametest/). The Gradle wrapper was taken from the official Forge MDK; its script retains the Gradle Apache-2.0 notice.
