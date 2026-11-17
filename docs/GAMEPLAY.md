# Initial gameplay operations

These operations run as incremental server jobs inside structured programs. They use the avatar's physical inventory, position, native interaction paths, and Forge hooks. The available operation names are included in snapshots. Use `/paracraft program <companion> <json>` to author a program directly; the deterministic chat simulator still recognizes its documented finite scripts rather than general gameplay language.

Native [crafting](CRAFTING.md) is available through `craft_item` and `search_recipes`: internal 2×2 crafting, physical table use for 3×3 recipes, automatic ingredient assignment, native remainders/hooks, and resumable batch progress. The linked agent can choose these typed operations; manual program examples are in that guide.

Agents can locate the nearest N blocks with [`search_blocks`](PORTALS.md#block-query-scope-and-freshness), using exact block IDs or tags, a result limit, and an optional radius. Results include ordered positions and distances from the starting position. The server scans loaded chunks around the avatar without moving it or generating missing chunks, and reports incomplete coverage explicitly.

Agents can also [right-click blocks and entities or use held items](INTERACTIONS.md), with physical approach, native interaction hooks, hand/sneak selection, precise hit points and timed item use. These actions cover native buttons, levers, tools, feeding/milking, armor-stand interaction and more, subject to actual world behavior and protection rules.

Coordinates below are absolute block coordinates in the avatar's current dimension. Replace example coordinates with reachable positions in a disposable world. Stock tools/materials through physical pickup or the existing container collection operations. No creative inventory, remote block mutation, or elevated command authority is supplied by these actions.

## Mining

`mine_blocks` accepts exactly one target form:

```json
{"op":"mine_blocks","block":[100,64,100],"saveAs":"dug"}
{"op":"mine_blocks","blocks":[[100,64,100],[101,64,100]],"saveAs":"dug"}
{"op":"mine_blocks","cuboid":{"from":[100,64,100],"to":[104,66,104]},"saveAs":"dug"}
```

Cuboid endpoints are inclusive and normalized. The server walks top layers before lower layers and generates targets lazily; it does not allocate or load the whole cuboid. It approaches one target, chooses an effective carried tool using native harvest and break-speed behavior, advances actual break progress over ticks, and invokes native destruction. The normal tool hooks, durability, loot, experience, and break protection apply. An inappropriate tool tier does not destroy a block whose drops it cannot harvest.

After breaking, it physically approaches available nearby drops and uses the normal pickup interaction. Native pickup delays and ownership are respected. A full inventory leaves uncollected items in the world and fails with the actual partial result. Air targets are counted as skipped; an unexpected change during an active break fails explicitly.

The result reports requested/processed targets, broken/skipped counts, current target/progress, acquired count, and exact `acquiredStacks`. The ledger records native pickups while this job is active, excluding pre-existing carried stacks. Other loose items picked up during active work can enter that ledger; pickups while paused are not attributed to it. Replanting, when applicable, deducts consumed newly acquired seeds. The inventory snapshot remains authoritative if items are later consumed, removed, or changed.

Mining results can feed `deliver_items` through `itemsFrom: "dug"`, using the same acquired-stack rules as container collection. The server does not infer that every broken block must drop an item; loot rules and world conditions control actual drops.

Pause/cancel clears crack visuals, pickup subscriptions, and movement. No delayed native block break continues after cancellation. An interrupted block restarts its hold-to-break progress; completed targets and observed pickups remain checkpointed. Disconnect/rebind is tested between actual blocks without repeating extraction. Arbitrary cross-file crash transactions remain outside the guarantee.

## Placement

```json
{
  "op":"place_blocks",
  "placements":[
    {"position":[100,64,100],"item":"minecraft:cobblestone"},
    {"position":[101,64,100],"item":"minecraft:cobblestone"}
  ],
  "saveAs":"built"
}
```

The avatar approaches each target and selects the requested carried item. Placement finds a reachable visible face from the supporting block's actual shape, including partial-height blocks. It uses the native block/item interaction while sneaking to avoid opening a supporting container. World bounds, item use rules, normal placement requirements, Forge place hooks, stack metadata, and actual consumption govern the result.

Destinations must be replaceable, with a usable adjacent support face. Failed/denied placement stops the remaining list. Native canceled-place behavior restores world/item changes; results retain completed placement and consumed-input counts. The cursor survives body/NBT rebinds. A catch may handle failure and continue the enclosing program.

This supports initial block-placement sequences. Materials can be prepared with [crafting operations](CRAFTING.md). Structure planning, automatic recursive material acquisition, and every approach/terrain obstruction are not implemented.

## Farming

```json
{"op":"farm","cuboid":{"from":[100,64,100],"to":[104,64,104]},"plant":"minecraft:wheat_seeds","saveAs":"harvest"}
```

Targets are crop blocks above their soil, using the same single/list/cuboid forms as mining. The initial adapter recognizes mature `CropBlock` instances, including compatible subclasses. Air, non-crop blocks, and immature crops are skipped. Mature crops use native breaking and physical pickup; optional `plant` selects a carried seed/plant item and replants through normal placement. The operation verifies that the expected crop block was created.

The result includes harvested/broken, skipped, replanted, and acquired counts. Actual collected seeds and one consumed input per successful standard replant are verified in Minecraft. Omitting `plant` harvests without replanting. Missing seeds or refused planting report the already-harvested partial state. Tilling, irrigation, and other crop APIs remain later work. Native crafting is documented separately in [CRAFTING.md](CRAFTING.md).

## Equipment

```json
{"op":"equip","item":"minecraft:iron_helmet","slot":"head"}
{"op":"equip","item":"minecraft:shield","slot":"offhand"}
{"op":"equip","item":"minecraft:diamond_pickaxe","slot":"mainhand"}
```

Items accept registry IDs or tags. Slots are `mainhand`, `offhand`, `head`, `chest`, `legs`, and `feet`. Main-hand selection uses a hotbar item or exchanges it with a carried inventory item. Other equipment uses native slot acceptance/removal rules, including Forge item equipping and binding curses. Replaced equipment stays in the inventory; an exchange requiring unavailable space fails.

If no spare matching item is carried and the requested item is already equipped, it acknowledges that state. Otherwise it uses a matching carried item. Stack data is preserved. Extra accessory/custom equipment slots require adapters.

### Unequipping and dropping existing belongings

```json
{"op":"unequip","slot":"head","saveAs":"removed"}
{"op":"drop_items","item":"minecraft:diamond_helmet","quantity":1,"slot":"head","recipient":{"playerUuid":"<recipient UUID>"},"saveAs":"given"}
{"op":"drop_items","item":"minecraft:cobblestone","quantity":"all"}
```

`unequip` moves the entire named equipment/held stack into main inventory, preserving NBT, damage and enchantments. Main-hand unequipping excludes the selected hotbar cell so the hand actually becomes empty. Empty slots succeed with zero moved. Native removal restrictions, including Curse of Binding, apply. Insufficient inventory space leaves gear equipped. The result contains `moved` and an exact `acquiredStacks` ledger; this ledger may feed `deliver_items` even though the gear was previously owned.

`drop_items` needs no collection or crafting ledger. It accepts an item ID/tag and a nonnegative integer quantity or `"all"`. Without `slot`, it selects main inventory and offhand. A named equipment slot allows a direct worn/held drop, including when main inventory is full. Optional `recipient` makes the server walk to that player and drop at their feet with native pickup ownership; otherwise it uses the normal local toss. A missing recipient or different dimension fails without removing items.

Drops use native slot removal and Forge toss hooks. A cancelled toss or refused world entity returns missing items, without duplicating an item already refunded by the cancelling hook. Results retain actual dropped stacks after hook metadata changes, entity UUIDs, removed/dropped counts and any partial progress. At most 64 items drop per tick. Selection is frozen when dropping begins; an interrupted/restored action resumes its remaining source quantities instead of replaying prior drops. A shortage can leave successful partial drops and then report failure. No implicit crafting of replacement gear is needed to give away an existing helmet.

## Following and melee

```json
{"op":"follow","target":"<entity UUID>","distance":2,"ticks":200}
{"op":"attack_entity","target":"<living entity UUID>","ticks":400,"saveAs":"fight"}
```

Following continually updates its route to the entity's actual position. Optional `distance` is at least 0.5; it defaults to two blocks. Positive `ticks` follows for that many active ticks; zero/omitted runs until interrupted or the target is lost.

Melee approaches a living target, selects a carried weapon using its damage modifiers and enchantments, observes native attack cooldowns, and uses the normal attack/hurt/durability paths. Bare hands work without a carried weapon. Reach, visibility, Forge attack cancellation, and the target's damage rules still apply. Ten consecutive attempts causing no observable damage report refusal instead of attacking forever. Optional positive `ticks` bounds the attempt; zero/omitted continues until the target is defeated or work fails.

Results report actual attempts, damaging hits, elapsed ticks, and observed target health/defeat. They do not claim exclusive kill credit if something else defeats the target. Target loss or a different dimension fails rather than automatically entering a portal. Ranged/special weapon adapters are later expansion. [BEHAVIOR.md](BEHAVIOR.md) describes automatic reactions, oxygen escape, and idle patrols.

## Verification and compatibility

`set_spawn` and server-driven eating/death/respawn are described in [SURVIVAL.md](SURVIVAL.md), including native bed rules, checkpoints, control interruption, and exact verified limits.

The headless suite uses real blocks, inventories, equipment, crop drops, moving entities, and attacks. It verifies tool selection/wear, mining checkpoints and exact drop-ledger delivery, pause/cancel, wrong tiers, denied clicks/breaks/placements/attacks, full inventory preservation, placement checkpoints, crop maturity/replant consumption, armor slot/binding rules, following, armed combat, and bare hands.

The implementation uses the native/Forge item and loot entry points rather than a registry-name tool whitelist. [Forge item properties](https://docs.minecraftforge.net/en/1.20.1/items/) and [Forge loot behavior](https://docs.minecraftforge.net/en/1.20.x/resources/server/loottables/) provide the underlying extension surfaces. Testing so far covers vanilla items plus actual Forge cancellation hooks, not an arbitrary modpack. Custom crop systems, direct-inventory harvesters, accessory slots, special weapon control, and unusual placement mechanics can require adapters. See [NAVIGATION.md](NAVIGATION.md) for physical travel and local terrain recovery, [COMMANDS.md](COMMANDS.md) for native commands, and [PORTALS.md](PORTALS.md) for selected dimension travel.
