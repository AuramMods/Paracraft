package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Incremental local volumetric search and physical swimming toward breathable space. */
public final class AirEscapeJob extends GameJob {
    private final BlockPos origin;
    private final ArrayDeque<BlockPos> frontier = new ArrayDeque<>();
    private final Map<BlockPos, BlockPos> previous = new HashMap<>();
    private List<Vec3> route;
    private int cursor, stalled;
    private Vec3 last;
    private static final Direction[] ORDER = {Direction.UP, Direction.EAST, Direction.WEST, Direction.NORTH, Direction.SOUTH, Direction.DOWN};
    public AirEscapeJob(CompanionPlayer actor) {
        super(UUID.randomUUID(), "oxygen_escape", actor); origin = actor.blockPosition(); frontier.add(origin); previous.put(origin, null); last = actor.position();
    }
    @Override protected void advance() {
        result.addProperty("air", actor.getAirSupply()); result.addProperty("visited", previous.size());
        if (!actor.needsAir()) {
            phase = "recovering_air";
            if (actor.isInWater()) actor.setDeltaMovement(0, 0.08, 0);
            if (!actor.canRefillAir() || actor.getAirSupply() >= actor.getMaxAirSupply() / 2) { result.addProperty("breathingRestored", true); succeed(result); }
            return;
        }
        if (route == null) {
            phase = "searching_for_air";
            for (int budget = 0; budget < 128 && !frontier.isEmpty(); budget++) {
                BlockPos node = frontier.remove();
                if (breathable(point(node)) && !node.equals(origin)) {
                    var reverse = new ArrayList<Vec3>();
                    for (BlockPos step = node; step != null && !step.equals(origin); step = previous.get(step)) reverse.add(point(step));
                    java.util.Collections.reverse(reverse); route = List.copyOf(reverse); result.add("destination", JobJson.vector(point(node))); break;
                }
                for (Direction direction : ORDER) {
                    BlockPos next = node.relative(direction);
                    if (Math.abs(next.getX()-origin.getX()) > 8 || Math.abs(next.getZ()-origin.getZ()) > 8 || Math.abs(next.getY()-origin.getY()) > 16
                            || previous.containsKey(next) || !passable(next)) continue;
                    previous.put(next, node); frontier.add(next);
                }
            }
            if (route == null) {
                if (frontier.isEmpty()) throw new IllegalStateException("No breathable route found in the loaded local oxygen-escape volume");
                return;
            }
        }
        phase = "swimming_to_air";
        while (cursor < route.size() && actor.position().distanceTo(route.get(cursor)) < 0.35) cursor++;
        result.addProperty("routeStep", cursor); result.addProperty("routeSteps", route.size());
        if (cursor >= route.size()) throw new IllegalStateException("Reached nominal air but native breathing is still obstructed");
        Vec3 destination = route.get(cursor);
        if (!passable(BlockPos.containing(destination))) throw new IllegalStateException("Oxygen escape route became obstructed");
        if (actor.position().distanceTo(last) < 0.01) stalled++; else stalled = 0; last = actor.position();
        if (stalled > 80) throw new IllegalStateException("Physical oxygen escape movement is stuck");
        if (!actor.isInWater() && !actor.isInFluidType()) {
            navigate(destination, 0.35); return;
        }
        Vec3 delta = destination.subtract(actor.position());
        double speed = Math.min(0.18 * actor.getAttributeValue(net.minecraftforge.common.ForgeMod.SWIM_SPEED.get()), delta.length());
        actor.setDeltaMovement(delta.normalize().scale(speed));
    }
    private Vec3 point(BlockPos pos) { return new Vec3(pos.getX()+0.5, pos.getY(), pos.getZ()+0.5); }
    private boolean passable(BlockPos pos) {
        var level = actor.serverLevel();
        if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) return false;
        if (level.getBlockState(pos).is(BlockTags.PORTALS) || level.getBlockState(pos.above()).is(BlockTags.PORTALS)
                || level.getFluidState(pos).is(FluidTags.LAVA)) return false;
        Vec3 shift = point(pos).subtract(actor.position());
        if (!level.noCollision(actor, actor.getBoundingBox().move(shift))) return false;
        return !level.getFluidState(pos).isEmpty() || !level.getFluidState(pos.below()).isEmpty()
                || !level.getBlockState(pos.below()).getCollisionShape(level,pos.below()).isEmpty() || level.getBlockState(pos).is(BlockTags.CLIMBABLE);
    }
    private boolean breathable(Vec3 feet) {
        var level = actor.serverLevel(); var eye = BlockPos.containing(feet.add(0,actor.getEyeHeight(),0));
        var fluid = level.getFluidState(eye);
        return level.getBlockState(eye).is(Blocks.BUBBLE_COLUMN) || fluid.isEmpty()
                || !actor.canDrownInFluidType(fluid.getFluidType()) || feet.y + actor.getEyeHeight() >= eye.getY() + fluid.getHeight(level, eye);
    }
    @Override protected void onStop() { actor.setDeltaMovement(0, actor.getDeltaMovement().y, 0); }
}
