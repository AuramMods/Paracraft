# Live Luna gameplay QA

After linking through `/paracraft link chatgpt` or the optional `./gradlew chatgptLogin`, run with Java 17:

```sh
./scripts/live-qa.sh
```

This explicitly consumes the selected local account's ChatGPT plan usage. It runs only `gpt-6-luna`, with `none` for Light and `xhigh` for planning. Ordinary `scripts/qa.sh` stays simulated and does not use credentials or inference.

Both routes use strict typed tool arguments; programs and nested recovery are structured objects rather than encoded JSON strings. Returned batches are checked before effects, and only terminally successful streamed decisions can reach the authoritative execution service.

The live suite starts a windowless Forge GameTest server in disposable `run/live-qa/world`. A separate local Java client owns the account cache, HTTP requests, and refresh. The server sends synthetic snapshots and receives completed decisions over a private process pipe; it never receives OAuth credentials. The test uses the actual harness and authoritative Minecraft control service with an in-process sink. It does not simulate a graphical client, Forge TCP login, signed chat, or screen rendering.

The delivery scenario asks Josh to find and deliver nearby diamonds. Assertions require both Luna routes, real movement between two source chests, exactly twelve acquired diamonds delivered, three pre-existing diamonds preserved, emptied sources, a completed task, and an idle interval with no further inference.

The crafting scenario asks another avatar to craft eight planks and eight sticks internally, then one wooden pickaxe at a selected real table. It requires both grid sizes, physical table travel, exact remaining material/output counts, preservation of pre-existing sticks, task completion, and idle suppression. These live scenarios run in separate batches paced at 20 TPS so external requests have real wall-clock time; normal GameTests retain accelerated execution. Each scenario owns a separate credential-holding client process.

Logs are `build/qa/live.log` and `run/live-qa/client.log`. `QA_EVT` records routes, effort, tool names, process IDs, and actual delivery/crafting results, without tokens or full request/output content. Client output is appended across scenarios and reset for each new live run. The script verifies both actual success events after the server exits, so an accidentally empty or incomplete suite cannot report successful live QA.

For a detached run:

```sh
python3 scripts/qa-background.py start --mode live
python3 scripts/qa-background.py status
python3 scripts/qa-background.py stop
```

The same owned worker/lock and graceful stop controls apply. No browser or game window opens.

For native UI verification, join with an unlinked local profile, check the clickable join reminder, create Josh and check the creation reminder, then run `/paracraft link chatgpt`. The account screen should remain open after chat closes. Click Continue with ChatGPT, complete consent in the browser, return, and check the updated status. Existing avatars should use the selected live provider without a server reconnect. These visual/connected-client checks must be reported separately from the headless result.

## November 17 result

The final background run passed with the strict typed schemas. Eight real Luna decisions (four planning) delivered exactly twelve diamonds, preserved three pre-existing diamonds, walked 33.93 blocks, completed the task, and remained idle. The server was JVM 79749 and the credential-owning client was JVM 79799. The worker recorded exit zero and released its lock. The normal native suite passed all 86 tests; all 73 unit tests passed. The authenticated model catalog also returned Luna. No graphical client or sign-in browser was launched by automation.

The subsequent crafting extension also passed both live scenarios in server JVM 36259. Delivery used client JVM 36328. Crafting used client JVM 36728 and six Luna decisions (two planning); the native result was two logs, one plank, nine sticks including the three pre-existing sticks, and one wooden pickaxe. The worker exited zero and both tasks returned to idle. All inference used Luna `none`/`xhigh`.
