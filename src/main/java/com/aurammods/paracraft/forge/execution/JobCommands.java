package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.AvatarEntry;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import java.util.UUID;

public final class JobCommands {
    private JobCommands() { }
    public static void register(LiteralArgumentBuilder<CommandSourceStack> root) {
        for (String command : new String[]{"search", "collect"}) {
            root.then(Commands.literal(command).then(Commands.argument("companion", StringArgumentType.word())
                    .then(Commands.argument("item", new ItemSelectorArgument()).executes(ctx -> run(ctx, (entry, actor) -> {
                        ItemSelector selector = new ItemSelector(ctx.getArgument("item", String.class));
                        return command.equals("search") ? new SearchJob(UUID.randomUUID(), actor, selector)
                                : new CollectJob(UUID.randomUUID(), actor, selector, -1);
                    })))));
        }
        root.then(Commands.literal("travel").then(Commands.argument("companion", StringArgumentType.word())
                .then(Commands.argument("destination", BlockPosArgument.blockPos()).executes(ctx -> run(ctx,
                        (entry, actor) -> new TravelJob(UUID.randomUUID(), actor,
                                net.minecraft.world.phys.Vec3.atBottomCenterOf(BlockPosArgument.getBlockPos(ctx, "destination"))))))));
        root.then(Commands.literal("deposit").then(Commands.argument("companion", StringArgumentType.word())
                .then(Commands.argument("destination", BlockPosArgument.blockPos())
                        .then(Commands.argument("item", new ItemSelectorArgument())
                                .then(Commands.argument("quantity", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                        .executes(ctx -> run(ctx, (entry, actor) -> new DepositJob(UUID.randomUUID(), actor,
                                                BlockPosArgument.getBlockPos(ctx, "destination"),
                                                new ItemSelector(ctx.getArgument("item", String.class)),
                                                ctx.getArgument("quantity", Integer.class)))))))));
        root.then(Commands.literal("deliver").then(Commands.argument("companion", StringArgumentType.word())
                .then(Commands.argument("recipient", net.minecraft.commands.arguments.EntityArgument.player())
                        .then(Commands.argument("item", new ItemSelectorArgument()).executes(ctx -> {
                            UUID recipient = net.minecraft.commands.arguments.EntityArgument.getPlayer(ctx, "recipient").getUUID();
                            return run(ctx, (entry, actor) -> {
                                ItemSelector selector = new ItemSelector(ctx.getArgument("item", String.class));
                                return new DeliverJob(UUID.randomUUID(), actor, recipient,
                                        actor.getInventory().items.stream().filter(selector::matches).map(net.minecraft.world.item.ItemStack::copy).toList());
                            });
                        })))));
        root.then(Commands.literal("job").then(Commands.argument("companion", StringArgumentType.word()).executes(ctx -> {
            var owner = ctx.getSource().getPlayerOrException();
            var manager = ServerEvents.avatars(owner.server);
            try {
                var entry = find(ctx, manager, owner.getUUID());
                var job = manager.jobs().get(entry.id);
                owner.sendSystemMessage(Component.literal(job == null ? "No operation" : job.view().toString()));
                return 1;
            } catch (IllegalArgumentException failure) { ctx.getSource().sendFailure(Component.literal(failure.getMessage())); return 0; }
        })));
        root.then(Commands.literal("cancel").then(Commands.argument("companion", StringArgumentType.word()).executes(ctx -> {
            var owner = ctx.getSource().getPlayerOrException(); var manager = ServerEvents.avatars(owner.server);
            try { manager.jobs().cancel(find(ctx, manager, owner.getUUID()).id, "Cancelled by owner"); return 1; }
            catch (IllegalArgumentException failure) { ctx.getSource().sendFailure(Component.literal(failure.getMessage())); return 0; }
        })));
    }
    private static int run(CommandContext<CommandSourceStack> ctx, Factory factory) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var owner = ctx.getSource().getPlayerOrException(); var manager = ServerEvents.avatars(owner.server);
        try {
            AvatarEntry entry = find(ctx, manager, owner.getUUID());
            var actor = manager.actor(entry.id);
            if (actor == null) throw new IllegalStateException("Companion is saved/offline");
            var job = factory.create(entry, actor);
            manager.jobs().submit(owner.getUUID(), entry.id, entry.authority.session(), job, true);
            owner.sendSystemMessage(Component.literal("Started " + job.type + " " + job.id)); return 1;
        } catch (IllegalArgumentException | IllegalStateException failure) { ctx.getSource().sendFailure(Component.literal(failure.getMessage())); return 0; }
    }
    private static AvatarEntry find(CommandContext<CommandSourceStack> ctx, AvatarManager manager, UUID owner) {
        String name = StringArgumentType.getString(ctx, "companion");
        return manager.entries().stream().filter(e -> e.authority.owner().equals(owner)
                && (e.name.equalsIgnoreCase(name) || e.id.toString().equals(name))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No companion owned by you matches " + name));
    }
    @FunctionalInterface private interface Factory { GameJob create(AvatarEntry entry, CompanionPlayer actor); }
}
