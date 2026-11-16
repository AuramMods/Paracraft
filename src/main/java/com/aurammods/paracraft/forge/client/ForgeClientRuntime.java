package com.aurammods.paracraft.forge.client;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.agent.AgentClock;
import com.aurammods.paracraft.core.agent.AgentStore;
import com.aurammods.paracraft.core.agent.ClientCoordinator;
import com.aurammods.paracraft.core.protocol.WireCodec;
import com.aurammods.paracraft.forge.network.ForgeNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = Paracraft.ID, value = Dist.CLIENT)
public final class ForgeClientRuntime {
    private static final KeyMapping OPEN = new KeyMapping("key.paracraft.companions", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, "key.categories.paracraft");
    private static final WireCodec.Assembler INCOMING = new WireCodec.Assembler();
    private static ClientCoordinator coordinator;
    private static Connection connection;
    private ForgeClientRuntime() { }
    public static ClientCoordinator coordinator() { return coordinator; }
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) { disconnect(); return; }
        Connection current = minecraft.getConnection().getConnection();
        if (connection != current || coordinator == null) {
            disconnect(); connection = current; start();
            minecraft.player.displayClientMessage(Component.literal("Paracraft: SIMULATED companions; no AI usage. Press K for controls."), false);
        }
        coordinator.tick(); INCOMING.expire(System.nanoTime());
        while (OPEN.consumeClick()) minecraft.setScreen(new CompanionScreen(minecraft.screen));
    }
    private static void start() {
        Minecraft minecraft = Minecraft.getInstance();
        coordinator = new ClientCoordinator(minecraft.player.getUUID(), new AgentStore(minecraft.gameDirectory.toPath().resolve("paracraft/memory")),
                AgentClock.SYSTEM, ForgeNetwork::toServer);
    }
    public static void retry() {
        if (coordinator != null) coordinator.close(); INCOMING.clear();
        if (Minecraft.getInstance().player != null && Minecraft.getInstance().getConnection() != null) start();
    }
    public static void receive(WireCodec.Frame frame) {
        if (coordinator == null) return;
        try { INCOMING.accept(frame, System.nanoTime()).ifPresent(coordinator::receive); }
        catch (RuntimeException failure) { INCOMING.clear(); coordinator.transportFailed("Transport error: " + failure); }
    }
    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) { disconnect(); }
    private static void disconnect() {
        if (coordinator != null) coordinator.close(); coordinator = null; connection = null; INCOMING.clear();
    }
    @Mod.EventBusSubscriber(modid = Paracraft.ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent public static void keys(RegisterKeyMappingsEvent event) { event.register(OPEN); }
        @SubscribeEvent public static void setup(FMLClientSetupEvent event) {
            ModLoadingContext.get().registerExtensionPoint(ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new ConfigScreenHandler.ConfigScreenFactory((minecraft, parent) -> new CompanionScreen(parent)));
        }
    }
}
