package com.aurammods.paracraft.forge.avatar;

import net.minecraft.server.level.ServerPlayer;

/** Forge/version-specific native roster access, widened by the bundled access transformer. */
final class AvatarRoster {
    private AvatarRoster() { }
    static void register(CompanionPlayer player) {
        var list=player.server.getPlayerList();
        if(list.playersByUUID.putIfAbsent(player.getUUID(),player)!=null) throw new IllegalStateException("Avatar identity is already present in the native player roster");
        list.players.add(player);
    }
    static void rollback(CompanionPlayer player) {
        var list=player.server.getPlayerList(); list.players.remove(player); list.playersByUUID.remove(player.getUUID(),player);
    }
    static void loggedIn(CompanionPlayer player) {
        player.server.getCustomBossEvents().onPlayerConnect(player);
        net.minecraftforge.event.ForgeEventFactory.firePlayerLoggedIn(player);
    }
    static void removeForRespawn(CompanionPlayer player) {
        // Native respawn retains stats/advancements and does not emit logout/login.
        rollback(player); player.serverLevel().removePlayerImmediately(player,net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
    }
    static void failed(CompanionPlayer player,boolean preserveStats){
        var list=player.server.getPlayerList();var stats=list.stats.remove(player.getUUID());if(stats!=null && preserveStats)stats.save();
        var advancements=list.advancements.remove(player.getUUID());if(advancements!=null){if(preserveStats)advancements.save();advancements.stopListening();}
    }
    static void saveOfflineMirror(CompanionPlayer player) {
        var list=player.server.getPlayerList(); list.playerIo.save(player);
        var stats=list.stats.remove(player.getUUID()); if(stats!=null)stats.save();
        var advancements=list.advancements.remove(player.getUUID()); if(advancements!=null){advancements.save();advancements.stopListening();}
    }
}
