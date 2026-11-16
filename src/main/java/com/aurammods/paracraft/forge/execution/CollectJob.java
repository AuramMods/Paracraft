package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.InventoryAccess;
import com.aurammods.paracraft.forge.inventory.InventorySearch;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class CollectJob extends GameJob {
    private final ItemSelector selector;
    private final long quantity;
    private final InventorySearch search;
    private List<InventorySearch.Match> sources;
    private final List<ItemStack> acquired = new ArrayList<>();
    private long count, fromSource, requested;
    private int visited;
    private boolean opened;

    /** quantity -1 means all initially observed matches in the frozen neighborhood. */
    public CollectJob(UUID id, CompanionPlayer actor, ItemSelector selector, long quantity) {
        super(id, "collect_items", actor);
        if (quantity < -1) throw new IllegalArgumentException("Quantity must be nonnegative or -1 for all");
        this.selector = selector; this.quantity = quantity;
        search = new InventorySearch(actor.serverLevel(), actor.chunkPosition(), selector);
    }
    public List<ItemStack> acquired() { return acquired.stream().map(ItemStack::copy).toList(); }
    @Override protected void onPause() { opened = false; }
    @Override protected void advance() {
        if (sources == null) {
            phase = "searching"; search.tick();
            if (!search.done()) return;
            sources = new ArrayList<>(search.matches());
            sources.sort(Comparator.comparingDouble(m -> actor.distanceToSqr(Vec3.atCenterOf(m.position()))));
            requested = quantity < 0 ? sources.stream().mapToLong(InventorySearch.Match::count).sum() : quantity;
        }
        updateResult();
        if (count >= requested) { succeed(result); return; }
        if (visited >= sources.size()) { fail("Only " + count + " of " + requested + " requested items could be collected"); return; }
        InventorySearch.Match source = sources.get(visited);
        phase = "traveling to source " + (visited + 1) + "/" + sources.size();
        if (!navigate(Vec3.atCenterOf(source.position()), 2.6)) return;
        if (!opened) { InventoryAccess.open(actor, source.position()); opened = true; }
        phase = "collecting";
        long sourceRemaining = source.count() - fromSource;
        int amount = (int)Math.min(64, Math.min(requested - count, sourceRemaining));
        if (amount <= 0) { nextSource(); return; }
        int transferred = InventoryAccess.take(actor, source.position(), selector, amount, stack -> {
            rememberAcquired(stack); count += stack.getCount(); fromSource += stack.getCount(); updateResult();
        });
        if (transferred == 0) {
            var current = InventoryAccess.resolve(actor.serverLevel(), source.position());
            if (current != null && InventoryAccess.count(current, selector) > 0)
                throw new IllegalStateException("Matching items are visible but extraction is refused");
            nextSource();
        } else if (fromSource >= source.count()) nextSource();
        updateResult();
    }
    private void nextSource() { visited++; fromSource = 0; opened = false; actor.closeContainer(); }
    private void rememberAcquired(ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (ItemStack existing : acquired) if (ItemStack.isSameItemSameTags(existing, remaining)) {
            int merged = Math.min(remaining.getCount(), existing.getMaxStackSize() - existing.getCount());
            existing.grow(merged); remaining.shrink(merged);
            if (remaining.isEmpty()) return;
        }
        if (!remaining.isEmpty()) acquired.add(remaining);
    }
    private void updateResult() {
        result.addProperty("requested", requested); result.addProperty("acquired", count);
        result.addProperty("visitedSources", visited); result.addProperty("remainingSources", sources == null ? 0 : sources.size() - visited);
        result.add("search", search.result());
        JsonArray stacks = new JsonArray();
        for (ItemStack stack : acquired) stacks.add(stack.save(new net.minecraft.nbt.CompoundTag()).toString());
        result.add("acquiredStacks", stacks);
    }
}
