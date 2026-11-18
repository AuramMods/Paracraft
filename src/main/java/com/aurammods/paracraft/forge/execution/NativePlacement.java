package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonArray;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Predicate;

final class NativePlacement {
    private NativePlacement() { }
    private static final Direction[] SIDES = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP};
    static int place(CompanionPlayer actor, BlockPos target) {
        var level = actor.serverLevel(); var original = level.getBlockState(target); ItemStack held = actor.getMainHandItem(), before = held.copy();
        if (!(held.getItem() instanceof BlockItem) && !(held.getItem() instanceof net.minecraftforge.common.IPlantable))
            throw new IllegalStateException("Carried item does not expose block/plant placement");
        if (!original.canBeReplaced()) throw new IllegalStateException("Placement destination is occupied");
        if (!level.getWorldBorder().isWithinBounds(target) || level.isOutsideBuildHeight(target)) throw new IllegalStateException("Placement is outside world bounds");
        BlockHitResult hit = supportFace(actor, target, actor.getEyePosition(), support -> actor.canReach(support, 0));
        if (hit == null) throw new IllegalStateException("No visible reachable block face supports placement");
        BlockPos support = hit.getBlockPos();
        if (!level.mayInteract(actor, support) || !level.mayInteract(actor, target) || !actor.mayUseItemAt(support, hit.getDirection(), held))
            throw new IllegalStateException("Server denied placement interaction");
        actor.lookAt(EntityAnchorArgument.Anchor.EYES, hit.getLocation());
        boolean sneaking = actor.isShiftKeyDown(); actor.setShiftKeyDown(true);
        net.minecraft.world.InteractionResult outcome;
        try { outcome = actor.gameMode.useItemOn(actor, level, held, InteractionHand.MAIN_HAND, hit); }
        finally { actor.setShiftKeyDown(sneaking); }
        var placed = level.getBlockState(target);
        if (placed.equals(original)) throw new IllegalStateException("Native placement did not change the destination: " + outcome);
        if (!placed.isAir()) actor.recordAction(ActionRecords.block("placed", placed, target), level);
        actor.swing(InteractionHand.MAIN_HAND);
        ItemStack after = actor.getMainHandItem();
        return after.is(before.getItem()) ? Math.max(0, before.getCount() - after.getCount()) : before.getCount();
    }

    static boolean approach(GameJob job, BlockApproach approach, BlockPos target) {
        var actor = job.actor();
        return approach.tick(job, target, () -> {
            if (!actor.serverLevel().getBlockState(target).canBeReplaced()) {
                job.stopMoving();
                approach.reset();
                throw new BlockApproach.Unreachable("Placement destination is occupied");
            }
            return !actor.getBoundingBox().intersects(new AABB(target))
                    && supportFace(actor, target, actor.getEyePosition(), support -> actor.canReach(support, 0)) != null;
        }, () -> approaches(actor, target), "No reachable face supports placement");
    }

    /** Try actual sight lines from nearby safe feet positions. The navigator still has to reach them. */
    private static List<BlockApproach.Stance> approaches(CompanionPlayer actor, BlockPos target) {
        var level = actor.serverLevel();
        boolean supported = false;
        for (Direction side : SIDES) {
            var support = target.relative(side);
            if (level.hasChunkAt(support) && !level.getBlockState(support).canBeReplaced()) {
                supported = true;
                break;
            }
        }
        if (!supported) return List.of();
        double reach = actor.getBlockReach();
        return BlockApproach.candidates(actor, target, true,
                eye -> supportFace(actor, target, eye, support -> eye.distanceToSqr(Vec3.atCenterOf(support)) <= reach * reach) != null);
    }

    static void skipUnreachable(GameJob job, BlockPos target) {
        var progress = job.result;
        if (!progress.has("unreachablePositions")) progress.add("unreachablePositions", new JsonArray());
        var positions = progress.getAsJsonArray("unreachablePositions");
        positions.add(JobJson.position(target));
        progress.addProperty("unreachableCount", positions.size());
    }

    static void finish(GameJob job) {
        var progress = job.result;
        if (progress.has("unreachableCount"))
            job.fail("Skipped " + progress.get("unreachableCount").getAsInt() + " unreachable placement positions");
        else job.succeed(progress);
    }

    private static BlockHitResult supportFace(CompanionPlayer actor, BlockPos target, Vec3 eye, Predicate<BlockPos> withinReach) {
        var level = actor.serverLevel();
        for (Direction side : SIDES) {
            BlockPos support = target.relative(side);
            if (!level.hasChunkAt(support)) continue;
            var state = level.getBlockState(support);
            if (state.isAir() || state.canBeReplaced() || !withinReach.test(support)) continue;
            var shape = state.getShape(level, support, net.minecraft.world.phys.shapes.CollisionContext.of(actor)); if (shape.isEmpty()) continue;
            var bounds = shape.bounds().move(support); Vec3 middle = bounds.getCenter(); Direction face = side.getOpposite();
            Vec3 point = new Vec3(face.getStepX() == 0 ? middle.x : face.getStepX() > 0 ? bounds.maxX : bounds.minX,
                    face.getStepY() == 0 ? middle.y : face.getStepY() > 0 ? bounds.maxY : bounds.minY,
                    face.getStepZ() == 0 ? middle.z : face.getStepZ() > 0 ? bounds.maxZ : bounds.minZ);
            Vec3 inside = point.subtract(face.getStepX() * 0.001, face.getStepY() * 0.001, face.getStepZ() * 0.001);
            var visible = level.clip(new ClipContext(eye, inside, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, actor));
            if (visible.getType() != HitResult.Type.BLOCK || !visible.getBlockPos().equals(support) || visible.getDirection() != face) continue;
            return visible;
        }
        return null;
    }
}
