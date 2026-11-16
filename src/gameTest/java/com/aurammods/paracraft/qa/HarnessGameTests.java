package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.core.agent.AgentBinding;
import com.aurammods.paracraft.core.agent.AgentClock;
import com.aurammods.paracraft.core.agent.AgentState;
import com.aurammods.paracraft.core.agent.HarnessLoop;
import com.aurammods.paracraft.core.agent.SimulatedProvider;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.AvatarEntry;
import com.aurammods.paracraft.forge.avatar.AvatarSavedData;
import com.aurammods.paracraft.forge.execution.GameJob;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class HarnessGameTests {
    @GameTest(template = "arena", timeoutTicks = 1000)
    public static void simulatedClientLoopCollectsRealDiamondsReportsProgressAndReturnsToIdle(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); var control = manager.control();
        // A real server-side recipient body stands in for a connected owner; no client/TCP session is claimed.
        UUID recipientOwner = UUID.randomUUID();
        var recipient = manager.spawn(recipientOwner, helper.getLevel(), helper.absoluteVec(new Vec3(66.5, 2, 48.5)), name());
        UUID owner = recipient.id;
        var entry = manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(48.5, 2, 48.5)), name());
        var actor = manager.actor(entry.id); actor.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        chest(helper, new BlockPos(38, 2, 48), 5); chest(helper, new BlockPos(60, 2, 50), 7);
        var clock = new VirtualClock(); var memory = new AgentState();
        var loop = new HarnessLoop(new AgentBinding(manager.worldId(), owner, entry.id), memory, new SimulatedProvider(), clock,
                (id, tool, args, trigger) -> control.request(owner, entry.id, entry.authority.session(), id, tool, args, trigger), () -> {});
        helper.assertTrue(control.chat(UUID.randomUUID(), entry.name + ", collect diamonds") == 0, "Unauthorized chat cannot wake the harness");
        helper.assertTrue(control.chat(owner, entry.name + ", collect all the diamonds nearby and give them to me.") == 1, "Owner mention creates one instruction");
        boolean[] asked = {false}, gotProgress = {false}; UUID[] program = {null}; long[] idleCalls = {-1}; int[] idleTicks = {0};
        Vec3[] previous = {actor.position()}; double[] distance = {0};
        helper.onEachTick(() -> {
            clock.advance(250); loop.accept(control.snapshot(owner, entry.id)); loop.tick(); loop.tick();
            helper.assertTrue(loop.lastError().isEmpty(), "Harness failed: " + loop.lastError());
            double step = actor.position().distanceTo(previous[0]); distance[0] += step; previous[0] = actor.position();
            helper.assertTrue(step < 1, "Physical job movement never teleports between inventories");
            GameJob current = manager.jobs().get(entry.id);
            if (current != null) {
                if (program[0] == null) program[0] = current.id;
                helper.assertTrue(current.id.equals(program[0]) && current.state() != JobState.FAILED, "Progress does not replace the request: " + current.view());
                if (!asked[0] && current.result().has("pending")) {
                    JsonObject partial = current.result().getAsJsonObject("pending").getAsJsonObject("result");
                    if (partial.has("acquired") && partial.get("acquired").getAsInt() > 0) {
                        asked[0] = true; control.chat(owner, entry.name + ", progress?");
                    }
                }
            }
            for (var event : entry.control.getAsJsonArray("events")) {
                JsonObject value = event.getAsJsonObject();
                if (value.get("type").getAsString().equals("response") && value.get("message").getAsString().contains("Actual progress:")) gotProgress[0] = true;
            }
            if (memory.tasks().isEmpty() || !memory.tasks().get(0).getAsJsonObject().get("status").getAsString().equals("COMPLETED") || !loop.status().equals("Idle")) return;
            if (idleCalls[0] < 0) idleCalls[0] = loop.calls();
            helper.assertTrue(loop.calls() == idleCalls[0], "Idle snapshots do not invoke inference");
            if (++idleTicks[0] < 100) return;
            helper.assertTrue(asked[0] && gotProgress[0], "A real in-flight progress question received a response");
            helper.assertTrue(loop.planningCalls() >= 2 && loop.simulated(), "Both routes run through the labeled simulator");
            helper.assertTrue(manager.jobs().requests(entry.id).size() == 1, "The diamond program is submitted once");
            helper.assertTrue(distance[0] > 20, "The actor physically walks between real inventories");
            helper.assertTrue(actor.getInventory().countItem(Items.DIAMOND) == 3, "Pre-existing belongings remain carried");
            var receiver = manager.actor(recipient.id);
            int delivered = receiver.getInventory().countItem(Items.DIAMOND) + helper.getLevel()
                    .getEntitiesOfClass(ItemEntity.class, receiver.getBoundingBox().inflate(3)).stream()
                    .filter(item -> item.getItem().is(Items.DIAMOND) && assigned(item, recipient.id)).mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(delivered == 12, "Exactly the twelve collected diamonds reached the recipient");
            helper.assertTrue(chestCount(helper, new BlockPos(38, 2, 48)) == 0 && chestCount(helper, new BlockPos(60, 2, 50)) == 0, "Real source containers were emptied");
            loop.close(); actor.getInventory().clearContent(); receiver.getInventory().clearContent();
            manager.destroy(owner, entry.id); manager.destroy(recipientOwner, recipient.id);
            Paracraft.LOG.info("QA_EVT {\"event\":\"simulated_harness_real_diamond_delivery\",\"status\":\"pass\",\"context\":{\"clock\":\"virtual\",\"transport\":\"in-process\",\"liveAI\":false}}"); helper.succeed();
        });
    }
    @GameTest(template = "empty")
    public static void authoritativeInstructionsDelegationReceiptsAndWorldBindingPersist(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); var service = manager.control(); UUID owner = UUID.randomUUID(), trusted = UUID.randomUUID();
        var entry = manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(2.5, 2, 2.5)), name()); manager.actor(entry.id).setNoGravity(true);
        var actor = manager.actor(entry.id); ItemStack helmet = new ItemStack(Items.IRON_HELMET); helmet.setDamageValue(7);
        actor.setItemSlot(net.minecraft.world.entity.EquipmentSlot.HEAD, helmet);
        JsonObject physical = service.snapshot(owner, entry.id);
        helper.assertTrue(physical.get("health").getAsFloat() == actor.getHealth()
                && physical.getAsJsonObject("equipment").getAsJsonObject("head").get("damage").getAsInt() == helmet.getDamageValue(),
                "Snapshot reports native health and equipped durability");
        helper.assertTrue(service.chat(owner, entry.name + "suffix") == 0, "Name matching respects word boundaries");
        helper.assertTrue(service.chat(trusted, entry.name + " hello") == 0, "Unknown sender cannot instruct");
        service.chat(owner, entry.name + " trust " + trusted); long ownerEvent = lastEvent(entry);
        var trust = service.request(owner, entry.id, entry.authority.session(), UUID.randomUUID(), "trust", json("{\"playerUuid\":\"" + trusted + "\"}"), ownerEvent).join();
        helper.assertTrue(trust.get("ok").getAsBoolean() && service.chat(trusted, entry.name.toLowerCase(java.util.Locale.ROOT) + " hello") == 1, "Owner delegation permits case-insensitive instructions");
        long delegatedEvent = lastEvent(entry);
        var noAdmin = service.request(owner, entry.id, entry.authority.session(), UUID.randomUUID(), "trust", json("{\"playerUuid\":\"" + UUID.randomUUID() + "\"}"), delegatedEvent).join();
        helper.assertTrue(!noAdmin.get("ok").getAsBoolean(), "A trusted instruction cannot administer permissions");
        manager.trust(owner, entry.id, trusted, false);
        var late = service.request(owner, entry.id, entry.authority.session(), UUID.randomUUID(), "program", json("{\"id\":\"" + UUID.randomUUID() + "\",\"steps\":[]}"), delegatedEvent).join();
        helper.assertTrue(!late.get("ok").getAsBoolean() && manager.jobs().get(entry.id) == null, "A revoked instruction's late result starts no operation");
        UUID id = UUID.randomUUID(); JsonObject message = json("{\"message\":\"[SIMULATED] once\"}");
        var sent = service.request(owner, entry.id, entry.authority.session(), id, "chat", message, ownerEvent).join(); int events = entry.control.getAsJsonArray("events").size();
        AvatarEntry decoded = AvatarEntry.load(entry.save()); entry.control = decoded.control;
        helper.assertTrue(service.request(owner, entry.id, entry.authority.session(), id, "chat", message, ownerEvent).join().equals(sent)
                && entry.control.getAsJsonArray("events").size() == events, "Stored acknowledgment prevents a duplicate chat effect");
        helper.assertTrue(!service.request(owner, entry.id, entry.authority.session(), id, "chat", json("{\"message\":\"different\"}"), ownerEvent).join().get("ok").getAsBoolean(), "Request ID content conflicts are reported");
        UUID old = entry.authority.session(); manager.pause(owner, entry.id, true); manager.pause(owner, entry.id, false);
        helper.assertTrue(!service.request(owner, entry.id, old, id, "chat", message, ownerEvent).join().get("ok").getAsBoolean(), "A receipt never bypasses a stale control lease");
        helper.assertTrue(service.request(owner, entry.id, entry.authority.session(), id, "chat", message, ownerEvent).join().equals(sent), "Same owner can recover its acknowledgment under a new lease");
        var wrongController = service.request(trusted, entry.id, entry.authority.session(), UUID.randomUUID(), "chat", message, ownerEvent).join();
        helper.assertTrue(!wrongController.get("ok").getAsBoolean(), "Instruction rights do not transfer client control");
        AvatarSavedData data = new AvatarSavedData(); data.put(entry); AvatarSavedData loaded = AvatarSavedData.load(data.save(new CompoundTag()));
        helper.assertTrue(data.worldId().equals(loaded.worldId()) && loaded.get(entry.id).control.equals(entry.control), "World binding, events and receipts survive actual NBT serialization");
        actor.getInventory().clearContent(); manager.destroy(owner, entry.id); Paracraft.LOG.info("QA_EVT {\"event\":\"server_control_authority_and_receipts\",\"status\":\"pass\"}"); helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 150)
    public static void observationReceiptsSurviveReconnectAndUseActualChangedInventory(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); var service = manager.control(); UUID owner = UUID.randomUUID();
        var entry = manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(2.5, 2, 2.5)), name()); manager.actor(entry.id).setNoGravity(true);
        BlockPos source = new BlockPos(3, 2, 2); helper.setBlock(source, Blocks.CHEST);
        var chest = (ChestBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(source)); chest.setItem(0, new ItemStack(Items.EMERALD, 5));
        UUID observationId = UUID.randomUUID(); JsonObject query = json("{\"kind\":\"inventory\",\"item\":\"minecraft:emerald\"}");
        var interrupted = service.request(owner, entry.id, entry.authority.session(), observationId, "observe", query, 0);
        manager.disconnectOwner(owner);
        helper.assertTrue(interrupted.isDone() && !interrupted.join().get("ok").getAsBoolean(), "Disconnect interrupts a pending observation");
        helper.assertTrue(manager.restoreOwner(owner).isEmpty(), "Body restores before retry");
        var observation = service.request(owner, entry.id, entry.authority.session(), observationId, "observe", query, 0);
        GameJob[] program = {null};
        helper.onEachTick(() -> {
            if (!observation.isDone()) return;
            helper.assertTrue(observation.join().get("ok").getAsBoolean(), "Actual observation completed");
            if (program[0] == null) {
                JsonObject observed = observation.join().getAsJsonObject("value").getAsJsonObject("observation");
                helper.assertTrue(observed.get("total").getAsInt() == 5, "Observed count comes from the actual chest");
                // Rebind the body and decode control NBT before recovering the immutable search receipt.
                AvatarEntry decoded = AvatarEntry.load(entry.save()); entry.control = decoded.control;
                manager.disconnectOwner(owner); helper.assertTrue(manager.restoreOwner(owner).isEmpty(), "Restored body keeps server receipts");
                helper.assertTrue(service.request(owner, entry.id, entry.authority.session(), observationId, "observe", query, 0).join().equals(observation.join()),
                        "Completed observation retries acknowledge the original receipt");
                chest.setItem(0, new ItemStack(Items.EMERALD, 3));
                UUID programId = UUID.randomUUID(); JsonObject request = json("""
                    {"id":"%s","observationRefs":{"known":"%s"},
                     "initialResults":{"known":{"total":500000}},
                     "steps":[{"try":[{"op":"collect_items","item":"minecraft:emerald","quantity":"all","containersFrom":"known","saveAs":"partial"}],
                               "catch":[{"op":"report_failure","saveAs":"caught"}]}]}
                    """.formatted(programId, observationId));
                JsonObject forged = request.deepCopy(); forged.getAsJsonObject("observationRefs").addProperty("known", UUID.randomUUID().toString());
                helper.assertTrue(!service.request(owner, entry.id, entry.authority.session(), UUID.randomUUID(), "program", forged, 0).join().get("ok").getAsBoolean()
                        && manager.jobs().get(entry.id) == null, "Invented observation receipts cannot start a program");
                var reply = service.request(owner, entry.id, entry.authority.session(), UUID.randomUUID(), "program", request, 0).join();
                helper.assertTrue(reply.get("ok").getAsBoolean(), "Actual receipt binds to the program");
                program[0] = manager.jobs().get(entry.id);
            }
            helper.assertTrue(program[0].state() != JobState.FAILED, "Recovery program remains valid: " + program[0].view());
            if (program[0].state() != JobState.SUCCEEDED) return;
            JsonObject partial = program[0].result().getAsJsonObject("results").getAsJsonObject("partial");
            helper.assertTrue(partial.get("requested").getAsInt() == 5 && partial.get("acquired").getAsInt() == 3, "Server observation and actual changed contents govern the partial result");
            helper.assertTrue(manager.actor(entry.id).getInventory().countItem(Items.EMERALD) == 3 && chest.isEmpty(), "Actual transfer conserves the three remaining items");
            manager.actor(entry.id).getInventory().clearContent(); manager.destroy(owner, entry.id);
            Paracraft.LOG.info("QA_EVT {\"event\":\"server_observation_receipt_reconnect_changed_inventory\",\"status\":\"pass\"}"); helper.succeed();
        });
    }
    private static final class VirtualClock implements AgentClock {
        private long nanos, millis = 1_800_000_000_000L;
        public long nanoTime() { return nanos; }
        public long epochMillis() { return millis; }
        void advance(long amount) { nanos += amount * 1_000_000; millis += amount; }
    }
    private static long lastEvent(AvatarEntry entry) { var events = entry.control.getAsJsonArray("events"); return events.get(events.size() - 1).getAsJsonObject().get("id").getAsLong(); }
    private static String name() { return "QA" + UUID.randomUUID().toString().substring(0, 8); }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    private static boolean assigned(ItemEntity item, UUID recipient) { CompoundTag tag = item.saveWithoutId(new CompoundTag()); return tag.hasUUID("Owner") && recipient.equals(tag.getUUID("Owner")); }
    private static void chest(GameTestHelper helper, BlockPos position, int count) { helper.setBlock(position, Blocks.CHEST); ((ChestBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(position))).setItem(0, new ItemStack(Items.DIAMOND, count)); }
    private static int chestCount(GameTestHelper helper, BlockPos position) { return ((ChestBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(position))).getItem(0).getCount(); }
}
