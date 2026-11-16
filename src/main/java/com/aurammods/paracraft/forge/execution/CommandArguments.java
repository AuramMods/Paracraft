package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.Paracraft;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.commands.synchronization.SingletonArgumentInfo;
import net.minecraft.core.registries.Registries;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;

/** The client must be able to reconstruct custom command arguments during login. */
public final class CommandArguments {
    private static final DeferredRegister<ArgumentTypeInfo<?, ?>> TYPES =
            DeferredRegister.create(Registries.COMMAND_ARGUMENT_TYPE, Paracraft.ID);
    static {
        TYPES.register("item_selector", () -> ArgumentTypeInfos.registerByClass(ItemSelectorArgument.class,
                SingletonArgumentInfo.contextFree(ItemSelectorArgument::new)));
    }
    private CommandArguments() { }
    public static void register(IEventBus bus) { TYPES.register(bus); }
}
