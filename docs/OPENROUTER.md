# OpenRouter and compatible API endpoints

Open **K → Accounts → OpenRouter**, or run **`/paracraft link openrouter`**. This client command opens the local settings screen; the API key is entered in a masked field, never in a Minecraft chat command. Outside a world, use **Mods → Paracraft → Config → Accounts → OpenRouter**.

1. Set the **API base URL**. The default is `https://openrouter.ai/api/v1`; for local Ollama use `http://localhost:11434/v1`. Trailing slashes are accepted, and a pasted `/chat/completions` endpoint is normalized to its base. Use an HTTP(S) URL without embedded credentials, query, or fragment.
2. Enter your API key if required. Local endpoints can leave it blank. Enter the exact **Light model ID**: OpenRouter's provider/model ID or the installed model name on your local server. IDs and suffixes are sent unchanged.
3. Optionally enter a different **Planning model ID**. Leaving it blank uses the Light model for both routes.
4. Leave reasoning efforts at **`auto`** to omit the reasoning override, or specify the effort you want for each route. The request reports actual unsupported-model/parameter errors; a guessed compatibility list does not block it.
5. Click **Save & use**. The live provider changes on the existing connection. Resume an avatar if an earlier error left it paused.

These API model routes apply to all companions controlled by this client. **K → Model routes** opens the same endpoint settings while this provider is active. The per-avatar ChatGPT model routes are preserved and used again after choosing a saved ChatGPT account. Light still handles social chat or escalates; Planning uses the configured model/effort for gameplay, memory, tasks and other actions.

A blank API key field retains the saved key only when saving the same normalized base URL. Changing the URL clears the old endpoint's key unless you explicitly enter a key for the new endpoint. The stored secret is never filled into the screen. **Remove key** removes it locally and returns to the simulator; save again with a blank key to use an unauthenticated local endpoint. **Accounts → Use simulator** stops live inference while retaining settings. Selecting a saved ChatGPT account switches back to that provider. No automatic fallback sends a failed request through another endpoint, model, ChatGPT, or the simulator.

## Ollama locally

Run your Ollama server, open `/paracraft link openrouter`, set **API base URL** to `http://127.0.0.1:11434/v1`, leave **API key** blank, enter an installed model that supports tool calling, and leave both efforts at `auto` initially. A blank Planning model uses the same model for both routes. Click **Save & use** and resume any avatar paused after an earlier error. Loopback addresses refer to the computer running your Minecraft client, including when connected to a remote Minecraft server.

Java 17 can resolve `localhost` to IPv6 `::1` first while Ollama listens only on IPv4 `127.0.0.1`. The API transport now retries literal IPv4/IPv6 loopback addresses only after an actual HTTP localhost TCP connection refusal, keeping the method, headers, body, port and encoded path/query. It does not probe the service on Save, retry a received HTTP error, change the selected model/provider, force global IPv4, or alter HTTPS hostname verification. Existing 1.1.0 installations can use the literal IPv4 URL above as an immediate workaround. A failed connection now identifies its endpoint; the client console reports each pause failure once, and enabled server debugging also receives sanitized provider error diagnostics. Resume retries the underlying request, so repeated clicks cannot repair an unreachable address by themselves.

Ollama documents this [OpenAI-compatible base URL and Chat Completions/tool support](https://docs.ollama.com/api/openai-compatibility). Paracraft still requires a complete valid `paracraft_decide` tool response; a model that ignores tools produces an actual validation error. Save makes no connectivity/model-discovery request. The next agent decision attempts the configured endpoint directly.

## Requests and storage

The base URL, key, and model settings are stored in the existing private client record, `~/.paracraft/chatgpt/accounts.json`, separately from Minecraft worlds, companion memory and packets. Unix permissions are `0700` for the directory and `0600` for the record; supported Windows filesystems use the existing owner-only ACL. Existing OAuth registrations remain saved independently. Older records without a base URL keep using OpenRouter. Do not share the private record. No API key is stored on a Minecraft server or passed in a snapshot/model prompt.

Requests POST to `<base URL>/chat/completions` with explicit model IDs, selective context, and the typed `paracraft_decide` function. Custom endpoints receive a schema that lets the model omit optional fields. The decoder accepts JSON objects, encoded arguments, and a single complete JSON batch in message content. It fills optional defaults, then validates the complete batch before executing any actions. Invalid decision JSON or schema mismatches get one correction attempt. If that fails, the avatar pauses and reports the error. See [decision context](CONTINUITY.md#selective-context).

Use a model that supports tool calls. Saving settings does not contact the provider. Authorization is sent only when a key is configured, and OpenRouter attribution headers apply only to OpenRouter.

Reasoning `auto` leaves the model default untouched; explicit efforts use OpenRouter's [unified reasoning parameters](https://openrouter.ai/docs/guides/best-practices/reasoning-tokens). Hidden reasoning is excluded from diagnostic output. Requests are independent, non-streaming decisions; there is no implicit conversation retained by Paracraft. Pause, disconnect and provider/settings changes cancel or invalidate pending inference. HTTP and model errors appear through the normal paused-agent error handling, with credential text redacted.

The shared [continuity layer](CONTINUITY.md) explicitly replays recent decisions/results and supplies the saved task request and next-step note with each fresh snapshot. OpenRouter uses the same bounded journal, empty-decision recovery and mechanical-work scheduling as ChatGPT. OpenRouter's local HTTP fixtures cover this message shape; the live gameplay measurements use Luna through the linked ChatGPT plan.

## Usage

The K menu and **Usage details** show daily input/output tokens for the configured API endpoint/key. Cost comes from the API response's [`usage.cost`](https://openrouter.ai/docs/cookbook/administration/usage-accounting), including a reported zero for free calls. Missing usage/cost stays marked partial; Luna token prices are not applied. Local servers commonly omit monetary cost; the UI identifies custom API usage and marks missing costs as unpriced. For OpenRouter, **Manage usage** opens OpenRouter Activity and the pricing link opens OpenRouter models. These links are disabled for custom endpoints, whose billing controls are provider-specific.

Changing models with the same endpoint/key preserves daily totals. Changing the base URL or replacing/removing the key creates a separate ledger so late responses cannot add usage to another endpoint/key. Counts include only requests observed by Paracraft, not other applications; pending requests canceled before a usage response may still have upstream charges. ChatGPT and API usage remain separate.

## Verification

Loopback HTTP tests use fake credentials to verify the actual Authorization header and request body, exact model/effort selection, typed tools, response validation, cancellation/settings changes, private persisted storage, key removal/replacement, usage isolation and cost accounting. Additional tests use the production provider constructor against saved custom URLs with path prefixes, no authentication, restored configuration, endpoint changes during inference, old-key isolation, legacy records, URL normalization/errors, local HTTP failures, and unpriced local usage. A harness test verifies that resolved provider models appear in diagnostics while saved avatar routes remain unchanged. Normal headless gameplay tests run through the unchanged execution path. No graphical client, actual Ollama model, or paid inference was launched for this implementation.

After loading the updated mod, configure your key/model and ask a single nearby controlled avatar `Hello`, then `Find the nearest three crafting tables within sixteen blocks`. Check the K-menu provider/model label, agent response/job result, and usage totals. Verify that a bad model ID reports its actual provider error; restore the correct ID and resume. Key entry, layout and connected-client interaction remain manual checks.
