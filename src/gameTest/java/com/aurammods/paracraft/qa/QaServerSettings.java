package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Native GameTestServer's minimal PlayerList otherwise leaves both distances at zero. */
@Mod.EventBusSubscriber(modid = Paracraft.ID)
public final class QaServerSettings {
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        // GameTestServer does not use DedicatedPlayerList, so server.properties
        // alone does not apply these settings. Apply them to the actual runtime.
        event.getServer().getPlayerList().setViewDistance(8);
        event.getServer().getPlayerList().setSimulationDistance(8);
        event.getServer().setPvpAllowed(true);
    }
}
