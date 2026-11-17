package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.forge.ServerEvents;
import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Actual asynchronous Forge chat decoration/event dispatch; no client window or LLM. */
@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class ChatGameTests {
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void asyncForgeChatPreservesMessagesAndRoutesOwnerMentions(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer(); var manager = ServerEvents.avatars(server);
        UUID ownerId = UUID.randomUUID();
        var owner = new ServerPlayer(server, helper.getLevel(), new GameProfile(ownerId, "ChatOwner"));
        var stranger = new ServerPlayer(server, helper.getLevel(), new GameProfile(UUID.randomUUID(), "ChatStranger"));
        var entry = manager.spawn(ownerId, helper.getLevel(), helper.absoluteVec(new Vec3(2.5, 2, 2.5)), "Chat" + UUID.randomUUID().toString().substring(0, 8));
        manager.actor(entry.id).setNoGravity(true);
        owner.setPos(manager.actor(entry.id).position().add(40, 0, 0));
        var probe = new Probe(server, ownerId, ""); MinecraftForge.EVENT_BUS.register(probe);
        String mention = entry.name + ", hello from real Forge chat";
        try {
            helper.assertTrue(decorate(owner, "ordinary chat").getString().equals("ordinary chat"), "Ordinary chat decoration must complete unchanged");
            helper.assertTrue(decorate(stranger, mention).getString().equals(mention), "Unauthorized chat still follows normal Minecraft delivery");
            helper.assertTrue(decorate(owner, mention).getString().equals(mention), "Owner mention must not break Minecraft's chat future");
            helper.assertTrue(probe.offThread.get(), "Forge actually dispatched this event outside the server thread");
        } finally { MinecraftForge.EVENT_BUS.unregister(probe); }
        helper.runAfterDelay(2, () -> {
            var chats = manager.control().snapshot(ownerId, entry.id).getAsJsonArray("events").asList().stream()
                    .map(value -> value.getAsJsonObject()).filter(event -> event.get("type").getAsString().equals("chat")).toList();
            helper.assertTrue(chats.size() == 1 && chats.get(0).get("senderUuid").getAsString().equals(ownerId.toString())
                    && chats.get(0).get("message").getAsString().equals(mention), "Only the authorized mention reaches the server-thread wake journal, once");
            manager.destroy(ownerId, entry.id);
            Paracraft.LOG.info("QA_EVT {\"event\":\"async_forge_chat_preserves_delivery_and_owner_wake\",\"status\":\"pass\"}"); helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void unnamedAsyncChatReachesTheSingleNearbyOwnedAvatar(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer(); var manager = ServerEvents.avatars(server); UUID ownerId = UUID.randomUUID();
        var owner = new ServerPlayer(server, helper.getLevel(), new GameProfile(ownerId, "NearbyChatOwner"));
        var entry = manager.spawn(ownerId, helper.getLevel(), helper.absoluteVec(new Vec3(2.5, 2, 2.5)), "Chat" + UUID.randomUUID().toString().substring(0, 8));
        var actor = manager.actor(entry.id); actor.setNoGravity(true); owner.setPos(actor.position().add(2, 0, 0));
        String text = "Can you follow me?";
        helper.assertTrue(decorate(owner, text).getString().equals(text), "Unnamed nearby chat keeps its original Minecraft message");
        helper.runAfterDelay(2, () -> {
            var chats = manager.control().snapshot(ownerId, entry.id).getAsJsonArray("events").asList().stream()
                    .map(value -> value.getAsJsonObject()).filter(event -> event.get("type").getAsString().equals("chat")).toList();
            helper.assertTrue(chats.size() == 1 && chats.get(0).get("senderUuid").getAsString().equals(ownerId.toString())
                    && chats.get(0).get("message").getAsString().equals(text), "The actual asynchronous event routes the unchanged request to one nearby avatar, once");
            manager.destroy(ownerId, entry.id);
            Paracraft.LOG.info("QA_EVT {\"event\":\"async_unnamed_chat_single_nearby_avatar\",\"status\":\"pass\"}"); helper.succeed();
        });
    }
    @GameTest(template = "empty")
    public static void nearbyChatUsesPermissionsDistanceDimensionAndExplicitNames(GameTestHelper helper) {
        var server = helper.getLevel().getServer(); var manager = ServerEvents.avatars(server); var control = manager.control();
        UUID ownerId = UUID.randomUUID(), otherOwner = UUID.randomUUID(), trustedId = UUID.randomUUID();
        var owner = new ServerPlayer(server, helper.getLevel(), new GameProfile(ownerId, "ChatRoutingOwner"));
        var trusted = new ServerPlayer(server, helper.getLevel(), new GameProfile(trustedId, "ChatRoutingTrust"));
        Vec3 center = helper.absoluteVec(new Vec3(2.5, 2, 2.5)); owner.setPos(center); trusted.setPos(center);
        var first = manager.spawn(ownerId, helper.getLevel(), center.add(2, 0, 0), "Chat" + UUID.randomUUID().toString().substring(0, 8));
        var second = manager.spawn(ownerId, helper.getLevel(), center.add(30, 0, 0), "Chat" + UUID.randomUUID().toString().substring(0, 8));
        var foreign = manager.spawn(otherOwner, helper.getLevel(), center.add(3, 0, 0), "Chat" + UUID.randomUUID().toString().substring(0, 8));
        var elsewhere = manager.spawn(ownerId, server.getLevel(net.minecraft.world.level.Level.NETHER), center.add(2, 0, 0), "Chat" + UUID.randomUUID().toString().substring(0, 8));
        for (var entry : java.util.List.of(first, second, foreign, elsewhere)) manager.actor(entry.id).setNoGravity(true);
        helper.assertTrue(control.chat(owner, "Please follow me") == 1, "Only the nearby authorized avatar counts; distant, foreign and other-dimension bodies do not");
        helper.assertTrue(control.chat(owner, foreign.name + ", hello") == 0, "An explicit unauthorized name must not redirect to the nearby owned avatar");
        helper.assertTrue(control.chat(trusted, "Please follow me") == 0, "Untrusted nearby players cannot give unnamed instructions");
        manager.trust(ownerId, first.id, trustedId, true);
        helper.assertTrue(control.chat(trusted, "Please follow me") == 1, "Delegated instruction permission supports nearby chat");
        manager.trust(ownerId, first.id, trustedId, false);
        helper.assertTrue(control.chat(trusted, "Please follow me") == 0, "Revocation immediately removes nearby instruction rights");
        var actor = manager.actor(first.id); actor.setPos(center.add(24, 0, 0));
        helper.assertTrue(control.chat(owner, "At the edge") == 1, "The 24-block boundary is inclusive");
        actor.setPos(center.add(24.01, 0, 0));
        helper.assertTrue(control.chat(owner, "Too far away") == 0, "No nearby authorized avatar means no unnamed instruction");
        actor.setPos(center.add(2, 0, 0)); manager.actor(second.id).setPos(center.add(4, 0, 0));
        helper.assertTrue(control.chat(owner, "Ambiguous request") == 0, "Multiple nearby authorized avatars require an explicit name");
        helper.assertTrue(control.chat(owner, first.name.toLowerCase(java.util.Locale.ROOT) + ", hello") == 1, "Explicit names take priority over proximity without duplicating the event");
        helper.assertTrue(control.chat(owner, first.name + " and " + second.name + ", hello") == 2, "Explicit multi-avatar addressing remains supported");
        helper.assertTrue(control.chat(owner, elsewhere.name + ", hello") == 1, "Explicit remote/dimensional addressing remains supported");
        for (var entry : java.util.List.of(first, second, elsewhere)) manager.destroy(ownerId, entry.id);
        manager.destroy(otherOwner, foreign.id);
        Paracraft.LOG.info("QA_EVT {\"event\":\"nearby_chat_authority_ambiguity_and_name_priority\",\"status\":\"pass\"}"); helper.succeed();
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void cancelledAndCompanionChatDoNotWakeAgents(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer(); var manager = ServerEvents.avatars(server); UUID ownerId = UUID.randomUUID();
        var owner = new ServerPlayer(server, helper.getLevel(), new GameProfile(ownerId, "ChatCancelOwner"));
        var entry = manager.spawn(ownerId, helper.getLevel(), helper.absoluteVec(new Vec3(2.5, 2, 2.5)), "Chat" + UUID.randomUUID().toString().substring(0, 8));
        var actor = manager.actor(entry.id); actor.setNoGravity(true); manager.trust(ownerId, entry.id, entry.id, true);
        String cancelled = entry.name + ", cancelled owner instruction";
        var probe = new Probe(server, ownerId, cancelled); MinecraftForge.EVENT_BUS.register(probe);
        try {
            helper.assertTrue(decorate(owner, cancelled) == null, "Other mods' chat cancellation remains effective");
            String ownMessage = entry.name + ", companion response";
            helper.assertTrue(decorate(actor, ownMessage).getString().equals(ownMessage), "Companion chat is still decoratable without a feedback wake");
        } finally { MinecraftForge.EVENT_BUS.unregister(probe); }
        helper.runAfterDelay(2, () -> {
            boolean wake = manager.control().snapshot(ownerId, entry.id).getAsJsonArray("events").asList().stream()
                    .anyMatch(value -> value.getAsJsonObject().get("type").getAsString().equals("chat"));
            helper.assertTrue(!wake, "Cancelled and companion-originated chat must not enter the instruction journal");
            manager.destroy(ownerId, entry.id);
            Paracraft.LOG.info("QA_EVT {\"event\":\"async_forge_chat_cancellation_and_companion_filter\",\"status\":\"pass\"}"); helper.succeed();
        });
    }
    private static Component decorate(ServerPlayer player, String text) throws Exception {
        // The future must finish while the server thread is here: the event handler
        // must enqueue world work without waiting on that same server thread.
        return ForgeHooks.getServerChatSubmittedDecorator().decorate(player, Component.literal(text)).get(5, TimeUnit.SECONDS);
    }
    public static final class Probe {
        private final MinecraftServer server; private final UUID player; private final String cancelled;
        final AtomicBoolean offThread = new AtomicBoolean();
        Probe(MinecraftServer server, UUID player, String cancelled) { this.server = server; this.player = player; this.cancelled = cancelled; }
        @SubscribeEvent(priority = EventPriority.HIGH)
        public void chat(ServerChatEvent event) {
            if (!event.getPlayer().getUUID().equals(player)) return;
            offThread.set(!server.isSameThread());
            if (event.getRawText().equals(cancelled)) event.setCanceled(true);
        }
    }
}
