# Install and configure the foundation

## Build and install

Select Java 17 with `JAVA_HOME` and run `./gradlew build` from this repository. The reobfuscated artifact is:

```text
build/libs/paracraft-forge-1.20.1-0.1.0-dev.jar
```

Put that JAR in the `mods` directory of the Forge 1.20.1 server and every connecting client. The declared dependency is Forge 47.4.28 or later in the 47.x series. This pass targets Forge 1.20.1; Fabric, NeoForge, other Minecraft versions, specialized storage integrations, and advanced construction/crafting are later work. Python and the QA scripts are developer tooling; players install the mod without a companion app.

Before linking an account, the provider is the explicitly labeled deterministic simulator and needs no credentials. Players can join first, then run `/paracraft link chatgpt` or open **K → Accounts**. Clickable reminders appear for unlinked players on join and avatar creation. [AUTH.md](AUTH.md) describes sign-in, cached/refreshable credentials, models, and live inference. Real owner-authenticated Luna inference and headless gameplay have passed; in-game screen checks remain pending. No API key is required for the documented ChatGPT plan flow.

## Server configuration and identity

Forge creates `<world>/serverconfig/paracraft-server.toml`:

```toml
avatarsPerOwner = 4
activeAvatarsPerServer = 64
```

The owner limit counts saved companions, including offline ones. The server limit counts active bodies. Everyone may spawn by default, within these limits. Pause retains the body and survival ticking. Owner disconnect saves/despawns it and releases tracking; reconnect restores at its saved location when capacity allows. Destroy permanently removes the saved avatar and drops its carried/equipped items once.

Avatars have distinct native player profiles/UUIDs, survival inventories, permissions, statistics, and command identities. They appear in the native online-player roster. Configure Minecraft's `max-players`, view distance, and simulation distance with companions in mind; native online entries affect player admission independently of Paracraft limits. No owner operator permission or separate Minecraft authentication is inherited. See [COMMANDS.md](COMMANDS.md).

## First scenario

Use a disposable world for the first pass:

1. Open companion controls with **K**, remappable in Minecraft Controls, or Mods → Paracraft → Config. The screen opens only on your explicit action and shows the simulated/disconnected status.
2. Spawn Josh through the screen or `/paracraft spawn Josh` on safe ground.
3. Put five and seven diamonds into two reachable nearby chests within Josh's current chunk or eight neighboring chunks, including diagonals.
4. Say `Josh, collect all the diamonds nearby and give them to me.` Allow the five-second inference-start cadence, including planning/observation continuations. Initial routing can take several intervals before physical work starts.
5. During walking, ask `Josh, progress?`. Expect a short simulated response with actual counts and the same job continuing. The snapshot remains available for detailed inspection; chat does not dump its inventory metadata.
6. Ask `Josh, travel to X Y Z` using reachable floor coordinates. Gathering becomes inactive while that request runs, then resumes its existing program. Expect delivery and completed task state, followed by no inference while idle.
7. Pause/resume, disconnect/reconnect, and inspect retained state. Destruction is a separate explicit control and drops belongings.

The simulator recognizes a finite grammar documented in [HARNESS.md](HARNESS.md). It does not understand arbitrary requests or claim live AI reasoning. Other supported domain operations are available through [structured programs](PROGRAMS.md).

Only the owner may instruct initially. `/paracraft trust Josh <online-player>` permits another player to send authorized mentions through the owner's harness. They receive neither controller ownership nor credentials. The planning simulator also supports trust/untrust by UUID. Native commands use Josh's permissions; home/TPA conveniences require the relevant command mod and permission on that server.

## Local state and account follow-up

Client cognitive files live under `<game directory>/paracraft/memory/<world UUID>/<owner UUID>/<avatar UUID>.json`. Routes, tasks, memory, personality, history, waits, and pending effects persist there. Server physical/execution state lives in the world's Paracraft saved data, with native player data maintained for compatibility. Back up the world and corresponding client directory together. Corrupt client files are retained for diagnosis and surfaced in the controls.

World knowledge is isolated by persistent world/owner/avatar binding. A reusable cross-server personality/profile library is not implemented in this pass; do not copy whole world-bound task files between unrelated worlds. Existing local memory is independent of future account credentials.

The client-side account/provider integration is implemented behind `InferenceProvider`, with protected local storage, refresh, cancellation, explicit snapshot/history reconstruction, and existing routing/tool restrictions. [LIVE_QA.md](LIVE_QA.md) records the opt-in windowless gameplay check using Luna with `none`/`xhigh`. Existing Codex account/token files are not read or reused; see [AUTH.md](AUTH.md).

For test commands and visual checks, see [QA.md](QA.md); for background execution and stopping, see [BACKGROUND.md](BACKGROUND.md); for the exact evidence and remaining limits, see [VALIDATION.md](VALIDATION.md).
