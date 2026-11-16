package com.aurammods.paracraft.forge.execution;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.resources.ResourceLocation;

/** Namespaced IDs and #tags are not Brigadier's restricted unquoted word syntax. */
public final class ItemSelectorArgument implements ArgumentType<String> {
    @Override public String parse(StringReader reader) throws CommandSyntaxException {
        boolean tag = reader.canRead() && reader.peek() == '#';
        if (tag) reader.skip();
        return (tag ? "#" : "") + ResourceLocation.read(reader);
    }
}
