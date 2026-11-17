package com.aurammods.paracraft;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(Paracraft.ID)
public final class Paracraft {
    public static final String ID = "paracraft";
    public static final Logger LOG = LogUtils.getLogger();
    public static final ForgeConfigSpec.IntValue OWNER_LIMIT;
    public static final ForgeConfigSpec.IntValue SERVER_LIMIT;
    public static final ForgeConfigSpec.BooleanValue NAVIGATION_TERRAIN_CHANGES;
    public static final ForgeConfigSpec.BooleanValue DEBUG_AGENT_OUTPUT;
    private static final ForgeConfigSpec CONFIG;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        OWNER_LIMIT = builder.comment("Maximum saved companions per owner.")
                .defineInRange("avatarsPerOwner", 4, 1, Integer.MAX_VALUE);
        SERVER_LIMIT = builder.comment("Maximum simultaneously active companions.")
                .defineInRange("activeAvatarsPerServer", 64, 1, Integer.MAX_VALUE);
        NAVIGATION_TERRAIN_CHANGES = builder.comment("Allow navigation to break obstructions or place bridges/pillars. Default routes preserve builds.")
                .define("navigationTerrainChanges", false);
        DEBUG_AGENT_OUTPUT = builder.comment("Log agent-visible output and tool calls on this server. Gradle runs enable it through a JVM property.")
                .define("debugAgentOutput", false);
        CONFIG = builder.build();
    }
    public static boolean debugAgentOutput() {
        String override = System.getProperty("paracraft.debugAgentOutput");
        return override == null ? DEBUG_AGENT_OUTPUT.get() : Boolean.parseBoolean(override);
    }

    public Paracraft() {
        com.aurammods.paracraft.forge.network.ForgeNetwork.register();
        com.aurammods.paracraft.forge.execution.CommandArguments.register(
                net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus());
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, CONFIG);
        LOG.info("Paracraft loaded; model sign-in and credentials stay on owner clients.");
    }
}
