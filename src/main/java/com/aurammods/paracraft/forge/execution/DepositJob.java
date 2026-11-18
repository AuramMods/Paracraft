package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.InventoryAccess;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public final class DepositJob extends GameJob {
    private final BlockPos destination;
    private final ItemSelector selector;
    private final int quantity;
    private int deposited;
    private boolean opened;
    public DepositJob(UUID id, CompanionPlayer actor, BlockPos destination, ItemSelector selector, int quantity) {
        super(id, "deposit_items", actor); this.destination = destination.immutable(); this.selector = selector; this.quantity = quantity;
        if (quantity < 0) throw new IllegalArgumentException("Deposit quantity must be nonnegative");
    }
    @Override protected void advance() {
        phase = "traveling to destination";
        if (!navigate(Vec3.atCenterOf(destination), 2.6)) return;
        if (!opened) { InventoryAccess.open(actor, destination); opened = true; }
        phase = "depositing";
        if (deposited >= quantity) { succeed(result); return; }
        var stock = CarriedItems.creativeStock(actor, selector);
        int count = Math.min(64, quantity - deposited);
        int moved = stock.isEmpty() ? InventoryAccess.deposit(actor, destination, selector, count)
                : InventoryAccess.depositCreative(actor, destination, stock, count);
        deposited += moved; result.addProperty("deposited", deposited);
        if (moved == 0) fail("Destination refused remaining items or avatar has insufficient matching items");
    }
    @Override protected void onPause() { opened = false; }
    @Override protected com.google.gson.JsonObject writeData() {
        var value = new com.google.gson.JsonObject(); value.add("destination", JobJson.position(destination));
        value.addProperty("selector", selector.expression()); value.addProperty("quantity", quantity); value.addProperty("deposited", deposited); return value;
    }
    @Override protected void readData(com.google.gson.JsonObject value) { deposited = value.get("deposited").getAsInt(); opened = false; }
}
