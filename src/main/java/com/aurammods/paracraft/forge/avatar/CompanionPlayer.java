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
    private boolean retired, insideTeleport;
    private long teleportSerial;
    private boolean nativeTeleportSeen;
    private String disconnectReason="";
    private final java.util.Set<java.util.function.Consumer<Component>> messageObservers=new java.util.HashSet<>();
    private java.util.function.Consumer<net.minecraft.nbt.CompoundTag> saveObserver;
    public Runnable observeSave(java.util.function.Consumer<net.minecraft.nbt.CompoundTag> observer){saveObserver=observer;return ()->saveObserver=null;}
    @Override public net.minecraft.nbt.CompoundTag saveWithoutId(net.minecraft.nbt.CompoundTag tag){
        var saved=super.saveWithoutId(tag);if(saveObserver!=null)saveObserver.accept(saved.copy());return saved;
    }
    public boolean retired(){return retired;}
    public void retire(){retired=true;if(connection!=null && connection.connection instanceof LocalConnection local)local.closeLocal();}
    public String disconnectReason(){return disconnectReason;}
    public long teleportSerial(){return teleportSerial;}
    public Runnable observeMessages(java.util.function.Consumer<Component> observer){messageObservers.add(observer);return ()->messageObservers.remove(observer);}
    @Override public void sendSystemMessage(Component message,boolean overlay){
        for(var observer:java.util.List.copyOf(messageObservers))observer.accept(message.copy());
        super.sendSystemMessage(message,overlay);
    }
    @Override public void displayClientMessage(Component message,boolean overlay){
        for(var observer:java.util.List.copyOf(messageObservers))observer.accept(message.copy());
        super.displayClientMessage(message,overlay);
    }
    private void localTeleport(){if(insideTeleport)nativeTeleportSeen=true;else teleportSerial++;}
    private void requestDisconnect(Component reason){disconnectReason=reason.getString();disconnect();}
    @Override public void teleportTo(ServerLevel destination,double x,double y,double z,float yaw,float pitch){
        if(retired)return;
        boolean outer=insideTeleport; if(!outer)nativeTeleportSeen=false; insideTeleport=true;
        try{super.teleportTo(destination,x,y,z,yaw,pitch);}
        finally{insideTeleport=outer; if(!outer && nativeTeleportSeen)teleportSerial++;}
    }
    @Override public net.minecraft.world.entity.Entity changeDimension(ServerLevel destination,net.minecraftforge.common.util.ITeleporter teleporter){
        if(retired)return null;
        boolean outer=insideTeleport;if(!outer)nativeTeleportSeen=false;insideTeleport=true;
        try{return super.changeDimension(destination,teleporter);}
        finally{insideTeleport=outer;if(!outer && nativeTeleportSeen){teleportSerial++;hasChangedDimension();gameMode.setLevel(serverLevel());}}
    }
    @Override public boolean canChangeDimensions(){return false;} // An explicit portal operation grants this in the portal adapter.
    private boolean needsAir, canRefillAir = true;
    private java.util.function.Consumer<net.minecraft.world.damagesource.DamageSource> damageObserver = source -> { };
    public boolean needsAir() { return needsAir; }
    public boolean canRefillAir() { return canRefillAir; }
    public void recordBreathing(boolean canBreathe, boolean canRefill) { needsAir = !canBreathe; canRefillAir = canRefill; }
    public void observeDamage(java.util.function.Consumer<net.minecraft.world.damagesource.DamageSource> observer) { damageObserver = observer; }
    @Override public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        float before = getHealth() + getAbsorptionAmount(); boolean accepted = super.hurt(source, amount);
        if (accepted && getHealth() + getAbsorptionAmount() < before) damageObserver.accept(source);
        return accepted;
    }
    private boolean insideDeath, deathCommitted;
    private String sleepProblem = "";
    public String sleepProblem() { return sleepProblem; }
    public void clearSleepProblem() { sleepProblem = ""; }
    @Override public com.mojang.datafixers.util.Either<net.minecraft.world.entity.player.Player.BedSleepingProblem, net.minecraft.util.Unit>
            startSleepInBed(net.minecraft.core.BlockPos position) {
        var response = super.startSleepInBed(position);
        sleepProblem = response.left().map(Enum::name).orElse(""); return response;
    }
    public boolean deathCommitted() { return deathCommitted; }

    @Override public void die(net.minecraft.world.damagesource.DamageSource source) {
        if (deathCommitted) return;
        // Return cursor/crafting items before the native death-loot path sees the inventory.
        closeContainer();
        insideDeath = true;
        try { super.die(source); }
        finally { insideDeath = false; }
    }
    @Override public void setLastDeathLocation(java.util.Optional<net.minecraft.core.GlobalPos> location) {
        super.setLastDeathLocation(location);
        // ServerPlayer reaches this native completion point only after an uncancelled
        // death, including its loot, XP, Forge hooks, and death statistics.
        if (insideDeath) deathCommitted = true;
    }
    @Override public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag); tag.putBoolean("ParacraftDeathCommitted", deathCommitted);tag.putLong("ParacraftTeleportSerial",teleportSerial);
    }
    @Override public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag); deathCommitted = tag.getBoolean("ParacraftDeathCommitted");teleportSerial=tag.getLong("ParacraftTeleportSerial");
    }
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
            super(player.server, new LocalConnection(player), player);
        }
        @Override public void send(Packet<?> packet) { }
        @Override public void send(Packet<?> packet, PacketSendListener listener) { }
        @Override public void tick() { }
        @Override public void disconnect(Component reason) { ((CompanionPlayer)player).requestDisconnect(reason); }
        @Override public void onDisconnect(Component reason) { disconnect(reason); }
        @Override public void resetPosition() { }
        @Override
        public void teleport(double x, double y, double z, float yaw, float pitch) {
            teleport(x, y, z, yaw, pitch, Set.of());
        }
        @Override
        public void teleport(double x, double y, double z, float yaw, float pitch,
                             Set<RelativeMovement> relative) {
            CompanionPlayer body=(CompanionPlayer)player;if(body.retired)return;
            player.absMoveTo(relative.contains(RelativeMovement.X) ? player.getX() + x : x,
                    relative.contains(RelativeMovement.Y) ? player.getY() + y : y,
                    relative.contains(RelativeMovement.Z) ? player.getZ() + z : z,
                    relative.contains(RelativeMovement.Y_ROT) ? player.getYRot() + yaw : yaw,
                    relative.contains(RelativeMovement.X_ROT) ? player.getXRot() + pitch : pitch);
            player.resetFallDistance();
            body.localTeleport();
        }
    }
    /** Supplies native channel attributes without a socket, handshake, or outbound packet queue. */
    private static final class LocalConnection extends Connection {
        private final CompanionPlayer owner;
        private final io.netty.channel.embedded.EmbeddedChannel embedded;
        private boolean closing;
        private LocalConnection(CompanionPlayer owner){super(PacketFlow.SERVERBOUND);this.owner=owner;embedded=new io.netty.channel.embedded.EmbeddedChannel(this);}
        @Override public void send(Packet<?> packet){}
        @Override public void send(Packet<?> packet,PacketSendListener listener){}
        @Override public void tick(){}
        @Override public boolean isMemoryConnection(){return true;}
        @Override public void disconnect(Component reason){if(!closing && owner!=null)owner.requestDisconnect(reason);}
        private void closeLocal(){if(!closing){closing=true;embedded.finishAndReleaseAll();}}
    }
}
