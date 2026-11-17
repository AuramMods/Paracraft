# Native crafting

Agents can craft with their internal 2×2 inventory grid, or walk to a selected crafting table and use its real 3×3 menu. Both operations are advertised in snapshots and in the planning route's strict typed schema. Light still escalates gameplay requests.

## Craft an item

```json
{"op":"craft_item","item":"minecraft:stick","quantity":8,"saveAs":"made"}
```

The server chooses a matching loaded crafting recipe, assigns actual carried ingredients, fills the grid, and takes the result through the native menu. Ingredient matching supports tags, overlapping alternatives, Forge NBT ingredients, and shaped/shapeless layouts. Materials come from the 36 main/hotbar inventory slots. Equipped armor/offhand items are not automatically consumed.

`quantity` is the desired number of output items, not the number of recipe executions. Crafting occurs in whole native batches: asking for one stick may produce four. Materials are not recursively acquired or crafted; use collection and intermediate crafting steps in the same program when needed.

For a recipe requiring a table:

```json
{"op":"craft_item","item":"minecraft:wooden_pickaxe","quantity":1,"table":[100,64,100],"saveAs":"pickaxe"}
```

The avatar physically approaches that block and interacts through the native right-click path, including server/Forge permission hooks. The interaction must actually open a supported `CraftingMenu`; missing tables or unrelated machine menus do not grant a 3×3 grid. Table implementations opening that native menu or a compatible subclass can work; custom crafting stations may require an adapter.

An internal request never silently gains a larger grid. A recipe that cannot fit reports failure with partial progress, allowing the agent to choose a table, place one it carries, or ask for help.

## Discover recipes

```json
{"op":"search_recipes","item":"minecraft:wooden_pickaxe","limit":16,"saveAs":"recipes"}
```

Results include loaded recipe IDs, native output SNBT/counts, ingredient examples, shape dimensions where supplied, 2×2/3×3 fit, and current carried-material feasibility. The default result limit is 16, with a maximum of 128; ingredient examples report truncation beyond 16 alternatives. These observations are advisory. `craft_item` evaluates the real current ingredients and menu output when executing.

## Exact and dynamic recipes

Optional `recipe` pins a registry ID. It must also be the recipe selected by the real menu. No recipe unlock, feature flag, or native output check is bypassed.

Most shaped/shapeless recipes need no explicit grid. For dynamic recipes without an automatic ingredient list, optional `grid` supplies row-major source inventory indices, with `-1` for empty cells. Use exactly four entries internally or nine with a table:

```json
{"op":"craft_item","item":"minecraft:stone_pickaxe","quantity":1,"grid":[0,1,-1,-1],"saveAs":"repaired"}
```

This example places one item each from main-inventory slots 0 and 1 into the internal grid. Minecraft determines whether those actual stacks form a repair recipe and computes durability/metadata normally. Dynamic recipes with an empty preview output are not listed by output-item discovery; their grid can still execute natively. Arbitrary custom recipe semantics are not inferred by the automatic planner.

## Results, hooks, and interruption

Each crafting operation advances at most one recipe batch per job tick. Native menus enforce recipe availability/unlocks and handle ingredient consumption, recipe awards, item callbacks, Forge crafting events, and remaining buckets/tools. The menu closes between batches so unconsumed inputs and remainders return through Minecraft's normal inventory/drop path.

Results include `requested`, `crafted`, `crafts`, `acquired`, `overflow`, recipe ID, actual grid size, and `acquiredStacks`. The ledger records actual positive output gains with their inventory metadata and can feed `deliver_items` immediately. For example:

```json
{"steps":[
  {"op":"craft_item","item":"minecraft:stick","quantity":8,"saveAs":"made"},
  {"op":"deliver_items","itemsFrom":"made","recipient":{"playerUuid":"<owner UUID>"}}
]}
```

Full inventories can refuse native result transfer without consuming the inputs. If native partial transfer drops overflow, the operation reports failure with the actual crafted/carried counts; it does not delete or invent the remainder. Missing ingredients after completed batches likewise preserve partial results. The usual program try/catch, cancellation, and explicit resumption apply.

Completed-batch counts and output ledgers are checkpointed with the body/program state. Owner disconnect or pause closes the matrix and returns remaining inputs. Resume reopens a fresh menu and continues from the saved counts rather than replaying completed batches. This follows the existing orderly checkpoint model, not cross-mod crash-transaction guarantees.

This implementation handles crafting recipes and native player/table grids. Smelting and arbitrary machine-specific recipe types remain separate future adapters.
