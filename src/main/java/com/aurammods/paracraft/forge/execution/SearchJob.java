package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.InventorySearch;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import java.util.UUID;

public final class SearchJob extends GameJob {
    private final InventorySearch search;
    public SearchJob(UUID id, CompanionPlayer actor, ItemSelector selector) {
        super(id, "search_containers", actor);
        search = new InventorySearch(actor.serverLevel(), actor.chunkPosition(), selector);
    }
    @Override protected void advance() {
        phase = "searching fixed nine-chunk neighborhood";
        search.tick(); result = search.result();
        if (search.done()) succeed(search.result());
    }
}
