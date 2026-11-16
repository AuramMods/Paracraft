package com.aurammods.paracraft.forge.inventory;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.WorldlyContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.items.wrapper.PlayerMainInvWrapper;
import net.minecraftforge.items.wrapper.SidedInvWrapper;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Re-resolve on each mutation: capability lifetime and slot rules belong to the target mod. */
public final class InventoryAccess {
    private InventoryAccess() { }
    public record View(Direction side, IItemHandler handler) { }
    public record Target(BlockPos position, BlockPos identity, Container vanilla, List<View> views) { }

    public static Target resolve(net.minecraft.server.level.ServerLevel level, BlockPos position) {
        var blockEntity = level.getBlockEntity(position);
        if (blockEntity == null) return null;
        var state = level.getBlockState(position);
        BlockPos identity = position.immutable();
        Container vanilla = blockEntity instanceof Container container ? container : null;
        if (state.getBlock() instanceof ChestBlock chest) {
            vanilla = ChestBlock.getContainer(chest, state, level, position, true);
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
                BlockPos other = position.relative(ChestBlock.getConnectedDirection(state));
                if (level.getBlockEntity(other) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity)
                    identity = position.compareTo(other) <= 0 ? position.immutable() : other.immutable();
            }
        }
        List<View> views = new ArrayList<>();
        Set<IItemHandler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Direction side : Direction.values()) {
            IItemHandler handler = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER, side).orElse(null);
            if (handler == null && vanilla != null) handler = vanilla instanceof WorldlyContainer sided
                    ? new SidedInvWrapper(sided, side) : new InvWrapper(vanilla);
            if (handler != null && seen.add(handler)) views.add(new View(side, handler));
        }
        return vanilla == null && views.isEmpty() ? null : new Target(position.immutable(), identity, vanilla, List.copyOf(views));
    }

    public static long count(Target target, ItemSelector selector) {
        if (target.vanilla() != null) {
            long total = 0;
            for (int i = 0; i < target.vanilla().getContainerSize(); i++) {
                ItemStack stack = target.vanilla().getItem(i);
                if (selector.matches(stack)) total += stack.getCount();
            }
            return total;
        }
        // Generic face wrappers have no universal backing-slot identity. Use the largest
        // visible view and explicitly label this conservative count as uncertain.
        long maximum = 0;
        for (View view : target.views()) {
            long count = 0;
            for (int slot = 0; slot < view.handler().getSlots(); slot++) {
                ItemStack stack = view.handler().getStackInSlot(slot);
                if (selector.matches(stack)) count += stack.getCount();
            }
            maximum = Math.max(maximum, count);
        }
        return maximum;
    }

    public static boolean countCertain(Target target) { return target.vanilla() != null || target.views().size() <= 1; }

    public static void open(CompanionPlayer actor, BlockPos position) {
        var level = actor.serverLevel();
        if (actor.distanceToSqr(Vec3.atCenterOf(position)) > 12.25)
            throw new IllegalStateException("Container is outside interaction reach");
        if (!level.mayInteract(actor, position)) throw new IllegalStateException("World refused this interaction");
        var state = level.getBlockState(position);
        var hit = new BlockHitResult(Vec3.atCenterOf(position), Direction.UP, position, false);
        var event = ForgeHooks.onRightClickBlock(actor, InteractionHand.MAIN_HAND, position, hit);
        if (event.isCanceled() || event.getUseBlock() == Event.Result.DENY)
            throw new IllegalStateException("Container interaction was denied by a server hook");
        var entity = level.getBlockEntity(position);
        if (entity instanceof BaseContainerBlockEntity container && !container.canOpen(actor))
            throw new IllegalStateException("Container is locked");
        if (state.getBlock() instanceof ChestBlock chest) {
            if (ChestBlock.getContainer(chest, state, level, position, false) == null)
                throw new IllegalStateException("Chest opening is obstructed");
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE
                    && level.getBlockEntity(position.relative(ChestBlock.getConnectedDirection(state))) instanceof BaseContainerBlockEntity other
                    && !other.canOpen(actor)) throw new IllegalStateException("Other half of chest is locked");
        }
        if (state.use(level, actor, InteractionHand.MAIN_HAND, hit) == InteractionResult.FAIL)
            throw new IllegalStateException("Container refused opening");
    }

    /** Move at most maxCount items; return the actual count. Remainders are conserved. */
    public static int take(CompanionPlayer actor, BlockPos position, ItemSelector selector, int maxCount,
                           java.util.function.Consumer<ItemStack> acquired) {
        requireReach(actor, position);
        Target target = resolve(actor.serverLevel(), position);
        if (target == null) throw new IllegalStateException("Container disappeared or has no supported item interface");
        var destination = new PlayerMainInvWrapper(actor.getInventory());
        int moved = 0;
        for (View view : target.views()) for (int slot = 0; slot < view.handler().getSlots(); slot++) {
            if (moved >= maxCount) return moved;
            ItemStack present = view.handler().getStackInSlot(slot);
            if (!selector.matches(present)) continue;
            ItemStack offered = present.copyWithCount(Math.min(present.getCount(), maxCount - moved));
            int capacity = offered.getCount() - ItemHandlerHelper.insertItemStacked(destination, offered, true).getCount();
            if (capacity <= 0) throw new IllegalStateException("Avatar inventory is full; collection stopped with partial results");
            ItemStack extracted = view.handler().extractItem(slot, capacity, false);
            if (extracted.isEmpty()) continue;
            ItemStack remaining = ItemHandlerHelper.insertItemStacked(destination, extracted, false);
            int accepted = extracted.getCount() - remaining.getCount();
            if (accepted > 0) { acquired.accept(extracted.copyWithCount(accepted)); moved += accepted; }
            if (!remaining.isEmpty()) {
                ItemStack returned = view.handler().insertItem(slot, remaining, false);
                if (!returned.isEmpty()) actor.drop(returned, false);
                throw new IllegalStateException("Destination changed during transfer; remainder returned or dropped at avatar");
            }
        }
        return moved;
    }

    public static int deposit(CompanionPlayer actor, BlockPos position, ItemSelector selector, int maxCount) {
        requireReach(actor, position);
        Target target = resolve(actor.serverLevel(), position);
        if (target == null) throw new IllegalStateException("Destination no longer exposes an inventory");
        int moved = 0;
        var source = new PlayerMainInvWrapper(actor.getInventory());
        for (int slot = 0; slot < source.getSlots() && moved < maxCount; slot++) {
            ItemStack present = source.getStackInSlot(slot);
            if (!selector.matches(present)) continue;
            for (View view : target.views()) {
                ItemStack offer = source.extractItem(slot, Math.min(present.getCount(), maxCount - moved), true);
                if (offer.isEmpty()) break;
                int capacity = offer.getCount() - ItemHandlerHelper.insertItemStacked(view.handler(), offer, true).getCount();
                if (capacity <= 0) continue;
                ItemStack extracted = source.extractItem(slot, capacity, false);
                ItemStack remainder = ItemHandlerHelper.insertItemStacked(view.handler(), extracted, false);
                moved += extracted.getCount() - remainder.getCount();
                if (!remainder.isEmpty()) {
                    ItemStack returned = source.insertItem(slot, remainder, false);
                    if (!returned.isEmpty()) actor.drop(returned, false);
                }
                if (moved >= maxCount) break;
            }
        }
        return moved;
    }
    private static void requireReach(CompanionPlayer actor, BlockPos position) {
        if (actor.distanceToSqr(Vec3.atCenterOf(position)) > 12.25)
            throw new IllegalStateException("Inventory mutation is outside interaction reach");
    }
}
