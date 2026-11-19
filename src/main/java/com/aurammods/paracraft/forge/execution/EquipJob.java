package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

public final class EquipJob extends GameJob {
    private final String item, slot;
    public EquipJob(UUID id, CompanionPlayer actor, String item, String slot) { super(id, "equip", actor); this.item = item; this.slot = slot; }
    @Override protected void advance() {
        ItemSelector selector = new ItemSelector(item); EquipmentSlot destination = EquipmentSlot.byName(slot);
        if (destination == EquipmentSlot.OFFHAND && selector.matches(actor.getOffhandItem())) {
            result.addProperty("alreadyEquipped", true);
            finish(destination);
            return;
        }
        int index = CarriedItems.find(actor, selector);
        if (index < 0) {
            if (selector.matches(actor.getItemBySlot(destination))) result.addProperty("alreadyEquipped", true);
            else equipFromCreative(selector);
            finish(destination);
            return;
        }
        if (destination == EquipmentSlot.MAINHAND) CarriedItems.select(actor, index);
        else if (destination == EquipmentSlot.OFFHAND) EquipmentAccess.swapOffhand(actor, index);
        else {
            var target = EquipmentAccess.slot(actor, slot); ItemStack source = actor.getInventory().getItem(index), previous = target.getItem();
            if (!target.mayPlace(source) || !target.mayPickup(actor)) throw new IllegalStateException("Native equipment slot refuses this exchange");
            if (!previous.isEmpty() && source.getCount() > 1 && actor.getInventory().getFreeSlot() < 0) throw new IllegalStateException("No inventory space for replaced equipment");
            ItemStack equipped = source.split(1);
            if (!previous.isEmpty()) {
                if (source.isEmpty()) actor.getInventory().setItem(index, previous);
                else actor.getInventory().setItem(actor.getInventory().getFreeSlot(), previous);
            }
            target.setByPlayer(equipped); actor.getInventory().setChanged();
        }
        finish(destination);
    }
    private void equipFromCreative(ItemSelector selector) {
        ItemStack stock = CarriedItems.creativeStock(actor, selector);
        if (stock.isEmpty()) throw CarriedItems.missing(selector);
        var target = EquipmentAccess.slot(actor, slot);
        ItemStack previous = target.getItem();
        if (!target.mayPlace(stock) || !target.mayPickup(actor))
            throw new IllegalStateException("Native equipment slot refuses this exchange");
        if (!previous.isEmpty()) {
            int free = actor.getInventory().getFreeSlot();
            if (free < 0) throw new IllegalStateException("No inventory space for replaced equipment");
            actor.getInventory().setItem(free, previous);
        }
        target.setByPlayer(stock);
        actor.getInventory().setChanged();
    }
    private void finish(EquipmentSlot destination) { var stack = actor.getItemBySlot(destination); result.addProperty("slot", slot); result.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); result.addProperty("stack", stack.save(new CompoundTag()).toString()); succeed(result); }
    @Override protected JsonObject writeData() { JsonObject data = new JsonObject(); data.addProperty("item", item); data.addProperty("slot", slot); return data; }
}
