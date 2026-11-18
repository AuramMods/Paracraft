package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.InventoryAccess;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.navigation.FlyingPathNavigation;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.FlyNodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;

/** Bounded three-dimensional routing for player-ability flight. The avatar moves with native velocity. */
final class FlightNavigator {
    private static final double SEGMENT_LENGTH = 24;
    private final CompanionPlayer actor;
    private final PathfinderMob proxy;
    private final FlyingPathNavigation navigation;
    private Vec3 destination, waypoint, lastPosition;
    private Path path;
    private int stationaryTicks, replans;

    FlightNavigator(CompanionPlayer actor, PathfinderMob proxy) {
        this.actor = actor;
        this.proxy = proxy;
        navigation = new FlyingPathNavigation(proxy, actor.serverLevel()) {
            @Override protected PathFinder createPathFinder(int maximum) {
                nodeEvaluator = new FlyNodeEvaluator() {
                    @Override public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z) {
                        BlockPos pos = new BlockPos(x, y, z);
                        var state = level.getBlockState(pos);
                        if (state.is(BlockTags.PORTALS) && !actor.portalAuthorized(pos))
                            return BlockPathTypes.BLOCKED;
                        if (PlayerPathEvaluator.closedOpenable(state)) return BlockPathTypes.DOOR_WOOD_CLOSED;
                        return super.getBlockPathType(level, x, y, z);
                    }
                };
                return new PathFinder(nodeEvaluator, maximum);
            }
        };
        navigation.setCanPassDoors(true);
        navigation.setCanOpenDoors(true);
        navigation.setMaxVisitedNodesMultiplier(3);
    }

    boolean tick(Vec3 target, double reach) {
        Vec3 position = actor.position();
        if (position.distanceTo(target) <= reach) return true;
        if (!actor.serverLevel().getWorldBorder().isWithinBounds(BlockPos.containing(target)))
            throw new IllegalStateException("Flight destination is outside the world border");
        if (!actor.getAbilities().flying) {
            actor.getAbilities().flying = true;
            actor.onUpdateAbilities();
        }
        if (lastPosition != null && position.distanceToSqr(lastPosition) < 0.0001) stationaryTicks++;
        else stationaryTicks = 0;
        if (stationaryTicks > 30) throw new IllegalStateException("Flight is blocked");
        lastPosition = position;

        if (destination == null || destination.distanceToSqr(target) > 1) {
            destination = target;
            waypoint = null;
            path = null;
        }
        if (path != null) {
            while (!path.isDone() && position.distanceToSqr(path.getNextEntityPos(proxy)) < 0.06) path.advance();
            if (path.isDone()) { path = null; waypoint = null; }
            else waypoint = path.getNextEntityPos(proxy);
        }
        if (waypoint == null || position.distanceToSqr(waypoint) < 0.01) plan(position, target, reach);

        Vec3 delta = waypoint.subtract(position);
        double speed = Math.min(0.8, actor.getAbilities().getFlyingSpeed() * 10);
        Vec3 step = delta.length() <= speed ? delta : delta.normalize().scale(speed);
        Vec3 next = position.add(step);
        if (!clear(position, next) && (!openFlightBarriers(step) || !clear(position, next))) {
            waypoint = null;
            path = null;
            actor.setDeltaMovement(Vec3.ZERO);
            return false;
        }
        actor.lookAt(EntityAnchorArgument.Anchor.FEET, waypoint);
        actor.setDeltaMovement(step);
        return false;
    }

    private void plan(Vec3 position, Vec3 target, double reach) {
        Vec3 delta = target.subtract(position);
        double distance = Math.min(SEGMENT_LENGTH, delta.length() - reach * 0.8);
        Vec3 direct = position.add(delta.normalize().scale(distance));
        if (clear(position, direct)) { waypoint = direct; return; }
        replans++;
        proxy.moveTo(position.x, position.y, position.z, actor.getYRot(), actor.getXRot());
        path = navigation.createPath(BlockPos.containing(direct), 0);
        if (path == null || path.getNodeCount() < 2)
            throw new IllegalStateException("No traversable flight route was found");
        if (path.getNextNodePos().equals(actor.blockPosition())) path.advance();
        waypoint = path.getNextEntityPos(proxy);
    }

    private boolean clear(Vec3 from, Vec3 to) {
        var level = actor.serverLevel();
        int samples = Math.max(1, (int)Math.ceil(from.distanceTo(to) / 0.25));
        for (int i = 1; i <= samples; i++) {
            var box = actor.getBoundingBox().move(from.lerp(to, (double)i / samples).subtract(actor.position()));
            if (!level.noCollision(actor, box)) return false;
            for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                    BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
                if (level.getBlockState(pos).is(BlockTags.PORTALS) && !actor.portalAuthorized(pos)) return false;
            }
        }
        return true;
    }

    private boolean openFlightBarriers(Vec3 step) {
        var level = actor.serverLevel();
        var box = actor.getBoundingBox().expandTowards(step);
        boolean opened = false;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            var state = level.getBlockState(pos);
            if (!PlayerPathEvaluator.closedOpenable(state) || !actor.canReach(pos, 0)
                    || state.getCollisionShape(level, pos).toAabbs().stream().noneMatch(shape -> shape.move(pos).intersects(box))) continue;
            InventoryAccess.open(actor, pos.immutable());
            if (PlayerPathEvaluator.closedOpenable(level.getBlockState(pos)))
                throw new IllegalStateException("Route barrier did not open");
            opened = true;
        }
        return opened;
    }

    int replans() { return replans; }

    void stop() {
        actor.setDeltaMovement(Vec3.ZERO);
        // In midair, retain native hover for the next instruction. Stop flying on the ground or on revocation.
        if (actor.getAbilities().flying && (!actor.getAbilities().mayfly || actor.onGround() && !actor.isSpectator())) {
            actor.getAbilities().flying = false;
            actor.onUpdateAbilities();
        }
    }
}
