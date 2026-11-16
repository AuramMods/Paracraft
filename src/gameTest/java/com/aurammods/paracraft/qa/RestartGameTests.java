package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.execution.JobJson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** Two actual Forge JVMs: the marker stores expectations, never replacement avatar/job state. */
@GameTestHolder("paracraft_restart")
@PrefixGameTestTemplate(false)
public final class RestartGameTests {
    private static final BlockPos SOURCE = new BlockPos(3001, 80, 3000), DESTINATION = new BlockPos(3000, 80, 3002);
    private static final Path MARKER = Path.of("expected.json");

    @GameTest(template = "empty", templateNamespace = "paracraft_restart", timeoutTicks = 500)
    public static void processRestartRetainsPendingTransferAndRequestIdentity(GameTestHelper helper) throws Exception {
        String phase = System.getProperty("paracraft.qa.restartPhase", "");
        if (phase.equals("write")) write(helper);
        else if (phase.equals("read")) read(helper);
        else throw new IllegalStateException("Run via scripts/restart-qa.sh");
    }
    private static JsonObject program() {
        return JsonParser.parseString("""
                {"taskId":"restart-test","steps":[
                  {"op":"collect_items","item":"minecraft:diamond","quantity":3,"saveAs":"loot"},
                  {"op":"deposit_items","item":"minecraft:diamond","quantity":3,"destination":%s,"saveAs":"deposited"}]}
                """.formatted(JobJson.position(DESTINATION))).getAsJsonObject();
    }
    private static void write(GameTestHelper helper) {
        var level = helper.getLevel(); var manager = ServerEvents.avatars(level.getServer());
        for (BlockPos pos : BlockPos.betweenClosed(2998, 79, 2998, 3003, 83, 3004))
            level.setBlockAndUpdate(pos, pos.getY() == 79 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(SOURCE, Blocks.CHEST.defaultBlockState()); level.setBlockAndUpdate(DESTINATION, Blocks.CHEST.defaultBlockState());
        ((ChestBlockEntity)level.getBlockEntity(SOURCE)).setItem(0, new ItemStack(Items.DIAMOND, 6));
        UUID owner = UUID.randomUUID(), request = UUID.randomUUID();
        var entry = manager.spawn(owner, level, new Vec3(3000.5, 80, 3000.5), "RestartQA");
        manager.actor(entry.id).setNoGravity(true); UUID session = entry.authority.session();
        var commandEntry=manager.spawn(owner,level,new Vec3(3002.5,80,3002.5),"RestartCommand");manager.actor(commandEntry.id).setNoGravity(true);UUID commandId=UUID.randomUUID();
        var command=manager.jobs().submitProgram(owner,commandEntry.id,commandEntry.authority.session(),commandId,
                JsonParser.parseString("{\"steps\":[{\"op\":\"execute_command\",\"command\":\"qa_tpa RestartQA\",\"await\":\"teleport\",\"timeoutTicks\":40,\"saveAs\":\"request\"}]}").getAsJsonObject(),"replace");
        manager.configureBehavior(owner,entry.id,JsonParser.parseString("{\"players\":\"passive\",\"mobs\":\"flee\",\"idle\":{\"waitTicks\":17,\"waypoints\":[[3000.5,80,3000.5]]}}").getAsJsonObject());
        UUID receipt = UUID.randomUUID();
        JsonObject chat = new JsonObject(); chat.addProperty("message", "[SIMULATED] persisted receipt");
        helper.assertTrue(manager.control().request(owner, entry.id, session, receipt, "chat", chat, 0).join().get("ok").getAsBoolean(),
                "Writer accepts a durable control receipt");
        var job = manager.jobs().submitProgram(owner, entry.id, session, request, program(), "replace");
        helper.onEachTick(() -> {
            helper.assertTrue(job.state() != JobState.FAILED, "Writer program failed: " + job.view());
            if (!job.result().has("pending")) return;
            var partial = job.result().getAsJsonObject("pending").getAsJsonObject("result");
            if (!partial.has("acquired") || partial.get("acquired").getAsInt() != 3) return;
            if(CommandFixtures.calls(commandEntry.id)!=1)return;
            manager.disconnectOwner(owner);
            helper.assertTrue(count(helper, SOURCE) == 3 && count(helper, DESTINATION) == 0, "Pending transfer captured between collection and deposit");
            JsonObject expected = new JsonObject(); expected.addProperty("owner", owner.toString()); expected.addProperty("avatar", entry.id.toString());
            expected.addProperty("request", request.toString()); expected.addProperty("oldSession", session.toString()); expected.addProperty("writePid", ProcessHandle.current().pid());
            expected.addProperty("worldId", manager.worldId().toString()); expected.addProperty("receipt", receipt.toString());
            expected.addProperty("commandAvatar",commandEntry.id.toString());expected.addProperty("commandRequest",commandId.toString());
            try { Files.writeString(MARKER, expected.toString()); }
            catch (java.io.IOException failure) { throw new AssertionError(failure); }
            Paracraft.LOG.info("QA_EVT {\"event\":\"cold_restart_write\",\"status\":\"pass\",\"context\":{\"pid\":{}}}", ProcessHandle.current().pid());
            helper.succeed();
        });
    }
    private static void read(GameTestHelper helper) throws Exception {
        var expected = JsonParser.parseString(Files.readString(MARKER)).getAsJsonObject();
        helper.assertTrue(ProcessHandle.current().pid() != expected.get("writePid").getAsLong(), "Reader is a different JVM process");
        UUID owner = UUID.fromString(expected.get("owner").getAsString()), id = UUID.fromString(expected.get("avatar").getAsString());
        UUID request = UUID.fromString(expected.get("request").getAsString());
        var manager = ServerEvents.avatars(helper.getLevel().getServer());
        var entry = manager.entry(id);
        helper.assertTrue(manager.worldId().toString().equals(expected.get("worldId").getAsString()), "World binding survives a cold process restart");
        helper.assertTrue(entry != null && entry.authority.session() == null, "Loaded saved avatar without runtime lease");
        var behavior=entry.control.getAsJsonObject("survival").getAsJsonObject("behavior");
        helper.assertTrue(behavior.get("players").getAsString().equals("passive") && behavior.get("mobs").getAsString().equals("flee")
                && behavior.getAsJsonObject("idle").get("waitTicks").getAsInt()==17 && behavior.getAsJsonObject("idle").getAsJsonArray("waypoints").size()==1,
                "Nondefault reaction categories and idle settings loaded from disk before body reconstruction");
        helper.assertTrue(manager.restoreOwner(owner).isEmpty(), "Loaded actor and execution from world storage");
        helper.assertTrue(manager.actor(id).getInventory().countItem(Items.DIAMOND) == 3, "Actual disk inventory contains collected items");
        helper.assertTrue(count(helper, SOURCE) == 3 && count(helper, DESTINATION) == 0, "Actual persisted containers match the checkpoint");
        helper.assertTrue(!entry.authority.controls(owner, UUID.fromString(expected.get("oldSession").getAsString())), "Previous process's lease is stale");
        JsonObject chat = new JsonObject(); chat.addProperty("message", "[SIMULATED] persisted receipt");
        int eventsBefore = entry.control.getAsJsonArray("events").size();
        helper.assertTrue(manager.control().request(owner, id, entry.authority.session(), UUID.fromString(expected.get("receipt").getAsString()), "chat", chat, 0)
                .join().get("ok").getAsBoolean() && entry.control.getAsJsonArray("events").size() == eventsBefore, "Cold receipt recovery acknowledges without repeating chat");
        var restored = manager.jobs().get(id);
        helper.assertTrue(restored != null && restored.id.equals(request) && restored.state() == JobState.PAUSED, "Pending program is restored without running automatically");
        helper.assertTrue(manager.jobs().submitProgram(owner, id, entry.authority.session(), request, program(), "replace") == restored,
                "Persisted request ID deduplicates before resumption");
        manager.jobs().resumeRequest(owner, id, entry.authority.session(), request);
        UUID commandAvatar=UUID.fromString(expected.get("commandAvatar").getAsString()),commandId=UUID.fromString(expected.get("commandRequest").getAsString());
        var pendingCommand=manager.jobs().request(commandAvatar,commandId);
        helper.assertTrue(pendingCommand!=null && pendingCommand.state()==JobState.PAUSED && pendingCommand.result().getAsJsonObject("pending").getAsJsonObject("result").get("issued").getAsBoolean(),"Issued command checkpoint survived actual process restart");
        manager.jobs().resumeRequest(owner,commandAvatar,manager.entry(commandAvatar).authority.session(),commandId);
        helper.onEachTick(() -> {
            helper.assertTrue(restored.state() != JobState.FAILED, "Reader program failed: " + restored.view());
            if (restored.state() != JobState.SUCCEEDED) return;
            helper.assertTrue(count(helper, SOURCE) == 3 && count(helper, DESTINATION) == 3
                    && manager.actor(id).getInventory().countItem(Items.DIAMOND) == 0, "Fresh process resumed without replay, loss, or duplication");
            helper.assertTrue(restored.result().getAsJsonObject("results").getAsJsonObject("loot").get("acquired").getAsInt() == 3,
                    "Named result survived pending checkpoint recovery");
            helper.assertTrue(CommandFixtures.calls(commandAvatar)==0,"Reader never reissues the already accepted command after restart");
            if(pendingCommand.state()!=JobState.FAILED)return;
            helper.assertTrue(pendingCommand.view().get("error").getAsString().contains("not observed"),"A request absent from the new command fixture expires honestly instead of pretending to have teleported");
            Paracraft.LOG.info("QA_EVT {\"event\":\"cold_restart_read\",\"status\":\"pass\",\"context\":{\"pid\":{},\"previousPid\":{}}}",
                    ProcessHandle.current().pid(), expected.get("writePid").getAsLong());
            helper.succeed();
        });
    }
    private static int count(GameTestHelper helper, BlockPos position) {
        helper.getLevel().getChunkAt(position);
        var chest = (ChestBlockEntity)helper.getLevel().getBlockEntity(position);
        helper.assertTrue(chest != null, "Persisted chest exists at " + position); return chest.getItem(0).getCount();
    }
}
