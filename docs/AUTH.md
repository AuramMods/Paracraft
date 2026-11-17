# Link ChatGPT plan usage

Paracraft now implements local Sign in with ChatGPT and a real Responses provider. This uses eligible ChatGPT plan usage for the custom snapshot harness, independently of the Codex desktop app. No API key or copied Codex token is required. The simulator remains available and clearly labeled.

The implementation follows OpenAI's [open-source registration/sign-in](https://developers.openai.com/siwc/token-sharing-open-source/sign-in), [account/session](https://developers.openai.com/siwc/token-sharing-open-source/profiles-and-sessions), [models/inference](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference), and [preview limitations](https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations) documentation. Eligibility, models, and plan limits are determined by the actual account and service responses.

## Authenticate in Minecraft

Players can join first and link afterward. Run **`/paracraft link chatgpt`**, or open **K → Accounts**. Outside a world, use **Mods → Paracraft → Config → Accounts**. The command opens the local account screen; click **Continue with ChatGPT** to start browser sign-in. The joining player does not need Gradle, a terminal, an API key, or a pre-linked account.

Unlinked players see a clickable linking reminder when joining and when a new owned avatar appears, whether it was created through the controls or `/paracraft spawn`. The reminder opens the same account screen and never opens a browser automatically. The client command shares the `/paracraft` root with server commands; spawn and other gameplay commands continue to the server.

Complete browser consent and return to Minecraft. The account screen updates with success or the actual error. Successful linking selects the live provider on the existing connection; no server reconnect is needed. If Josh was paused from an earlier error, use **Resume**. Linking preserves its world, inventory, and tasks.

The account screen also supports saved account selection, adding another account/workspace, sign-out, reloading a session linked through the CLI, actual account models, and returning to the simulator. Distinct registrations stay separate even if they share an email address. If plan usage was declined, Continue with ChatGPT requests consent again using the same registration.

## Optional headless developer sign-in

From this repository with Java 17 selected:

```sh
./gradlew chatgptLogin
```

The command prints a local `http://127.0.0.1:<port>/` link. Open it, click **Continue with ChatGPT**, choose your account/workspace, and grant the requested ChatGPT plan permission. The CLI keeps the callback listener running for up to ten minutes, then confirms the result. It does not open a game window or browser automatically. Leave that command running until the browser returns.

Check the cached account and fetch its actual model catalog:

```sh
./gradlew chatgptStatus
./gradlew chatgptModels
```

The same local cache is used by the Minecraft mod and these headless client tools. After the first installation of this feature, restart the Minecraft client once to load the updated classes. Later sign-ins normally reuse/refresh the saved session.

## Models and a first live check

**Model routes → Pick** lists actual account-visible models. The raw model/effort fields remain editable. The configured defaults stay `gpt-6-luna / none` and `gpt-6-luna / xhigh`; Paracraft does not silently replace them if an account cannot use them. A catalog entry is advisory: the actual request reports model/effort/eligibility errors.

After authentication, this explicitly makes one real LIGHT request and prints its reply plus locally recorded usage:

```sh
./gradlew chatgptSmoke
```

For a different account-supported route:

```sh
./gradlew chatgptSmoke -PchatgptModel=<exact-slug> -PchatgptEffort=none
```

