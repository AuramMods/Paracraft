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
        ItemSelector selector = new ItemSelector(item); EquipmentSlot destination = EquipmentSlot.byName(slot); int index;
        try { index = CarriedItems.find(actor, selector); }
        catch (IllegalStateException absent) {
            if (!selector.matches(actor.getItemBySlot(destination))) throw absent;
            result.addProperty("alreadyEquipped", true); finish(destination); return;
        }
        if (destination == EquipmentSlot.MAINHAND) CarriedItems.select(actor, index);
        else {
            int menuIndex = destination == EquipmentSlot.OFFHAND ? 45 : 8 - destination.getIndex();
            var target = actor.inventoryMenu.getSlot(menuIndex); ItemStack source = actor.getInventory().getItem(index), previous = target.getItem();
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
    private void finish(EquipmentSlot destination) { result.addProperty("slot", slot); result.addProperty("stack", actor.getItemBySlot(destination).save(new CompoundTag()).toString()); succeed(result); }
    @Override protected JsonObject writeData() { JsonObject data = new JsonObject(); data.addProperty("item", item); data.addProperty("slot", slot); return data; }
}
