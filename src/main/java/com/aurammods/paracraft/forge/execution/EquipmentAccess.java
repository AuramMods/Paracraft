package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

final class EquipmentAccess {
    private EquipmentAccess() { }
    static Slot slot(CompanionPlayer actor,String name) {
        var equipment=EquipmentSlot.byName(name);
        int index=equipment==EquipmentSlot.MAINHAND?36+actor.getInventory().selected:equipment==EquipmentSlot.OFFHAND?45:8-equipment.getIndex();
        return actor.inventoryMenu.getSlot(index);
    }
    static int menuIndex(int inventoryIndex){return inventoryIndex<9?36+inventoryIndex:inventoryIndex;}
    static void restore(CompanionPlayer actor,Slot source,ItemStack stack) {
        if(stack.isEmpty())return;
        if(source.getItem().isEmpty())source.setByPlayer(stack);
        else if(ItemStack.isSameItemSameTags(source.getItem(),stack) && source.getItem().getCount()+stack.getCount()<=source.getMaxStackSize(stack)){
            var combined=source.getItem().copy();combined.grow(stack.getCount());source.setByPlayer(combined);
        }else actor.getInventory().placeItemBackInInventory(stack);
        actor.getInventory().setChanged();actor.inventoryMenu.broadcastChanges();
    }
}
