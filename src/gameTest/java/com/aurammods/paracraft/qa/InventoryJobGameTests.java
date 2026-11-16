package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.AvatarEntry;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.aurammods.paracraft.forge.execution.CollectJob;
import com.aurammods.paracraft.forge.execution.DeliverJob;
import com.aurammods.paracraft.forge.execution.TravelJob;
import com.aurammods.paracraft.forge.inventory.InventoryAccess;
import com.aurammods.paracraft.forge.inventory.InventorySearch;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class InventoryJobGameTests {
    @GameTest(template = "arena", timeoutTicks = 900)
    public static void walksCollectsAndDeliversOnlyAcquiredDiamonds(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        AvatarEntry entry = spawn(helper, manager, owner, 48.5, 48.5);
        AvatarEntry recipient = spawn(helper, manager, owner, 66.5, 48.5);
        var actor = manager.actor(entry.id);
        actor.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        chest(helper, new BlockPos(38, 2, 48), 5);
        chest(helper, new BlockPos(60, 2, 50), 7);
        ChunkPos originalCenter = actor.chunkPosition();
        var collect = new CollectJob(UUID.randomUUID(), actor, new ItemSelector("minecraft:diamond"), -1);
        manager.jobs().submit(owner, entry.id, entry.authority.session(), collect, false);
        DeliverJob[] delivery = {null};
        Vec3[] previous = {actor.position()};
        double[] distance = {0};
        helper.onEachTick(() -> {
            double step = actor.position().distanceTo(previous[0]);
            helper.assertTrue(step < 1, "Movement uses ordinary physics, no teleporting between containers");
            distance[0] += step; previous[0] = actor.position();
            helper.assertTrue(collect.state() != JobState.FAILED, "Collection failure: " + collect.view());
            if (collect.state() == JobState.SUCCEEDED && delivery[0] == null) {
                helper.assertTrue(collect.result().get("acquired").getAsLong() == 12, "Collected exactly twelve source diamonds: " + collect.view());
                helper.assertTrue(collect.result().getAsJsonObject("search").get("centerChunkX").getAsInt() == originalCenter.x,
                        "Search scope did not move with the avatar");
                delivery[0] = new DeliverJob(UUID.randomUUID(), actor, recipient.id, collect.acquired());
                manager.jobs().submit(owner, entry.id, entry.authority.session(), delivery[0], false);
            }
            if (delivery[0] == null) return;
            helper.assertTrue(delivery[0].state() != JobState.FAILED, "Delivery failure: " + delivery[0].view());
            if (delivery[0].state() != JobState.SUCCEEDED) return;
            int carried = actor.getInventory().countItem(Items.DIAMOND);
            var receiver = manager.actor(recipient.id);
            int received = receiver.getInventory().countItem(Items.DIAMOND)
                    + helper.getLevel().getEntitiesOfClass(ItemEntity.class, receiver.getBoundingBox().inflate(3)).stream()
                    .filter(e -> e.getItem().is(Items.DIAMOND) && assignedTo(e, recipient.id))
                    .mapToInt(e -> e.getItem().getCount()).sum();
            helper.assertTrue(carried == 3 && received == 12, "Pre-existing three retained; twelve delivered; carried="
                    + carried + " received=" + received + " delivery=" + delivery[0].view());
            helper.assertTrue(distance[0] > 20, "Avatar physically traversed the route");
            helper.assertTrue(actor.getLastSectionPos().x() == actor.chunkPosition().x
                    && actor.getLastSectionPos().z() == actor.chunkPosition().z,
                    "Server chunk tracking follows the actor across chunk boundaries");
            actor.getInventory().clearContent(); receiver.getInventory().clearContent();
            manager.destroy(owner, entry.id); manager.destroy(owner, recipient.id);
            pass("physical_collect_delivery"); helper.succeed();
        });
    }

    @GameTest(template = "arena")
    public static void searchUsesExactlyNineChunksAndDeduplicatesDoubleChest(GameTestHelper helper) {
        BlockPos centerPos = helper.absolutePos(new BlockPos(48, 2, 48));
        ChunkPos center = new ChunkPos(centerPos);
        int expected = 0;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            int count = ++expected;
            BlockPos pos = new BlockPos((center.x + dx) * 16 + 4, centerPos.getY(), (center.z + dz) * 16 + 4);
            helper.getLevel().setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
            ((ChestBlockEntity)helper.getLevel().getBlockEntity(pos)).setItem(0, new ItemStack(Items.DIAMOND, count));
        }
        BlockPos outside = new BlockPos((center.x + 2) * 16 + 2, centerPos.getY(), center.z * 16 + 2);
        helper.getLevel().setBlockAndUpdate(outside, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity)helper.getLevel().getBlockEntity(outside)).setItem(0, new ItemStack(Items.DIAMOND, 64));
        // Explicitly form a double chest in the center chunk; both block entities expose the same storage.
        BlockPos left = centerPos.offset(0, 0, 2), right = left.east();
        var leftState = Blocks.CHEST.defaultBlockState().setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                net.minecraft.world.level.block.state.properties.ChestType.LEFT)
                .setValue(net.minecraft.world.level.block.ChestBlock.FACING, net.minecraft.core.Direction.NORTH);
        var rightState = leftState.setValue(net.minecraft.world.level.block.ChestBlock.TYPE,
                net.minecraft.world.level.block.state.properties.ChestType.RIGHT);
        helper.getLevel().setBlock(left, leftState, 2); helper.getLevel().setBlock(right, rightState, 2);
        ((ChestBlockEntity)helper.getLevel().getBlockEntity(left)).setItem(0, new ItemStack(Items.DIAMOND, 2));
        ((ChestBlockEntity)helper.getLevel().getBlockEntity(right)).setItem(0, new ItemStack(Items.DIAMOND, 3));
        var search = new InventorySearch(helper.getLevel(), center, new ItemSelector("minecraft:diamond"));
        while (!search.done()) search.tick();
        helper.assertTrue(search.result().get("complete").getAsBoolean(), "Nine generated fixture chunks are covered");
        helper.assertTrue(search.result().getAsJsonArray("coveredChunks").size() == 9, "Only nine chunks covered");
        helper.assertTrue(search.result().get("total").getAsInt() == 50, "45 in single chests plus 5 in one double chest, outside 64 excluded: " + search.result());
        helper.assertTrue(search.matches().size() == 10, "Double chest contributes one inventory match");
        long observedAt = search.result().get("observedAt").getAsLong();
        helper.runAfterDelay(5, () -> {
            helper.assertTrue(helper.getLevel().getGameTime() > observedAt
                    && search.result().get("observedAt").getAsLong() == observedAt,
                    "Reading a completed search does not pretend its observations are fresh");
            pass("nine_chunk_search_and_double_chest"); helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void fullInventoryAndSidedOutputPreserveItems(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); var actor = manager.actor(entry.id);
        BlockPos furnacePos = helper.absolutePos(new BlockPos(3, 1, 2));
        helper.getLevel().setBlockAndUpdate(furnacePos, Blocks.FURNACE.defaultBlockState());
        var furnace = (FurnaceBlockEntity)helper.getLevel().getBlockEntity(furnacePos);
        furnace.setItem(2, new ItemStack(Items.DIAMOND, 5));
        InventoryAccess.open(actor, furnacePos);
        for (int slot = 0; slot < actor.getInventory().items.size(); slot++) actor.getInventory().setItem(slot, new ItemStack(Items.STONE, 64));
        try {
            InventoryAccess.take(actor, furnacePos, new ItemSelector("minecraft:diamond"), 5, stack -> { });
            throw new AssertionError("A full inventory must report failure");
        } catch (IllegalStateException expected) {
            helper.assertTrue(expected.getMessage().contains("full"), "Reported actual capacity failure");
        }
        helper.assertTrue(furnace.getItem(2).getCount() == 5, "Full avatar did not remove source items");
        actor.getInventory().setItem(0, ItemStack.EMPTY);
        int taken = InventoryAccess.take(actor, furnacePos, new ItemSelector("minecraft:diamond"), 5, stack -> { });
        helper.assertTrue(taken == 5 && furnace.getItem(2).isEmpty(), "Extracted through valid furnace output side");
        helper.assertTrue(actor.getInventory().countItem(Items.DIAMOND) == 5, "Items are in real actor inventory");
        actor.getInventory().clearContent(); manager.destroy(owner, entry.id);
        pass("sided_output_and_full_inventory"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void serverHookCanDenyInventoryInteraction(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); var actor = manager.actor(entry.id);
        BlockPos chest = helper.absolutePos(new BlockPos(3, 1, 2)); chest(helper, new BlockPos(3, 1, 2), 8);
        class Blocker {
            @SubscribeEvent public void deny(PlayerInteractEvent.RightClickBlock event) {
                if (event.getEntity() == actor && event.getPos().equals(chest)) event.setCanceled(true);
            }
        }
        Blocker blocker = new Blocker(); MinecraftForge.EVENT_BUS.register(blocker);
        try {
            try { InventoryAccess.open(actor, chest); throw new AssertionError("Denied interaction must fail"); }
            catch (IllegalStateException expected) { helper.assertTrue(expected.getMessage().contains("denied"), "Server hook refusal surfaced"); }
            helper.assertTrue(((ChestBlockEntity)helper.getLevel().getBlockEntity(chest)).getItem(0).getCount() == 8,
                    "Protected contents unchanged");
        } finally { MinecraftForge.EVENT_BUS.unregister(blocker); manager.destroy(owner, entry.id); }
        pass("inventory_permission_hook"); helper.succeed();
    }

    @GameTest(template = "arena", timeoutTicks = 500)
    public static void travelPauseAndResumeKeepsTheSameJob(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 40.5, 48.5); var actor = manager.actor(entry.id);
        var job = new TravelJob(UUID.randomUUID(), actor, helper.absoluteVec(new Vec3(62.5, 2, 48.5)));
        manager.jobs().submit(owner, entry.id, entry.authority.session(), job, false);
        Vec3[] stopped = {null};
        helper.runAfterDelay(15, () -> { manager.pause(owner, entry.id, true); stopped[0] = actor.position(); });
        helper.runAfterDelay(35, () -> {
            helper.assertTrue(job.state() == JobState.PAUSED && actor.position().distanceTo(stopped[0]) < 0.1,
                    "Pause stops horizontal movement and keeps progress");
            manager.pause(owner, entry.id, false);
        });
        helper.onEachTick(() -> {
            helper.assertTrue(job.state() != JobState.FAILED, "Travel failed: " + job.view());
            if (job.state() == JobState.SUCCEEDED) {
                helper.assertTrue(manager.jobs().get(entry.id) == job, "Resumed same operation");
                manager.destroy(owner, entry.id); pass("travel_pause_resume"); helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 150)
    public static void retryAfterAnotherJobDoesNotCollectTwice(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); var actor = manager.actor(entry.id);
        actor.setNoGravity(true);
        BlockPos source = new BlockPos(3, 2, 2); chest(helper, source, 3);
        UUID request = UUID.randomUUID();
        CollectJob original = new CollectJob(request, actor, new ItemSelector("minecraft:diamond"), 1);
        manager.jobs().submit(owner, entry.id, entry.authority.session(), original, false);
        helper.onEachTick(() -> {
            helper.assertTrue(original.state() != JobState.FAILED, "Initial collect: " + original.view());
            if (original.state() != JobState.SUCCEEDED) return;
            var other = new TravelJob(UUID.randomUUID(), actor, actor.position());
            manager.jobs().submit(owner, entry.id, entry.authority.session(), other, false);
            var retry = new CollectJob(request, actor, new ItemSelector("minecraft:diamond"), 1);
            var acknowledged = manager.jobs().submit(owner, entry.id, entry.authority.session(), retry, true);
            helper.assertTrue(acknowledged == original && manager.jobs().get(entry.id) == other,
                    "Retry acknowledges the old result without interrupting the new operation");
            helper.assertTrue(actor.getInventory().countItem(Items.DIAMOND) == 1
                    && ((ChestBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(source))).getItem(0).getCount() == 2,
                    "The source was only mutated once");
            actor.getInventory().clearContent(); manager.destroy(owner, entry.id);
            pass("job_request_deduplication"); helper.succeed();
        });
    }

    private static AvatarEntry spawn(GameTestHelper helper, AvatarManager manager, UUID owner, double x, double z) {
        return manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(x, 2, z)), "QA" + UUID.randomUUID().toString().substring(0, 8));
    }
    private static boolean assignedTo(ItemEntity entity, UUID recipient) {
        var tag = entity.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        return tag.hasUUID("Owner") && recipient.equals(tag.getUUID("Owner"));
    }
    private static void chest(GameTestHelper helper, BlockPos relative, int count) {
        helper.setBlock(relative, Blocks.CHEST);
        ((ChestBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(relative))).setItem(0, new ItemStack(Items.DIAMOND, count));
    }
    private static void pass(String event) { Paracraft.LOG.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\"}", event); }
}
