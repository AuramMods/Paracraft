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
    private int stalled, attempts;

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
        if (actor.position().distanceTo(destination) <= reach) { close(); return true; }
        if (!actor.onGround() && !actor.isInWater() && !actor.onClimbable()) return false;
        if (lastDestination != null && lastDestination.distanceTo(destination) > 4) path = null;
        lastDestination = destination;
        if (actor.position().distanceTo(lastPosition) < 0.015) stalled++; else stalled = 0;
        lastPosition = actor.position();
        if (stalled > 35) { path = null; stalled = 0; if (++attempts > 4) throw new IllegalStateException("Navigation stuck after replanning"); }
        if (path == null || path.isDone()) {
            proxy.moveTo(actor.getX(), actor.getY(), actor.getZ(), actor.getYRot(), actor.getXRot());
            proxy.setOnGround(actor.onGround() || actor.isInWater() || actor.onClimbable());
            Vec3 offset = destination.subtract(actor.position());
            Vec3 segment = offset.length() > 18 ? actor.position().add(offset.normalize().scale(18)) : destination;
            path = navigation.createPath(BlockPos.containing(segment), reach > 1 ? 1 : 0);
            if (path == null || path.getNodeCount() == 0) throw new IllegalStateException("No reachable path to destination");
        }
        while (!path.isDone()) {
            Vec3 node = path.getNextEntityPos(proxy);
            if (actor.position().distanceTo(node) > 0.5) break;
            path.advance();
        }
        if (path.isDone()) { path = null; closeVelocity(); return false; }
        BlockPos next = path.getNextNodePos();
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
    private static java.util.List<BlockPos> listAround(BlockPos next) { return java.util.List.of(next, next.above()); }
    private void closeVelocity() { actor.setDeltaMovement(0, actor.getDeltaMovement().y, 0); }
    @Override public void close() { path = null; closeVelocity(); proxy.discard(); }
}
