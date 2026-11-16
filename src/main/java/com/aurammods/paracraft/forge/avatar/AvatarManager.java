package com.aurammods.paracraft.forge.avatar;

import com.aurammods.paracraft.Paracraft;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Server-thread-only service. Network handlers must obtain the owner from their authenticated sender. */
public final class AvatarManager {
    private final MinecraftServer server;
    private final AvatarSavedData saved;
    private final Map<UUID, CompanionPlayer> active = new HashMap<>();
    private final com.aurammods.paracraft.forge.execution.JobManager jobs =
            new com.aurammods.paracraft.forge.execution.JobManager(this);

    public AvatarManager(MinecraftServer server) {
        this.server = server;
        saved = server.overworld().getDataStorage().computeIfAbsent(
                AvatarSavedData::load, AvatarSavedData::new, "paracraft_avatars");
    }

    public Collection<AvatarEntry> entries() { return saved.entries(); }
    public CompanionPlayer actor(UUID id) { return active.get(id); }
    public AvatarEntry entry(UUID id) { return saved.get(id); }
    public int activeCount() { return active.size(); }
    public com.aurammods.paracraft.forge.execution.JobManager jobs() { return jobs; }

    public AvatarEntry spawn(UUID owner, ServerLevel level, Vec3 position, String name) {
        if (!name.matches("[A-Za-z0-9_]{1,16}"))
            throw new IllegalArgumentException("Use a player-style name of 1 to 16 letters, digits, or underscores");
        if (saved.entries().stream().anyMatch(e -> e.name.equalsIgnoreCase(name))
                || server.getPlayerList().getPlayerByName(name) != null)
            throw new IllegalArgumentException("That player/companion name is already in use");
        long count = saved.entries().stream().filter(e -> e.authority.owner().equals(owner)).count();
        if (count >= Paracraft.OWNER_LIMIT.get()) throw new IllegalStateException("Owner companion limit reached");
        if (active.size() >= Paracraft.SERVER_LIMIT.get()) throw new IllegalStateException("Active companion limit reached");
        AvatarEntry entry = new AvatarEntry(UUID.randomUUID(), owner, name);
        CompanionPlayer player = new CompanionPlayer(level, new GameProfile(entry.id, name));
        player.moveTo(position.x, position.y, position.z, 0, 0);
        attach(entry, player);
        saved.put(entry);
        checkpoint(entry);
        return entry;
    }

    private void attach(AvatarEntry entry, CompanionPlayer player) {
        server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
        player.serverLevel().addNewPlayer(player);
        if (player.serverLevel().getEntity(player.getUUID()) != player) {
            server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
            throw new IllegalStateException("Avatar spawn was rejected by the world");
        }
        active.put(entry.id, player);
        if (!entry.paused) entry.authority.connect();
        jobs.restore(entry.id, entry.execution);
    }

    public void checkpoint(AvatarEntry entry) {
        CompanionPlayer player = active.get(entry.id);
        if (player != null) {
            entry.physical = player.saveWithoutId(new CompoundTag());
            entry.execution = jobs.checkpoint(entry.id);
            saved.setDirty();
        }
    }

    public void checkpointAll() { saved.entries().forEach(this::checkpoint); }

    public void disconnectOwner(UUID owner) {
        for (AvatarEntry entry : saved.entries()) {
            if (entry.authority.owner().equals(owner)) {
                jobs.pause(entry.id);
                entry.authority.disconnect();
                CompanionPlayer player = active.get(entry.id);
                // Closing first returns carried/crafting stacks before the snapshot is saved.
                if (player != null) player.closeContainer();
                checkpoint(entry);
                jobs.unbind(entry.id);
                detach(entry.id);
            }
        }
    }

    private void detach(UUID id) {
        CompanionPlayer player = active.remove(id);
        if (player == null) return;
        player.closeContainer();
        player.unRide();
        player.serverLevel().removePlayerImmediately(player, Entity.RemovalReason.UNLOADED_WITH_PLAYER);
        player.getAdvancements().stopListening();
        server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(id)));
    }

    public List<String> restoreOwner(UUID owner) {
        List<String> issues = new java.util.ArrayList<>();
        for (AvatarEntry entry : saved.entries()) {
            if (entry.authority.owner().equals(owner) && !active.containsKey(entry.id)) {
                if (active.size() >= Paracraft.SERVER_LIMIT.get()) {
                    issues.add(entry.name + " awaits active companion capacity");
                    continue;
                }
                try { attach(entry, loadPlayer(entry)); }
                catch (IllegalArgumentException | IllegalStateException failure) {
                    issues.add(entry.name + ": " + failure.getMessage());
                }
            }
        }
        return List.copyOf(issues);
    }

    private CompanionPlayer loadPlayer(AvatarEntry entry) {
        ResourceKey<net.minecraft.world.level.Level> dimension = ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(entry.physical.getString("Dimension")));
        ServerLevel level = server.getLevel(dimension);
        if (level == null) throw new IllegalStateException("Saved avatar dimension is unavailable: " + dimension.location());
        CompanionPlayer player = new CompanionPlayer(level, new GameProfile(entry.id, entry.name));
        player.load(entry.physical.copy());
        return player;
    }

    public void destroy(UUID owner, UUID id) {
        AvatarEntry entry = owned(owner, id);
        jobs.forget(id);
        CompanionPlayer player = active.get(id);
        if (player == null) player = loadPlayer(entry);
        player.closeContainer();
        player.getInventory().dropAll();
        entry.authority.disconnect();
        detach(id);
        saved.remove(id);
    }

    public void pause(UUID owner, UUID id, boolean paused) {
        AvatarEntry entry = owned(owner, id);
        entry.paused = paused;
        if (paused) jobs.pause(id); else jobs.resume(id);
        entry.authority.disconnect();
        if (!paused && active.containsKey(id)) entry.authority.connect();
        saved.setDirty();
    }

    public void trust(UUID owner, UUID id, UUID trusted, boolean add) {
        AvatarEntry entry = owned(owner, id);
        if (add) entry.authority.trust(trusted); else entry.authority.untrust(trusted);
        saved.setDirty();
    }

    public AvatarEntry owned(UUID owner, UUID id) {
        AvatarEntry entry = saved.get(id);
        if (entry == null || !entry.authority.owner().equals(owner))
            throw new IllegalArgumentException("No companion owned by you matches that ID");
        return entry;
    }

    public void sendProfiles(net.minecraft.server.level.ServerPlayer viewer) {
        if (!active.isEmpty()) viewer.connection.send(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(
                new java.util.ArrayList<net.minecraft.server.level.ServerPlayer>(active.values())));
    }
}
