package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.common.crafting.IShapedRecipe;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Assign real carried stacks to native recipe ingredients, including overlapping alternatives. */
final class CraftingPlanner {
    record Plan(CraftingRecipe recipe, int[] slots) { }
    private static final AbstractContainerMenu PROBE = new AbstractContainerMenu(null, -1) {
        public ItemStack quickMoveStack(Player player, int index) { return ItemStack.EMPTY; }
        public boolean stillValid(Player player) { return true; }
        public void slotsChanged(net.minecraft.world.Container container) { }
    };
    static Item item(String id) {
        var key = ResourceLocation.tryParse(id);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) throw new IllegalArgumentException("Unknown crafting output item: " + id);
        var item = BuiltInRegistries.ITEM.get(key);
        if (item == net.minecraft.world.item.Items.AIR) throw new IllegalArgumentException("Cannot craft air");
        return item;
    }
    static List<CraftingRecipe> recipes(CompanionPlayer actor, Item item, String recipeId) {
        return actor.serverLevel().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream()
                .filter(recipe -> recipeId == null ? recipe.getResultItem(actor.level().registryAccess()).is(item) : recipe.getId().toString().equals(recipeId))
                .sorted(java.util.Comparator.comparing(recipe -> recipe.getId().toString())).toList();
    }
    static Plan automatic(CompanionPlayer actor, CraftingRecipe recipe, int width, int height) {
        if (!recipe.canCraftInDimensions(width, height)) return null;
        var ingredients = recipe.getIngredients();
        if (ingredients.isEmpty()) return null; // Dynamic/special recipes can use an explicit carried-slot grid.
        int recipeWidth = width;
        if (recipe instanceof IShapedRecipe<?> shaped) {
            recipeWidth = shaped.getRecipeWidth();
            if (recipeWidth < 1 || shaped.getRecipeHeight() < 1 || recipeWidth > width || shaped.getRecipeHeight() > height
                    || ingredients.size() != recipeWidth * shaped.getRecipeHeight()) return null;
        }
        var needed = new ArrayList<Ingredient>(); var cells = new ArrayList<Integer>();
        for (int i = 0; i < ingredients.size(); i++) if (!ingredients.get(i).isEmpty()) {
            int cell = recipe instanceof IShapedRecipe<?> ? (i / recipeWidth) * width + i % recipeWidth : needed.size();
            if (cell >= width * height) return null;
            needed.add(ingredients.get(i)); cells.add(cell);
        }
        if (needed.isEmpty()) return null;
        var units = new ArrayList<ItemStack>(); var sources = new ArrayList<Integer>();
        for (int slot = 0; slot < actor.getInventory().items.size(); slot++) {
            var stack = actor.getInventory().getItem(slot);
            for (int count = 0; count < Math.min(stack.getCount(), needed.size()); count++) { units.add(stack.copyWithCount(1)); sources.add(slot); }
        }
        var owners = new int[units.size()]; Arrays.fill(owners, -1);
        for (int ingredient = 0; ingredient < needed.size(); ingredient++)
            if (!assign(ingredient, needed, units, owners, new boolean[units.size()])) return null;
        var slots = new int[width * height]; Arrays.fill(slots, -1);
        for (int unit = 0; unit < owners.length; unit++) if (owners[unit] >= 0) slots[cells.get(owners[unit])] = sources.get(unit);
        return recipe.matches(grid(actor, slots, width, height), actor.level()) ? new Plan(recipe, slots) : null;
    }
    private static boolean assign(int ingredient, List<Ingredient> needed, List<ItemStack> units, int[] owners, boolean[] visited) {
        for (int unit = 0; unit < units.size(); unit++) {
            if (visited[unit] || !needed.get(ingredient).test(units.get(unit))) continue;
            visited[unit] = true;
            if (owners[unit] < 0 || assign(owners[unit], needed, units, owners, visited)) { owners[unit] = ingredient; return true; }
        }
        return false;
    }
    static Plan explicit(CompanionPlayer actor, int[] slots, int width, int height, String recipeId) {
        var grid = grid(actor, slots, width, height);
        var recipe = actor.level().getRecipeManager().getRecipeFor(RecipeType.CRAFTING, grid, actor.level())
                .orElseThrow(() -> new IllegalStateException("The supplied crafting grid does not match a native recipe"));
        if (recipeId != null && !recipe.getId().toString().equals(recipeId)) throw new IllegalStateException("The native grid selected a different recipe: " + recipe.getId());
        return new Plan(recipe, slots);
    }
    static CraftingContainer grid(CompanionPlayer actor, int[] slots, int width, int height) {
        if (slots.length != width * height) throw new IllegalArgumentException("Crafting grid must contain exactly " + width * height + " carried-slot indices");
        var grid = new TransientCraftingContainer(PROBE, width, height); var used = new int[actor.getInventory().items.size()];
        for (int cell = 0; cell < slots.length; cell++) {
            int slot = slots[cell]; if (slot == -1) continue;
            if (slot < 0 || slot >= used.length) throw new IllegalArgumentException("Crafting source slots must be -1 or main-inventory indices 0..35");
            var stack = actor.getInventory().getItem(slot);
            if (++used[slot] > stack.getCount()) throw new IllegalStateException("Carried crafting ingredients are missing or depleted");
            grid.setItem(cell, stack.copyWithCount(1));
        }
        return grid;
    }
}
