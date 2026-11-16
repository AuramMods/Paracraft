package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class JobManager {
    private final AvatarManager avatars;
    private final Map<UUID, GameJob> jobs = new HashMap<>();
    private final Map<UUID, Map<UUID, GameJob>> requests = new HashMap<>();
    private final Map<UUID, ArrayDeque<GameJob>> queues = new HashMap<>();
    private final Map<UUID, UUID> reflexHeld = new HashMap<>();
    public JobManager(AvatarManager avatars) { this.avatars = avatars; }
    public GameJob get(UUID avatar) { return jobs.get(avatar); }
    public boolean hasUnfinished(UUID avatar) { return requests.getOrDefault(avatar,Map.of()).values().stream().anyMatch(job -> !job.state().terminal()); }
    public GameJob request(UUID avatar, UUID id) { return requests.getOrDefault(avatar, Map.of()).get(id); }
    public List<GameJob> requests(UUID avatar) { return List.copyOf(requests.getOrDefault(avatar, Map.of()).values()); }
    public GameJob submit(UUID sender, UUID avatar, UUID session, GameJob job, boolean replace) {
        return submit(sender, avatar, session, job, replace ? "replace" : "reject");
    }
    public GameJob submitProgram(UUID sender, UUID avatar, UUID session, UUID id, JsonObject program, String mode) {
        return submitProgram(sender, avatar, session, id, program, mode, new JsonObject());
    }
    public GameJob submitProgram(UUID sender, UUID avatar, UUID session, UUID id, JsonObject program, String mode, JsonObject initialResults) {
        authorize(sender, avatar, session);
        GameJob existing = request(avatar, id); if (existing != null) return existing;
        var actor = avatars.actor(avatar);
        if (actor == null) throw new IllegalStateException("Avatar is not active");
        return submit(sender, avatar, session, new ProgramJob(id, actor, program, initialResults), mode);
    }
    public GameJob submit(UUID sender, UUID avatar, UUID session, GameJob job, String mode) {
        authorize(sender, avatar, session);
        if (!List.of("replace", "append", "interrupt", "reject").contains(mode)) throw new IllegalArgumentException("Unknown submission mode: " + mode);
        if (avatars.actor(avatar) != job.actor()) throw new IllegalArgumentException("Job belongs to a different body");
        Map<UUID, GameJob> seen = requests.computeIfAbsent(avatar, ignored -> new LinkedHashMap<>());
        if (seen.containsKey(job.id)) return seen.get(job.id);
        GameJob previous = jobs.get(avatar);
        if (previous != null && !previous.state().terminal()) {
            switch (mode) {
                case "reject" -> throw new IllegalStateException("An operation is already in progress");
                case "append" -> { job.enqueue(); queues.computeIfAbsent(avatar, ignored -> new ArrayDeque<>()).add(job); seen.put(job.id, job); return job; }
                case "interrupt" -> { previous.pause(); queues.remove(avatar); }
                case "replace" -> cancel(avatar, "Replaced explicitly");
                default -> throw new IllegalArgumentException("Unknown submission mode");
            }
        } else if (mode.equals("replace")) cancelQueue(avatar, "Replaced explicitly");
        jobs.put(avatar, job); seen.put(job.id, job); return job;
    }
    private void authorize(UUID sender, UUID avatar, UUID session) {
        var entry = avatars.owned(sender, avatar);
        if (!entry.authority.controls(sender, session) || entry.paused) throw new IllegalArgumentException("Control session is inactive or stale");
        var body = avatars.actor(avatar);
        if (body == null || !body.isAlive() || body.isRemoved() || body.deathCommitted() || body.isSpectator()) throw new IllegalStateException("Avatar is not an active living participant");
    }
    public GameJob resumeRequest(UUID sender, UUID avatar, UUID session, UUID id) {
        authorize(sender, avatar, session);
        GameJob target = request(avatar, id);
        if (target == null) throw new IllegalArgumentException("No saved request matches " + id);
        if (target.state().terminal()) return target;
        if(target instanceof ParkedJob parked){if(!parked.canBind())return parked;target=rebind(avatar,parked);}
        if(target.requiredDimension()!=avatars.actor(avatar).serverLevel().dimension() && !target.allowsDimensionChange())return target;
        if (target.actor() != avatars.actor(avatar)) throw new IllegalStateException("Saved operation has not been rebound to the current body");
        GameJob current = jobs.get(avatar);
        if (current != target && current != null && !current.state().terminal()) current.pause();
        queues.remove(avatar); target.resume(); jobs.put(avatar, target); return target;
    }
    public void tick() {
        for (var entry : List.copyOf(jobs.entrySet())) {
            UUID avatar = entry.getKey(); GameJob current = entry.getValue();
            if (avatars.reflexBusy(avatar)) { holdForReflex(avatar); continue; }
            UUID held = reflexHeld.remove(avatar); var owner = avatars.entry(avatar);
            if (current.id.equals(held) && current.state() == JobState.PAUSED && owner != null
                    && !owner.paused && owner.authority.session() != null) current.resume();
            current.tick();
            var queue = queues.get(avatar);
            if (current.state() == JobState.SUCCEEDED && queue != null && !queue.isEmpty() && owner != null && !owner.paused
                    && avatars.actor(avatar) != null && owner.authority.session() != null) {
                GameJob next = queue.peek();
                if(next instanceof ParkedJob parked){if(!parked.canBind())continue;next=rebind(avatar,parked);queue=queues.get(avatar);}
                if(next.requiredDimension()!=avatars.actor(avatar).serverLevel().dimension())continue;
                queue.remove();next.resume(); jobs.put(avatar, next);
            }
        }
    }
    public void holdForReflex(UUID avatar) {
        var job = jobs.get(avatar);
        if (job != null && job.state() == JobState.RUNNING) { job.pause(); reflexHeld.put(avatar, job.id); }
    }
    public void pause(UUID avatar) { reflexHeld.remove(avatar); var job = jobs.get(avatar); if (job != null) job.pause(); }
    public void pauseRequest(UUID avatar, UUID request) { reflexHeld.remove(avatar, request); var job = request(avatar, request); if (job != null) job.pause(); }
    public void resume(UUID avatar) {
        var job=jobs.get(avatar);if(job instanceof ParkedJob parked){if(!parked.canBind())return;job=rebind(avatar,parked);}
        if(job!=null && (job.requiredDimension()==avatars.actor(avatar).serverLevel().dimension() || job.allowsDimensionChange()))job.resume();
    }
    private GameJob rebind(UUID avatar,ParkedJob parked){
        GameJob bound=parked.bind();requests.get(avatar).put(bound.id,bound);if(jobs.get(avatar)==parked)jobs.put(avatar,bound);
        var queue=queues.get(avatar);if(queue!=null){var updated=new ArrayDeque<GameJob>();for(var job:queue)updated.add(job==parked?bound:job);queues.put(avatar,updated);}return bound;
    }
    private void cancelQueue(UUID avatar, String reason) {
        var queue = queues.remove(avatar); if (queue != null) queue.forEach(job -> job.cancel(reason));
    }
    public void cancel(UUID avatar, String reason) { reflexHeld.remove(avatar); var job = jobs.get(avatar); if (job != null) job.cancel(reason); cancelQueue(avatar, reason); }
    public void cancelAll(UUID avatar, String reason) {
        reflexHeld.remove(avatar);
        requests.getOrDefault(avatar, Map.of()).values().forEach(job -> job.cancel(reason));
        queues.remove(avatar);
    }
    public void unbind(UUID avatar) { pause(avatar); jobs.remove(avatar); requests.remove(avatar); queues.remove(avatar); }
    public void forget(UUID avatar) {
        reflexHeld.remove(avatar);
        for (GameJob job : requests.getOrDefault(avatar, Map.of()).values()) job.cancel("Avatar destroyed");
        jobs.remove(avatar); requests.remove(avatar); queues.remove(avatar);
    }
    public JsonObject checkpoint(UUID avatar) {
        JsonObject value = new JsonObject(); value.addProperty("schema", 1);
        if (jobs.containsKey(avatar)) value.addProperty("current", jobs.get(avatar).id.toString());
        JsonArray history = new JsonArray(); requests.getOrDefault(avatar, Map.of()).values().forEach(job -> history.add(job.checkpoint()));
        JsonArray queue = new JsonArray(); queues.getOrDefault(avatar, new ArrayDeque<>()).forEach(job -> queue.add(job.id.toString()));
        value.add("requests", history); value.add("queue", queue); return value;
    }
    public void restore(UUID avatar, JsonObject saved) {
        if (!saved.has("schema")) return;
        if (saved.get("schema").getAsInt() != 1) throw new IllegalArgumentException("Unsupported execution checkpoint");
        var actor = avatars.actor(avatar); if (actor == null) throw new IllegalStateException("No body for execution restoration");
        Map<UUID, GameJob> history = new LinkedHashMap<>();
        for (var e : saved.getAsJsonArray("requests")) {
            JsonObject data = e.getAsJsonObject(); GameJob job;
            try { job = JobCodec.restore(actor, data); }
            catch (IllegalArgumentException | IllegalStateException failure) {
                JsonObject failed = data.deepCopy(); failed.addProperty("state", JobState.FAILED.name()); failed.addProperty("phase", "failed");
                failed.addProperty("error", "Could not restore operation: " + failure.getMessage()); job = new RecordedJob(actor, failed);
            }
            if (history.put(job.id, job) != null) throw new IllegalArgumentException("Duplicate saved request ID");
        }
        requests.put(avatar, history);
        if (saved.has("current")) {
            GameJob current = history.get(UUID.fromString(saved.get("current").getAsString()));
            if (current == null) throw new IllegalArgumentException("Missing current operation checkpoint");
            jobs.put(avatar, current);
        }
        ArrayDeque<GameJob> queue = new ArrayDeque<>();
        for (var e : saved.getAsJsonArray("queue")) {
            GameJob job = history.get(UUID.fromString(e.getAsString()));
            if (job == null) throw new IllegalArgumentException("Missing queued operation checkpoint");
            if (!job.state().terminal()) { job.enqueue(); queue.add(job); }
        }
        queues.put(avatar, queue);
    }
}
