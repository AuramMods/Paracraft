package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.core.program.BlockTargets;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Mining and mature-crop work share the normal break -> physical pickup -> optional plant pipeline. */
public final class BlockWorkJob extends GameJob {
    private final JsonObject specification;
    private final BlockTargets targets;
    private final boolean farm;
    private final List<ItemStack> acquired = new ArrayList<>();
    private long cursor, broken, skipped, replanted;
    private String stage = "approach", crop = "";
    private int quietTicks;
    private NativeBlockBreaker breaker;
    private Runnable unobserve;
    public BlockWorkJob(UUID id, CompanionPlayer actor, String type, JsonObject specification) {
        super(id, type, actor); this.specification = specification.deepCopy(); targets = new BlockTargets(specification); farm = type.equals("farm");
        update();
    }
    @Override protected void advance() {
        if (unobserve == null) unobserve = actor.observePickups(stack -> { acquire(stack); update(); });
        if (cursor >= targets.size()) { succeed(result); return; }
        var p = targets.get(cursor); BlockPos target = new BlockPos(p.x(), p.y(), p.z());
        if (actor.serverLevel().isOutsideBuildHeight(target) || !actor.serverLevel().getWorldBorder().isWithinBounds(target))
            throw new IllegalStateException("Work target is outside world bounds");
        phase = stage + " target " + (cursor + 1) + "/" + targets.size(); result.add("target", JobJson.position(target));
        switch (stage) {
            case "approach" -> {
                if (!navigate(Vec3.atCenterOf(target), 2.6)) return;
                if (actor.serverLevel().isOutsideBuildHeight(target) || !actor.serverLevel().getWorldBorder().isWithinBounds(target))
                    throw new IllegalStateException("Mining target is outside world bounds");
                if (!actor.serverLevel().hasChunkAt(target)) throw new IllegalStateException("Target chunk is not loaded");
                var state = actor.serverLevel().getBlockState(target);
                if (state.isAir() || farm && (!(state.getBlock() instanceof CropBlock block) || !block.isMaxAge(state))) {
                    skipped++; next(); return;
                }
                crop = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                CarriedItems.miningTool(actor, target, state); breaker = new NativeBlockBreaker(actor, target); stage = "breaking";
            }
            case "breaking" -> {
                if (breaker == null) { stage = "approach"; return; }
                if (!breaker.tick()) { result.addProperty("breakProgress", breaker.progress()); return; }
                breaker.close(); breaker = null; broken++; stage = "collecting"; quietTicks = 0;
            }
            case "collecting" -> {
                var items = actor.serverLevel().getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(2), item -> item.isAlive() && permitted(item));
                if (items.isEmpty()) { if (++quietTicks >= 2) { if (farm && specification.has("plant")) stage = "planting"; else next(); } break; }
                quietTicks = 0;
                ItemEntity item = items.stream().min(Comparator.comparingDouble(actor::distanceToSqr)).orElseThrow();
                if (!actor.getBoundingBox().inflate(1, 0.5, 1).intersects(item.getBoundingBox())) { navigate(item.position(), 0.8); return; }
                stopMoving();
                if (item.hasPickUpDelay()) { phase = "waiting for native pickup delay"; return; }
                int before = item.getItem().getCount(); item.playerTouch(actor);
                if (item.isAlive() && item.getItem().getCount() == before) throw new IllegalStateException("Native pickup was refused or inventory is full");
            }
            case "planting" -> {
                if (!navigate(Vec3.atCenterOf(target), 2.6)) return;
                CarriedItems.select(actor, CarriedItems.find(actor, new ItemSelector(specification.get("plant").getAsString())));
                ItemStack seed = actor.getMainHandItem().copy(); int consumed = NativePlacement.place(actor, target); consumeAcquired(seed, consumed);
                if (!BuiltInRegistries.BLOCK.getKey(actor.serverLevel().getBlockState(target).getBlock()).toString().equals(crop))
                    throw new IllegalStateException("Planting created a different crop/block");
                replanted++; next();
            }
            default -> throw new IllegalStateException("Unknown block-work stage " + stage);
        }
        update();
    }
    private boolean permitted(ItemEntity item) {
        CompoundTag saved = item.saveWithoutId(new CompoundTag());
        return !saved.hasUUID("Owner") || actor.getUUID().equals(saved.getUUID("Owner"));
    }
    private void acquire(ItemStack stack) {
        for (ItemStack existing : acquired) if (ItemStack.isSameItemSameTags(existing, stack)) {
            int moved = Math.min(stack.getCount(), existing.getMaxStackSize() - existing.getCount());
            existing.grow(moved); stack.shrink(moved); if (stack.isEmpty()) return;
        }
        if (!stack.isEmpty()) acquired.add(stack.copy());
    }
    private void consumeAcquired(ItemStack seed, int count) {
        for (ItemStack stack : acquired) if (ItemStack.isSameItemSameTags(stack, seed)) {
            int used = Math.min(count, stack.getCount()); stack.shrink(used); count -= used; if (count == 0) break;
        }
        acquired.removeIf(ItemStack::isEmpty);
    }
    private void next() { cursor++; stage = "approach"; result.remove("breakProgress"); update(); }
    private void update() {
        result.addProperty("requestedBlocks", targets.size()); result.addProperty("processedBlocks", cursor); result.addProperty("broken", broken);
        result.addProperty("skipped", skipped); result.addProperty("replanted", replanted); result.add("acquiredStacks", JobJson.stacks(acquired));
        result.addProperty("acquired", acquired.stream().mapToLong(ItemStack::getCount).sum());
    }
    @Override protected void onStop() {
        if (breaker != null) { breaker.close(); breaker = null; stage = "approach"; }
        result.remove("breakProgress");
        if (unobserve != null) { unobserve.run(); unobserve = null; }
    }
    @Override protected JsonObject writeData() {
        JsonObject data = new JsonObject(); data.add("specification", specification.deepCopy()); data.addProperty("cursor", cursor);
        data.addProperty("broken", broken); data.addProperty("skipped", skipped); data.addProperty("replanted", replanted);
        data.addProperty("stage", stage); data.addProperty("crop", crop); data.add("acquired", JobJson.stacks(acquired)); return data;
    }
    @Override protected void readData(JsonObject data) {
        cursor = data.get("cursor").getAsLong(); broken = data.get("broken").getAsLong(); skipped = data.get("skipped").getAsLong();
        replanted = data.get("replanted").getAsLong(); stage = data.get("stage").getAsString(); crop = data.get("crop").getAsString();
        if (stage.equals("breaking")) stage = "approach";
        acquired.clear(); acquired.addAll(JobJson.stacks(data.getAsJsonArray("acquired"))); update();
    }
}
