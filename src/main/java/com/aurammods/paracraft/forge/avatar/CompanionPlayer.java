package com.aurammods.paracraft.forge.avatar;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.level.GameType;

import java.util.Set;

/** Real survival player, ticked by its level; it has no socket or authenticated account. */
public final class CompanionPlayer extends ServerPlayer {
    private final java.util.Set<java.util.function.Consumer<net.minecraft.world.item.ItemStack>> pickupObservers = new java.util.HashSet<>();
    public Runnable observePickups(java.util.function.Consumer<net.minecraft.world.item.ItemStack> observer) {
        pickupObservers.add(observer); return () -> pickupObservers.remove(observer);
    }
    public void recordPickup(net.minecraft.world.item.ItemStack stack) {
        if (!stack.isEmpty()) for (var observer : java.util.List.copyOf(pickupObservers)) observer.accept(stack.copy());
    }
    public CompanionPlayer(ServerLevel level, GameProfile profile) {
        super(level.getServer(), level, profile);
        connection = new LocalListener(this);
        setGameMode(GameType.SURVIVAL);
        initInventoryMenu();
    }

    @Override
    public void tick() {
        super.tick();
        // Connected players normally receive this tick from their network listener.
        // Our listener has no network loop, so execute it exactly once here instead.
        super.doTick();
        // A normal connection updates the chunk map after movement. This actor has
        // no connection tick, so keep its loading/tracking position current here.
        if (!isRemoved()) serverLevel().getChunkSource().move(this);
    }

    private static final class LocalListener extends ServerGamePacketListenerImpl {
        private LocalListener(CompanionPlayer player) {
            super(player.server, new Connection(PacketFlow.SERVERBOUND), player);
        }
        @Override public void send(Packet<?> packet) { }
        @Override public void send(Packet<?> packet, PacketSendListener listener) { }
        @Override public void tick() { }
        @Override public void disconnect(Component reason) { }
        @Override public void onDisconnect(Component reason) { }
        @Override public void resetPosition() { }
        @Override
        public void teleport(double x, double y, double z, float yaw, float pitch) {
            teleport(x, y, z, yaw, pitch, Set.of());
        }
        @Override
        public void teleport(double x, double y, double z, float yaw, float pitch,
                             Set<RelativeMovement> relative) {
            player.absMoveTo(relative.contains(RelativeMovement.X) ? player.getX() + x : x,
                    relative.contains(RelativeMovement.Y) ? player.getY() + y : y,
                    relative.contains(RelativeMovement.Z) ? player.getZ() + z : z,
                    relative.contains(RelativeMovement.Y_ROT) ? player.getYRot() + yaw : yaw,
                    relative.contains(RelativeMovement.X_ROT) ? player.getXRot() + pitch : pitch);
            player.resetFallDistance();
        }
    }
}
