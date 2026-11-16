package com.aurammods.paracraft.forge;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
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
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && !(player instanceof CompanionPlayer))
            avatars(player.server).disconnectOwner(player.getUUID());
    }
    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.getServer().getTickCount() % 100 == 0) {
            AvatarManager manager = MANAGERS.get(event.getServer());
            if (manager != null) manager.checkpointAll();
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
}
