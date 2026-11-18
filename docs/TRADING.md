# Explicit villager trading

Agents can inspect and trade with villagers and wandering traders through their native merchant menus. These are explicit Planning program operations, advertised in `snapshot.operations`. Nearby native merchant entities have `merchant: true` in `nearbyEntities`. Neither the server's idle controller nor the simulator starts trades automatically. The planning policy restricts purchases/sales to a player request or a task that authorizes trading.

## Inspect offers

```json
{"steps":[
  {"op":"inspect_trades","target":"00000000-0000-0000-0000-000000000000","saveAs":"offers"}
]}
```

Replace `target` with the real merchant UUID from the snapshot. The avatar physically approaches the entity, faces it, and runs native interaction through Forge's specific/ordinary entity hooks. Obstructed interactions, unavailable targets, another player's active trading session, and denied interactions cannot open a trading session. An accepted interaction with no offers returns an empty offer list.

Inspection opens the actual merchant menu to obtain prices for this avatar, including native reputation and Hero of the Village discounts, then closes it. It does not take the result or spend payments. `offers` contains:

- `index` and `offerId`, identifying the observed trade terms.
- `costA`, `costB`, and `result`: item IDs, counts, and complete stack SNBT, or `empty: true` for an absent second payment.
- `uses`, `maxUses`, `remaining`, `outOfStock`, and the offer's `villagerXp`.
- `canAfford`, computed from actual carried main-inventory/offhand stacks using the native offer's item/NBT matching. This concerns payment, not stock or output space.

The result also identifies the merchant, position/dimension, observation time, and merchant XP. Offer observations are advisory: stock, entity position, prices, and output may change afterward.

## Execute trades

```json
{"steps":[
  {"op":"trade","target":"00000000-0000-0000-0000-000000000000",
   "offer":0,"offerId":"COPY_THE_64_CHARACTER_OFFER_ID_FROM_INSPECTION",
   "quantity":2,"saveAs":"purchases"}
]}
```

`quantity` counts executions of the offer, not individual output items, and defaults to one. For an offer selling three bread, quantity two buys six bread. The same operation can sell carried items for emeralds or execute two-input exchanges. Use the exact index and offer ID returned by inspection; the placeholder above is not executable.

The server approaches and opens the native menu, checks the selected offer's current actual costs/output against `offerId`, moves only real matching payments into its two input slots, and picks up its actual result. Native result pickup performs consumption, stock changes, stats, merchant/player XP, advancement behavior, crafting callbacks, and Forge trade notifications. Payments can come from several compatible main-inventory stacks and the offhand; equipped armor is not silently removed. Native NBT subset requirements apply. Shared inputs cannot count the same items toward both payments twice.

One trade completes per server tick, and each menu closes afterward. If reopened prices/output changed, the next trade stops for another inspection. Out-of-stock, missing materials, lost/blocked merchants, native refusal, and invalid menus stop the remaining batch. Program `try`/`catch` can handle these failures. Completed trades remain real; there is no rollback of previous successful purchases.

Results contain `requested`, `trades`, `produced`, `acquired`, `overflow`, and exact `acquiredStacks`. The ledger records actual post-hook items newly carried, excluding preexisting items. Use `deliver_items` with `itemsFrom: "purchases"` to deliver that ledger. Main-inventory capacity still applies: native menu closure returns its real cursor/payment stacks or drops overflow at the avatar. If output overflows, the operation reports the completed trade and actual partial result, then stops. No output is created by copying an offer into inventory.

Pause, disconnect, cancellation, and failure close owned menus and release merchant sessions. Checkpoints retain completed trade counts and acquired stacks; restoring a job continues the remaining batch, and existing request receipts prevent a repeated completed request from buying again. Actual world-save durability follows the project's normal server persistence.

## Verification scope

Native headless tests cover inspection/navigation/discounts without purchases, visible offer NBT and capabilities, two real payments including offhand items, native trade hooks/XP/stats, post-hook output delivery, exhausted batches and catches, NBT subset matching, shared-payment conservation, interaction protection and busy merchants, pause/body restoration and request deduplication, full-inventory world overflow, changed prices/obstruction/raw syntax, and selling to a wandering trader. Typed decisions have portable unit coverage.

No graphical client or live model is needed by these tests. After installing the mod with a linked provider, ask a nearby controlled avatar to inspect a real villager's offers, then explicitly request a purchase or sale using items it carries. Check the actual merchant's stock, avatar inventory, and `/paracraft job <name>`. Arbitrary modded merchant menus require their native callbacks/menu shape to be compatible; no universal mod support is inferred from vanilla tests.
