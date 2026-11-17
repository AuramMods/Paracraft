package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.InventoryAccess;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** One native recipe pickup per tick; all grid inputs/remainders stay in real player menus. */
public final class CraftJob extends GameJob {
    private final JsonObject specification;
    private final Item item;
    private final BlockPos table;
    private final int quantity;
    private final String recipeId;
    private AbstractContainerMenu menu;
    private CraftingContainer grid;
    private List<CraftingRecipe> candidates;
    private int cursor;
    private long crafted, crafts, carried;
    private final List<ItemStack> acquired = new ArrayList<>();
    public CraftJob(UUID id, CompanionPlayer actor, JsonObject specification) {
        super(id, "craft_item", actor); this.specification = specification.deepCopy();
        item = CraftingPlanner.item(specification.get("item").getAsString()); quantity = specification.get("quantity").getAsInt();
        table = specification.has("table") ? JobJson.position(specification.get("table")) : null;
        recipeId = specification.has("recipe") ? specification.get("recipe").getAsString() : null; update();
    }
    @Override protected void advance() {
        if (crafted >= quantity) { succeed(result); return; }
        if (table != null) { phase = "approaching_crafting_table"; if (!navigate(Vec3.atCenterOf(table), 2.6)) return; }
        if (menu == null) {
            actor.closeContainer();
            if (table == null) menu = actor.inventoryMenu;
            else {
                InventoryAccess.open(actor, table); menu = actor.containerMenu;
                if (!(menu instanceof CraftingMenu)) throw new IllegalStateException("Selected block did not open a supported crafting-table menu");
            }
            if (!(menu.getSlot(1).container instanceof CraftingContainer actualGrid)) throw new IllegalStateException("Menu has no native crafting grid");
            grid = actualGrid; candidates = null; cursor = 0;
        }
        if (actor.containerMenu != menu || !menu.stillValid(actor)) throw new IllegalStateException("Crafting menu was closed, removed, or became invalid");
        phase = "selecting_recipe";
        CraftingPlanner.Plan plan = null;
        if (specification.has("grid")) {
            int[] slots = specification.getAsJsonArray("grid").asList().stream().mapToInt(JsonElement::getAsInt).toArray();
            plan = CraftingPlanner.explicit(actor, slots, grid.getWidth(), grid.getHeight(), recipeId);
        } else {
            if (candidates == null) candidates = CraftingPlanner.recipes(actor, item, recipeId);
            for (int budget = 16; budget > 0 && cursor < candidates.size() && plan == null; budget--)
                plan = CraftingPlanner.automatic(actor, candidates.get(cursor++), grid.getWidth(), grid.getHeight());
            if (plan == null) {
                if (cursor < candidates.size()) return;
                throw new IllegalStateException("No native recipe could be filled with carried ingredients in the " + grid.getWidth() + "x" + grid.getHeight()
                        + " grid" + (table == null ? "; use a crafting table for larger recipes" : "") + ". Dynamic recipes may require an explicit grid.");
            }
        }
        // This is the real crafting matrix, not an assembled-item shortcut.
        CraftingPlanner.grid(actor, plan.slots(), grid.getWidth(), grid.getHeight());
        phase = "crafting";
        for (int cell = 0; cell < plan.slots().length; cell++) if (plan.slots()[cell] >= 0)
            grid.setItem(cell, actor.getInventory().removeItem(plan.slots()[cell], 1));
        menu.slotsChanged(grid);
        var outputSlot = menu.getSlot(0); var output = outputSlot.getItem().copy();
        if (output.isEmpty() || !output.is(item) || !outputSlot.mayPickup(actor)) throw new IllegalStateException("The native crafting menu did not offer the requested output");
        var nativeRecipe = outputSlot.container instanceof ResultContainer results ? results.getRecipeUsed() : null;
        if (recipeId != null && (nativeRecipe == null || !nativeRecipe.getId().toString().equals(recipeId)))
            throw new IllegalStateException("The native crafting menu selected a different recipe");
        result.addProperty("recipe", nativeRecipe == null ? plan.recipe().getId().toString() : nativeRecipe.getId().toString());
        result.addProperty("gridWidth", grid.getWidth()); result.addProperty("gridHeight", grid.getHeight());
        var before = actor.getInventory().items.stream().map(ItemStack::copy).toList();
        // Use one actual shift-click result transfer. Native ResultSlot handles consumption,
        // recipe awards, crafting callbacks and remaining buckets/tools.
        var taken = menu.quickMoveStack(actor, 0);
        if (taken.isEmpty()) throw new IllegalStateException("Crafting output could not move into the avatar inventory");
        long accepted = rememberActualOutputs(before, taken.getCount());
        crafted += taken.getCount(); crafts++; carried += accepted; update();
        actor.closeContainer(); menu = null; grid = null;
        if (accepted < taken.getCount()) throw new IllegalStateException("Crafted output did not fully fit; native overflow was dropped at the avatar");
        if (crafted >= quantity) succeed(result);
    }
    private long rememberActualOutputs(List<ItemStack> before, int maximum) {
        var groups = new ArrayList<ItemStack>();
        for (var stack : actor.getInventory().items) if (stack.is(item)) {
            var group = groups.stream().filter(existing -> ItemStack.isSameItemSameTags(existing, stack)).findFirst().orElse(null);
            if (group == null) groups.add(stack.copy()); else group.grow(stack.getCount());
        }
        long accepted = 0;
        for (var stack : groups) {
            long previous = before.stream().filter(old -> ItemStack.isSameItemSameTags(old, stack)).mapToLong(ItemStack::getCount).sum();
            long gained = Math.min(maximum - accepted, Math.max(0, stack.getCount() - previous));
            remember(stack, gained); accepted += gained;
        }
        return accepted;
    }
    private void remember(ItemStack stack, long amount) {
        for (var previous : acquired) if (ItemStack.isSameItemSameTags(previous, stack) && amount > 0) {
            int moved = (int)Math.min(amount, previous.getMaxStackSize() - previous.getCount()); previous.grow(moved); amount -= moved;
        }
        while (amount > 0) {
            int count = (int)Math.min(amount, stack.getMaxStackSize());
            acquired.add(stack.copyWithCount(count)); amount -= count;
        }
    }
    private void update() {
        result.addProperty("item", specification.get("item").getAsString()); result.addProperty("requested", quantity);
        result.addProperty("crafted", crafted); result.addProperty("crafts", crafts); result.addProperty("acquired", carried);
        result.addProperty("overflow", crafted - carried); result.add("acquiredStacks", JobJson.stacks(acquired));
        if (table != null) result.add("table", JobJson.position(table));
    }
    @Override protected void onStop() { menu = null; grid = null; candidates = null; cursor = 0; }
    @Override protected JsonObject writeData() {
        var data = new JsonObject(); data.add("specification", specification.deepCopy()); data.addProperty("crafted", crafted);
        data.addProperty("crafts", crafts); data.addProperty("carried", carried); data.add("acquired", JobJson.stacks(acquired)); return data;
    }
    @Override protected void readData(JsonObject data) {
        crafted = data.get("crafted").getAsLong(); crafts = data.get("crafts").getAsLong(); carried = data.get("carried").getAsLong();
        acquired.clear(); acquired.addAll(JobJson.stacks(data.getAsJsonArray("acquired"))); update();
    }
}
