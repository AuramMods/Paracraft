package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

final class NativePlacement {
    private NativePlacement() { }
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
        if (level.getBlockState(target).equals(original)) throw new IllegalStateException("Native placement did not change the destination: " + outcome);
        actor.swing(InteractionHand.MAIN_HAND);
        ItemStack after = actor.getMainHandItem();
        return after.is(before.getItem()) ? Math.max(0, before.getCount() - after.getCount()) : before.getCount();
    }

    static boolean canApproachHere(CompanionPlayer actor, BlockPos target) {
        return !actor.getBoundingBox().intersects(new AABB(target))
                && supportFace(actor, target, actor.getEyePosition(), support -> actor.canReach(support, 0)) != null;
    }

    /** Try actual sight lines from nearby safe feet positions. The navigator still has to reach them. */
    static List<Vec3> approaches(CompanionPlayer actor, BlockPos target) {
        var level = actor.serverLevel();
        var candidates = new ArrayList<Vec3>();
        var targetBox = new AABB(target);
        boolean flight = actor.getAbilities().mayfly && !actor.isPassenger();
        double reach = Math.max(0, actor.getBlockReach() - 0.25);
        for (int y = -2; y <= 2; y++) for (int z = -2; z <= 2; z++) for (int x = -2; x <= 2; x++) {
            BlockPos feet = target.offset(x, y, z);
            if (level.isOutsideBuildHeight(feet) || !level.hasChunkAt(feet) || !level.getWorldBorder().isWithinBounds(feet)) continue;
            Vec3 point = Vec3.atBottomCenterOf(feet);
            var box = actor.getBoundingBox().move(point.subtract(actor.position()));
            if (targetBox.intersects(box) || !level.noCollision(actor, box)) continue;
            if (!flight && !level.getBlockCollisions(actor, box.move(0, -0.15, 0)).iterator().hasNext()) continue;
            Vec3 eye = point.add(0, actor.getEyeHeight(), 0);
            if (supportFace(actor, target, eye, support -> eye.distanceToSqr(Vec3.atCenterOf(support)) <= reach * reach) != null)
                candidates.add(point);
        }
        candidates.sort(Comparator.comparingDouble(actor.position()::distanceToSqr));
        return candidates;
    }

    private static BlockHitResult supportFace(CompanionPlayer actor, BlockPos target, Vec3 eye, Predicate<BlockPos> withinReach) {
        var level = actor.serverLevel();
        for (Direction side : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP}) {
            BlockPos support = target.relative(side); var state = level.getBlockState(support);
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
