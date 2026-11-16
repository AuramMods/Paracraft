package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.AvatarManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class JobManager {
    private final AvatarManager avatars;
    private final Map<UUID, GameJob> jobs = new HashMap<>();
    private final Map<UUID, Map<UUID, GameJob>> requests = new HashMap<>();
    public JobManager(AvatarManager avatars) { this.avatars = avatars; }
    public GameJob get(UUID avatar) { return jobs.get(avatar); }
    public GameJob submit(UUID sender, UUID avatar, UUID session, GameJob job, boolean replace) {
        var entry = avatars.owned(sender, avatar);
        if (!entry.authority.controls(sender, session) || entry.paused)
            throw new IllegalArgumentException("Control session is inactive or stale");
        if (avatars.actor(avatar) != job.actor()) throw new IllegalArgumentException("Job belongs to a different body");
        Map<UUID, GameJob> seen = requests.computeIfAbsent(avatar, ignored -> new HashMap<>());
        if (seen.containsKey(job.id)) return seen.get(job.id);
        GameJob previous = jobs.get(avatar);
        if (previous != null && previous.id.equals(job.id)) return previous;
        if (previous != null && !previous.state().terminal()) {
            if (!replace) throw new IllegalStateException("An operation is already in progress");
            previous.cancel("Replaced explicitly");
        }
        jobs.put(avatar, job); seen.put(job.id, job); return job;
    }
    public void tick() { for (GameJob job : java.util.List.copyOf(jobs.values())) job.tick(); }
    public void pause(UUID avatar) { var job = jobs.get(avatar); if (job != null) job.pause(); }
    public void resume(UUID avatar) { var job = jobs.get(avatar); if (job != null) job.resume(); }
    public void cancel(UUID avatar, String reason) { var job = jobs.get(avatar); if (job != null) job.cancel(reason); }
    public void forget(UUID avatar) { cancel(avatar, "Avatar destroyed"); jobs.remove(avatar); requests.remove(avatar); }
}
