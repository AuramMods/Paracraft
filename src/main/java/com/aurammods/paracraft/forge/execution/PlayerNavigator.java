package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.InventoryAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;

/** Vanilla ground path computation on an unspawned geometry proxy; movement belongs to the player. */
public final class PlayerNavigator implements AutoCloseable {
    private final CompanionPlayer actor;
    private final PathfinderMob proxy;
    private final GroundPathNavigation navigation;
    private Path path;
    private Vec3 lastPosition;
    private Vec3 lastDestination;
    private Vec3 pathOrigin;
    private int stalled, attempts;
    private NativeBlockBreaker breaker;
    private BlockPos recoveryBlock,anchor;
    private String mode="walking";
    private int recoveryTicks;
    private boolean priorSneaking;

    public PlayerNavigator(CompanionPlayer actor) {
        this.actor = actor;
        proxy = new PathfinderMob(EntityType.VILLAGER, actor.serverLevel()) {
            @Override public net.minecraft.world.entity.EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose) {
                return actor.getDimensions(pose);
            }
        };
        proxy.setMaxUpStep(actor.maxUpStep());
        proxy.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(24);
        navigation = new GroundPathNavigation(proxy, actor.serverLevel());
        navigation.setCanOpenDoors(true); navigation.setCanPassDoors(true); navigation.setCanFloat(true);
        navigation.setMaxVisitedNodesMultiplier(0.5F);
        lastPosition = actor.position();
    }

    public boolean tick(Vec3 destination, double reach) {
        if(recoveryBlock!=null){recoverTick();return false;}
        if (actor.position().distanceTo(destination) <= reach && (reach>=1.5 || actor.onGround() || actor.isInWater())) { close(); return true; }
        Vec3 goal=destination.subtract(actor.position());
        if(actor.onClimbable() && goal.y>0.4 && goal.horizontalDistance()<2){path=null;mode="climbing";actor.setDeltaMovement(goal.x*0.05,0.18,goal.z*0.05);return false;}
        if(actor.isInWater()){
            Vec3 direction=goal.normalize();double vertical=goal.y>0.3?0.18:goal.y< -0.3?-0.12:0.02;
            double scale=actor.getAttributeValue(net.minecraftforge.common.ForgeMod.SWIM_SPEED.get());Vec3 motion=new Vec3(direction.x*0.15,vertical,direction.z*0.15).scale(scale);
            if(actor.serverLevel().noCollision(actor,actor.getBoundingBox().move(motion.scale(3)))){path=null;mode="swimming";actor.setDeltaMovement(motion);return false;}
        }
        mode="walking";
        if (!actor.onGround() && !actor.isInWater() && !actor.onClimbable()) return false;
        if (lastDestination != null && lastDestination.distanceTo(destination) > 4) path = null;
        lastDestination = destination;
        if (actor.position().distanceTo(lastPosition) < 0.015) stalled++; else stalled = 0;
        lastPosition = actor.position();
        if (stalled > 35) { path = null; stalled = 0; if (++attempts > 1){beginRecovery(destination);return false;} }
        if (path == null || path.isDone()) {
            pathOrigin=actor.position();
            proxy.moveTo(actor.getX(), actor.getY(), actor.getZ(), actor.getYRot(), actor.getXRot());
            proxy.setOnGround(actor.onGround() || actor.isInWater() || actor.onClimbable());
            Vec3 offset = destination.subtract(actor.position());
            Vec3 segment = offset.length() > 18 ? actor.position().add(offset.normalize().scale(18)) : destination;
            path = navigation.createPath(BlockPos.containing(segment), reach > 1 ? 1 : 0);
            if (path == null || path.getNodeCount() == 0) {beginRecovery(destination);return false;}
        }
        while (!path.isDone()) {
            Vec3 node = path.getNextEntityPos(proxy);
            double horizontalReach=actor.getBbWidth()>0.75F?actor.getBbWidth()/2:0.75F-actor.getBbWidth()/2;
            // Native ground navigation tolerates partial-height support vertically.
            if(Math.abs(actor.getX()-node.x)>=horizontalReach || Math.abs(actor.getZ()-node.z)>=horizontalReach || Math.abs(actor.getY()-node.y)>=1)break;
            path.advance();
        }
        if (path.isDone()) { path = null; closeVelocity();if(pathOrigin!=null && actor.position().distanceTo(pathOrigin)>0.5)attempts=0;else if(++attempts>1)beginRecovery(destination);return false; }
        BlockPos next = path.getNextNodePos();
        if(next.getY()<actor.blockPosition().getY()-1 && destination.y>=actor.getY()-0.5){beginRecovery(destination);return false;}
        if ((actor.serverLevel().getBlockState(next).is(BlockTags.PORTALS) && !actor.portalAuthorized(next))
                || (actor.serverLevel().getBlockState(next.above()).is(BlockTags.PORTALS) && !actor.portalAuthorized(next.above())))
            throw new IllegalStateException("Route enters a portal; explicit dimension travel is required");
        for (BlockPos pos : listAround(next)) {
            var state = actor.serverLevel().getBlockState(pos);
            if (state.getBlock() instanceof DoorBlock && !state.getValue(DoorBlock.OPEN)
                    && actor.distanceToSqr(Vec3.atCenterOf(pos)) < 9) InventoryAccess.open(actor, pos);
        }
        Vec3 delta = path.getNextEntityPos(proxy).subtract(actor.position());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        double speed = Math.min(actor.getAttributeValue(Attributes.MOVEMENT_SPEED) * 1.9, horizontal);
        double vertical = actor.getDeltaMovement().y;
        if ((delta.y > 0.45 && actor.onGround()) || (actor.isInWater() && delta.y > 0.1)) vertical = 0.42;
        if (horizontal > 0.001) {
            actor.setYRot((float)(Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90));
            actor.setYHeadRot(actor.getYRot());
            actor.setDeltaMovement(delta.x / horizontal * speed, vertical, delta.z / horizontal * speed);
        }
        return false;
    }
    private void beginRecovery(Vec3 destination){
        path=null;attempts=0;stalled=0;closeVelocity();BlockPos feet=actor.blockPosition();Vec3 delta=destination.subtract(actor.position());
        if(delta.horizontalDistance()<1 && delta.y>0.7){startPlacement(feet,feet,"pillaring");actor.setDeltaMovement(0,0.42,0);return;}
        net.minecraft.core.Direction facing=Math.abs(delta.x)>=Math.abs(delta.z)?(delta.x>=0?net.minecraft.core.Direction.EAST:net.minecraft.core.Direction.WEST):(delta.z>=0?net.minecraft.core.Direction.SOUTH:net.minecraft.core.Direction.NORTH);
        BlockPos front=feet.relative(facing);
        for(BlockPos obstacle:java.util.List.of(front.above(),front)){
            var state=actor.serverLevel().getBlockState(obstacle);
            if(state.getBlock() instanceof DoorBlock && !state.getValue(DoorBlock.OPEN)){InventoryAccess.open(actor,obstacle);return;}
            if(!state.getCollisionShape(actor.serverLevel(),obstacle).isEmpty()){
                if(state.hasBlockEntity() || state.is(BlockTags.PORTALS))throw new IllegalStateException("Navigation obstruction needs an explicit block action");
                recoveryBlock=obstacle;mode="breaking_obstruction";CarriedItems.miningTool(actor,obstacle,state);breaker=new NativeBlockBreaker(actor,obstacle);return;
            }
        }
        if(actor.serverLevel().getBlockState(front.below()).getCollisionShape(actor.serverLevel(),front.below()).isEmpty()){
            startPlacement(front.below(),feet,"bridging");priorSneaking=actor.isShiftKeyDown();actor.setShiftKeyDown(true);return;
        }
        throw new IllegalStateException("No reachable path or supported local terrain recovery");
    }
    private void startPlacement(BlockPos target,BlockPos anchor,String mode){
        for(int slot=0;slot<actor.getInventory().items.size();slot++){
            var stack=actor.getInventory().getItem(slot);
            if(stack.getItem() instanceof net.minecraft.world.item.BlockItem item && !(item.getBlock() instanceof net.minecraft.world.level.block.FallingBlock)){
                var state=item.getBlock().defaultBlockState();if(state.hasBlockEntity() || !state.isCollisionShapeFullBlock(actor.serverLevel(),target))continue;
                CarriedItems.select(actor,slot);this.mode=mode;this.anchor=anchor;recoveryBlock=target;recoveryTicks=0;return;
            }
        }
        throw new IllegalStateException("Navigation needs a carried solid block for "+mode);
    }
    private void recoverTick(){
        if(++recoveryTicks>80)throw new IllegalStateException("Local navigation recovery could not complete: "+mode);
        if(breaker!=null){if(breaker.tick()){breaker.close();breaker=null;finishRecovery();}return;}
        if(mode.equals("pillaring")){
            if(actor.getY()>=anchor.getY()+1.01){NativePlacement.place(actor,recoveryBlock);finishRecovery();}
            return;
        }
        var direction=new Vec3(recoveryBlock.getX()-anchor.getX(),0,recoveryBlock.getZ()-anchor.getZ());
        Vec3 edge=Vec3.atBottomCenterOf(anchor).add(direction.scale(0.57));Vec3 delta=edge.subtract(actor.position());
        if(delta.horizontalDistance()>0.035){Vec3 step=delta.normalize().scale(0.06);actor.setDeltaMovement(step.x,actor.getDeltaMovement().y,step.z);return;}
        closeVelocity();NativePlacement.place(actor,recoveryBlock);finishRecovery();
    }
    private void finishRecovery(){if(mode.equals("bridging"))actor.setShiftKeyDown(priorSneaking);recoveryBlock=null;anchor=null;path=null;attempts=0;stalled=0;recoveryTicks=0;mode="walking";}
    public com.google.gson.JsonObject view(){var value=new com.google.gson.JsonObject();value.addProperty("mode",mode);value.addProperty("replans",attempts);value.add("position",JobJson.vector(actor.position()));if(recoveryBlock!=null){value.add("recoveryBlock",JobJson.position(recoveryBlock));value.addProperty("recoveryBlockType",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(actor.serverLevel().getBlockState(recoveryBlock).getBlock()).toString());}if(breaker!=null)value.addProperty("breakProgress",breaker.progress());return value;}
    private static java.util.List<BlockPos> listAround(BlockPos next) { return java.util.List.of(next, next.above()); }
    private void closeVelocity() { actor.setDeltaMovement(0, actor.getDeltaMovement().y, 0); }
    @Override public void close() { if(breaker!=null){breaker.close();breaker=null;}if(mode.equals("bridging"))actor.setShiftKeyDown(priorSneaking);recoveryBlock=null;path = null; closeVelocity(); proxy.discard(); }
}
