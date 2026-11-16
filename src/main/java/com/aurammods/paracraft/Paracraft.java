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
    private static final ForgeConfigSpec CONFIG;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        OWNER_LIMIT = builder.comment("Maximum saved companions per owner.")
                .defineInRange("avatarsPerOwner", 4, 1, Integer.MAX_VALUE);
        SERVER_LIMIT = builder.comment("Maximum simultaneously active companions.")
                .defineInRange("activeAvatarsPerServer", 64, 1, Integer.MAX_VALUE);
        CONFIG = builder.build();
    }

    public Paracraft() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, CONFIG);
        LOG.info("Paracraft foundation loaded; live AI authentication is not connected.");
    }
}
