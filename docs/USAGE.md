# Daily usage in the K menu

Open **K** to see today's reported input tokens, output tokens, and estimated Luna API cost. **Usage details** shows total tokens, cached input, cache writes, completed requests, the reporting date/timezone, and pricing assumptions. **Manage usage** opens [ChatGPT usage settings](https://chatgpt.com/settings/usage) only when clicked. The pricing button opens the official rate card.

These are **local Paracraft totals for the selected account**, across its avatars and servers. They do not include Codex desktop/CLI activity, other apps, or another computer's Paracraft cache. Switching accounts changes the displayed totals. The simulator adds no tokens or estimated cost. The menu refreshes the local cache asynchronously every five seconds without inference or HTTP requests; reads coalesce if another process holds the account lock.

## Luna API estimate

Rates were verified on **2026-11-17** against [official API pricing](https://developers.openai.com/api/docs/pricing) and the [Luna model page](https://developers.openai.com/api/docs/models/gpt-6-luna):

| Token category | USD per million |
| --- | ---: |
| Ordinary input | $0.10 |
| Cached input | $0.01 |
| Default 30-minute cache writes | $0.125 |
| Output, including reasoning | $0.50 |

For each response, ordinary input is `input_tokens - cached_tokens - cache_write_tokens`. Each input token is priced once. Missing cache counts are treated as zero in the estimate; unreported cache writes are excluded. More than 272,000 input tokens in a single request doubles its input/cache rates and multiplies its output rate by 1.5. The daily total itself does not trigger long-context pricing. Decimal arithmetic avoids floating-point drift; tiny nonzero costs display as less than $0.000001 instead of zero.

All reported model usage is priced as a **Luna API equivalent**. This is a comparison estimate, including if a different model is selected. It is not a ChatGPT subscription charge, a plan balance, or an invoice. Nonstandard service tiers, regional premiums and unreported charges are excluded. [API prices and subscription usage are separate](https://learn.chatgpt.com/docs/pricing).

The UI displays **Codex left: unavailable** because Paracraft's current direct [Sign in with ChatGPT inference flow](https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference) has no documented account-remaining-percentage operation. Codex app-server documents a separate [`account/rateLimits/read` RPC](https://learn.chatgpt.com/docs/app-server#6-rate-limits-chatgpt), but Paracraft does not run an authenticated Codex app-server. API throughput headers are not treated as subscription percentages. Use **Manage usage** for the actual applicable plan/app limits; the UI does not infer zero or 100% from a usage-limit error.

## Persistence, dates, and partial reporting

Daily aggregates live beside the existing lifetime usage counters in the protected client account cache. Updates use the same process lock and atomic writes as the session store, so concurrent game/CLI processes merge their increments. Account data stays on the client and is not added to world state, snapshots or server packets.

The reporting timezone is captured from the local computer when daily tracking begins and displayed in Usage details. Keeping this zone stable prevents travel or system-timezone changes from silently reassigning previously aggregated days. The calendar date resets at midnight in that zone, respecting daylight-saving transitions, even while no inference runs and the menu stays open.

Older caches have lifetime totals and only a last-request timestamp, so those totals cannot accurately be assigned to today. They remain intact; daily accounting starts with this update. On the first tracking day, an asterisk and details explain the missing earlier history. Missing token usage and inconsistent cache counts also mark the estimate as partial instead of inventing values. Failed/incomplete streams contribute tokens if their terminal event actually reports them; they still cannot execute actions. Interrupted requests without final usage remain explicitly unreported.

## Verification

Headless tests cover cache-read/write pricing, reasoning inclusion, long-context thresholds per request, exact decimal sums, tiny costs, missing/malformed usage, legacy migration, local midnight and the 25-hour daylight-saving fallback day. Real HTTP fixture tests verify reported-token persistence, restored clients, two concurrent client instances, read-only refresh, account switching, late old-account completion, and failed/incomplete response accounting without actions.

One real Luna `none` smoke request reported **477 input and 34 output tokens**, displaying **$0.000065 USD**. Its greeting, local counters and estimate were obtained without a graphical client or browser. Actual K-menu appearance and clicks remain a manual check:

1. Restart the development client with `./gradlew runClient` and press K.
2. Check the input/output and estimate lines, then open Usage details and scroll through totals and assumptions.
3. Give an avatar a short instruction. After inference, confirm the counters increase; simulator activity must not increase them.
4. Close/reopen the menu or restart the client and confirm the totals persist. Account selection should display that account's own totals.
5. Click Manage usage to inspect actual subscription limits in the browser. This is the only step that deliberately opens that browser.

`./gradlew chatgptStatus` displays the same cached daily summary without inference. `./gradlew chatgptSmoke` explicitly consumes one live request and prints the updated summary. Do not share the credential cache for QA.
