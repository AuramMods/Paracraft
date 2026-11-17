# Windowless background QA

Repeated verification uses Forge GameTests and the deterministic harness by default. These commands never start a graphical Minecraft client, activate an application, or open a sign-in browser. They exercise actual Minecraft world state headlessly and do not provide a connectable public game server. The explicit `live` mode additionally uses the selected linked ChatGPT plan.

## Start, inspect, and stop

On macOS/Linux, select Java 17 with `JAVA_HOME`, then run from the repository root:

```sh
python3 scripts/qa-background.py start
python3 scripts/qa-background.py status
tail -f build/qa/background/output.log
```

`start` returns immediately with the owned worker PID, run ID, and absolute log path. The worker runs the actual `./scripts/qa.sh`, which builds and tests before starting the windowless GameTest server. It saves the real exit code and finishes automatically after the suite. `status` distinguishes a running worker, passed/failed completion, cancellation, and an exit without a recorded result.

For the separate writer/reader process suite:

```sh
python3 scripts/qa-background.py start --mode restart
```

For [live Luna gameplay QA](LIVE_QA.md), after linking the local account:

```sh
python3 scripts/qa-background.py start --mode live
```

This mode runs `scripts/live-qa.sh` and consumes eligible plan usage with Luna `none`/`xhigh`. A separate local client process holds credentials; the disposable server receives only synthetic snapshots/decisions.

Stop an active run using:

```sh
python3 scripts/qa-background.py stop
python3 scripts/qa-background.py status
```

Stop validates the actual worker command/run ID before signaling its owned process group. It requests native shutdown and retains the execution lock while the group's processes drain, allowing Java's shutdown hooks to finish. After thirty seconds it can terminate a remaining owned group; `forcedShutdown` records that case. The cancellation smoke check completed gracefully without a forced shutdown. An idle/completed worker needs no stop action.

An OS file lock prevents overlapping background workers against the shared QA workspaces. This is actual resource ownership, not a guessed port/PID/version preflight. The real build/server determines whether execution succeeds. Set `JAVA_HOME` correctly; a toolchain/launch failure is recorded rather than replaced by a guessed success. Run one QA command at a time, including foreground invocations, because they share generated output/world directories.

## Files and world lifetime

| File/directory | Purpose |
| --- | --- |
| `build/qa/background/output.log` | Combined output of the most recently started background run |
| `build/qa/background/state.json` | Its run ID, actual worker/group, mode, result, and timestamps |
| `build/qa/headless.log` | Normal test/build/GameTest output |
| `build/qa/restart/write.log`, `read.log` | Separate cold-process evidence |
| `build/qa/live.log`, `run/live-qa/client.log` | Opt-in live gameplay and separate client inference evidence |
| `run/gametest/world` | Disposable normal suite world, rebuilt on every normal run |
| `run/restart-qa` | Disposable cold suite workspace; reset before writer, retained for reader |
| `run/live-qa/world` | Disposable live suite world, rebuilt for each explicit live run |

The helper is optional Python standard-library developer tooling. The installed mod has no Python process requirement. Foreground equivalents remain `./scripts/qa.sh` and `./scripts/restart-qa.sh`; Ctrl-C interrupts those commands. Generated `build`, `run`, and local `.qa` artifacts are ignored by Git. Keep personal worlds elsewhere.

The final validation run also copied its normal/restart output and worker state into `build/qa/final-normal.*` and `build/qa/final-restart.*`. These local artifacts can be returned with a bug report; future starts overwrite only the regular background output/state.

## Native client checks

No verified focus-preserving graphical-client launcher has been established. Automation therefore leaves the game closed and documents client login, signed chat/TCP, rendering, animation, and screen checks for an owner-initiated session in [QA.md](QA.md). `./gradlew runClient` opens Minecraft and is deliberately excluded from automated QA. Once you choose to start a client, keep that same session for the manual checklist instead of relaunching it between checks.
