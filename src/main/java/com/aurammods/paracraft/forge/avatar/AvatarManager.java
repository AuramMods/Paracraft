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
    private final com.aurammods.paracraft.forge.control.AvatarStatuses statuses = new com.aurammods.paracraft.forge.control.AvatarStatuses(this);
    public com.aurammods.paracraft.forge.control.AvatarStatuses statuses() { return statuses; }
    private final MinecraftServer server;
    private final AvatarSavedData saved;
    private final com.aurammods.paracraft.forge.control.ServerControl control;
    private final com.aurammods.paracraft.forge.control.ControlEndpoint endpoint;
    private final Map<UUID, CompanionPlayer> active = new HashMap<>();
    private final Map<UUID, SurvivalController> survival = new HashMap<>();
    private final com.aurammods.paracraft.forge.execution.JobManager jobs =
            new com.aurammods.paracraft.forge.execution.JobManager(this);

    public AvatarManager(MinecraftServer server) {
        this.server = server;
        saved = server.overworld().getDataStorage().computeIfAbsent(
                AvatarSavedData::load, AvatarSavedData::new, "paracraft_avatars");
        control = new com.aurammods.paracraft.forge.control.ServerControl(this, server);
        endpoint = new com.aurammods.paracraft.forge.control.ControlEndpoint(this, server);
    }

    public Collection<AvatarEntry> entries() { return saved.entries(); }
    public CompanionPlayer actor(UUID id) { return active.get(id); }
    public AvatarEntry entry(UUID id) { return saved.get(id); }
    public int activeCount() { return active.size(); }
    public UUID worldId() { return saved.worldId(); }
    public void dirty() { saved.setDirty(); }
    public com.aurammods.paracraft.forge.control.ServerControl control() { return control; }
    public com.aurammods.paracraft.forge.control.ControlEndpoint endpoint() { return endpoint; }
    public void renewController(UUID owner) {
        for (AvatarEntry entry : saved.entries()) if (entry.authority.owner().equals(owner) && active.containsKey(entry.id)) {
            jobs.pause(entry.id); entry.authority.disconnect();
            if (!entry.paused && active.get(entry.id).isAlive() && !active.get(entry.id).deathCommitted()) entry.authority.connect();
        }
        saved.setDirty();
    }
    public void suspendController(UUID owner, String reason) {
        for (AvatarEntry entry : saved.entries()) if (entry.authority.owner().equals(owner) && active.containsKey(entry.id)) {
            pause(owner, entry.id, true); entry.control.addProperty("controllerStatus", reason);
        }
    }
    public com.aurammods.paracraft.forge.execution.JobManager jobs() { return jobs; }
    public boolean reflexBusy(UUID avatar) {
        SurvivalController reflex = survival.get(avatar); return reflex != null && reflex.busy();
    }
    public void pauseIdle(UUID avatar){var runtime=survival.get(avatar);if(runtime!=null)runtime.pauseRoutines();}
    public String survivalMode(UUID avatar){var runtime=survival.get(avatar);return runtime==null?"idle":runtime.mode();}
    public com.google.gson.JsonObject configureBehavior(UUID owner, UUID avatar, com.google.gson.JsonObject patch) {
        AvatarEntry entry = owned(owner, avatar);
        var state = behaviorState(entry);
        var next = com.aurammods.paracraft.core.BehaviorSettings.merge(state.getAsJsonObject("behavior"),patch);
        var runtime = survival.get(avatar);
        if (runtime != null) runtime.configure(next);
        else {
            boolean routineChanged=!state.getAsJsonObject("behavior").get("idle").equals(next.get("idle")); state.add("behavior",next);
            if(routineChanged || !state.has("idle")){var idle = new com.google.gson.JsonObject(); idle.addProperty("state","ready"); idle.addProperty("waypoint",0); idle.addProperty("waitRemaining",0); state.add("idle",idle);}
        }
        saved.setDirty(); return next.deepCopy();
    }
    public com.google.gson.JsonObject behavior(UUID owner, UUID avatar) { return behaviorState(owned(owner,avatar)).deepCopy(); }
    private com.google.gson.JsonObject behaviorState(AvatarEntry entry) {
        if (!entry.control.has("survival")) entry.control.add("survival",new com.google.gson.JsonObject());
        var state = entry.control.getAsJsonObject("survival");
        if (!state.has("behavior")) {
            String dimension = entry.physical.contains("Dimension") ? entry.physical.getString("Dimension") : "minecraft:overworld";
            state.add("behavior",com.aurammods.paracraft.core.BehaviorSettings.defaults(dimension)); saved.setDirty();
        }
        if(!state.getAsJsonObject("behavior").has("ambientIdle")){state.getAsJsonObject("behavior").addProperty("ambientIdle",true);saved.setDirty();}
        return state;
    }

    public void tickLifecycle() {
        for (var pair : List.copyOf(active.entrySet())) {
            AvatarEntry entry = saved.get(pair.getKey()); CompanionPlayer body = pair.getValue();
            if(body.hasDisconnected()){
                pause(entry.authority.owner(),entry.id,true);entry.control.addProperty("controllerStatus","Avatar disconnected: "+body.disconnectReason());
                survival.get(entry.id).close();checkpoint(entry);detach(entry.id);entry.execution=jobs.checkpoint(entry.id);jobs.unbind(entry.id);continue;
            }
            if(body.wonGame){respawn(entry,body,true);continue;}
            if (body.deathCommitted()) {
                if (!entry.control.has("lifecycle") || !entry.control.getAsJsonObject("lifecycle").get("state").getAsString().equals("dead")) {
                    survival.get(entry.id).onDeath(); jobs.cancelAll(entry.id, "Avatar died"); entry.authority.disconnect();
                    var death = new com.google.gson.JsonObject(); death.addProperty("state", "dead");
                    death.addProperty("dimension", body.serverLevel().dimension().location().toString());
                    death.add("position", com.aurammods.paracraft.forge.execution.JobJson.vector(body.position()));
                    death.addProperty("keepInventory", body.level().getGameRules().getBoolean(net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY));
                    entry.control.add("lifecycle", death); control.event(entry.id, "death", death); checkpoint(entry);
                }
                if (body.deathTime >= 20) respawn(entry, body);
            } else if (body.isAlive() && !body.isRemoved()) survival.get(entry.id).tick();
        }
    }

    private void respawn(AvatarEntry entry, CompanionPlayer old) {
        respawn(entry,old,false);
    }
    private void respawn(AvatarEntry entry, CompanionPlayer old,boolean endReturn) {
        boolean resume=endReturn && jobs.get(entry.id)!=null && jobs.get(entry.id).state()==com.aurammods.paracraft.core.JobState.RUNNING && !entry.paused;
        jobs.pause(entry.id);
        var spawnLevel = server.getLevel(old.getRespawnDimension()); var bed = old.getRespawnPosition();
        var location = spawnLevel != null && bed != null ? net.minecraft.world.entity.player.Player.findRespawnPositionAndUseSpawnBlock(
                spawnLevel, bed, old.getRespawnAngle(), old.isRespawnForced(), endReturn) : java.util.Optional.<Vec3>empty();
        ServerLevel destination = location.isPresent() ? spawnLevel : server.overworld();
        var execution = jobs.checkpoint(entry.id); jobs.unbind(entry.id); detach(entry.id,true);
        CompanionPlayer fresh = new CompanionPlayer(destination, old.getGameProfile());
        fresh.restoreFrom(old, endReturn); fresh.setId(old.getId()); fresh.setMainArm(old.getMainArm()); old.getTags().forEach(fresh::addTag);
        if (location.isPresent()) {
            Vec3 point = location.get(); float angle = old.getRespawnAngle(); var state = destination.getBlockState(bed);
            if (state.is(net.minecraft.tags.BlockTags.BEDS) || state.is(net.minecraft.world.level.block.Blocks.RESPAWN_ANCHOR)) {
                Vec3 away = Vec3.atBottomCenterOf(bed).subtract(point).normalize();
                angle = (float)net.minecraft.util.Mth.wrapDegrees(Math.toDegrees(Math.atan2(away.z, away.x)) - 90);
            }
            fresh.moveTo(point.x, point.y, point.z, angle, 0);
            fresh.setRespawnPosition(destination.dimension(), bed, old.getRespawnAngle(), old.isRespawnForced(), false);
        }
        while (!destination.noCollision(fresh) && fresh.getY() < destination.getMaxBuildHeight()) fresh.setPos(fresh.getX(), fresh.getY() + 1, fresh.getZ());
        if (server.isHardcore() && !endReturn) fresh.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
        if(!endReturn)entry.control.addProperty("lifeRevision", entry.control.has("lifeRevision") ? entry.control.get("lifeRevision").getAsLong() + 1 : 1);
        entry.execution = execution; attach(entry, fresh, true);
        var result = new com.google.gson.JsonObject(); result.addProperty("state", "alive"); result.addProperty("usedSpawnBlock", location.isPresent());
        result.addProperty("invalidSpawnBlock", bed != null && location.isEmpty()); result.addProperty("dimension", destination.dimension().location().toString());
        result.add("position", com.aurammods.paracraft.forge.execution.JobJson.vector(fresh.position()));
        entry.control.add("lifecycle", result);
        result.addProperty("endReturn",endReturn);net.minecraftforge.event.ForgeEventFactory.firePlayerRespawnEvent(fresh, endReturn);
        if(endReturn)net.minecraftforge.event.ForgeEventFactory.firePlayerChangedDimensionEvent(fresh,old.serverLevel().dimension(),destination.dimension());
        if(resume)jobs.resume(entry.id);
        control.event(entry.id, endReturn?"portal_return":"respawn", result); checkpoint(entry);
    }

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
        control.event(entry.id, "spawn", new com.google.gson.JsonObject());
        checkpoint(entry);
        return entry;
    }

    private void attach(AvatarEntry entry, CompanionPlayer player) {
        attach(entry, player, false);
    }
    private void attach(AvatarEntry entry, CompanionPlayer player, boolean respawn) {
        boolean registered=false;
        try {
        AvatarRoster.register(player);registered=true;
        server.getPlayerList().broadcastAll(ClientboundPlayerInfoUpdatePacket.createPlayerInitializing(List.of(player)));
        if (respawn) player.serverLevel().addRespawnedPlayer(player); else player.serverLevel().addNewPlayer(player);
        if (player.serverLevel().getEntity(player.getUUID()) != player) {
            throw new IllegalStateException("Avatar spawn was rejected by the world");
        }
        active.put(entry.id, player);
        survival.put(entry.id, new SurvivalController(this, entry, player));
        if (!entry.paused && player.isAlive() && !player.deathCommitted()) entry.authority.connect();
        jobs.restore(entry.id, entry.execution);
        if(!respawn)AvatarRoster.loggedIn(player);
        } catch(RuntimeException failure){
            if(active.remove(entry.id,player)){
                var runtime=survival.remove(entry.id);if(runtime!=null)runtime.close();jobs.unbind(entry.id);
            }
            player.retire();if(registered)AvatarRoster.rollback(player);
            if(player.serverLevel().getEntity(player.getUUID())==player)player.serverLevel().removePlayerImmediately(player,Entity.RemovalReason.DISCARDED);
            if(registered)AvatarRoster.failed(player,respawn || saved.get(entry.id)!=null);
            server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(player.getUUID())));
            throw failure;
        }
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
        endpoint.disconnect(owner);
        control.disconnect(owner);
        for (AvatarEntry entry : saved.entries()) {
            if (entry.authority.owner().equals(owner)) {
                jobs.pause(entry.id);
                entry.authority.disconnect();
                CompanionPlayer player = active.get(entry.id);
                SurvivalController reflex = survival.get(entry.id); if (reflex != null) reflex.close();
                // Closing first returns carried/crafting stacks before the snapshot is saved.
                if (player != null) player.closeContainer();
                checkpoint(entry);
                detach(entry.id);
                if(player!=null)entry.execution=jobs.checkpoint(entry.id);jobs.unbind(entry.id);
            }
        }
    }

    private void detach(UUID id) {
        detach(id,false);
    }
    private void detach(UUID id, boolean respawn) {
        SurvivalController reflex = survival.remove(id); if (reflex != null) reflex.close();
        CompanionPlayer player = active.remove(id);
        if (player == null) return;
        player.closeContainer();
        player.unRide();
        AvatarEntry entry=saved.get(id);Runnable capture=entry!=null && !respawn?player.observeSave(tag->{entry.physical=tag;saved.setDirty();}):()->{};
        try{
            if(respawn)AvatarRoster.removeForRespawn(player);
            else{player.disconnect();server.getPlayerList().remove(player);}
        }finally{capture.run();player.retire();}
        if(respawn)server.getPlayerList().broadcastAll(new ClientboundPlayerInfoRemovePacket(List.of(id)));
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
        boolean offline=player==null;
        if (offline) player = loadPlayer(entry);
        player.closeContainer();
        if(!offline)detach(id);
        player.getInventory().dropAll();
        AvatarRoster.saveOfflineMirror(player);
        entry.authority.disconnect();
        player.retire();
        saved.remove(id);
    }

    public void pause(UUID owner, UUID id, boolean paused) {
        AvatarEntry entry = owned(owner, id);
        if(!paused && !active.containsKey(id)){
            if(active.size()>=Paracraft.SERVER_LIMIT.get())throw new IllegalStateException("Active companion limit reached");
            attach(entry,loadPlayer(entry));
        }
        entry.paused = paused;
        if (!paused) entry.control.remove("controllerStatus");
        if (paused) jobs.pause(id); else jobs.resume(id);
        if (paused && survival.containsKey(id)) survival.get(id).pauseRoutines();
        entry.authority.disconnect();
        if (!paused && active.containsKey(id) && active.get(id).isAlive() && !active.get(id).deathCommitted()) entry.authority.connect();
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
