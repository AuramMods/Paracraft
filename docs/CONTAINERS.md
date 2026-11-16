# Inventory interfaces and selected-container actions

The broad `search_containers`, `collect_items`, `deposit_items`, and ledger-based `deliver_items` operations remain the preferred way to perform a whole task without decisions between individual transfers. An agent can also select a specific container explicitly:

```json
{"steps":[
  {"op":"open_inventory","position":[100,64,100],"saveAs":"contents"},
  {"op":"take_items","position":[100,64,100],"item":"minecraft:diamond","quantity":7,"saveAs":"taken"},
  {"op":"deposit_items","destination":[130,64,100],"item":"minecraft:diamond","quantity":7,"saveAs":"inserted"}
]}
```

These jobs approach the actual block, perform native interaction and protection/lock checks, and carry items in the avatar's native inventory. Container menus are managed by the server executor and closed at job/control boundaries. `take_items` names its position explicitly; it does not rely on an implicit last-opened menu. Physical approach to a second container can span chunks without another inference call.

## Inspection and transfer results

`open_inventory` reads native slots incrementally, with up to 64 inspected slots per tick. `limit` bounds the returned inspection to 256 slots by default, configurable from 1 through 4,096. `complete` and `truncated` distinguish a full inspection from a bounded prefix. Results include position/dimension, timestamps, and per-view slot counts/indices with actual stack SNBT. Empty slots are explicit. This is a time-stamped observation across ticks, not an atomic transaction.

Vanilla inventories are reported once. Generic item capabilities expose their actual available face views. Those views can overlap and are not claimed as distinct backing inventories. Broad search counts use the largest visible generic view and label uncertain counts rather than summing duplicates. Specialized networks with unusual virtual slots, enormous inventories, or custom APIs need dedicated adapters; this first pass does not claim universal storage support.

`take_items` accepts an exact nonnegative integer `quantity` and an item registry ID or `#tag`. It transfers at most 64 matching items per tick, reports actual `acquired` and `acquiredStacks`, and fails with partial progress if the inventory cannot supply the remainder or capacity/extraction is refused. The acquired ledger is compatible with `deliver_items` when its items are still carried. Checkpoints preserve already acquired counts/stacks and reopen under the restored body before continuing. Stable program IDs acknowledge an accepted request without taking another copy.

`deposit_items` selects matching carried inventory slots, preserving each stack's metadata and following actual insertion rules. It is a selector-based deposit, not an acquired-ledger filter: pre-existing matching items may be deposited first. Use ledger-based delivery when only a particular collection's belongings should be delivered. Depositing or otherwise consuming part of an acquired ledger does not automatically rewrite that earlier named observation; later instructions must use the actual remaining inventory/results.

## Generic capability boundary

The adapter resolves actual [Forge item capabilities](https://docs.minecraftforge.net/en/1.20.1/datastorage/capabilities/) and appropriate vanilla inventory interfaces. It respects exposed sides, extraction/insertion responses, slot acceptance, actual remaining stacks, and avatar capacity. Handlers are re-resolved for mutations so their lifetime belongs to the block/mod. Native chest locks, obstructed/double chests, interaction hooks, and partial transfer behavior are covered separately in the existing suite.

A test-only machine attaches a serialized item capability to a real skull block entity, which has no vanilla `Container`. It exposes overlapping north/top views, permits extraction only from its north output slot, and accepts diamonds only into its top input. Actual native NBT restoration, inspection, conservative query count/uncertainty, physical extraction, and insertion all pass. The fixture is excluded from the mod JAR; this verifies the public integration interface rather than an installed third-party modpack.

Additional native scenarios verify open/take/deposit over more than 25 blocks with metadata/slot-order conservation and request deduplication, and restoration between batches of a 130-item fixed-source transfer. That new fixed-source checkpoint test reconstructs a body through saved NBT in one process; the separate cold suite continues to cover broad inventory/receipt/command recovery.
