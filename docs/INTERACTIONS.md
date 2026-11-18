# Right-click interactions

Agents can issue `interact_block`, `interact_entity` and `use_item` inside action programs. Both live providers receive the typed actions and planning instructions. The server handles physical navigation, aiming and Minecraft/Forge callbacks using the avatar's actual held items. No client window is opened by these actions.

## Blocks

```json
{"steps":[
  {"op":"interact_block","position":[100,64,100],"saveAs":"pressed"},
  {"op":"report_result"}
]}
```

`position` is an absolute block coordinate. The avatar approaches it and selects a visible, reachable face from the actual block shape. Optional `face` is `up`, `down`, `north`, `south`, `east` or `west`; optional `hit` is a point relative to the block's lower corner, with each coordinate between zero and one. A requested face/point must be visible from the approached position; the action cannot click through a wall. Ordinary navigation preserves builds.

The click runs `ServerPlayerGameMode.useItemOn`, including Forge right-click events, block use, item-first/use-on callbacks, sneak behavior, consumption and cooldown rules. An unhandled block click may fall through to ordinary held-item use, as needed for buckets. Actual block-event cancellation or item denial prevents that fallback. Source-fluid hits support native bucket use; ordinary solid-block clicks see through water.

Use this for buttons, levers, doors, machines, bonemeal, tools and other native/mod interactions. Use [`search_blocks`](PORTALS.md#block-query-scope-and-freshness) for real nearby positions. Coordinates in examples are placeholders to replace with actual world observations.

## Entities

```json
{"steps":[
  {"op":"equip","item":"minecraft:bucket","slot":"mainhand"},
  {"op":"interact_entity","target":"00000000-0000-0000-0000-000000000000","saveAs":"milked"}
]}
```

Replace `target` with an actual UUID from `nearbyEntities` in the snapshot. The avatar approaches the moving target and checks native reach and block line of sight. The default point is the entity's center; optional `hit: [x,y,z]` is an offset from its feet inside its actual bounding box, useful for armor-stand slots.

The server runs Forge's specific interaction hook and native `interactAt`, then ordinary `Player.interactOn` only if the specific interaction was unhandled. Specific-hook cancellation never falls through to bypass protection. Ordinary entity/item interaction hooks, item use and survival consumption still apply. Feeding, milking, mounting, trading and mod interactions depend on what the target actually accepts.

## Hands, sneaking and held item use

All three actions accept:

- `hand`: `mainhand` (default) or `offhand`. Use `equip` beforehand to select an existing carried item, or `unequip` to free a hand.
- `sneak`: whether this click uses the sneak modifier. The prior modifier is restored afterward.
- `holdTicks`: nonnegative server ticks to hold a native item use. Default zero is a click/release. Items that finish naturally can end earlier.

`use_item` uses the current hand directly and optionally aims at an absolute world point with `lookAt`:

```json
{"steps":[
  {"op":"equip","item":"minecraft:milk_bucket","slot":"mainhand"},
  {"op":"use_item","holdTicks":40,"saveAs":"drank"}
]}
```

For eating/drinking, provide enough hold time for the native item duration; a normal drink needs 32 ticks, so 40 leaves room for completion. For bows and other release-driven items, the server releases normally at the selected duration. Native durability, projectiles, remainders and Forge item hooks govern the outcome.

Pause/cancel aborts held use without invoking a release action that could fire a bow. An interrupted held gesture cannot resume as another click: resuming/restoring reports failure with actual partial results, allowing a program catch or the next agent decision to select an explicit retry. An approach interrupted before the click can resume normally. Completed requests retain the existing deduplication behavior.

## Results and limits

Results include the native `outcome`, `accepted`, selected hand, actual `handBefore`/`handAfter` stacks, held ticks/use state, and available block-before/after, hit/face, opened-menu or mounted-vehicle observations. `PASS`/`FAIL` fails the operation and can enter an ordinary program catch. Native acceptance acknowledges the interaction; it does not prove the larger requested goal completed.

Opened server menus are observed and closed when the job ends. Generic clicking does not implement arbitrary graphical menu buttons or client-only mod interfaces. Use the existing inventory/crafting actions for those supported workflows, and [`inspect_trades` / `trade`](TRADING.md) for actual merchant offers and purchases/sales. Modded callbacks following the native paths can work, but arbitrary mod compatibility is not inferred from vanilla tests. A native callback that kills/removes/transfers the avatar stops subsequent instructions through the existing job lifecycle; generic interaction does not grant automatic portal travel.

## Verification

Ten native headless GameTests cover lever/chest approach, checkpoint restoration before a single click, menu observation and deduplication; offhand sneaking bonemeal and item conservation; source-fluid bucket fallback; cow milking and both entity hook paths; an armor-stand head hit; block/specific/entity/air hook cancellation; wall obstruction and invalid raw syntax; native drinking and aimed snowballs; timed bow release; and paused/restored bow use without release or repeated clicks. The shared schema has unit coverage for targets, hands, hit points, nullable defaults and invalid holds/coordinates.

For an owner check after loading the updated mod, try `Right-click that lever`, `Use bonemeal on that crop with your offhand`, or `Use a bucket to milk that cow` with actual nearby targets and carried items. Inspect `/paracraft job Josh` and the resulting world/inventory. General natural-language interactions require a live provider; the deterministic simulator's finite chat grammar is unchanged. Automated validation opens no graphical client and makes no live model request; visuals, real connected-client delivery and arbitrary mod machines remain manual checks.
