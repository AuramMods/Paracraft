package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraftforge.common.crafting.IShapedRecipe;
import java.util.List;
import java.util.UUID;

/** Advisory current recipe/ingredient discovery; actual crafting still uses the real menu. */
public final class RecipeSearchJob extends GameJob {
    private final JsonObject specification;
    private final Item item;
    private List<CraftingRecipe> recipes;
    private int cursor;
    private JsonArray matches = new JsonArray();
    public RecipeSearchJob(UUID id, CompanionPlayer actor, JsonObject specification) {
        super(id, "search_recipes", actor); this.specification = specification.deepCopy(); item = CraftingPlanner.item(specification.get("item").getAsString());
    }
    @Override protected void advance() {
        phase = "searching_recipes"; if (recipes == null) recipes = CraftingPlanner.recipes(actor, item, null);
        int limit = specification.has("limit") ? specification.get("limit").getAsInt() : 16;
        for (int budget = 16; budget > 0 && cursor < recipes.size() && matches.size() < limit; budget--) {
            var recipe = recipes.get(cursor++); var value = new JsonObject();
            value.addProperty("recipe", recipe.getId().toString()); value.addProperty("output", recipe.getResultItem(actor.level().registryAccess()).save(new CompoundTag()).toString());
            value.addProperty("special", recipe.isSpecial()); value.addProperty("fits2x2", recipe.canCraftInDimensions(2, 2)); value.addProperty("fits3x3", recipe.canCraftInDimensions(3, 3));
            value.addProperty("craftableNow2x2", CraftingPlanner.automatic(actor, recipe, 2, 2) != null);
            value.addProperty("craftableNow3x3", CraftingPlanner.automatic(actor, recipe, 3, 3) != null);
            if (recipe instanceof IShapedRecipe<?> shaped) { value.addProperty("width", shaped.getRecipeWidth()); value.addProperty("height", shaped.getRecipeHeight()); }
            var ingredients = new JsonArray();
            for (var ingredient : recipe.getIngredients()) {
                var detail = new JsonObject(); var choices = ingredient.getItems(); var examples = new JsonArray();
                for (int i = 0; i < Math.min(16, choices.length); i++) { var stack = new JsonObject(); stack.addProperty("item", BuiltInRegistries.ITEM.getKey(choices[i].getItem()).toString()); stack.addProperty("snbt", choices[i].save(new CompoundTag()).toString()); examples.add(stack); }
                detail.add("examples", examples); detail.addProperty("alternativesTotal", choices.length); detail.addProperty("truncated", choices.length > 16); ingredients.add(detail);
            }
            value.add("ingredients", ingredients); matches.add(value);
        }
        result.addProperty("item", specification.get("item").getAsString()); result.addProperty("observedAt", actor.level().getGameTime());
        result.add("recipes", matches.deepCopy()); result.addProperty("total", recipes.size()); result.addProperty("truncated", cursor < recipes.size() && matches.size() >= limit);
        if (cursor >= recipes.size() || matches.size() >= limit) succeed(result);
    }
    @Override protected JsonObject writeData() { var data = new JsonObject(); data.add("specification", specification.deepCopy()); return data; }
    @Override protected void readData(JsonObject data) { recipes = null; cursor = 0; matches = new JsonArray(); }
}
