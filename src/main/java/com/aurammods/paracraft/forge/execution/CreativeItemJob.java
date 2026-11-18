package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import com.google.gson.JsonObject;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;

import java.util.UUID;

/** Search the real catalog or take a finite quantity of one of its stack variants. */
final class CreativeItemJob extends GameJob {
    private final JsonObject specification;
    private int acquired;

    CreativeItemJob(UUID id, CompanionPlayer actor, String type, JsonObject specification) {
        super(id, type, actor);
        this.specification = specification.deepCopy();
    }

    @Override protected void advance() {
        var catalog = ServerEvents.avatars(actor.server).creativeItems();
        if (type.equals("search_creative_items")) {
            String query = specification.has("query") ? specification.get("query").getAsString() : "";
            succeed(catalog.search(actor, query, integer("offset", 0), integer("limit", 16)));
            return;
        }
        var sample = catalog.require(actor, new ItemSelector(specification.get("item").getAsString()), integer("variant", 0));
        int quantity = integer("quantity", 1);
        int count = Math.min(sample.getMaxStackSize(), quantity - acquired);
        var offered = sample.copyWithCount(count);
        var remainder = ItemHandlerHelper.insertItemStacked(new PlayerMainInvWrapper(actor.getInventory()), offered, false);
        acquired += count - remainder.getCount();
        result.addProperty("item", specification.get("item").getAsString());
        result.addProperty("requested", quantity);
        result.addProperty("acquired", acquired);
        if (acquired == quantity) succeed(result);
        else if (!remainder.isEmpty()) fail("Inventory is full; creative items taken so far are retained");
    }

    private int integer(String key, int fallback) { return specification.has(key) ? specification.get(key).getAsInt() : fallback; }

    @Override protected JsonObject writeData() {
        var data = new JsonObject();
        data.add("specification", specification.deepCopy());
        data.addProperty("acquired", acquired);
        return data;
    }

    @Override protected void readData(JsonObject data) { acquired = data.get("acquired").getAsInt(); }
}
