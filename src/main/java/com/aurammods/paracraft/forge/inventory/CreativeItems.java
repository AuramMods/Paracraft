package com.aurammods.paracraft.forge.inventory;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackLinkedSet;
import net.minecraftforge.common.ForgeHooks;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Server-owned catalog of actual tab entries, including their native stack variants. */
public final class CreativeItems {
    private final Map<CreativeModeTab.ItemDisplayParameters, Map<String, List<ItemStack>>> catalogs = new HashMap<>();
    private ResourceManager resources;

    public static boolean available(CompanionPlayer actor) { return actor.isCreative(); }

    private Map<String, List<ItemStack>> catalog(CompanionPlayer actor) {
        if (!available(actor)) throw new IllegalStateException("Creative items require Creative game mode");
        MinecraftServer server = actor.server;
        if (resources != server.getResourceManager()) {
            catalogs.clear();
            resources = server.getResourceManager();
        }
        var parameters = new CreativeModeTab.ItemDisplayParameters(actor.serverLevel().enabledFeatures(),
                actor.hasPermissions(2), actor.serverLevel().registryAccess());
        return catalogs.computeIfAbsent(parameters, CreativeItems::build);
    }

    private static Map<String, List<ItemStack>> build(CreativeModeTab.ItemDisplayParameters parameters) {
        var visible = ItemStackLinkedSet.createTypeAndTagSet();
        var searchable = ItemStackLinkedSet.createTypeAndTagSet();
        for (var entry : BuiltInRegistries.CREATIVE_MODE_TAB.entrySet()) {
            var tab = entry.getValue();
            if (tab.getType() != CreativeModeTab.Type.CATEGORY) continue;
            ForgeHooks.onCreativeModeTabBuildContents(tab, entry.getKey(), tab.displayItemsGenerator, parameters, (stack, visibility) -> {
                if (stack.isEmpty() || !stack.isItemEnabled(parameters.enabledFeatures())) return;
                if (visibility != CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY || tab.hasSearchBar()) visible.add(stack.copy());
                if (visibility != CreativeModeTab.TabVisibility.PARENT_TAB_ONLY) searchable.add(stack.copy());
            });
        }
        // Run the real search tab's Forge event with the server's search entries as its starting contents.
        ForgeHooks.onCreativeModeTabBuildContents(CreativeModeTabs.searchTab(), CreativeModeTabs.SEARCH,
                (ignored, output) -> output.acceptAll(searchable), parameters, (stack, visibility) -> {
                    if (visibility != CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY
                            && !stack.isEmpty() && stack.isItemEnabled(parameters.enabledFeatures())) visible.add(stack.copy());
                });
        var items = new LinkedHashMap<String, List<ItemStack>>();
        visible.stream().sorted(Comparator.comparing(CreativeItems::id).thenComparing(ItemStack::hasTag))
                .forEach(stack -> items.computeIfAbsent(id(stack), ignored -> new ArrayList<>()).add(stack));
        return items;
    }

    private static String id(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }

    public ItemStack find(CompanionPlayer actor, ItemSelector selector) {
        if (!available(actor)) return ItemStack.EMPTY;
        var items = catalog(actor);
        if (!selector.expression().startsWith("#")) {
            var variants = items.get(selector.expression());
            return variants == null ? ItemStack.EMPTY : variants.get(0).copy();
        }
        return items.values().stream().map(stacks -> stacks.get(0)).filter(selector::matches)
                .findFirst().map(ItemStack::copy).orElse(ItemStack.EMPTY);
    }

    public ItemStack require(CompanionPlayer actor, ItemSelector selector, int variant) {
        var variants = catalog(actor).get(selector.expression());
        if (variants == null || variant < 0 || variant >= variants.size())
            throw new IllegalStateException("No visible creative item or variant matches " + selector.expression());
        return variants.get(variant).copy();
    }

    public JsonObject search(CompanionPlayer actor, String query, int offset, int limit) {
        var entries = new JsonArray();
        int matched = 0;
        String term = query.toLowerCase(Locale.ROOT).replace('_', ' ');
        for (var group : catalog(actor).entrySet()) {
            for (int variant = 0; variant < group.getValue().size(); variant++) {
                var stack = group.getValue().get(variant);
                String id = group.getKey(), name = stack.getHoverName().getString();
                if (!id.replace('_', ' ').contains(term) && !name.toLowerCase(Locale.ROOT).contains(term)) continue;
                if (matched++ < offset || entries.size() >= limit) continue;
                var entry = new JsonObject();
                entry.addProperty("item", id);
                entry.addProperty("name", name);
                entry.addProperty("variant", variant);
                if (stack.hasTag()) entry.addProperty("snbt", stack.save(new CompoundTag()).toString());
                entries.add(entry);
            }
        }
        var result = new JsonObject();
        result.add("entries", entries);
        result.addProperty("total", matched);
        if ((long) offset + entries.size() < matched) result.addProperty("nextOffset", offset + entries.size());
        return result;
    }
}
