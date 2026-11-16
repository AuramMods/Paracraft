package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.AvatarEntry;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.aurammods.paracraft.forge.execution.GameJob;
import com.aurammods.paracraft.forge.execution.JobJson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class ProgramGameTests {
    @GameTest(template = "arena", timeoutTicks = 900)
    public static void searchResultFeedsCollectionAndLedgerFeedsDelivery(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 48.5, 48.5);
        var recipient = spawn(helper, manager, owner, 66.5, 48.5);
        var actor = manager.actor(entry.id); actor.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        chest(helper, new BlockPos(38, 2, 48), 5); chest(helper, new BlockPos(60, 2, 50), 7);
        var program = json("""
                {"taskId":"diamonds","steps":[
                  {"op":"search_containers","item":"minecraft:diamond","saveAs":"found"},
                  {"op":"collect_items","item":"diamond","quantity":"all","containersFrom":"found","saveAs":"loot"},
                  {"op":"deliver_items","itemsFrom":"loot","recipient":{"playerUuid":"%s"}},
                  {"op":"report_result","saveAs":"summary"}]}
                """.formatted(recipient.id));
        GameJob job = manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), UUID.randomUUID(), program, "replace");
        helper.onEachTick(() -> {
            helper.assertTrue(job.state() != JobState.FAILED, "Program failed: " + job.view());
            if (job.state() != JobState.SUCCEEDED) return;
            var results = job.result().getAsJsonObject("results");
            helper.assertTrue(results.getAsJsonObject("loot").get("acquired").getAsInt() == 12, "Named collection result has twelve diamonds");
            var receiver = manager.actor(recipient.id);
            int received = receiver.getInventory().countItem(Items.DIAMOND) + helper.getLevel()
                    .getEntitiesOfClass(ItemEntity.class, receiver.getBoundingBox().inflate(3)).stream()
                    .filter(e -> e.getItem().is(Items.DIAMOND) && assigned(e, recipient.id)).mapToInt(e -> e.getItem().getCount()).sum();
            helper.assertTrue(received == 12 && actor.getInventory().countItem(Items.DIAMOND) == 3, "Named ledger conserves pre-existing belongings");
            actor.getInventory().clearContent(); receiver.getInventory().clearContent();
            manager.destroy(owner, entry.id); manager.destroy(owner, recipient.id);
            pass("program_search_collect_deliver"); helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void nestedCatchDeliversPartialAcquisitionAndContinues(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); var recipient = spawn(helper, manager, owner, 2.5, 4.5);
        manager.actor(entry.id).setNoGravity(true); manager.actor(recipient.id).setNoGravity(true);
        chest(helper, new BlockPos(3, 2, 2), 3);
        var program = json("""
                {"steps":[{"try":[{"try":[
                  {"op":"collect_items","item":"minecraft:diamond","quantity":10000,"saveAs":"partial"}
                ],"catch":[{"op":"report_failure","saveAs":"inner"},{"op":"rethrow"}]}],"catch":[
                  {"op":"deliver_items","itemsFrom":"partial","recipient":"%s"},
                  {"op":"report_failure","saveAs":"outer"}
                ]},{"op":"report_result","saveAs":"continued"}]}
                """.formatted(recipient.id));
        var job = manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), UUID.randomUUID(), program, "replace");
        helper.onEachTick(() -> {
            helper.assertTrue(job.state() != JobState.FAILED, "Recovery program failed: " + job.view());
            if (job.state() != JobState.SUCCEEDED) return;
            var results = job.result().getAsJsonObject("results");
            helper.assertTrue(results.has("continued") && results.getAsJsonObject("outer").getAsJsonObject("partial").get("acquired").getAsInt() >= 3,
                    "Outer handler received the original failure and actual partial result");
            helper.assertTrue(manager.actor(entry.id).getInventory().countItem(Items.DIAMOND) == 0, "Recovery delivered actual collected items");
            manager.actor(recipient.id).getInventory().clearContent(); manager.destroy(owner, entry.id); manager.destroy(owner, recipient.id);
            pass("program_nested_partial_recovery"); helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void reconnectRebindsPendingCollectionWithoutRepeatingTransfer(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); manager.actor(entry.id).setNoGravity(true);
        BlockPos source = new BlockPos(3, 2, 2), destination = new BlockPos(2, 2, 4);
        chest(helper, source, 6); chest(helper, destination, 0);
        var program = json("""
                {"steps":[{"op":"collect_items","item":"minecraft:diamond","quantity":3,"saveAs":"loot"},
                  {"op":"deposit_items","item":"minecraft:diamond","quantity":3,"destination":%s}]}
                """.formatted(JobJson.position(helper.absolutePos(destination))));
        UUID id = UUID.randomUUID(), oldSession = entry.authority.session();
        var original = manager.jobs().submitProgram(owner, entry.id, oldSession, id, program, "replace");
        boolean[] reconnected = {false};
        helper.onEachTick(() -> {
            GameJob current = manager.jobs().get(entry.id);
            helper.assertTrue(current.state() != JobState.FAILED, "Checkpoint program failed: " + current.view());
            if (!reconnected[0] && current.result().has("pending")) {
                var partial = current.result().getAsJsonObject("pending").getAsJsonObject("result");
                if (partial.has("acquired") && partial.get("acquired").getAsInt() == 3) {
                    manager.disconnectOwner(owner);
                    helper.assertTrue(!entry.authority.controls(owner, oldSession), "Old session revoked");
                    try {
                        var bytes = new ByteArrayOutputStream(); NbtIo.writeCompressed(entry.save(), bytes);
                        AvatarEntry decoded = AvatarEntry.load(NbtIo.readCompressed(new ByteArrayInputStream(bytes.toByteArray())));
                        entry.execution = decoded.execution; entry.physical = decoded.physical;
                    } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
                    helper.assertTrue(manager.restoreOwner(owner).isEmpty(), "Restored body and execution");
                    GameJob restored = manager.jobs().get(entry.id);
                    helper.assertTrue(restored != original && restored.state() == JobState.PAUSED, "Pending program was rebound and awaits explicit resume");
                    manager.jobs().resumeRequest(owner, entry.id, entry.authority.session(), id);
                    reconnected[0] = true;
                }
            }
            current = manager.jobs().get(entry.id);
            if (current.state() != JobState.SUCCEEDED) return;
            helper.assertTrue(reconnected[0] && chestCount(helper, source) == 3 && chestCount(helper, destination) == 3
                    && manager.actor(entry.id).getInventory().countItem(Items.DIAMOND) == 0, "Transfer was not replayed after body/NBT restoration");
            var duplicate = manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), id, program, "replace");
            helper.assertTrue(duplicate == current, "Restored request ID acknowledges original completion");
            manager.destroy(owner, entry.id); pass("program_pending_reconnect_no_replay"); helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void appendInterruptAndExplicitResumePreservePrograms(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); manager.actor(entry.id).setNoGravity(true);
        UUID firstId = UUID.randomUUID(), appendedId = UUID.randomUUID();
        var first = manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), firstId, json("{\"steps\":[{\"op\":\"wait_ticks\",\"ticks\":50}]}"), "replace");
        var appended = manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), appendedId, json("{\"steps\":[{\"op\":\"report_result\"}]}"), "append");
        GameJob[] interrupt = {null}; int[] stage = {0};
        helper.runAfterDelay(5, () -> interrupt[0] = manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), UUID.randomUUID(),
                json("{\"steps\":[{\"op\":\"wait_ticks\",\"ticks\":3}]}"), "interrupt"));
        helper.onEachTick(() -> {
            if (interrupt[0] == null) return;
            helper.assertTrue(first.state() != JobState.CANCELLED && appended.state() != JobState.CANCELLED, "Interrupt preserves inactive requests");
            if (stage[0] == 0 && interrupt[0].state() == JobState.SUCCEEDED) {
                helper.assertTrue(first.state() == JobState.PAUSED && appended.state() == JobState.QUEUED, "Prior work stays inactive until chosen");
                manager.jobs().resumeRequest(owner, entry.id, entry.authority.session(), firstId); stage[0] = 1;
            } else if (stage[0] == 1 && first.state() == JobState.SUCCEEDED) {
                manager.jobs().resumeRequest(owner, entry.id, entry.authority.session(), appendedId); stage[0] = 2;
            } else if (stage[0] == 2 && appended.state() == JobState.SUCCEEDED) {
                manager.destroy(owner, entry.id); pass("program_interrupt_resume_queue"); helper.succeed();
            }
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void cancellationBypassesCatchAndFailureStopsAppend(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); var actor = manager.actor(entry.id); actor.setNoGravity(true);
        actor.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3)); BlockPos target = new BlockPos(2, 2, 4); chest(helper, target, 0);
        String deposit = "{\"op\":\"deposit_items\",\"item\":\"minecraft:diamond\",\"quantity\":3,\"destination\":" + JobJson.position(helper.absolutePos(target)) + "}";
        var cancelled = manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), UUID.randomUUID(),
                json("{\"steps\":[{\"try\":[{\"op\":\"wait_ticks\",\"ticks\":50}],\"catch\":[" + deposit + "]}]}"), "replace");
        helper.runAfterDelay(5, () -> manager.jobs().cancel(entry.id, "Owner stop"));
        helper.runAfterDelay(10, () -> {
            helper.assertTrue(cancelled.state() == JobState.CANCELLED && chestCount(helper, target) == 0, "Cancellation does not run a catch");
            var failure = manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), UUID.randomUUID(), json("{\"steps\":[{\"op\":\"fail\",\"message\":\"expected\"}]}"), "replace");
            var appended = manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), UUID.randomUUID(), json("{\"steps\":[" + deposit + "]}"), "append");
            helper.runAfterDelay(5, () -> {
                helper.assertTrue(failure.state() == JobState.FAILED && appended.state() == JobState.QUEUED
                        && chestCount(helper, target) == 0 && actor.getInventory().countItem(Items.DIAMOND) == 3,
                        "Unhandled failure prevents appended dependent work");
                actor.getInventory().clearContent(); manager.destroy(owner, entry.id);
                pass("program_control_events_bypass_recovery"); helper.succeed();
            });
        });
    }

    @GameTest(template = "empty")
    public static void malformedLaterStepRejectsBeforeEarlierWorldMutation(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); var actor = manager.actor(entry.id); actor.setNoGravity(true);
        actor.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3)); BlockPos target = new BlockPos(2, 2, 4); chest(helper, target, 0);
        var invalid = json("{\"steps\":[{\"op\":\"deposit_items\",\"item\":\"minecraft:diamond\",\"quantity\":3,\"destination\":"
                + JobJson.position(helper.absolutePos(target)) + "},{\"op\":\"travel\"}]}");
        try {
            manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), UUID.randomUUID(), invalid, "replace");
            throw new AssertionError("Malformed travel instruction must reject the program");
        } catch (IllegalArgumentException expected) {
            helper.assertTrue(expected.getMessage().contains("destination"), "Actual missing syntax field is reported");
        }
        helper.assertTrue(manager.jobs().get(entry.id) == null && actor.getInventory().countItem(Items.DIAMOND) == 3
                && chestCount(helper, target) == 0, "Syntax rejection precedes any world operation");
        actor.getInventory().clearContent(); manager.destroy(owner, entry.id);
        pass("program_syntax_before_mutation"); helper.succeed();
    }

    private static AvatarEntry spawn(GameTestHelper helper, AvatarManager manager, UUID owner, double x, double z) {
        return manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(x, 2, z)), "QA" + UUID.randomUUID().toString().substring(0, 8));
    }
    private static void chest(GameTestHelper helper, BlockPos relative, int count) {
        helper.setBlock(relative, Blocks.CHEST);
        ((ChestBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(relative))).setItem(0, count == 0 ? ItemStack.EMPTY : new ItemStack(Items.DIAMOND, count));
    }
    private static int chestCount(GameTestHelper helper, BlockPos relative) {
        return ((ChestBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(relative))).getItem(0).getCount();
    }
    private static boolean assigned(ItemEntity item, UUID recipient) { CompoundTag tag = item.saveWithoutId(new CompoundTag()); return tag.hasUUID("Owner") && recipient.equals(tag.getUUID("Owner")); }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static void pass(String event) { Paracraft.LOG.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\"}", event); }
}
