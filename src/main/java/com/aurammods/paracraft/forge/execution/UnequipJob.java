package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;
import java.util.List;
import java.util.UUID;

/** Move native equipment into main inventory, preserving tags and native removal restrictions. */
public final class UnequipJob extends GameJob {
    private final String slot;
    public UnequipJob(UUID id,CompanionPlayer actor,String slot){super(id,"unequip",actor);this.slot=slot;}
    @Override protected void advance(){
        var source=EquipmentAccess.slot(actor,slot);var before=source.getItem().copy();
        result.addProperty("slot",slot);result.addProperty("moved",0);result.add("acquiredStacks",JobJson.stacks(List.of()));
        if(before.isEmpty()){result.addProperty("alreadyEmpty",true);succeed(result);return;}
        if(!source.mayPickup(actor))throw new IllegalStateException("Native equipment slot refuses removal (for example Curse of Binding)");
        // The selected hotbar cell is itself part of the main inventory. Exclude it
        // so unequipping the main hand actually leaves the held slot empty.
        int excluded=slot.equals("mainhand")?actor.getInventory().selected:-1;
        var inventory=new PlayerMainInvWrapper(actor.getInventory()){
            @Override public ItemStack insertItem(int index,ItemStack stack,boolean simulate){return index==excluded?stack:super.insertItem(index,stack,simulate);}
        };
        if(!ItemHandlerHelper.insertItemStacked(inventory,before.copy(),true).isEmpty())
            throw new IllegalStateException("No main-inventory space for unequipped items; drop from the equipment slot explicitly if desired");
        ItemStack removed=source.remove(before.getCount());
        ItemStack remainder=ItemHandlerHelper.insertItemStacked(inventory,removed.copy(),false);
        int moved=removed.getCount()-remainder.getCount();
        if(!remainder.isEmpty())EquipmentAccess.restore(actor,source,remainder);
        if(moved>0){source.onTake(actor,removed.copyWithCount(moved));result.addProperty("moved",moved);result.add("acquiredStacks",JobJson.stacks(List.of(removed.copyWithCount(moved))));}
        actor.getInventory().setChanged();actor.inventoryMenu.broadcastChanges();
        if(!remainder.isEmpty())throw new IllegalStateException("Inventory changed during unequip; remaining equipment was retained");
        succeed(result);
    }
    @Override protected JsonObject writeData(){var data=new JsonObject();data.addProperty("slot",slot);return data;}
}
