package com.aurammods.paracraft.forge.network;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.protocol.WireCodec;
import com.aurammods.paracraft.forge.ServerEvents;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.Optional;

public final class ForgeNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(new ResourceLocation(Paracraft.ID, "control"),
            () -> VERSION, VERSION::equals, VERSION::equals);
    public record ToServer(WireCodec.Frame frame) { }
    public record ToClient(WireCodec.Frame frame) { }
    private ForgeNetwork() { }
    public static void register() {
        CHANNEL.registerMessage(0, ToServer.class, (m, b) -> write(m.frame, b), b -> new ToServer(read(b)), (message, context) -> {
            var ctx = context.get(); ctx.enqueueWork(() -> {
                ServerPlayer sender = ctx.getSender(); if (sender == null) return;
                ServerPlayer current = sender.server.getPlayerList().getPlayer(sender.getUUID());
                if (current == null || current.connection != sender.connection) return; // Drop packets from an old native connection.
                try { ServerEvents.avatars(current.server).endpoint().receiveFrame(current, message.frame, response -> toClient(current, response)); }
                catch (RuntimeException failure) { current.connection.disconnect(Component.literal("Invalid Paracraft transport: " + failure.getMessage())); }
            }); ctx.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(1, ToClient.class, (m, b) -> write(m.frame, b), b -> new ToClient(read(b)), (message, context) -> {
            var ctx = context.get(); ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                    () -> () -> com.aurammods.paracraft.forge.client.ForgeClientRuntime.receive(message.frame)));
            ctx.setPacketHandled(true);
        }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }
    public static void toServer(JsonObject message) { for (var frame : WireCodec.encode(message)) CHANNEL.sendToServer(new ToServer(frame)); }
    public static void toClient(ServerPlayer player, JsonObject message) {
        for (var frame : WireCodec.encode(message)) CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new ToClient(frame));
    }
    public static void write(WireCodec.Frame frame, FriendlyByteBuf buffer) {
        buffer.writeUUID(frame.id()); buffer.writeVarInt(frame.index()); buffer.writeVarInt(frame.count()); buffer.writeByteArray(frame.bytes());
    }
    public static WireCodec.Frame read(FriendlyByteBuf buffer) {
        return new WireCodec.Frame(buffer.readUUID(), buffer.readVarInt(), buffer.readVarInt(), buffer.readByteArray(WireCodec.CHUNK_BYTES));
    }
}
