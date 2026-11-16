# Paracraft

Server-owned Minecraft companions with a client-owned decision harness. Initial target: Forge 1.20.1, Java 17.

The first foundation pass is ready for owner QA with the labeled simulated provider. Start with [installation/configuration](docs/INSTALL.md), [validation evidence](docs/VALIDATION.md), and [background QA controls](docs/BACKGROUND.md). Authentication/live AI and native client visual/login checks remain follow-up work.

See [the design](docs/PLAN.md), [cycle progress](docs/PROGRESS.md), [action programs](docs/PROGRAMS.md), [gameplay actions](docs/GAMEPLAY.md), [survival and respawn](docs/SURVIVAL.md), [server behavior](docs/BEHAVIOR.md), [native commands](docs/COMMANDS.md), [decision harness](docs/HARNESS.md), [client runtime](docs/NETWORK.md), and [QA instructions](docs/QA.md).

## Build

With a Java 17 JDK selected through `JAVA_HOME`:

```sh
./gradlew build
```

The reobfuscated mod is written to `build/libs/paracraft-forge-1.20.1-0.1.0-dev.jar`. Install it on Forge 47.4.28 or newer within the 47.x series, on both client and server. Java 17 is required to build and run this Minecraft version.

In a disposable world, press **K** for companion controls or run `/paracraft spawn Josh`, then mention Josh in chat. The initial provider is a labeled deterministic simulator. Try `Josh, collect the diamonds nearby and give them to me.` Model routes and local pause state are saved in the game directory; no credentials are required.

Nearby [block queries and explicit Nether/End portals](docs/PORTALS.md) are available through programs and finite simulator instructions. Normal travel does not automatically enter portals.

[Physical navigation](docs/NAVIGATION.md) includes independent native chunk tracking, basic swimming/ladder/door movement, and local obstacle/bridge/pillar recovery with carried tools and materials.

[Container actions](docs/CONTAINERS.md) support broad collection/delivery and explicit open/take/deposit programs through vanilla inventories and sided Forge item capabilities. Admission at four saved avatars per owner and 64 active per server, plus configurable limits, has native QA coverage.

## Headless verification

```sh
./scripts/qa.sh
```

This runs ordinary tests, builds the mod, and starts a windowless Forge GameTest server in a disposable development world. It does not start a graphical Minecraft client or open authentication browsers. Test-only classes/resources belong to `src/gameTest` and are excluded from the distributable JAR.

For an immediate-return background run on macOS/Linux:

```sh
python3 scripts/qa-background.py start
python3 scripts/qa-background.py status
python3 scripts/qa-background.py stop
```

The suite exits when complete; stop applies only while it is running. See [background instructions](docs/BACKGROUND.md) for logs and the separate restart mode. Final checks passed 46 unit tests, 86 native GameTests, and the two-process recovery suite. Real sign-in, live AI, and visual client behavior have not been verified.

Sources: [Forge setup](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/), [Forge GameTests](https://docs.minecraftforge.net/en/1.20.1/misc/gametest/). The Gradle wrapper was taken from the official Forge MDK; its script retains the Gradle Apache-2.0 notice.
