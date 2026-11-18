package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.BlockPathTypes;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.Vec3;

/** Keep native walking and stairs. Add the vertical and shore connections a player can traverse. */
final class PlayerPathEvaluator extends WalkNodeEvaluator {
    private static final Direction[] DIRECTIONS = Direction.values();
    private final CompanionPlayer actor;
    private final Vec3 idleCenter;
    private final double idleRadius;

    PlayerPathEvaluator(CompanionPlayer actor, Vec3 idleCenter, double idleRadius) {
        this.actor = actor;
        this.idleCenter = idleCenter;
        this.idleRadius = idleRadius;
        setCanPassDoors(true);
    }

    @Override public Node getStart() {
        BlockPos pos = mob.blockPosition();
        if (idleCenter == null && (water(level, pos) || climbable(level, pos))) {
            Node node = getNode(pos);
            node.type = water(level, pos) ? BlockPathTypes.WATER : BlockPathTypes.WALKABLE;
            node.costMalus = mob.getPathfindingMalus(node.type);
            return node;
        }
        return super.getStart();
    }

    @Override public BlockPathTypes getBlockPathType(BlockGetter level, int x, int y, int z) {
        var pos = new BlockPos(x, y, z);
        var state = level.getBlockState(pos);
        if (idleCenter != null && (Vec3.atBottomCenterOf(pos).distanceToSqr(idleCenter) > (idleRadius - 0.5) * (idleRadius - 0.5)
                || !state.getFluidState().isEmpty())) return BlockPathTypes.BLOCKED;
        if (idleCenter == null && (climbable(level, pos) || climbable(level, pos.below())) && clear(level, pos))
            return BlockPathTypes.WALKABLE;
        if (closedOpenable(state)) return BlockPathTypes.DOOR_WOOD_CLOSED;
        return super.getBlockPathType(level, x, y, z);
    }

    @Override public int getNeighbors(Node[] neighbors, Node node) {
        int count = super.getNeighbors(neighbors, node);
        if (idleCenter != null) return count;
        BlockPos here = node.asBlockPos();
        boolean wet = water(level, here);
        if (climbable(level, here)) count = add(neighbors, count, here.above(), BlockPathTypes.WALKABLE);
        if (climbable(level, here.below())) count = add(neighbors, count, here.below(), BlockPathTypes.WALKABLE);
        for (Direction direction : DIRECTIONS) {
            BlockPos next = here.relative(direction);
            if (wet || direction.getAxis().isHorizontal()) {
                if (water(level, next)) count = add(neighbors, count, next, BlockPathTypes.WATER);
                else if (!wet && level.getBlockState(next).isAir() && water(level, next.below()) && clear(level, next))
                    count = add(neighbors, count, next.below(), BlockPathTypes.WATER);
            }
        }
        return count;
    }

    private int add(Node[] neighbors, int count, BlockPos pos, BlockPathTypes type) {
        if (level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos) || !clear(level, pos)) return count;
        Node next = getNode(pos);
        if (next.closed) return count;
        for (int i = 0; i < count; i++) if (neighbors[i] == next) return count;
        next.type = type;
        next.costMalus = mob.getPathfindingMalus(type);
        neighbors[count] = next;
        return count + 1;
    }

    private boolean clear(BlockGetter level, BlockPos pos) {
        var box = actor.getDimensions(Pose.STANDING).makeBoundingBox(Vec3.atBottomCenterOf(pos));
        for (BlockPos cell : BlockPos.betweenClosed(pos, pos.above((int)Math.ceil(box.getYsize()) - 1))) {
            var state = level.getBlockState(cell);
            if (state.is(BlockTags.PORTALS) && !actor.portalAuthorized(cell) || state.getFluidState().is(FluidTags.LAVA)) return false;
            if (closedOpenable(state)) state = state.setValue(DoorBlock.OPEN, true);
            if (state.getCollisionShape(level, cell).toAabbs().stream().anyMatch(shape -> shape.move(cell).intersects(box))) return false;
        }
        return true;
    }

    static boolean water(BlockGetter level, BlockPos pos) { return level.getFluidState(pos).is(FluidTags.WATER); }

    static boolean climbable(BlockGetter level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (state.is(BlockTags.CLIMBABLE)) return true;
        if (!(state.getBlock() instanceof TrapDoorBlock trapdoor)
                || !state.getValue(TrapDoorBlock.OPEN) && !trapdoor.type.canOpenByHand()) return false;
        var below = level.getBlockState(pos.below());
        return below.is(Blocks.LADDER) && below.getValue(LadderBlock.FACING) == state.getValue(TrapDoorBlock.FACING);
    }

    static boolean closedOpenable(BlockState state) {
        var block = state.getBlock();
        boolean handOpenable = block instanceof FenceGateBlock || block instanceof DoorBlock door && door.type().canOpenByHand()
                || block instanceof TrapDoorBlock trapdoor && trapdoor.type.canOpenByHand();
        return handOpenable && !state.getValue(DoorBlock.OPEN);
    }
}
