package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.core.agent.AgentClock;
import com.aurammods.paracraft.core.agent.AgentStore;
import com.aurammods.paracraft.core.agent.ClientCoordinator;
import com.aurammods.paracraft.core.protocol.WireCodec;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.execution.GameJob;
import com.aurammods.paracraft.forge.network.ForgeNetwork;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class ClientTransportGameTests {
    @GameTest(template = "arena", timeoutTicks = 1400)
    public static void coordinatorUsesNativePayloadsAndResumesInactiveRealGathering(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID recipientOwner = UUID.randomUUID();
        var recipient = manager.spawn(recipientOwner, helper.getLevel(), helper.absoluteVec(new Vec3(66.5, 2, 48.5)), name());
        UUID owner = recipient.id; var nativeOwner = manager.actor(owner);
        var entry = manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(48.5, 2, 48.5)), name());
        var actor = manager.actor(entry.id); var carried = new ItemStack(Items.DIAMOND, 3); String[] data = new String[3];
        for (int i = 0; i < 3; i++) {
            var text = new StringBuilder(); for (int j = 0; j < 800; j++) text.append(UUID.randomUUID()); data[i] = text.toString();
            carried.getOrCreateTag().putString("qa_large_metadata_" + i, data[i]);
        }
        actor.getInventory().setItem(0, carried);
        chest(helper, new BlockPos(38, 2, 48), 5); chest(helper, new BlockPos(60, 2, 50), 7);
        Path memory = Path.of("client-qa", UUID.randomUUID().toString()); var clock = new Clock();
        var incoming = new WireCodec.Assembler(); ClientCoordinator[] client = {null}; int[] largestMessage = {0};
        client[0] = new ClientCoordinator(owner, new AgentStore(memory), clock, message -> {
            // Payload owner fields are ignored; the endpoint receives its native sender separately.
            message.addProperty("ownerId", UUID.randomUUID().toString());
            for (var frame : WireCodec.encode(message)) manager.endpoint().receiveFrame(nativeOwner, nativeFrame(frame, true), reply -> {
                var frames = WireCodec.encode(reply); largestMessage[0] = Math.max(largestMessage[0], frames.size());
                for (var part : frames) incoming.accept(nativeFrame(part, false), clock.nanoTime()).ifPresent(client[0]::receive);
            });
        });
        manager.control().chat(owner, entry.name + ", collect all the diamonds nearby and give them to me.");
        GameJob[] gathering = {null}; int[] stage = {0}, idleTicks = {0}; boolean[] inactive = {false}, resumed = {false}, progress = {false};
        long[] idleCalls = {-1}; Vec3[] previous = {actor.position()}; double[] distance = {0};
        boolean[] sawTaskStatus={false};
        helper.onEachTick(() -> {
            clock.advance(250); client[0].tick(); client[0].tick();
            helper.assertTrue(!client[0].status().startsWith("Paused:"), "Coordinator transport failed: " + client[0].status());
            var views = client[0].views(); if (views.isEmpty()) return; var view = views.get(0);
            String publicStatus=manager.statuses().label(entry.id);if(publicStatus.contains("task"))sawTaskStatus[0]=true;
            helper.assertTrue(!view.status().startsWith("Paused:"), "Avatar loop failed: " + view.status());
            double step = actor.position().distanceTo(previous[0]); distance[0] += step; previous[0] = actor.position();
            helper.assertTrue(step < 1, "Native program movement remains physical");
            if (gathering[0] == null && manager.jobs().get(entry.id) != null) gathering[0] = manager.jobs().get(entry.id);
            if (gathering[0] == null) return;
            helper.assertTrue(gathering[0].state() != JobState.FAILED, "Gathering failed: " + gathering[0].view());
            if (stage[0] == 0 && gathering[0].result().has("pending")) {
                JsonObject partial = gathering[0].result().getAsJsonObject("pending").getAsJsonObject("result");
                if (partial.has("acquired") && partial.get("acquired").getAsInt() > 0) {
                    manager.control().chat(owner, entry.name + ", progress?"); stage[0] = 1;
                }
            }
            for (var item : entry.control.getAsJsonArray("events")) {
                JsonObject event = item.getAsJsonObject();
                if (event.get("type").getAsString().equals("response") && event.get("message").getAsString().contains("Actual progress:")) progress[0] = true;
            }
            if (stage[0] == 1 && progress[0]) {
                Vec3 target = helper.absoluteVec(new Vec3(50.5, 2, 52.5));
                manager.control().chat(owner, String.format(Locale.ROOT, "%s, travel to %.2f %.2f %.2f", entry.name, target.x, target.y, target.z)); stage[0] = 2;
            }
            for (var item : view.memory().tasks()) {
                JsonObject task = item.getAsJsonObject();
                if (task.has("programId") && gathering[0].id.toString().equals(task.get("programId").getAsString())
                        && task.get("status").getAsString().equals("INACTIVE")) inactive[0] = true;
            }
            if (inactive[0] && manager.jobs().get(entry.id) == gathering[0] && gathering[0].state() == JobState.RUNNING) resumed[0] = true;
            boolean complete = view.memory().tasks().size() == 2 && view.memory().tasks().asList().stream()
                    .allMatch(task -> task.getAsJsonObject().get("status").getAsString().equals("COMPLETED"));
            if (!complete || !view.status().equals("Idle")) return;
            if (idleCalls[0] < 0) idleCalls[0] = view.decisions();
            helper.assertTrue(view.decisions() == idleCalls[0], "Snapshot heartbeats do not wake idle inference; turn=" + view.memory().save().get("turn")
                    + " inbox=" + view.memory().save().get("inbox") + " events=" + entry.control.get("events"));
            if (++idleTicks[0] < 100) return;
            helper.assertTrue(inactive[0] && resumed[0] && progress[0], "Gathering becomes inactive for another request, then resumes");
            helper.assertTrue(manager.jobs().requests(entry.id).size() == 2 && gathering[0].state() == JobState.SUCCEEDED, "Two programs complete without replaying gathering");
            helper.assertTrue(largestMessage[0] > 1, "Real item metadata traversed fragmented native payloads");
            helper.assertTrue(distance[0] > 20 && actor.getInventory().countItem(Items.DIAMOND) == 3, "Physical travel preserves pre-existing items");
            helper.assertTrue(actor.getInventory().getItem(0).hasTag() && actor.getInventory().getItem(0).getTag().getString("qa_large_metadata_2").equals(data[2]), "Large stack metadata survives");
            int delivered = nativeOwner.getInventory().countItem(Items.DIAMOND) + helper.getLevel().getEntitiesOfClass(ItemEntity.class, nativeOwner.getBoundingBox().inflate(3)).stream()
                    .filter(item -> item.getItem().is(Items.DIAMOND) && assigned(item, owner)).mapToInt(item -> item.getItem().getCount()).sum();
            helper.assertTrue(delivered == 12, "All twelve gathered diamonds reach the recipient after resumption");
            helper.assertTrue(sawTaskStatus[0] && manager.statuses().label(entry.id).equals("Idle"),"Public status reflected real tasks and returned to idle");
            client[0].close(); actor.getInventory().clearContent(); nativeOwner.getInventory().clearContent();
            manager.destroy(owner, entry.id); manager.destroy(recipientOwner, recipient.id); manager.endpoint().disconnect(owner);
            try (var paths = Files.walk(memory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); }
            catch (java.io.IOException failure) { throw new AssertionError(failure); }
            Paracraft.LOG.info("QA_EVT {\"event\":\"client_coordinator_native_payload_interrupt_resume\",\"status\":\"pass\",\"context\":{\"clock\":\"virtual\",\"transport\":\"native-payload-codec/in-process-endpoint\",\"liveAI\":false}}"); helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 260)
    public static void controllerExpiryStopsJobsAndPayloadOwnerCannotStealControl(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID recipientOwner = UUID.randomUUID(), otherOwner = UUID.randomUUID();
        var recipient = manager.spawn(recipientOwner, helper.getLevel(), helper.absoluteVec(new Vec3(2.5, 2, 2.5)), name());
        var other = manager.spawn(otherOwner, helper.getLevel(), helper.absoluteVec(new Vec3(5.5, 2, 5.5)), name());
        UUID owner = recipient.id; var entry = manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(2.5, 2, 4.5)), name());
        manager.actor(owner).setNoGravity(true); manager.actor(other.id).setNoGravity(true); manager.actor(entry.id).setNoGravity(true);
        UUID client = UUID.randomUUID(), attacker = UUID.randomUUID();
        long[] firstRevision = {0};
        manager.endpoint().receive(manager.actor(owner), envelope(client, "poll"), reply -> firstRevision[0] = reply.get("revision").getAsLong());
        manager.endpoint().receive(manager.actor(other.id), envelope(attacker, "poll"), ignored -> {});
        UUID session = entry.authority.session(); JsonObject forged = envelope(attacker, "effect");
        forged.addProperty("ownerId", owner.toString()); forged.addProperty("avatar", entry.id.toString()); forged.addProperty("session", session.toString());
        forged.addProperty("tool", "chat"); forged.addProperty("trigger", 0); forged.add("arguments", JsonParser.parseString("{\"message\":\"hijack\"}"));
        manager.endpoint().receive(manager.actor(other.id), forged, response -> helper.assertTrue(!response.get("ok").getAsBoolean(), "Native sender, not payload identity, controls authority"));
        var job = manager.jobs().submitProgram(owner, entry.id, session, UUID.randomUUID(), JsonParser.parseString("{\"steps\":[{\"op\":\"wait_ticks\",\"ticks\":1000}]}").getAsJsonObject(), "replace");
        helper.runAfterDelay(210, () -> {
            helper.assertTrue(entry.paused && entry.authority.session() == null && job.state() == JobState.PAUSED, "Missing heartbeat stops the program and revokes the lease");
            UUID replacement = client;
            manager.endpoint().receive(manager.actor(owner), envelope(replacement, "poll"),
                    reply -> helper.assertTrue(reply.get("revision").getAsLong() > firstRevision[0], "Snapshot revisions remain monotonic after same-client lease reacquisition"));
            helper.assertTrue(entry.paused && job.state() == JobState.PAUSED, "Reacquisition does not silently unpause lost control");
            JsonObject resume = envelope(replacement, "control"); resume.addProperty("avatar", entry.id.toString()); resume.addProperty("action", "resume"); resume.add("arguments", new JsonObject());
            manager.endpoint().receive(manager.actor(owner), resume, response -> helper.assertTrue(response.get("ok").getAsBoolean(), "Owner can deliberately resume"));
            helper.assertTrue(job.state() == JobState.RUNNING && !entry.authority.controls(owner, session), "Explicit resume issues a new avatar lease");
            manager.destroy(owner, entry.id); manager.destroy(recipientOwner, recipient.id); manager.destroy(otherOwner, other.id);
            manager.endpoint().disconnect(owner); manager.endpoint().disconnect(other.id);
            Paracraft.LOG.info("QA_EVT {\"event\":\"native_sender_and_controller_expiry\",\"status\":\"pass\"}"); helper.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=40)
    public static void diagnosticsAndPublicActivityUseNativeOwnerAndActiveSession(GameTestHelper h) {
        var manager=ServerEvents.avatars(h.getLevel().getServer());UUID root=UUID.randomUUID();
        var ownerEntry=manager.spawn(root,h.getLevel(),h.absoluteVec(new Vec3(2.5,2,2.5)),name());UUID owner=ownerEntry.id;
        var entry=manager.spawn(owner,h.getLevel(),h.absoluteVec(new Vec3(3.5,2,3.5)),name());var sender=manager.actor(owner);UUID client=UUID.randomUUID();
        var poll=envelope(client,"poll");var activity=JsonParser.parseString("{\"phase\":\"THINKING\",\"tasks\":2}").getAsJsonObject();activity.addProperty("avatar",entry.id.toString());
        var activities=new com.google.gson.JsonArray();activities.add(activity);poll.add("activities",activities);
        manager.endpoint().receive(sender,poll,reply->h.assertTrue(reply.get("ok").getAsBoolean(),"Native owner reports aggregate activity"));
        h.assertTrue(manager.statuses().label(entry.id).equals("Thinking · 2 tasks"),"Public status uses only aggregate phase/count");
        var logs=new java.util.ArrayList<String>();
        var logger=(org.apache.logging.log4j.core.Logger)org.apache.logging.log4j.LogManager.getLogger(Paracraft.LOG.getName());
        var appender=new org.apache.logging.log4j.core.appender.AbstractAppender("qa-agent-output-"+entry.id,null,null,false,org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override public void append(org.apache.logging.log4j.core.LogEvent event){logs.add(event.getMessage().getFormattedMessage());}
        };appender.start();var logConfig=logger.getContext().getConfiguration().getRootLogger();logConfig.addAppender(appender,null,null);logger.getContext().updateLoggers();String prior=System.getProperty("paracraft.debugAgentOutput");
        try {
            var trace=envelope(client,"agent_output");trace.addProperty("avatar",entry.id.toString());trace.addProperty("session",entry.authority.session().toString());
            trace.addProperty("outputId",UUID.randomUUID().toString());trace.addProperty("part",1);trace.addProperty("parts",1);trace.addProperty("data","{\"visible\":\"native-output-test\"}");
            System.setProperty("paracraft.debugAgentOutput","true");
            manager.endpoint().receive(sender,trace,reply->{throw new AssertionError("Valid debug output should not require an acknowledgement: "+reply);});
            h.assertTrue(logs.size()==1 && logs.get(0).contains("native-output-test") && logs.get(0).contains(entry.name),"Server logs every received visible output with avatar identity");
            System.setProperty("paracraft.debugAgentOutput","false");manager.endpoint().receive(sender,trace,ignored->{});h.assertTrue(logs.size()==1,"Debug flag actually suppresses logging");
            System.setProperty("paracraft.debugAgentOutput","true");trace.addProperty("session",UUID.randomUUID().toString());
            manager.endpoint().receive(sender,trace,reply->h.assertTrue(!reply.get("ok").getAsBoolean(),"Stale avatar lease cannot relay diagnostics"));h.assertTrue(logs.size()==1,"Stale output is not logged");
            var forged=envelope(client,"poll");activity.addProperty("avatar",ownerEntry.id.toString());forged.add("activities",activities);
            manager.endpoint().receive(sender,forged,reply->h.assertTrue(!reply.get("ok").getAsBoolean(),"Payload cannot publish status for another owner's avatar"));
            manager.pause(owner,entry.id,true);h.assertTrue(manager.statuses().label(entry.id).equals("Paused"),"Real server pause overrides client phase");
            manager.endpoint().receive(sender,envelope(client,"release"),ignored->{});h.assertTrue(manager.endpoint().activity(entry.id).tasks()==0,"Release clears transient public task count");
            activity.addProperty("avatar",UUID.randomUUID().toString());var stalePoll=envelope(client,"poll");stalePoll.add("activities",activities);
            manager.endpoint().receive(sender,stalePoll,reply->h.assertTrue(reply.get("ok").getAsBoolean(),"Stale status for a removed avatar does not prevent fresh snapshots"));
        } finally {
            if(prior==null)System.clearProperty("paracraft.debugAgentOutput");else System.setProperty("paracraft.debugAgentOutput",prior);
            logConfig.removeAppender(appender.getName());logger.getContext().updateLoggers();appender.stop();manager.destroy(owner,entry.id);manager.destroy(root,owner);manager.endpoint().disconnect(owner);
        }
        Paracraft.LOG.info("QA_EVT {\"event\":\"native_agent_output_debug_flag_authority_public_activity\",\"status\":\"pass\"}");h.succeed();
    }
    private static WireCodec.Frame nativeFrame(WireCodec.Frame frame, boolean serverbound) {
        FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.buffer()), encoded = new FriendlyByteBuf(Unpooled.buffer()); FriendlyByteBuf decoded = null;
        try {
            data.writeVarInt(serverbound ? 0 : 1); ForgeNetwork.write(frame, data);
            var channel = new ResourceLocation(Paracraft.ID, "control");
            if (serverbound) { new ServerboundCustomPayloadPacket(channel, data).write(encoded); decoded = new ServerboundCustomPayloadPacket(encoded).getData(); }
            else { new ClientboundCustomPayloadPacket(channel, data).write(encoded); decoded = new ClientboundCustomPayloadPacket(encoded).getData(); }
            decoded.readVarInt(); return ForgeNetwork.read(decoded);
        } finally { if (decoded != null) decoded.release(); encoded.release(); data.release(); }
    }
    private static JsonObject envelope(UUID client, String kind) {
        JsonObject value = new JsonObject(); value.addProperty("protocol", 1); value.addProperty("client", client.toString());
        value.addProperty("kind", kind); value.addProperty("requestId", UUID.randomUUID().toString()); value.addProperty("attempt", UUID.randomUUID().toString()); value.addProperty("sequence", 1); return value;
    }
    private static final class Clock implements AgentClock {
        long nano, epoch = 1_800_000_000_000L;
        public long nanoTime() { return nano; } public long epochMillis() { return epoch; }
        void advance(long millis) { nano += millis * 1_000_000; epoch += millis; }
    }
    private static String name() { return "QA" + UUID.randomUUID().toString().substring(0, 8); }
    private static void chest(GameTestHelper helper, BlockPos position, int count) { helper.setBlock(position, Blocks.CHEST); ((ChestBlockEntity)helper.getLevel().getBlockEntity(helper.absolutePos(position))).setItem(0, new ItemStack(Items.DIAMOND, count)); }
    private static boolean assigned(ItemEntity entity, UUID recipient) { CompoundTag tag = entity.saveWithoutId(new CompoundTag()); return tag.hasUUID("Owner") && tag.getUUID("Owner").equals(recipient); }
}
