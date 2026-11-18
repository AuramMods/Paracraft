package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.eventbus.api.Event;

/** Hold-to-break timing, then the normal native/Forge destruction path. No delayed native break survives pause. */
final class NativeBlockBreaker implements AutoCloseable {
    private final CompanionPlayer actor; private final BlockPos position; private final BlockState expected;
    private final net.minecraft.server.level.ServerLevel level;
    private final boolean instant;
    private boolean completed, closed;
    private float progress;
    NativeBlockBreaker(CompanionPlayer actor, BlockPos position) {
        this.actor = actor; this.position = position; level = actor.serverLevel(); expected = level.getBlockState(position);
        if (expected.isAir()) throw new IllegalStateException("Target disappeared before mining");
        if (!actor.canReach(position, 0) || !actor.serverLevel().mayInteract(actor, position)
                || actor.blockActionRestricted(actor.serverLevel(), position, actor.gameMode.getGameModeForPlayer()))
            throw new IllegalStateException("Mining target is out of reach or interaction is denied");
        if (!actor.isCreative() && expected.getDestroySpeed(actor.serverLevel(), position) < 0) throw new IllegalStateException("Block cannot be broken");
        var event = ForgeHooks.onLeftClickBlock(actor, position, Direction.UP, ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
        if (event.isCanceled() || event.getResult() == Event.Result.DENY || event.getUseItem() == Event.Result.DENY)
            throw new IllegalStateException("Server denied mining interaction");
        if (event.getUseBlock() != Event.Result.DENY) expected.attack(actor.serverLevel(), position, actor);
        instant = actor.isCreative() || expected.getDestroyProgress(actor, level, position) >= 1;
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
            var stop = ForgeHooks.onLeftClickBlock(actor, position, Direction.UP, ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK);
            if (stop.isCanceled() || stop.getResult() == Event.Result.DENY || stop.getUseItem() == Event.Result.DENY)
                throw new IllegalStateException("Server denied completion of mining interaction");
        }
        if (!actor.isCreative() && !expected.canHarvestBlock(level, position, actor)) throw new IllegalStateException("Current tool can no longer harvest the target");
        boolean accepted = actor.gameMode.destroyBlock(position);
        if (level.getBlockState(position).equals(expected)) throw new IllegalStateException(accepted ? "Block destruction left the target unchanged" : "Server or item refused block destruction");
        completed = true; close(); return true;
    }
    float progress() { return progress; }
    @Override public void close() {
        if (closed) return; closed = true;
        if (!completed && actor.serverLevel() == level)
            ForgeHooks.onLeftClickBlock(actor, position, Direction.UP, ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK);
        level.destroyBlockProgress(actor.getId(), position, -1);
    }
}
