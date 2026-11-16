package com.aurammods.paracraft.forge;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.forge.avatar.AvatarEntry;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = Paracraft.ID)
public final class CompanionCommands {
    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("paracraft");
        root.then(Commands.literal("behavior").then(Commands.argument("companion",StringArgumentType.word())
                .executes(ctx -> invoke(ctx,(owner,manager) -> {
                    var entry = find(ctx,owner,manager); owner.sendSystemMessage(Component.literal(manager.behavior(owner.getUUID(),entry.id).toString())); return 1;
                })).then(Commands.argument("settings",StringArgumentType.greedyString()).executes(ctx -> invoke(ctx,(owner,manager) -> {
                    var entry = find(ctx,owner,manager);
                    com.google.gson.JsonObject patch;
                    try { patch = com.google.gson.JsonParser.parseString(StringArgumentType.getString(ctx,"settings")).getAsJsonObject(); }
                    catch (com.google.gson.JsonParseException invalid) { throw new IllegalArgumentException("Invalid behavior JSON: " + invalid.getMessage()); }
                    owner.sendSystemMessage(Component.literal(manager.configureBehavior(owner.getUUID(),entry.id,patch).toString())); return 1;
                })))));
        root.then(Commands.literal("spawn").then(Commands.argument("name", StringArgumentType.word())
                .executes(ctx -> invoke(ctx, (owner, manager) -> {
                    AvatarEntry entry = manager.spawn(owner.getUUID(), owner.serverLevel(), owner.position(),
                            StringArgumentType.getString(ctx, "name"));
                    owner.sendSystemMessage(Component.literal("Created " + entry.name + " (" + entry.id + ")"));
                    return 1;
                }))));
        root.then(Commands.literal("list").executes(ctx -> invoke(ctx, (owner, manager) -> {
            int count = 0;
            for (AvatarEntry entry : manager.entries()) if (entry.authority.owner().equals(owner.getUUID())) {
                owner.sendSystemMessage(Component.literal(entry.name + " " + entry.id + " "
                        + (manager.actor(entry.id) == null ? "saved" : "active") + (entry.paused ? " (paused)" : "")));
                count++;
            }
            if (count == 0) owner.sendSystemMessage(Component.literal("No companions. Use /paracraft spawn <name>."));
            return count;
        })));
        for (String operation : new String[]{"pause", "resume", "destroy", "status"}) {
            root.then(Commands.literal(operation).then(Commands.argument("companion", StringArgumentType.word())
                    .executes(ctx -> invoke(ctx, (owner, manager) -> {
                        AvatarEntry entry = find(ctx, owner, manager);
                        if (operation.equals("destroy")) manager.destroy(owner.getUUID(), entry.id);
                        else if (operation.equals("pause") || operation.equals("resume"))
                            manager.pause(owner.getUUID(), entry.id, operation.equals("pause"));
                        var actor = manager.actor(entry.id);
                        owner.sendSystemMessage(Component.literal(entry.name + ": " + operation
                                + (actor == null ? " (saved/offline)" : " health=" + actor.getHealth()
                                + " food=" + actor.getFoodData().getFoodLevel() + " at " + actor.blockPosition().toShortString())));
                        return 1;
                    }))));
        }
        for (boolean add : new boolean[]{true, false}) {
            root.then(Commands.literal(add ? "trust" : "untrust")
                    .then(Commands.argument("companion", StringArgumentType.word())
                            .then(Commands.argument("player", EntityArgument.player()).executes(ctx -> {
                                ServerPlayer trusted = EntityArgument.getPlayer(ctx, "player");
                                return invoke(ctx, (owner, manager) -> {
                                    AvatarEntry entry = find(ctx, owner, manager);
                                    manager.trust(owner.getUUID(), entry.id, trusted.getUUID(), add);
                                    owner.sendSystemMessage(Component.literal(entry.name + ": "
                                            + (add ? "trusted " : "untrusted ") + trusted.getGameProfile().getName()));
                                    return 1;
                                });
                            }))));
        }
        com.aurammods.paracraft.forge.execution.JobCommands.register(root);
        event.getDispatcher().register(root);
    }

    private static AvatarEntry find(CommandContext<CommandSourceStack> ctx, ServerPlayer owner, AvatarManager manager) {
        String name = StringArgumentType.getString(ctx, "companion");
        return manager.entries().stream().filter(e -> e.authority.owner().equals(owner.getUUID())
                && (e.name.equalsIgnoreCase(name) || e.id.toString().equals(name))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No companion owned by you matches " + name));
    }

    private static int invoke(CommandContext<CommandSourceStack> ctx, Work work) throws CommandSyntaxException {
        ServerPlayer owner = ctx.getSource().getPlayerOrException();
        try {
            if(owner instanceof com.aurammods.paracraft.forge.avatar.CompanionPlayer)throw new IllegalArgumentException("Companion management requires its connected owner");
            return work.run(owner, ServerEvents.avatars(owner.server));
        }
        catch (IllegalArgumentException | IllegalStateException failure) {
            ctx.getSource().sendFailure(Component.literal(failure.getMessage()));
            return 0;
        }
    }

    @FunctionalInterface
    private interface Work { int run(ServerPlayer owner, AvatarManager manager); }
}
