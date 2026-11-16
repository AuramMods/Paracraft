package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.InventoryAccess;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.UUID;

/** One selected container, with bounded inspection/transfer and an exact acquired ledger. */
public final class ContainerJob extends GameJob {
    private final JsonObject specification;
    private final BlockPos position;
    private boolean opened;
    private long requested=-1,acquired;
    private final ArrayList<ItemStack> stacks=new ArrayList<>();
    private int viewIndex,slot,inspected;
    private JsonArray views=new JsonArray();
    public ContainerJob(UUID id,CompanionPlayer actor,String type,JsonObject specification){super(id,type,actor);this.specification=specification.deepCopy();position=JobJson.position(specification.get("position"));}
    @Override protected void advance(){
        phase="approaching_container";if(!navigate(Vec3.atCenterOf(position),2.6))return;
        if(!opened){InventoryAccess.open(actor,position);opened=true;}
        var target=InventoryAccess.resolve(actor.serverLevel(),position);if(target==null)throw new IllegalStateException("Selected block has no supported inventory interface");
        result.add("position",JobJson.position(position));result.addProperty("dimension",actor.serverLevel().dimension().location().toString());
        result.addProperty("observedAt",actor.level().getGameTime());result.addProperty("countCertain",InventoryAccess.countCertain(target));
        if(type.equals("open_inventory")){inspect(target);return;}
        phase="taking_items";var selector=new ItemSelector(specification.get("item").getAsString());
        if(requested<0)requested=specification.get("quantity").getAsInt();updateAcquired();
        if(acquired>=requested){succeed(result);return;}
        int moved=InventoryAccess.take(actor,position,selector,(int)Math.min(64,requested-acquired),stack->{remember(stack);acquired+=stack.getCount();updateAcquired();});
        if(moved==0)throw new IllegalStateException("Selected inventory cannot supply remaining matching items through its permitted faces");
    }
    private void inspect(InventoryAccess.Target target){
        phase="inspecting_container";int limit=specification.has("limit")?specification.get("limit").getAsInt():256;
        int count=target.vanilla()!=null?1:target.views().size();
        for(int budget=64;budget>0 && viewIndex<count && inspected<limit;budget--){
            var handler=target.vanilla()!=null?null:target.views().get(viewIndex).handler();int slots=handler==null?target.vanilla().getContainerSize():handler.getSlots();
            if(views.size()<=viewIndex){var value=new JsonObject();value.addProperty("side",handler==null?"vanilla":target.views().get(viewIndex).side().getName());value.addProperty("slotCount",slots);value.add("slots",new JsonArray());views.add(value);}
            if(slot>=slots){viewIndex++;slot=0;continue;}
            ItemStack stack=handler==null?target.vanilla().getItem(slot):handler.getStackInSlot(slot);var value=new JsonObject();value.addProperty("slot",slot++);value.addProperty("observedAt",actor.level().getGameTime());value.addProperty("snbt",stack.save(new CompoundTag()).toString());value.addProperty("count",stack.getCount());
            views.get(viewIndex).getAsJsonObject().getAsJsonArray("slots").add(value);inspected++;
        }
        while(viewIndex<count){int slots=target.vanilla()!=null?target.vanilla().getContainerSize():target.views().get(viewIndex).handler().getSlots();if(slot<slots)break;viewIndex++;slot=0;}
        result.add("views",views.deepCopy());result.addProperty("inspectedSlots",inspected);result.addProperty("complete",viewIndex>=count);result.addProperty("truncated",viewIndex<count && inspected>=limit);
        if(viewIndex>=count || inspected>=limit)succeed(result);
    }
    private void remember(ItemStack stack){for(var previous:stacks)if(ItemStack.isSameItemSameTags(previous,stack) && previous.getCount()+stack.getCount()<=previous.getMaxStackSize()){previous.grow(stack.getCount());return;}stacks.add(stack.copy());}
    private void updateAcquired(){result.addProperty("requested",requested);result.addProperty("acquired",acquired);result.add("acquiredStacks",JobJson.stacks(stacks));}
    @Override protected void onPause(){opened=false;}
    @Override protected JsonObject writeData(){var data=new JsonObject();data.add("specification",specification.deepCopy());data.addProperty("requested",requested);data.addProperty("acquired",acquired);data.add("stacks",JobJson.stacks(stacks));data.addProperty("viewIndex",viewIndex);data.addProperty("slot",slot);data.addProperty("inspected",inspected);data.add("views",views.deepCopy());return data;}
    @Override protected void readData(JsonObject data){requested=data.get("requested").getAsLong();acquired=data.get("acquired").getAsLong();stacks.clear();stacks.addAll(JobJson.stacks(data.getAsJsonArray("stacks")));viewIndex=data.get("viewIndex").getAsInt();slot=data.get("slot").getAsInt();inspected=data.get("inspected").getAsInt();views=data.getAsJsonArray("views").deepCopy();opened=false;}
}
