package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.eventbus.api.Event;

/** Hold-to-break timing, then the normal native/Forge destruction path. No delayed native break survives pause. */
final class NativeBlockBreaker implements AutoCloseable {
    private final CompanionPlayer actor; private final BlockPos position; private final BlockState expected;
    private final net.minecraft.server.level.ServerLevel level;
    private final Direction face;
    private final boolean instant;
    private boolean completed, closed;
    private float progress;
    NativeBlockBreaker(CompanionPlayer actor, BlockPos position) {
        this(actor, position, null);
    }
    NativeBlockBreaker(CompanionPlayer actor, BlockPos position, BlockHitResult hit) {
        this.actor = actor; this.position = position; level = actor.serverLevel(); expected = level.getBlockState(position);
        face = hit == null ? Direction.UP : hit.getDirection();
        if (expected.isAir()) throw new IllegalStateException("Target disappeared before mining");
        if (!actor.canReach(position, 0) || !actor.serverLevel().mayInteract(actor, position)
                || actor.blockActionRestricted(actor.serverLevel(), position, actor.gameMode.getGameModeForPlayer()))
            throw new IllegalStateException("Mining target is out of reach or interaction is denied");
        if (!actor.isCreative() && expected.getDestroySpeed(actor.serverLevel(), position) < 0) throw new IllegalStateException("Block cannot be broken");
        if (hit != null) actor.lookAt(EntityAnchorArgument.Anchor.EYES, hit.getLocation());
        var event = ForgeHooks.onLeftClickBlock(actor, position, face, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
        if (event.isCanceled() || event.getResult() == Event.Result.DENY || event.getUseItem() == Event.Result.DENY)
            throw new IllegalStateException("Server denied mining interaction");
        if (event.getUseBlock() != Event.Result.DENY) expected.attack(actor.serverLevel(), position, actor);
        instant = actor.isCreative() || expected.getDestroyProgress(actor, level, position) >= 1;
    }
    static BlockHitResult hit(CompanionPlayer actor, BlockPos target, Vec3 eye) {
        var level = actor.serverLevel();
        double reach = actor.getBlockReach();
        if (eye.distanceToSqr(Vec3.atCenterOf(target)) > reach * reach) return null;
        var shape = level.getBlockState(target).getShape(level, target, net.minecraft.world.phys.shapes.CollisionContext.of(actor));
        for (var local : shape.toAabbs()) {
            var box = local.move(target);
            var direct = trace(actor, target, eye, box.getCenter());
            if (direct != null) return direct;
            // A partially covered block can be hit near the edges of the exposed part of its outline.
            for (double x : new double[] {0.15, 0.85}) for (double y : new double[] {0.15, 0.85}) for (double z : new double[] {0.15, 0.85}) {
                var point = new Vec3(box.minX + x * box.getXsize(), box.minY + y * box.getYsize(), box.minZ + z * box.getZsize());
                var hit = trace(actor, target, eye, point);
                if (hit != null) return hit;
            }
        }
        return null;
    }
    private static BlockHitResult trace(CompanionPlayer actor, BlockPos target, Vec3 eye, Vec3 point) {
        var hit = actor.serverLevel().clip(new ClipContext(eye, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, actor));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target) ? hit : null;
    }
    boolean tick() {
        var level = actor.serverLevel();
        if (!level.getBlockState(position).equals(expected)) throw new IllegalStateException("Target block changed while mining");
        float rate = actor.isCreative() ? 1 : expected.getDestroyProgress(actor, level, position);
        if (Float.isNaN(rate) || rate <= 0) throw new IllegalStateException("Actual block break speed is zero");
        progress += rate; actor.swing(InteractionHand.MAIN_HAND);
        level.destroyBlockProgress(actor.getId(), position, Math.min(9, (int)(progress * 10)));
        if (progress < 1) return false;
        if (!instant) {
            var stop = ForgeHooks.onLeftClickBlock(actor, position, face, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
            if (stop.isCanceled() || stop.getResult() == Event.Result.DENY || stop.getUseItem() == Event.Result.DENY)
                throw new IllegalStateException("Server denied completion of mining interaction");
        }
        if (!actor.isCreative() && !expected.canHarvestBlock(level, position, actor)) throw new IllegalStateException("Current tool can no longer harvest the target");
        boolean accepted = actor.gameMode.destroyBlock(position);
        if (level.getBlockState(position).equals(expected)) throw new IllegalStateException(accepted ? "Block destruction left the target unchanged" : "Server or item refused block destruction");
        actor.recordAction(ActionRecords.block("mined", expected, position), level);
        completed = true; close(); return true;
    }
    float progress() { return progress; }
    @Override public void close() {
        if (closed) return; closed = true;
        if (!completed && actor.serverLevel() == level)
            ForgeHooks.onLeftClickBlock(actor, position, face, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
        level.destroyBlockProgress(actor.getId(), position, -1);
    }
}
