package com.aurammods.paracraft.forge;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.IdentityHashMap;
import java.util.Map;

@Mod.EventBusSubscriber(modid = Paracraft.ID)
public final class ServerEvents {
    private static final Map<MinecraftServer, AvatarManager> MANAGERS = new IdentityHashMap<>();
    public static AvatarManager avatars(MinecraftServer server) {
        return MANAGERS.computeIfAbsent(server, AvatarManager::new);
    }
    @SubscribeEvent
    public static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof CompanionPlayer)) {
            AvatarManager manager = avatars(player.server);
            manager.restoreOwner(player.getUUID()).forEach(issue -> player.sendSystemMessage(
                    net.minecraft.network.chat.Component.literal("Paracraft: " + issue)));
            manager.sendProfiles(player);
        }
    }
    @SubscribeEvent
    public static void tracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer viewer && !(viewer instanceof CompanionPlayer)
                && event.getTarget() instanceof CompanionPlayer actor)
            avatars(viewer.server).statuses().tracking(viewer, actor.getUUID());
    }
    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof CompanionPlayer))
            avatars(player.server).disconnectOwner(player.getUUID());
    }
    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            AvatarManager manager = MANAGERS.get(event.getServer());
            if (manager != null) {
                manager.tickLifecycle();
                manager.jobs().tick();
                manager.control().tick();
                manager.endpoint().tick();
                manager.statuses().tick();
                if (event.getServer().getTickCount() % 100 == 0) manager.checkpointAll();
            }
        }
    }
    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        AvatarManager manager = MANAGERS.get(event.getServer());
        if (manager != null) {
            manager.checkpointAll();
            manager.entries().stream().map(e -> e.authority.owner()).distinct().toList()
                    .forEach(manager::disconnectOwner);
        }
    }
    @SubscribeEvent
    public static void stopped(ServerStoppedEvent event) { MANAGERS.remove(event.getServer()); }
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void chat(net.minecraftforge.event.ServerChatEvent event) {
        var player = event.getPlayer();
        if (player instanceof CompanionPlayer) return;
        var server = player.server;
        var text = event.getRawText();
        // Forge's chat decorator fires this event asynchronously. Leave its delivery
        // future unblocked and touch companion/world state only on the server thread.
        server.execute(() -> avatars(server).control().chat(player, text));
    }
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void breathe(net.minecraftforge.event.entity.living.LivingBreatheEvent event) {
        if (event.getEntity() instanceof CompanionPlayer actor) actor.recordBreathing(event.canBreathe(), event.canRefillAir());
    }
    @SubscribeEvent(priority = net.minecraftforge.eventbus.api.EventPriority.LOWEST)
    public static void damage(net.minecraftforge.event.entity.living.LivingDamageEvent event) {
        if (event.getEntity() instanceof CompanionPlayer actor) {
            var details = new com.google.gson.JsonObject(); details.addProperty("amount", event.getAmount());
            if (event.getSource().getEntity() != null) details.addProperty("attackerUuid", event.getSource().getEntity().getUUID().toString());
            avatars(actor.server).control().event(actor.getUUID(), "damage", details);
        }
    }
    @SubscribeEvent
    public static void meleeDrops(LivingDropsEvent event) {
        if (event.getSource().getEntity() instanceof CompanionPlayer actor) actor.recordMeleeDrops(event);
    }
    @SubscribeEvent
    public static void pickup(PlayerEvent.ItemPickupEvent event) {
        if (event.getEntity() instanceof CompanionPlayer actor) {
            boolean expectedMeleeLoot = actor.recordPickup(event.getOriginalEntity(), event.getStack());
            var details = new com.google.gson.JsonObject(); details.addProperty("item", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(event.getStack().getItem()).toString());
            details.addProperty("count", event.getStack().getCount());
            if (expectedMeleeLoot) details.addProperty("wake", false);
            avatars(actor.server).control().event(actor.getUUID(), "pickup", details);
        }
    }
}
