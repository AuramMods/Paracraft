package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Deliver only the explicitly supplied stack ledger, including its exact tag data. */
public final class DeliverJob extends GameJob {
    private final UUID recipient;
    private final List<ItemStack> remaining;
    private long delivered;
    public DeliverJob(UUID id, CompanionPlayer actor, UUID recipient, List<ItemStack> stacks) {
        super(id, "deliver_items", actor); this.recipient = recipient;
        remaining = new ArrayList<>(stacks.stream().map(ItemStack::copy).toList());
    }
    @Override protected void advance() {
        var entity = actor.server.getPlayerList().getPlayer(recipient);
        if (entity == null && actor.serverLevel().getEntity(recipient) instanceof ServerPlayer player) entity = player;
        if (entity == null || !entity.isAlive()) throw new IllegalStateException("Recipient is unavailable");
        if (entity.serverLevel() != actor.serverLevel()) throw new IllegalStateException("Recipient is in another dimension");
        phase = "traveling to recipient";
        if (!navigate(entity.position(), 2.0)) return;
        phase = "dropping at recipient's feet";
        if (remaining.isEmpty()) { succeed(result); return; }
        ItemStack wanted = remaining.get(0);
        boolean found = false;
        for (int slot = 0; slot < actor.getInventory().items.size(); slot++) {
            ItemStack present = actor.getInventory().getItem(slot);
            if (!ItemStack.isSameItemSameTags(present, wanted)) continue;
            ItemStack moved = actor.getInventory().removeItem(slot, Math.min(wanted.getCount(), present.getCount()));
            var dropped = actor.drop(moved, false);
            if (dropped == null) {
                actor.getInventory().placeItemBackInInventory(moved);
                throw new IllegalStateException("World refused item drop");
            }
            dropped.setPos(entity.getX(), entity.getY() + 0.1, entity.getZ());
            dropped.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            dropped.setTarget(recipient);
            delivered += moved.getCount(); wanted.shrink(moved.getCount()); found = true;
            result.addProperty("delivered", delivered);
            if (wanted.isEmpty()) remaining.remove(0);
            break;
        }
        if (!found) throw new IllegalStateException("Acquired items are no longer in the avatar inventory");
    }
}
