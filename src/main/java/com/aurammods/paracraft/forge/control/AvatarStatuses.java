package com.aurammods.paracraft.forge.control;

import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.aurammods.paracraft.forge.network.ForgeNetwork;
import net.minecraft.server.level.ServerPlayer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Public presentation only; the owner keeps task descriptions and memory locally. */
public final class AvatarStatuses {
    private final AvatarManager avatars;
    private final Map<UUID, String> sent = new HashMap<>();
    public AvatarStatuses(AvatarManager avatars) { this.avatars = avatars; }
    public String label(UUID avatar) {
        var entry = avatars.entry(avatar); if (entry == null) return "";
        var job = avatars.jobs().get(avatar);
        String operation = job != null && job.state() == JobState.RUNNING ? (job.type.equals("program") ? job.phase() : job.type) : null;
        return avatars.endpoint().activity(avatar).label(operation, entry.paused, entry.authority.session() != null);
    }
    public void tick() {
        sent.keySet().removeIf(id -> avatars.actor(id) == null);
        for (var entry : avatars.entries()) {
            var actor = avatars.actor(entry.id); if (actor == null) continue;
            String label = label(entry.id);
            if (!label.equals(sent.put(entry.id, label))) ForgeNetwork.status(actor, label);
        }
    }
    public void tracking(ServerPlayer viewer, UUID avatar) { ForgeNetwork.status(viewer, avatar, label(avatar)); }
}
