# OpenRouter API keys and model IDs

Open **K → Accounts → OpenRouter**, or run **`/paracraft link openrouter`**. This client command opens the local settings screen; the API key is entered in a masked field, never in a Minecraft chat command. Outside a world, use **Mods → Paracraft → Config → Accounts → OpenRouter**.

1. Paste your OpenRouter API key.
2. Enter the exact **Light model ID**, including its provider prefix, such as the ID copied from your OpenRouter model page. IDs and suffixes are sent unchanged.
3. Optionally enter a different **Planning model ID**. Leaving it blank uses the Light model for both routes.
4. Leave reasoning efforts at **`auto`** to omit the reasoning override, or specify the effort you want for each route. The request reports actual unsupported-model/parameter errors; a guessed compatibility list does not block it.
5. Click **Save & use**. The live provider changes on the existing connection. Resume an avatar if an earlier error left it paused.

These OpenRouter model routes apply to all companions controlled by this client. **K → Model routes** opens the same OpenRouter settings while this provider is active. The per-avatar ChatGPT model routes are preserved and used again after choosing a saved ChatGPT account. Light still handles social chat or escalates; Planning uses the configured model/effort for gameplay, memory, tasks and other actions.

A blank API key field retains the saved key. It is never filled with the stored secret when opening the screen. **Remove key** removes it locally; if OpenRouter was active, the client returns to the simulator. **Accounts → Use simulator** stops live inference while retaining settings. Selecting a saved ChatGPT account switches back to that provider. No automatic fallback sends a failed request through another model, ChatGPT, or the simulator.

## Requests and storage

The key and model settings are stored in the existing private client record, `~/.paracraft/chatgpt/accounts.json`, separately from Minecraft worlds, companion memory and packets. Unix permissions are `0700` for the directory and `0600` for the record; supported Windows filesystems use the existing owner-only ACL. Existing OAuth registrations remain saved independently. Do not share the private record. No OpenRouter key is stored on a Minecraft server or passed in a snapshot/model prompt.

Requests use OpenRouter's [Chat Completions API](https://openrouter.ai/docs/api/reference/overview) at `https://openrouter.ai/api/v1/chat/completions`, with bearer authentication, explicit model IDs, the current snapshot/history, and a single typed `paracraft_decide` function. The [forced tool call](https://openrouter.ai/docs/guides/features/tool-calling#tool-choice-configuration) carries the same strict arguments schema as the ChatGPT provider. The complete batch is validated locally before effects; server permissions, syntax validation and job execution still apply. Truncated, malformed, missing, duplicate, unknown or forbidden function calls cannot apply partial decisions. Use a model supporting tool calling. No model catalog lookup or billable test runs on Save.

Reasoning `auto` leaves the model default untouched; explicit efforts use OpenRouter's [unified reasoning parameters](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens). Hidden reasoning is excluded from diagnostic output. Requests are independent, non-streaming decisions; there is no implicit conversation retained by Paracraft. Pause, disconnect and provider/settings changes cancel or invalidate pending inference. HTTP and model errors appear through the normal paused-agent error handling, with credential text redacted.

The shared [continuity layer](CONTINUITY.md) explicitly replays recent decisions/results and supplies the saved task request and next-step note with each fresh snapshot. OpenRouter uses the same bounded journal, empty-decision recovery and mechanical-work scheduling as ChatGPT. OpenRouter's local HTTP fixtures cover this message shape; the live gameplay measurements use Luna through the linked ChatGPT plan.

## Usage

The K menu and **Usage details** show daily input/output tokens for the saved OpenRouter key. Cost comes from the API response's [`usage.cost`](https://openrouter.ai/docs/cookbook/administration/usage-accounting), including a reported zero for free calls. Missing usage/cost stays marked partial; Luna token prices are not applied. **Manage usage** opens OpenRouter Activity, and the pricing link opens OpenRouter models.

Changing models with the same key preserves daily totals. Replacing/removing the key creates a separate ledger so late responses cannot add usage to another key. Counts include only requests observed by Paracraft, not other applications; pending requests canceled before a usage response may still have upstream charges. ChatGPT and OpenRouter usage remain separate.

## Verification

Loopback HTTP tests use fake credentials to verify the actual Authorization header and request body, exact model/effort selection, typed tools, response validation, cancellation/settings changes, private persisted storage, key removal/replacement, usage isolation and cost accounting. A harness test verifies that resolved provider models appear in diagnostics while saved avatar routes remain unchanged. Normal headless gameplay tests run through the unchanged execution path. No graphical client or paid OpenRouter inference was launched for this implementation.

After loading the updated mod, configure your key/model and ask a single nearby controlled avatar `Hello`, then `Find the nearest three crafting tables within sixteen blocks`. Check the K-menu provider/model label, agent response/job result, and usage totals. Verify that a bad model ID reports its actual provider error; restore the correct ID and resume. Key entry, layout and connected-client interaction remain manual checks.
