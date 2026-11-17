# Avatar status and agent output

Look directly at an avatar to see its activity above the normal player nameplate: Idle, Thinking, Waiting, Traveling, Crafting, Collecting items, Moving items, Mining, Farming, Fighting, Busy, Paused, or Disconnected. An unfinished task count appears when nonzero, such as `Traveling · 2 tasks`. Queued, active, inactive and waiting tasks count; completed/failed tasks do not. The server's current physical action takes precedence over client thinking, and a server pause/disconnection takes precedence over both.

The controlling client reports only aggregate phase/count with its regular two-second heartbeat. The server broadcasts changed labels to players tracking the avatar and supplies the current label when tracking begins. Other players receive no task goals, personality or durable memory in this status packet. F1, invisibility and a denied nameplate render event suppress the extra line. Actual client appearance remains a manual QA check.

## Output logging

For now all Gradle launch configurations enable server-side agent output debugging, including `runClient`, `runServer` and `runGameTestServer`. An integrated single-player server logs into the same launch console. A dedicated server writes its own console/logs. To turn it off for a Gradle run:

```sh
./gradlew runClient -PagentOutputDebug=false
```

Installed servers default to `debugAgentOutput = false` in `<world>/serverconfig/paracraft-server.toml`; set it to `true` to enable logging. The optional JVM property `-Dparacraft.debugAgentOutput=true|false` overrides the world setting (Gradle supplies this property).

The client relays assistant-visible text/refusals and complete tool-call arguments, including calls that later fail validation or arrive before a failed response. The simulator reports its ordered decisions as well. Records identify the avatar, turn, model, thinking effort and Light/planning route. Large outputs are split losslessly into numbered chunks under one output UUID. Credentials, HTTP headers and opaque reasoning are excluded. Debug records have no gameplay authority and cannot make a partial response executable.

The server accepts diagnostics only from the actual controlling connection, owner and active avatar session. A revoked session drops late output. Debug sink failures do not stop gameplay. Task/memory mutations are tools, so their arguments can appear in debug logs while enabled; account credentials stay in the local account process.

The opt-in live QA process bridge forwards the same visible provider output from its credential-owning client JVM to the server-side harness log. This is headless process-pipe QA; production in-game delivery uses the Forge owner/session transport.
