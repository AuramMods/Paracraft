# Paracraft

Server-owned Minecraft companions with a client-owned decision harness. Initial target: Forge 1.20.1, Java 17.

The Forge foundation includes a labeled simulator and client-side ChatGPT sign-in/live provider. **[Link your account](docs/AUTH.md)** after joining with `/paracraft link chatgpt` or **K → Accounts**. Unlinked players receive clickable join and avatar-creation reminders. The session is cached privately on your computer; `./gradlew chatgptLogin` is optional developer tooling. Real Luna inference and headless diamond retrieval have passed; native client visual checks remain pending.

Start with [installation/configuration](docs/INSTALL.md), [foundation validation evidence](docs/VALIDATION.md), and [background QA controls](docs/BACKGROUND.md).

See [the design](docs/PLAN.md), [cycle progress](docs/PROGRESS.md), [action programs](docs/PROGRAMS.md), [gameplay actions](docs/GAMEPLAY.md), [survival and respawn](docs/SURVIVAL.md), [server behavior](docs/BEHAVIOR.md), [native commands](docs/COMMANDS.md), [decision harness](docs/HARNESS.md), [client runtime](docs/NETWORK.md), and [QA instructions](docs/QA.md).

## Build

With a Java 17 JDK selected through `JAVA_HOME`:

```sh
./gradlew build
```

The reobfuscated mod is written to `build/libs/paracraft-forge-1.20.1-0.1.0-dev.jar`. Install it on Forge 47.4.28 or newer within the 47.x series, on both client and server. Java 17 is required to build and run this Minecraft version.

In a disposable world, press **K** for companion controls or run `/paracraft spawn Josh`, then talk in chat. If exactly one avatar you may instruct is within 24 blocks in your dimension, you can omit its name. With multiple nearby authorized avatars, address one by name. Before linking, the provider is a labeled deterministic simulator with fixed phrases. After linking, new decisions use the actual account/model route. Try `collect the diamonds nearby and give them to me`, or explicitly `Josh, collect the diamonds nearby and give them to me.` Model routes and local pause state are saved in the game directory; the simulator needs no credentials.

The K menu shows [today's input/output tokens and Luna API cost estimate](docs/USAGE.md) for the selected local account. Usage details includes cache counts, pricing assumptions, and a Manage usage link for actual ChatGPT plan limits.

Linked agents can also [craft items](docs/CRAFTING.md) in their internal 2×2 grid or at an actual crafting table. For example: `Craft eight sticks with the planks you're carrying` or `Use that crafting table to make a wooden pickaxe.` The server handles recipes, ingredients, navigation, and native crafting interactions.

Nearby [block queries and explicit Nether/End portals](docs/PORTALS.md) are available through programs and finite simulator instructions. Normal travel does not automatically enter portals.

[Physical navigation](docs/NAVIGATION.md) walks or sprints through existing routes, opens doors and fence gates, and preserves builds by default. Automatic breaking/bridging/pillaring requires a server opt-in. Look directly at an avatar to see Idle, Thinking, Traveling, or other activity with its unfinished task count above its name. [Agent output debugging](docs/DEBUG.md) is enabled for Gradle launches.

[Ambient idling](docs/BEHAVIOR.md#ambient-idling-default-on) is on by default: brief looks, gentle pacing around a fixed sixteen-block resting area, nearby nighttime beds and food restocking. It runs entirely on the server without extra model decisions. Tell the agent `stop moving` to disable it or `resume idling` to turn it back on.

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

The suite exits when complete; stop applies only while it is running. See [background instructions](docs/BACKGROUND.md) for logs and the separate restart mode. The account integration adds local HTTP/OIDC/SSE fixture tests documented in [AUTH.md](docs/AUTH.md). [Live Luna QA](docs/LIVE_QA.md) explicitly uses the linked plan to test real gameplay in a separate disposable world; default QA remains simulated. Native client visuals and connected-player UI checks remain pending.

Sources: [Forge setup](https://docs.minecraftforge.net/en/1.20.1/gettingstarted/), [Forge GameTests](https://docs.minecraftforge.net/en/1.20.1/misc/gametest/). The Gradle wrapper was taken from the official Forge MDK; its script retains the Gradle Apache-2.0 notice.
