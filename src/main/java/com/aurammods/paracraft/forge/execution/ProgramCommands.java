package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.ServerEvents;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import java.util.UUID;

public final class ProgramCommands {
    private ProgramCommands() { }
    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("program").then(Commands.argument("companion", StringArgumentType.word())
                .then(Commands.argument("json", StringArgumentType.greedyString()).executes(ctx -> {
                    var owner = ctx.getSource().getPlayerOrException(); var manager = ServerEvents.avatars(owner.server);
                    try {
                        var entry = JobCommands.find(ctx, manager, owner.getUUID());
                        JsonObject program = JsonParser.parseString(StringArgumentType.getString(ctx, "json")).getAsJsonObject();
                        UUID id = program.has("id") ? UUID.fromString(program.get("id").getAsString()) : UUID.randomUUID();
                        String mode = program.has("mode") ? program.get("mode").getAsString() : "replace";
                        GameJob job = mode.equals("resume")
                                ? manager.jobs().resumeRequest(owner.getUUID(), entry.id, entry.authority.session(), id)
                                : manager.jobs().submitProgram(owner.getUUID(), entry.id, entry.authority.session(), id, program, mode);
                        owner.sendSystemMessage(Component.literal("Program " + job.id + " " + job.state())); return 1;
                    } catch (IllegalArgumentException | IllegalStateException | com.google.gson.JsonParseException invalid) {
                        ctx.getSource().sendFailure(Component.literal("Program: " + invalid.getMessage())); return 0;
                    }
                }))));
    }
}
