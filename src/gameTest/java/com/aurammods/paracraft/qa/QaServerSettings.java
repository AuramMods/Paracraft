package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Native GameTestServer's minimal PlayerList otherwise leaves both distances at zero. */
@Mod.EventBusSubscriber(modid = Paracraft.ID)
public final class QaServerSettings {
    @SubscribeEvent public static void stationaryFixtures(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if(event.phase!=net.minecraftforge.event.TickEvent.Phase.START)return;
        var manager=com.aurammods.paracraft.forge.ServerEvents.avatars(event.getServer());
        // Existing mechanics fixtures control actor position explicitly. Ambient scenarios opt into
        // the production default with this test-only tag, before the first server tick.
        for(var entry:manager.entries()) {
            var actor=manager.actor(entry.id);if(actor==null || actor.getTags().contains("qa_ambient"))continue;
            var settings=manager.behavior(entry.authority.owner(),entry.id).getAsJsonObject("behavior");
            if(settings.get("ambientIdle").getAsBoolean()) {
                var patch=new com.google.gson.JsonObject();patch.addProperty("ambientIdle",false);
                manager.configureBehavior(entry.authority.owner(),entry.id,patch);
            }
        }
    }
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        // GameTestServer does not use DedicatedPlayerList, so server.properties
        // alone does not apply these settings. Apply them to the actual runtime.
        event.getServer().getPlayerList().setViewDistance(8);
        event.getServer().getPlayerList().setSimulationDistance(8);
        event.getServer().setPvpAllowed(true);
        event.getServer().getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false,event.getServer());
    }
}
