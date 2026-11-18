package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.InventoryAccess;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
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
    private final Vec3 idleCenter;
    private final double idleRadius;
    private final boolean sprintAllowed;
    private FlightNavigator flight;
    private int flightReplans;

    public PlayerNavigator(CompanionPlayer actor) {
        this(actor, true, null, 0);
    }
    public PlayerNavigator(CompanionPlayer actor, boolean sprintAllowed) { this(actor,sprintAllowed,null,0); }
    /** Ambient routes walk, preserve terrain, and keep every route node inside the resting area. */
    public PlayerNavigator(CompanionPlayer actor, Vec3 idleCenter, double idleRadius) {
        this(actor,false,idleCenter,idleRadius);
    }
    private PlayerNavigator(CompanionPlayer actor, boolean sprintAllowed, Vec3 idleCenter, double idleRadius) {
        this.actor = actor;
        this.idleCenter = idleCenter; this.idleRadius = idleRadius;
        this.sprintAllowed = sprintAllowed;
        proxy = new PathfinderMob(EntityType.VILLAGER, actor.serverLevel()) {
            @Override public net.minecraft.world.entity.EntityDimensions getDimensions(net.minecraft.world.entity.Pose pose) {
                return actor.getDimensions(pose);
            }
        };
        proxy.setMaxUpStep(actor.maxUpStep());
        proxy.getAttribute(Attributes.FOLLOW_RANGE).setBaseValue(64);
        navigation = new GroundPathNavigation(proxy, actor.serverLevel()) {
            @Override protected net.minecraft.world.level.pathfinder.PathFinder createPathFinder(int maximum) {
                nodeEvaluator = new PlayerPathEvaluator(actor, idleCenter, idleRadius);
                return new net.minecraft.world.level.pathfinder.PathFinder(nodeEvaluator, maximum) {
                    @Override public Path findPath(net.minecraft.world.level.PathNavigationRegion region, net.minecraft.world.entity.Mob mob,
                                                   java.util.Set<BlockPos> targets, float range, int accuracy, float visitedMultiplier) {
                        // Vanilla also caps cumulative route length at the spatial radius. A winding
                        // maze needs a longer walk within the same bounded region and node budget.
                        return super.findPath(region, mob, targets, range * 4, accuracy, visitedMultiplier);
                    }
                };
            }
        };
        navigation.setCanOpenDoors(true); navigation.setCanPassDoors(true); navigation.setCanFloat(true);
        navigation.setMaxVisitedNodesMultiplier(4F);
        lastPosition = actor.position();
    }

    public boolean tick(Vec3 destination, double reach) {
        var level = actor.serverLevel();
        boolean diving = underwaterTarget(actor, destination);
        if (idleCenter == null && actor.getAbilities().mayfly && !actor.isPassenger() && (!diving || !actor.isInWater())) {
            if (flight == null) {
                if (breaker != null) { breaker.close(); breaker = null; }
                if (recoveryBlock != null) finishRecovery();
                path = null;
                sprint(false);
                flight = new FlightNavigator(actor, proxy);
            }
            mode = "flying";
            Vec3 flightTarget = destination;
            if (diving) {
                BlockPos target = BlockPos.containing(destination);
                if (!PlayerPathEvaluator.water(level, target) || !level.getBlockState(target).getCollisionShape(level, target).isEmpty())
                    target = target.above();
                flightTarget = Vec3.atCenterOf(target);
            }
            boolean arrived = flight.tick(flightTarget, diving ? 0.25 : reach);
            flightReplans = flight.replans();
            return arrived && !diving;
        }
        if (flight != null) { flight.stop(); flight = null; }
        if (diving && actor.getAbilities().flying && !actor.isSpectator()) {
            actor.getAbilities().flying = false;
            actor.onUpdateAbilities();
        }
        if(recoveryBlock!=null){recoverTick();return false;}
        if (arrived(actor, destination, reach)) { close(); return true; }
        Vec3 goal=destination.subtract(actor.position());
        BlockPos feet = actor.blockPosition();
        boolean ladder = actor.onClimbable() || PlayerPathEvaluator.climbable(level, feet.below());
        mode="walking";
        if (!actor.onGround() && !actor.isInWater() && !ladder) return false;
        if (lastDestination != null && lastDestination.distanceTo(destination) > 4) path = null;
        lastDestination = destination;
        if (actor.position().distanceTo(lastPosition) < 0.015) stalled++; else stalled = 0;
        lastPosition = actor.position();
        if (stalled > 35) { path = null; stalled = 0; if (++attempts > 1){beginRecovery(destination);return false;} }
        if (path == null || path.isDone()) {
            pathOrigin=actor.position();
            proxy.moveTo(actor.getX(), actor.getY(), actor.getZ(), actor.getYRot(), actor.getXRot());
            proxy.setOnGround(actor.onGround() || actor.isInWater() || ladder);
            Vec3 offset = destination.subtract(actor.position());
            Vec3 segment = offset.length() > 48 ? actor.position().add(offset.normalize().scale(48)) : destination;
            path = navigation.createPath(BlockPos.containing(segment), reach > 1 ? 1 : 0);
            if (path == null || path.getNodeCount() == 0) {beginRecovery(destination);return false;}
        }
        while (!path.isDone()) {
            Vec3 node = path.getNextEntityPos(proxy);
            BlockPos nodePos = path.getNextNodePos();
            double horizontalReach=actor.getBbWidth()>0.75F?actor.getBbWidth()/2:0.75F-actor.getBbWidth()/2;
            boolean preciseHeight = ladder || actor.isInWater() || PlayerPathEvaluator.water(level, nodePos)
                    || PlayerPathEvaluator.climbable(level, nodePos) || PlayerPathEvaluator.climbable(level, nodePos.below());
            // Walking nodes tolerate partial-height support. A ladder or swim node needs the actual depth.
            if(Math.abs(actor.getX()-node.x)>=horizontalReach || Math.abs(actor.getZ()-node.z)>=horizontalReach
                    || Math.abs(actor.getY()-node.y)>=(preciseHeight ? 0.20 : 1))break;
            path.advance();
        }
        if (path.isDone()) { path = null; closeVelocity();if(pathOrigin!=null && actor.position().distanceTo(pathOrigin)>0.5)attempts=0;else if(++attempts>1)beginRecovery(destination);return false; }
        BlockPos next = path.getNextNodePos();
        if(idleCenter==null && !ladder && !actor.isInWater() && !PlayerPathEvaluator.water(level, next)
                && com.aurammods.paracraft.Paracraft.NAVIGATION_TERRAIN_CHANGES.get() && next.getY()<actor.blockPosition().getY()-1 && destination.y>=actor.getY()-0.5){beginRecovery(destination);return false;}
        if ((actor.serverLevel().getBlockState(next).is(BlockTags.PORTALS) && !actor.portalAuthorized(next))
                || (actor.serverLevel().getBlockState(next.above()).is(BlockTags.PORTALS) && !actor.portalAuthorized(next.above())))
            throw new IllegalStateException("Route enters a portal; explicit dimension travel is required");
        for (BlockPos pos : listAround(next)) {
            var state = actor.serverLevel().getBlockState(pos);
            if (PlayerPathEvaluator.closedOpenable(state) && actor.distanceToSqr(Vec3.atCenterOf(pos)) < 9) {
                InventoryAccess.open(actor, pos);
                if (PlayerPathEvaluator.closedOpenable(level.getBlockState(pos)))
                    throw new IllegalStateException("Route barrier did not open");
            }
        }
        Vec3 waypoint = path.getNextEntityPos(proxy);
        Vec3 delta = waypoint.subtract(actor.position());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        if (actor.isInWater()) {
            swim(delta);
            return false;
        }
        if (idleCenter == null && ladder && horizontal < 0.8 && Math.abs(delta.y) > 0.12) {
            sprint(false);
            mode = "climbing";
            actor.setDeltaMovement(delta.x * 0.35, delta.y > 0 ? 0.21 : -0.15, delta.z * 0.35);
            return false;
        }
        sprint(sprintAllowed && goal.horizontalDistance() > 3 && actor.getFoodData().getFoodLevel() > 6 && !actor.isUsingItem() && !actor.isShiftKeyDown() && actor.onGround());
        mode = actor.isSprinting() ? "sprinting" : "walking";
        double speed = Math.min(actor.getAttributeValue(Attributes.MOVEMENT_SPEED) * 1.9, horizontal);
        double vertical = actor.getDeltaMovement().y;
        if ((delta.y > 0.45 && actor.onGround()) || (actor.isInWater() && delta.y > 0.1)) vertical = 0.42;
        if (horizontal > 0.001) {
            if (idleCenter != null && !insideIdle(actor.position().add(delta.x / horizontal * speed, vertical, delta.z / horizontal * speed), 0.2))
                throw new IllegalStateException("Idle route would leave its resting area");
            actor.setYRot((float)(Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90));
            actor.setYHeadRot(actor.getYRot());
            actor.setDeltaMovement(delta.x / horizontal * speed, vertical, delta.z / horizontal * speed);
        }
        return false;
    }
    private void swim(Vec3 delta) {
        mode = "swimming";
        sprint(sprintAllowed && actor.isUnderWater() && actor.getFoodData().getFoodLevel() > 6
                && !actor.isUsingItem() && !actor.isShiftKeyDown());
        actor.lookAt(EntityAnchorArgument.Anchor.EYES, actor.getEyePosition().add(delta));
        double speed = Math.min(delta.length(), actor.getAttributeValue(net.minecraftforge.common.ForgeMod.SWIM_SPEED.get()) * 0.22);
        actor.setDeltaMovement(delta.normalize().scale(speed));
    }

    static boolean arrived(CompanionPlayer actor, Vec3 destination, double reach) {
        if (actor.position().distanceTo(destination) > reach) return false;
        if (underwaterTarget(actor, destination))
            return actor.isInWater() && actor.getY() <= destination.y + 0.65;
        return reach >= 1.5 || actor.onGround() || actor.isInWater();
    }

    private static boolean underwaterTarget(CompanionPlayer actor, Vec3 destination) {
        var level = actor.serverLevel();
        BlockPos pos = BlockPos.containing(destination);
        return level.hasChunkAt(pos) && (PlayerPathEvaluator.water(level, pos)
                || !level.getBlockState(pos).isAir() && PlayerPathEvaluator.water(level, pos.above()));
    }
    private void beginRecovery(Vec3 destination){
        path=null;attempts=0;stalled=0;closeVelocity();BlockPos feet=actor.blockPosition();Vec3 delta=destination.subtract(actor.position());
        if(idleCenter != null || !com.aurammods.paracraft.Paracraft.NAVIGATION_TERRAIN_CHANGES.get())
            throw new IllegalStateException("No traversable walking route was found; navigation preserves blocks. Choose another route or request an explicit block action.");
        if(delta.horizontalDistance()<1 && delta.y>0.7){startPlacement(feet,feet,"pillaring");actor.setDeltaMovement(0,0.42,0);return;}
        net.minecraft.core.Direction facing=Math.abs(delta.x)>=Math.abs(delta.z)?(delta.x>=0?net.minecraft.core.Direction.EAST:net.minecraft.core.Direction.WEST):(delta.z>=0?net.minecraft.core.Direction.SOUTH:net.minecraft.core.Direction.NORTH);
        BlockPos front=feet.relative(facing);
        for(BlockPos obstacle:java.util.List.of(front.above(),front)){
            var state=actor.serverLevel().getBlockState(obstacle);
            if (PlayerPathEvaluator.closedOpenable(state)) { InventoryAccess.open(actor, obstacle); return; }
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
    public com.google.gson.JsonObject view(){var value=new com.google.gson.JsonObject();value.addProperty("mode",mode);value.addProperty("replans",mode.equals("flying")?flightReplans:attempts);value.add("position",JobJson.vector(actor.position()));if(recoveryBlock!=null){value.add("recoveryBlock",JobJson.position(recoveryBlock));value.addProperty("recoveryBlockType",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(actor.serverLevel().getBlockState(recoveryBlock).getBlock()).toString());}if(breaker!=null)value.addProperty("breakProgress",breaker.progress());return value;}
    private static java.util.List<BlockPos> listAround(BlockPos next) { return java.util.List.of(next, next.above()); }
    private void sprint(boolean value) { if (actor.isSprinting() != value) actor.setSprinting(value); }
    private boolean insideIdle(Vec3 point, double margin) { return idleCenter == null || point.distanceToSqr(idleCenter) <= (idleRadius-margin)*(idleRadius-margin); }
    private void closeVelocity() {
        sprint(false);
        actor.setDeltaMovement(0, actor.isInWater() ? 0 : actor.getDeltaMovement().y, 0);
    }
    @Override public void close() { if(flight!=null){flight.stop();flight=null;}if(breaker!=null){breaker.close();breaker=null;}if(mode.equals("bridging"))actor.setShiftKeyDown(priorSneaking);recoveryBlock=null;path = null; closeVelocity(); proxy.discard(); }
}
