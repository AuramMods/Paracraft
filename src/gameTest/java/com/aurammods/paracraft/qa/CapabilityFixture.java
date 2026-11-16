package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SkullBlockEntity;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;
import java.util.IdentityHashMap;

/** Real attached Forge capability on a non-Container block entity. Test-only machine semantics. */
@Mod.EventBusSubscriber(modid=Paracraft.ID)
public final class CapabilityFixture {
    private static final IdentityHashMap<BlockEntity,Machine> MACHINES=new IdentityHashMap<>();
    public static Machine machine(BlockEntity entity){return MACHINES.get(entity);}
    @SubscribeEvent public static void attach(AttachCapabilitiesEvent<BlockEntity> event){
        if(!(event.getObject() instanceof SkullBlockEntity))return;
        var machine=new Machine();MACHINES.put(event.getObject(),machine);event.addCapability(new ResourceLocation(Paracraft.ID,"qa_sided_machine"),machine);event.addListener(machine::invalidate);
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){MACHINES.clear();}
    public static final class Machine implements ICapabilitySerializable<CompoundTag>{
        public final ItemStackHandler storage=new ItemStackHandler(3);
        private final LazyOptional<IItemHandler> north=LazyOptional.of(()->face(Direction.NORTH)),up=LazyOptional.of(()->face(Direction.UP));
        private IItemHandler face(Direction direction){return new IItemHandler(){
            public int getSlots(){return storage.getSlots();}
            public ItemStack getStackInSlot(int slot){return storage.getStackInSlot(slot);}
            public int getSlotLimit(int slot){return storage.getSlotLimit(slot);}
            public boolean isItemValid(int slot,ItemStack stack){return direction==Direction.UP && slot==0 && stack.is(net.minecraft.world.item.Items.DIAMOND);}
            public ItemStack insertItem(int slot,ItemStack stack,boolean simulate){return isItemValid(slot,stack)?storage.insertItem(slot,stack,simulate):stack;}
            public ItemStack extractItem(int slot,int count,boolean simulate){return direction==Direction.NORTH && slot==1?storage.extractItem(slot,count,simulate):ItemStack.EMPTY;}
        };}
        public <T> LazyOptional<T> getCapability(Capability<T> capability,Direction side){return capability==ForgeCapabilities.ITEM_HANDLER?(side==Direction.NORTH?north:side==Direction.UP?up:LazyOptional.<IItemHandler>empty()).cast():LazyOptional.empty();}
        public CompoundTag serializeNBT(){return storage.serializeNBT();}
        public void deserializeNBT(CompoundTag tag){storage.deserializeNBT(tag);}
        private void invalidate(){north.invalidate();up.invalidate();}
    }
}
