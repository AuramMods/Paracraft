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
