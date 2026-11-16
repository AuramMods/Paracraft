package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;

/** Test-only normal command handlers; no command/provider fixture is shipped in the mod JAR. */
@Mod.EventBusSubscriber(modid=Paracraft.ID)
public final class CommandFixtures {
    record Request(UUID recipient){}
    private static final Map<MinecraftServer,Map<UUID,Request>> PENDING=new IdentityHashMap<>();
    private static final Map<MinecraftServer,Map<UUID,GlobalPos>> HOMES=new IdentityHashMap<>();
    private static final Map<UUID,Integer> CALLS=new HashMap<>();
    public static int calls(UUID actor){return CALLS.getOrDefault(actor,0);}
    @SubscribeEvent public static void register(RegisterCommandsEvent event){
        event.getDispatcher().register(Commands.literal("qa_identity").executes(ctx->{
            var player=ctx.getSource().getPlayerOrException();ctx.getSource().sendSuccess(()->Component.literal("identity="+player.getUUID()+" name="+player.getGameProfile().getName()+" op="+ctx.getSource().hasPermission(2)),false);
            player.sendSystemMessage(Component.literal("direct-player-reply"));return 1;
        }));
        event.getDispatcher().register(Commands.literal("qa_zero").executes(ctx->0));
        event.getDispatcher().register(Commands.literal("qa_secret").requires(source->source.hasPermission(2)).executes(ctx->1));
        event.getDispatcher().register(Commands.literal("qa_tpa").then(Commands.argument("recipient",EntityArgument.player()).executes(ctx->{
            var player=ctx.getSource().getPlayerOrException();var recipient=EntityArgument.getPlayer(ctx,"recipient");
            PENDING.computeIfAbsent(player.server,s->new HashMap<>()).put(player.getUUID(),new Request(recipient.getUUID()));CALLS.merge(player.getUUID(),1,Integer::sum);
            ctx.getSource().sendSuccess(()->Component.literal("Teleport request accepted; awaiting recipient"),false);return 1;
        })));
        event.getDispatcher().register(Commands.literal("qa_tpaccept").then(Commands.argument("requester",StringArgumentType.word()).executes(ctx->{
            var player=ctx.getSource().getPlayerOrException();String name=StringArgumentType.getString(ctx,"requester");net.minecraft.server.level.ServerPlayer requester;
            try{requester=player.server.getPlayerList().getPlayer(UUID.fromString(name));}catch(IllegalArgumentException notUuid){requester=player.server.getPlayerList().getPlayerByName(name);}
            if(requester==null)throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("Requester is not online")).create();
            var requests=PENDING.getOrDefault(player.server,Map.of());var request=requests.get(requester.getUUID());
            if(request==null || !request.recipient.equals(player.getUUID()))throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("No authorized pending teleport request")).create();
            requests.remove(requester.getUUID());requester.teleportTo(player.serverLevel(),player.getX(),player.getY(),player.getZ(),player.getYRot(),player.getXRot());
            requester.sendSystemMessage(Component.literal("Teleport completed"));return 1;
        })));
        event.getDispatcher().register(Commands.literal("qa_sethome").executes(ctx->{var p=ctx.getSource().getPlayerOrException();HOMES.computeIfAbsent(p.server,s->new HashMap<>()).put(p.getUUID(),GlobalPos.of(p.serverLevel().dimension(),p.blockPosition()));return 1;}));
        event.getDispatcher().register(Commands.literal("qa_home").executes(ctx->{
            var p=ctx.getSource().getPlayerOrException();var home=HOMES.getOrDefault(p.server,Map.of()).get(p.getUUID());
            if(home==null)throw new com.mojang.brigadier.exceptions.SimpleCommandExceptionType(Component.literal("No home for this identity")).create();
            var level=p.server.getLevel(home.dimension());p.teleportTo(level,home.pos().getX()+0.5,home.pos().getY(),home.pos().getZ()+0.5,p.getYRot(),p.getXRot());return 1;
        }));
        event.getDispatcher().register(Commands.literal("qa_disconnect_self").requires(source->source.hasPermission(2)).executes(ctx->{ctx.getSource().getPlayerOrException().connection.disconnect(Component.literal("QA native disconnect"));return 1;}));
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event){PENDING.remove(event.getServer());HOMES.remove(event.getServer());CALLS.clear();}
}
