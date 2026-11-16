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
        int moved = InventoryAccess.deposit(actor, destination, selector, Math.min(64, quantity - deposited));
        deposited += moved; result.addProperty("deposited", deposited);
        if (moved == 0) fail("Destination refused remaining items or avatar has insufficient matching items");
    }
    @Override protected void onPause() { opened = false; }
}
