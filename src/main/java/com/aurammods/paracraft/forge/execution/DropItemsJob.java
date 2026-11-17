package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Explicit disposal/giving of existing belongings, including optional worn equipment. */
public final class DropItemsJob extends GameJob {
    private final JsonObject specification;
    private final List<Source> sources=new ArrayList<>();
    private final List<ItemStack> droppedStacks=new ArrayList<>();
    private final JsonArray entities=new JsonArray();
    private record Source(int slot,ItemStack remaining) { }
    private boolean prepared;
    private int cursor;
    private long requested,processed,dropped;
    public DropItemsJob(UUID id,CompanionPlayer actor,JsonObject specification){super(id,"drop_items",actor);this.specification=specification.deepCopy();if(!specification.get("quantity").getAsString().equals("all"))requested=specification.get("quantity").getAsInt();}
    @Override protected void advance(){
        update();
        ServerPlayer recipient=null;
        if(specification.has("recipient")){
            var value=specification.get("recipient");UUID target=UUID.fromString(value.isJsonObject()?value.getAsJsonObject().get("playerUuid").getAsString():value.getAsString());
            recipient=actor.server.getPlayerList().getPlayer(target);
            if(recipient==null || !recipient.isAlive() || recipient.isRemoved())throw new IllegalStateException("Drop recipient is unavailable");
            if(recipient.serverLevel()!=actor.serverLevel())throw new IllegalStateException("Drop recipient is in another dimension");
            if(recipient==actor)throw new IllegalArgumentException("An avatar cannot hand items to itself");
            phase="traveling to recipient";if(!navigate(recipient.position(),2.0))return;
        }
        if(!prepared)prepare();
        if(processed>=requested){succeed(result);return;}
        while(cursor<sources.size() && sources.get(cursor).remaining.isEmpty())cursor++;
        if(cursor>=sources.size())throw new IllegalStateException("Not enough matching carried items; actual partial drops are preserved");
        var wanted=sources.get(cursor);Slot source=actor.inventoryMenu.getSlot(wanted.slot);ItemStack present=source.getItem();
        if(!ItemStack.isSameItemSameTags(present,wanted.remaining))throw new IllegalStateException("The selected item slot changed; actual partial drops are preserved");
        if(!source.mayPickup(actor))throw new IllegalStateException("Native item slot refuses removal (for example Curse of Binding)");
        phase="dropping_items";int amount=(int)Math.min(Math.min(64,requested-processed),Math.min(present.getCount(),wanted.remaining.getCount()));
        long ownedBefore=count(present);
        ItemStack removed=source.remove(amount),backup=removed.copy();
        var entity=actor.drop(removed,false); // Native Forge ItemTossEvent, including cancellation.
        if(entity==null || entity.isRemoved() || !entity.isAddedToWorld()){
            // Some cancellation hooks already return the item themselves. Restore only
            // the actual deficit so a native refund is not duplicated by this action.
            int missing=(int)Math.min(backup.getCount(),Math.max(0,ownedBefore-count(backup)));
            EquipmentAccess.restore(actor,source,backup.copyWithCount(missing));throw new IllegalStateException("Native item drop was cancelled or the world refused its entity");
        }
        if(recipient!=null){entity.setPos(recipient.getX(),recipient.getY()+0.1,recipient.getZ());entity.setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);entity.setTarget(recipient.getUUID());}
        wanted.remaining.shrink(amount);processed+=amount;dropped+=entity.getItem().getCount();droppedStacks.add(entity.getItem().copy());entities.add(entity.getUUID().toString());
        source.onTake(actor,backup);actor.getInventory().setChanged();actor.inventoryMenu.broadcastChanges();update();
        if(processed>=requested)succeed(result);
    }
    private long count(ItemStack item){long total=0;for(int i=0;i<actor.getInventory().getContainerSize();i++){var stack=actor.getInventory().getItem(i);if(stack.is(item.getItem()))total+=stack.getCount();}return total;}
    private void prepare(){
        var selector=new ItemSelector(specification.get("item").getAsString());
        boolean all=specification.get("quantity").isJsonPrimitive() && specification.get("quantity").getAsString().equals("all");
        requested=all?Long.MAX_VALUE:specification.get("quantity").getAsInt();long available=0;
        var slots=new ArrayList<Integer>();
        if(specification.has("slot"))slots.add(EquipmentAccess.slot(actor,specification.get("slot").getAsString()).index);
        else {for(int i=0;i<actor.getInventory().items.size();i++)slots.add(EquipmentAccess.menuIndex(i));slots.add(45);}
        for(int index:slots){ItemStack stack=actor.inventoryMenu.getSlot(index).getItem();if(!selector.matches(stack))continue;
            int count=(int)Math.min(stack.getCount(),requested-available);if(count<=0)break;
            sources.add(new Source(index,stack.copyWithCount(count)));available+=count;
        }
        if(all)requested=available;
        prepared=true;update();
    }
    private void update(){result.addProperty("requested",requested);result.addProperty("removed",processed);result.addProperty("dropped",dropped);result.add("droppedStacks",JobJson.stacks(droppedStacks));result.add("itemEntities",entities.deepCopy());}
    @Override protected JsonObject writeData(){
        var data=new JsonObject();data.add("specification",specification.deepCopy());data.addProperty("prepared",prepared);data.addProperty("cursor",cursor);
        data.addProperty("requested",requested);data.addProperty("processed",processed);data.addProperty("dropped",dropped);data.add("droppedStacks",JobJson.stacks(droppedStacks));data.add("entities",entities.deepCopy());
        var entries=new JsonArray();for(var source:sources){var value=new JsonObject();value.addProperty("slot",source.slot);value.addProperty("stack",source.remaining.save(new CompoundTag()).toString());entries.add(value);}data.add("sources",entries);return data;
    }
    @Override protected void readData(JsonObject data){
        prepared=data.get("prepared").getAsBoolean();cursor=data.get("cursor").getAsInt();requested=data.get("requested").getAsLong();processed=data.get("processed").getAsLong();dropped=data.get("dropped").getAsLong();
        droppedStacks.clear();droppedStacks.addAll(JobJson.stacks(data.getAsJsonArray("droppedStacks")));entities.addAll(data.getAsJsonArray("entities"));
        sources.clear();for(var entry:data.getAsJsonArray("sources")){var value=entry.getAsJsonObject();var stack=new JsonArray();stack.add(value.get("stack"));sources.add(new Source(value.get("slot").getAsInt(),JobJson.stacks(stack).get(0)));}
    }
}
