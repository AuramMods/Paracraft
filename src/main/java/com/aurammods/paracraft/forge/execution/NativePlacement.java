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
import net.minecraft.world.phys.Vec3;

final class NativePlacement {
    private NativePlacement() { }
    static int place(CompanionPlayer actor, BlockPos target) {
        var level = actor.serverLevel(); var original = level.getBlockState(target); ItemStack held = actor.getMainHandItem(), before = held.copy();
        if (!(held.getItem() instanceof BlockItem) && !(held.getItem() instanceof net.minecraftforge.common.IPlantable))
            throw new IllegalStateException("Carried item does not expose block/plant placement");
        if (!original.canBeReplaced()) throw new IllegalStateException("Placement destination is occupied");
        if (!level.getWorldBorder().isWithinBounds(target) || level.isOutsideBuildHeight(target)) throw new IllegalStateException("Placement is outside world bounds");
        BlockHitResult hit = null;
        for (Direction side : new Direction[] {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST, Direction.UP}) {
            BlockPos support = target.relative(side); var state = level.getBlockState(support);
            if (state.isAir() || state.canBeReplaced() || !actor.canReach(support, 0)) continue;
            var shape = state.getShape(level, support, net.minecraft.world.phys.shapes.CollisionContext.of(actor)); if (shape.isEmpty()) continue;
            var bounds = shape.bounds().move(support); Vec3 middle = bounds.getCenter(); Direction face = side.getOpposite();
            Vec3 point = new Vec3(face.getStepX() == 0 ? middle.x : face.getStepX() > 0 ? bounds.maxX : bounds.minX,
                    face.getStepY() == 0 ? middle.y : face.getStepY() > 0 ? bounds.maxY : bounds.minY,
                    face.getStepZ() == 0 ? middle.z : face.getStepZ() > 0 ? bounds.maxZ : bounds.minZ);
            Vec3 inside = point.subtract(face.getStepX() * 0.001, face.getStepY() * 0.001, face.getStepZ() * 0.001);
            var visible = level.clip(new ClipContext(actor.getEyePosition(), inside, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, actor));
            if (visible.getType() != HitResult.Type.BLOCK || !visible.getBlockPos().equals(support) || visible.getDirection() != face) continue;
            if (!level.mayInteract(actor, support) || !level.mayInteract(actor, target) || !actor.mayUseItemAt(support, face, held))
                throw new IllegalStateException("Server denied placement interaction");
            hit = visible; break;
        }
        if (hit == null) throw new IllegalStateException("No visible reachable block face supports placement");
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
}
