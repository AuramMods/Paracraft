package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.agent.*;
import com.aurammods.paracraft.forge.ServerEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/** Explicit live/billed QA only. No graphical client, browser, or server-side credential access. */
@GameTestHolder("paracraft_live")
@PrefixGameTestTemplate(false)
public final class LiveHarnessGameTests {
    @GameTest(template = "arena", templateNamespace = "paracraft_live", timeoutTicks = 7200)
    public static void lunaCollectsAndDeliversActualDiamonds(GameTestHelper helper) throws Exception {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); var control = manager.control();
        UUID recipientOwner = UUID.randomUUID();
        var recipient = manager.spawn(recipientOwner, helper.getLevel(), helper.absoluteVec(new Vec3(66.5, 2, 48.5)), "OwnerQA");
        UUID owner = recipient.id;
        var entry = manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(48.5, 2, 48.5)), "Josh");
        var actor = manager.actor(entry.id); actor.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        var sourceA = new BlockPos(38, 2, 48); var sourceB = new BlockPos(60, 2, 50);
        chest(helper, sourceA, 5); chest(helper, sourceB, 7);
        var memory = new AgentState(); memory.lightModel = memory.planningModel = "gpt-6-luna";
        memory.lightEffort = "none"; memory.planningEffort = "xhigh";
        var provider = new LiveClientProcess.Remote();
        var loop = new HarnessLoop(new AgentBinding(manager.worldId(), owner, entry.id), memory, provider, AgentClock.SYSTEM,
                (id, tool, arguments, trigger) -> control.request(owner, entry.id, entry.authority.session(), id, tool, arguments, trigger), () -> {}, output -> trace(entry.name,output));
        control.chat(owner, "Josh, collect all the diamonds in nearby containers and give them to me. Keep any diamonds you already owned.");
        Vec3[] previous = {actor.position()}; double[] distance = {0}; long[] idleAt = {0}, idleCalls = {0}; boolean[] finished = {false};
        helper.onEachTick(() -> {
            if (finished[0]) return;
            // GameTestServer otherwise advances thousands of ticks per wall-clock second.
            // Only this opt-in external-inference scenario runs at ordinary 20 TPS.
            java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L);
            try {
                loop.accept(control.snapshot(owner, entry.id)); loop.tick(); loop.tick();
                helper.assertTrue(loop.lastError().isEmpty(), "Live harness: " + loop.lastError());
                distance[0] += actor.position().distanceTo(previous[0]); previous[0] = actor.position();
                boolean completed = java.util.stream.StreamSupport.stream(memory.tasks().spliterator(), false).anyMatch(t -> t.getAsJsonObject().get("status").getAsString().equals("COMPLETED"));
                boolean failed = java.util.stream.StreamSupport.stream(memory.tasks().spliterator(), false).anyMatch(t -> t.getAsJsonObject().get("status").getAsString().equals("FAILED"));
                helper.assertTrue(!failed, "Luna marked the native delivery task failed: " + memory.context());
                if (!completed || !loop.status().equals("Idle")) { idleAt[0] = 0; return; }
                if (idleAt[0] == 0) { idleAt[0] = System.nanoTime(); idleCalls[0] = loop.calls(); }
                helper.assertTrue(loop.calls() == idleCalls[0], "Idle snapshots must not consume live inference");
                if (System.nanoTime() - idleAt[0] < 6_000_000_000L) return;
                var receiver = manager.actor(recipient.id);
                int delivered = receiver.getInventory().countItem(Items.DIAMOND) + helper.getLevel().getEntitiesOfClass(ItemEntity.class, receiver.getBoundingBox().inflate(3)).stream()
                        .filter(item -> item.getItem().is(Items.DIAMOND) && assigned(item, recipient.id)).mapToInt(item -> item.getItem().getCount()).sum();
                helper.assertTrue(delivered == 12, "Actual delivered diamonds: " + delivered);
                helper.assertTrue(actor.getInventory().countItem(Items.DIAMOND) == 3, "Pre-existing diamonds stay carried");
                helper.assertTrue(count(helper, sourceA) == 0 && count(helper, sourceB) == 0, "Both real containers were emptied");
                helper.assertTrue(distance[0] > 20 && loop.planningCalls() > 0 && loop.calls() > loop.planningCalls(), "Physical travel and both Luna routes ran");
                finished[0] = true; loop.close(); provider.close(); actor.getInventory().clearContent(); receiver.getInventory().clearContent();
                manager.destroy(owner, entry.id); manager.destroy(recipientOwner, recipient.id);
                var event = new com.google.gson.JsonObject(); event.addProperty("event", "live_luna_real_diamond_delivery"); event.addProperty("status", "pass");
                var context = new com.google.gson.JsonObject(); context.addProperty("model", "gpt-6-luna"); context.addProperty("lightEffort", "none"); context.addProperty("planningEffort", "xhigh");
                context.addProperty("decisions", loop.calls()); context.addProperty("planningDecisions", loop.planningCalls()); context.addProperty("delivered", delivered);
                context.addProperty("preserved", 3); context.addProperty("distance", distance[0]); context.addProperty("serverPid", ProcessHandle.current().pid());
                context.addProperty("credentials", "separate client process"); event.add("context", context); Paracraft.LOG.info("QA_EVT {}", event);
                helper.succeed();
            } catch (Throwable failure) {
                finished[0] = true; loop.close(); provider.close();
                Paracraft.LOG.error("QA_EVT {\"event\":\"live_luna_real_diamond_delivery\",\"status\":\"fail\"}");
                helper.fail(failure.toString());
            }
        });
    }
    private static void trace(String name,com.google.gson.JsonObject output) {
        if(!Paracraft.debugAgentOutput())return;
        UUID id=UUID.randomUUID();var chunks=AgentOutput.chunks(output.toString());
        for(int i=0;i<chunks.size();i++)Paracraft.LOG.info("[Agent {} output {} {}/{}] {}",name,id,i+1,chunks.size(),chunks.get(i));
    }
    private static void chest(GameTestHelper helper, BlockPos position, int count) { helper.setBlock(position, Blocks.CHEST); ((ChestBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(position))).setItem(0, new ItemStack(Items.DIAMOND, count)); }
    @GameTest(batch = "crafting", template = "arena", templateNamespace = "paracraft_live", timeoutTicks = 7200)
    public static void lunaUsesInternalAndTableCrafting(GameTestHelper helper) throws Exception {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); var control = manager.control(); UUID recipientOwner = UUID.randomUUID();
        var recipient = manager.spawn(recipientOwner, helper.getLevel(), helper.absoluteVec(new Vec3(66.5, 2, 48.5)), "CraftOwner"); UUID owner = recipient.id;
        var entry = manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(48.5, 2, 48.5)), "CraftQA"); var actor = manager.actor(entry.id);
        actor.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 4)); actor.getInventory().setItem(1, new ItemStack(Items.STICK, 3));
        var table = helper.absolutePos(new BlockPos(58, 2, 48)); helper.setBlock(new BlockPos(58, 2, 48), Blocks.CRAFTING_TABLE);
        var memory = new AgentState(); memory.lightModel = memory.planningModel = "gpt-6-luna"; memory.lightEffort = "none"; memory.planningEffort = "xhigh";
        var provider = new LiveClientProcess.Remote();
        var loop = new HarnessLoop(new AgentBinding(manager.worldId(), owner, entry.id), memory, provider, AgentClock.SYSTEM,
                (id, tool, arguments, trigger) -> control.request(owner, entry.id, entry.authority.session(), id, tool, arguments, trigger), () -> {}, output -> trace(entry.name,output));
        control.chat(owner, "CraftQA, craft exactly 8 oak planks from 2 of your 4 oak logs, then use 4 planks to craft exactly 8 new sticks. "
                + "Then use 3 planks and 2 sticks to craft one wooden pickaxe at the crafting table at " + table.getX() + " " + table.getY() + " " + table.getZ()
                + ". Keep everything in your inventory. Use your internal grid for the smaller recipes. Keep your 3 pre-existing sticks.");
        long[] idleAt = {0}, idleCalls = {0}; boolean[] finished = {false}; Vec3 start = actor.position();
        helper.onEachTick(() -> {
            if (finished[0]) return; java.util.concurrent.locks.LockSupport.parkNanos(50_000_000L);
            try {
                loop.accept(control.snapshot(owner, entry.id)); loop.tick(); loop.tick();
                helper.assertTrue(loop.lastError().isEmpty(), "Live crafting harness: " + loop.lastError());
                boolean completed = memory.tasks().asList().stream().anyMatch(t -> t.getAsJsonObject().get("status").getAsString().equals("COMPLETED"));
                helper.assertTrue(memory.tasks().asList().stream().noneMatch(t -> t.getAsJsonObject().get("status").getAsString().equals("FAILED")), "Luna marked crafting failed: " + memory.context());
                if (!completed || !loop.status().equals("Idle")) { idleAt[0] = 0; return; }
                if (idleAt[0] == 0) { idleAt[0] = System.nanoTime(); idleCalls[0] = loop.calls(); }
                helper.assertTrue(loop.calls() == idleCalls[0], "Completed crafting stays idle without more inference");
                if (System.nanoTime() - idleAt[0] < 6_000_000_000L) return;
                helper.assertTrue(actor.getInventory().countItem(Items.OAK_LOG) == 2 && actor.getInventory().countItem(Items.OAK_PLANKS) == 1
                        && actor.getInventory().countItem(Items.STICK) == 9 && actor.getInventory().countItem(Items.WOODEN_PICKAXE) == 1, "Actual native crafting output/material totals must match the request");
                helper.assertTrue(hasCraftGrid(manager, entry.id, 2) && hasCraftGrid(manager, entry.id, 3) && actor.position().distanceTo(start) > 3
                        && loop.planningCalls() > 0 && loop.calls() > loop.planningCalls(), "Luna used both native grid sizes, physical table travel, and both inference routes");
                finished[0] = true; loop.close(); provider.close(); actor.getInventory().clearContent(); manager.actor(recipient.id).getInventory().clearContent();
                manager.destroy(owner, entry.id); manager.destroy(recipientOwner, recipient.id);
                var event = new com.google.gson.JsonObject(); event.addProperty("event", "live_luna_native_crafting"); event.addProperty("status", "pass");
                var context = new com.google.gson.JsonObject(); context.addProperty("model", "gpt-6-luna"); context.addProperty("lightEffort", "none"); context.addProperty("planningEffort", "xhigh");
                context.addProperty("decisions", loop.calls()); context.addProperty("planningDecisions", loop.planningCalls()); context.addProperty("remainingLogs", 2);
                context.addProperty("remainingPlanks", 1); context.addProperty("sticks", 9); context.addProperty("pickaxes", 1); context.addProperty("serverPid", ProcessHandle.current().pid());
                event.add("context", context); Paracraft.LOG.info("QA_EVT {}", event); helper.succeed();
            } catch (Throwable failure) { finished[0] = true; loop.close(); provider.close(); Paracraft.LOG.error("QA_EVT {\"event\":\"live_luna_native_crafting\",\"status\":\"fail\"}"); helper.fail(failure.toString()); }
        });
    }
    private static boolean hasCraftGrid(com.aurammods.paracraft.forge.avatar.AvatarManager manager, UUID avatar, int width) {
        return manager.jobs().requests(avatar).stream().map(job -> job.result()).filter(result -> result.has("results"))
                .flatMap(result -> result.getAsJsonObject("results").entrySet().stream()).map(java.util.Map.Entry::getValue)
                .filter(com.google.gson.JsonElement::isJsonObject).map(com.google.gson.JsonElement::getAsJsonObject)
                .anyMatch(value -> value.has("gridWidth") && value.get("gridWidth").getAsInt() == width);
    }
    private static int count(GameTestHelper helper, BlockPos position) { return ((ChestBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(position))).getItem(0).getCount(); }
    private static boolean assigned(ItemEntity item, UUID owner) { CompoundTag tag = item.saveWithoutId(new CompoundTag()); return tag.hasUUID("Owner") && owner.equals(tag.getUUID("Owner")); }
}
