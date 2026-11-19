package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import com.google.gson.JsonObject;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import java.util.List;
import java.util.UUID;

/** A physical right-click gesture, using the same item/block/entity callbacks and Forge hooks as players. */
public final class InteractionJob extends GameJob {
    private final JsonObject specification;
    private final InteractionHand hand;
    private final int holdTicks;
    private boolean issued,ownsUse,interrupted;
    private int elapsed;
    public InteractionJob(UUID id,CompanionPlayer actor,JsonObject specification){
        super(id,specification.get("op").getAsString(),actor);this.specification=specification.deepCopy();
        hand=specification.has("hand") && specification.get("hand").getAsString().equals("offhand")?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;
        holdTicks=specification.has("holdTicks")?specification.get("holdTicks").getAsInt():0;
    }
    @Override protected void advance(){
        if(issued){
            if(interrupted)throw new IllegalStateException("Held interaction was interrupted; the click was not repeated");
            if(!ownsUse){finish();return;}
            if(!actor.isUsingItem()){ownsUse=false;result.addProperty("useEnded",true);finish();return;}
            if(actor.getUsedItemHand()!=hand)throw new IllegalStateException("The held interaction changed hands; the click was not repeated");
            phase="holding_item_use";elapsed++;result.addProperty("heldTicks",elapsed);
            if(elapsed>=holdTicks){actor.releaseUsingItem();ownsUse=false;result.addProperty("released",true);finish();}return;
        }
        BlockHitResult blockHit=null;Entity entity=null;Vec3 entityPoint=null;
        if(type.equals("interact_block")){
            BlockPos position=JobJson.position(specification.get("position"));phase="approaching block interaction";if(!navigate(Vec3.atBottomCenterOf(position),2.5))return;
            blockHit=blockHit(position);result.add("position",JobJson.position(position));result.addProperty("blockBefore",actor.level().getBlockState(position).toString());
            if(!actor.serverLevel().mayInteract(actor,position))throw new IllegalStateException("World refused this block interaction");
        }else if(type.equals("interact_entity")){
            entity=actor.serverLevel().getEntity(UUID.fromString(specification.get("target").getAsString()));
            if(entity==null || entity==actor || entity.isRemoved() || !entity.isAlive())throw new IllegalStateException("Interaction target is unavailable in this dimension");
            phase="approaching entity interaction";if(!navigate(entity.position(),2.0))return;
            Vec3 aim=specification.has("hit")?entity.position().add(JobJson.vector(specification.get("hit"))):entity.getBoundingBox().getCenter();
            if(!entity.getBoundingBox().inflate(0.0001).contains(aim))throw new IllegalStateException("Requested entity hit point lies outside its actual bounds");
            entityPoint=entity.getBoundingBox().clip(actor.getEyePosition(),aim).orElse(aim);
            var obstruction=actor.level().clip(new ClipContext(actor.getEyePosition(),entityPoint,ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,actor));
            if(!actor.canReach(entityPoint,0) || obstruction.getType()!=HitResult.Type.MISS)throw new IllegalStateException("Entity interaction is obstructed or out of reach");
            result.addProperty("target",entity.getUUID().toString());result.add("hit",JobJson.vector(entityPoint));
        }else if(specification.has("lookAt"))actor.lookAt(EntityAnchorArgument.Anchor.EYES,JobJson.vector(specification.get("lookAt")));
        if(actor.isUsingItem())throw new IllegalStateException("Avatar is already using an item");
        if (specification.has("item")) {
            var selector = new ItemSelector(specification.get("item").getAsString());
            if (!selector.matches(actor.getItemInHand(hand))) {
                int slot = CarriedItems.find(actor, selector);
                if (slot < 0) throw CarriedItems.missing(selector);
                if (hand == InteractionHand.MAIN_HAND) CarriedItems.select(actor, slot);
                else EquipmentAccess.swapOffhand(actor, slot);
            }
        }
        ItemStack before=actor.getItemInHand(hand).copy();result.add("handBefore",JobJson.stacks(List.of(before)));result.addProperty("hand",hand==InteractionHand.MAIN_HAND?"mainhand":"offhand");
        if (!before.isEmpty()) result.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(before.getItem()).toString());
        if(!before.isItemEnabled(actor.level().enabledFeatures()))throw new IllegalStateException("Held item is disabled by world features");
        boolean previousSneak=actor.isShiftKeyDown();InteractionResult outcome;
        phase="interacting";actor.resetLastActionTime();
        try{
            actor.setShiftKeyDown(specification.has("sneak") && specification.get("sneak").getAsBoolean());issued=true;
            if(blockHit!=null){
                actor.lookAt(EntityAnchorArgument.Anchor.EYES,blockHit.getLocation());result.add("hit",JobJson.vector(blockHit.getLocation()));result.addProperty("face",blockHit.getDirection().getName());
                var observed=new BlockClick(actor,hand,blockHit.getBlockPos());MinecraftForge.EVENT_BUS.register(observed);
                try{outcome=actor.gameMode.useItemOn(actor,actor.serverLevel(),actor.getItemInHand(hand),hand,blockHit);}finally{MinecraftForge.EVENT_BUS.unregister(observed);}
                result.addProperty("blockOutcome",outcome.name());
                if(outcome==InteractionResult.PASS && !actor.getItemInHand(hand).isEmpty() && (observed.event==null || !observed.event.isCanceled() && observed.event.getUseItem()!=Event.Result.DENY))outcome=actor.gameMode.useItem(actor,actor.level(),actor.getItemInHand(hand),hand);
            }else if(entity!=null){
                actor.lookAt(EntityAnchorArgument.Anchor.EYES,entityPoint);Vec3 relative=entityPoint.subtract(entity.position());
                var hook=ForgeHooks.onInteractEntityAt(actor,entity,relative,hand);
                // An explicit hook cancellation, even PASS, must not fall through to another interaction.
                outcome=hook==null?entity.interactAt(actor,relative,hand):hook;
                if(hook==null && outcome==InteractionResult.PASS)outcome=actor.interactOn(entity,hand);
                if(outcome.consumesAction())CriteriaTriggers.PLAYER_INTERACTED_WITH_ENTITY.trigger(actor,before,entity);
            }else outcome=actor.gameMode.useItem(actor,actor.level(),actor.getItemInHand(hand),hand);
            result.addProperty("outcome",outcome.name());result.addProperty("accepted",outcome.consumesAction());
            ownsUse=actor.isUsingItem() && actor.getUsedItemHand()==hand;
            if(outcome.shouldSwing())actor.swing(hand,true);
        }finally{ownsUse=actor.isUsingItem() && actor.getUsedItemHand()==hand;actor.setShiftKeyDown(previousSneak);observe();}
        if(!outcome.consumesAction())throw new IllegalStateException("Native right-click returned "+outcome+"; no interaction was accepted");
        if(ownsUse && holdTicks>0){phase="holding_item_use";return;}
        if(ownsUse){actor.releaseUsingItem();ownsUse=false;result.addProperty("released",true);}finish();
    }
    private BlockHitResult blockHit(BlockPos position){
        var level=actor.serverLevel();if(level.isOutsideBuildHeight(position) || !level.getWorldBorder().isWithinBounds(position))throw new IllegalStateException("Block interaction is outside world bounds");
        if(!actor.canReach(position,0))throw new IllegalStateException("Block interaction is outside native reach");
        Direction face=specification.has("face")?Direction.byName(specification.get("face").getAsString()):null;
        if(specification.has("hit")){
            var hit=clip(position,Vec3.atLowerCornerOf(position).add(JobJson.vector(specification.get("hit"))),face);if(hit!=null)return hit;
        }else{
            var state=level.getBlockState(position);var shape=state.getShape(level,position,CollisionContext.of(actor));
            if(shape.isEmpty() && !state.getFluidState().isEmpty())shape=state.getFluidState().getShape(level,position);
            var boxes=shape.toAabbs();
            for(var box:boxes)for(var side:Direction.values()){
                if(face!=null && side!=face)continue;Vec3 middle=box.getCenter();Vec3 point=new Vec3(side.getStepX()==0?middle.x:side.getStepX()>0?box.maxX:box.minX,side.getStepY()==0?middle.y:side.getStepY()>0?box.maxY:box.minY,side.getStepZ()==0?middle.z:side.getStepZ()>0?box.maxZ:box.minZ).add(Vec3.atLowerCornerOf(position));
                var hit=clip(position,point,side);if(hit!=null)return hit;
            }
        }
        throw new IllegalStateException("No visible reachable face matches the requested block interaction");
    }
    private BlockHitResult clip(BlockPos position,Vec3 point,Direction face){
        Vec3 eyes=actor.getEyePosition(),end=point.add(point.subtract(eyes).normalize().scale(0.001));
        // Ordinary clicks see through water; an explicitly selected source fluid can still
        // provide a hit for bucket-style items whose native use performs its own fluid raycast.
        var fluid=actor.level().getBlockState(position).getShape(actor.level(),position,CollisionContext.of(actor)).isEmpty()?ClipContext.Fluid.SOURCE_ONLY:ClipContext.Fluid.NONE;
        var hit=actor.level().clip(new ClipContext(eyes,end,ClipContext.Block.OUTLINE,fluid,actor));
        return hit.getType()==HitResult.Type.BLOCK && hit.getBlockPos().equals(position) && (face==null || hit.getDirection()==face) && eyes.distanceTo(hit.getLocation())<=actor.getBlockReach()?hit:null;
    }
    private void observe(){
        result.add("handAfter",JobJson.stacks(List.of(actor.getItemInHand(hand).copy())));result.addProperty("usingItem",actor.isUsingItem());result.addProperty("heldTicks",elapsed);result.addProperty("dimension",actor.serverLevel().dimension().location().toString());
        if(type.equals("interact_block") && actor.serverLevel().dimension()==requiredDimension())result.addProperty("blockAfter",actor.level().getBlockState(JobJson.position(specification.get("position"))).toString());
        if(actor.containerMenu!=actor.inventoryMenu){result.addProperty("menuOpened",true);result.addProperty("menuSlots",actor.containerMenu.slots.size());}
        if(actor.getVehicle()!=null)result.addProperty("vehicle",actor.getVehicle().getUUID().toString());
    }
    private void finish(){observe();succeed(result);}
    @Override protected void onStop(){if(ownsUse){if(actor.isUsingItem() && actor.getUsedItemHand()==hand)actor.stopUsingItem();ownsUse=false;interrupted=true;result.addProperty("interrupted",true);result.addProperty("usingItem",actor.isUsingItem());}}
    @Override protected JsonObject writeData(){var data=new JsonObject();data.add("specification",specification.deepCopy());data.addProperty("issued",issued);data.addProperty("ownsUse",ownsUse);data.addProperty("interrupted",interrupted);data.addProperty("elapsed",elapsed);return data;}
    @Override protected void readData(JsonObject data){issued=data.get("issued").getAsBoolean();ownsUse=data.get("ownsUse").getAsBoolean();elapsed=data.get("elapsed").getAsInt();interrupted=data.has("interrupted") && data.get("interrupted").getAsBoolean();
        // Active item use is not serialized with a body. A saved click must never be reissued.
        if(issued && ownsUse && !actor.isUsingItem()){ownsUse=false;interrupted=true;}
    }
    private static final class BlockClick{
        final CompanionPlayer actor;final InteractionHand hand;final BlockPos position;PlayerInteractEvent.RightClickBlock event;
        BlockClick(CompanionPlayer actor,InteractionHand hand,BlockPos position){this.actor=actor;this.hand=hand;this.position=position;}
        @SubscribeEvent(receiveCanceled=true) public void clicked(PlayerInteractEvent.RightClickBlock event){if(event.getEntity()==actor && event.getHand()==hand && event.getPos().equals(position))this.event=event;}
    }
}