Each real decision consumes eligible plan usage. The saved session avoids repeated browser authentication; it does not make inference free. The account screen links to [ChatGPT usage settings](https://chatgpt.com/settings/usage). Local counters record completed requests and reported token/cache usage; they do not estimate remaining plan quota.

## Credential ownership and lifecycle

The local cache is `~/.paracraft/chatgpt/accounts.json`, outside Minecraft worlds, server state, packets, and companion memory. It contains protected OAuth credentials; **do not share that file**. On Unix the directory is owner-only `0700` and files `0600`; supported Windows filesystems use an owner-only ACL. Writes replace files atomically. Refreshes reload and serialize the current rotating token through a process lock, including when the game and headless client use the cache together.

A stable opaque host ID is created once. First sign-in uses OpenAI's dynamic client registration; later sign-ins reuse the issued client ID. PKCE/state/nonce, the actual RSA signature/JWKS, issuer, audience, expiry, and returning identity are checked before a session is activated. Partial/failed sign-in does not overwrite an existing valid account. An expired initial exchange retains its issued registration for fresh authorization.

Access tokens refresh near expiry and once after an actual unauthorized API response. Temporary failures preserve credentials. Terminal refresh revocation clears unusable tokens while retaining the account/client mapping. Sign-out stops new live requests immediately, attempts remote revocation, and clears local tokens. If revocation cannot be confirmed, that fact is shown; the account can also disconnect Paracraft in ChatGPT settings.

No existing `~/.codex` credentials are read, and no authentication token is sent through Paracraft's Minecraft protocol. The production server does not instantiate the account client. The optional `paracraft.auth.directory` JVM property is for isolated developer tooling; ordinary installs use the default protected cache.

## Provider behavior and errors

Every request uses public `POST /v1/responses`, OAuth bearer authorization, `store:false`, `stream:true`, explicit snapshot/history input, and a namespaced decision function. It omits unsupported conversation retention and output/billing overrides. Returned ordered decisions enter the same permission-checked harness as the simulator. Light cannot perform gameplay or memory/permission changes. Planning receives the packaged domain-action contract.

The decision function uses [strict Structured Outputs](https://developers.openai.com/api/docs/guides/function-calling#strict-mode). Every call has a typed `arguments` object selected by its tool name; Light exposes only chat and escalation. Planning additionally constrains tasks, waits, permission/behavior settings, observation bindings, and recursively typed action programs including nested try/catch. There are no JSON-encoded argument strings. All object properties are declared, extra fields are forbidden, and optional values are represented by required nullable fields. The client validates the actual returned batch against the same schema before any effects, removes optional nulls, and converts typed observation-binding arrays to native server maps. The existing server syntax, permissions, request deduplication, and execution checks still govern world actions.

Only a terminal `response.completed` can produce actions. Completed output items emitted earlier in the stream are retained when the terminal response omits them. Failed, incomplete, malformed, conflicting, or interrupted streams cannot apply partial decisions. Pause/disconnect/account changes cancel client inference and reject late results. Usage-limit and provider failures pause the loop and expose actual diagnostic codes/request IDs, preserving credentials when appropriate. There is no automatic fallback to API-key billing or another model.

Ordinary authoritative tool/query rejection is returned to planning through `observations.tool_failures`, with actual partial information and the original request. Its dependent calls stop; corrected submissions get a fresh round instead of replaying a permanently failed receipt. Authority loss and request conflicts still pause control rather than being treated as recoverable gameplay failures.

## Validation status

All 75 unit tests pass. Automated tests use a local HTTP OAuth/OIDC/Responses fixture with fake identities/tokens. They verify browser callbacks, PKCE, signature/nonce/audience/expiry checks, protected cache reuse, rotating refresh across clients, sign-out/re-consent, expired-code registration recovery, account separation, real HTTP/SSE parsing, strict typed decisions/programs including both crafting grids, route restrictions, cancellation, actual-request model errors, usage accounting, and durable tool-error replanning. The normal native gameplay suite remains headless and simulated; [LIVE_QA.md](LIVE_QA.md) describes the separate explicit live delivery/crafting suite.

The owner completed real sign-in through the CLI, and Luna `none`/`xhigh` requests have used that cached session. Actual headless diamond retrieval also passed using a separate local credential-owning client process. In-game account/model screens, command clicks, browser return, and actual connected-client login/logout have not been visually verified. No sign-in browser or graphical Minecraft client was automatically launched during development.

Optional public connection diagnostic (no account or inference required):

```sh
./gradlew chatgptMetadata
```

For an authentication error, return the displayed/CLI error and request ID, not the account cache or authorization codes. For game behavior, use the existing [QA checklist](QA.md) once the live account/model route is selected.
