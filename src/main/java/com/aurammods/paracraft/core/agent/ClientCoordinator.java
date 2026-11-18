package com.aurammods.paracraft.core.agent;

import com.aurammods.paracraft.core.protocol.WireCodec;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;

/** Client-thread runtime shared by Forge and headless transport QA. No credentials or Minecraft types. */
public final class ClientCoordinator implements AutoCloseable {
    public record View(UUID id, String name, String status, JsonObject snapshot, AgentState memory, boolean freshMemory, long decisions, long planningDecisions) { }
    public record Failure(UUID avatar,String name,String error) { }
    private record Pending(CompletableFuture<JsonObject> future, long started, UUID attempt) { }
    private final UUID owner, client = UUID.randomUUID();
    private final AgentStore store;
    private final AgentClock clock;
    private final Consumer<JsonObject> send;
    private final Function<AgentBinding, InferenceProvider> providers;
    private final java.util.function.Supplier<String> providerStatus;
    private final Consumer<Failure> failureSink;
    private final Map<UUID, Companion> companions = new LinkedHashMap<>();
    private final Map<UUID, Pending> pending = new HashMap<>();
    private final Map<UUID, String> failures = new HashMap<>();
    private final Map<UUID, JsonObject> failedSnapshots = new LinkedHashMap<>();
    private UUID world, polling;
    private long pollStarted, nextPoll, sequence, acceptedSequence = -1, acceptedRevision = -1;
    private boolean closed, suspended;
    private com.google.gson.JsonArray reportedActivities;
    private String status = "Connecting — SIMULATED provider; account disconnected";
    private final class Companion {
        final AgentBinding binding; final AgentState memory; final HarnessLoop loop; final boolean fresh;
        JsonObject snapshot; boolean faultReported;
        Companion(AgentBinding binding, JsonObject snapshot) throws IOException {
            this.binding = binding; this.snapshot = snapshot.deepCopy();
            var loaded = store.load(binding); memory = loaded.orElseGet(AgentState::new); fresh = loaded.isEmpty();
            if (fresh && snapshot.has("programs")) for (var item : snapshot.getAsJsonArray("programs")) {
                JsonObject program = item.getAsJsonObject(); String state = program.get("state").getAsString();
                if (List.of("SUCCEEDED", "FAILED", "CANCELLED").contains(state)) continue;
                JsonObject task = new JsonObject(); String id = program.get("id").getAsString();
                task.addProperty("id", "recovered:" + id); task.addProperty("programId", id); task.addProperty("status", "INACTIVE");
                task.addProperty("goal", "Recover saved server operation " + id + "; prior local intention is unavailable");
                memory.mutate("task_upsert", task);
            }
            loop = new HarnessLoop(binding, memory, providers.apply(binding), clock,
                    (id, tool, args, trigger) -> {
                        JsonObject request = envelope("effect", id); request.addProperty("avatar", binding.avatar().toString());
                        request.add("session", this.snapshot.get("session")); request.addProperty("tool", tool);
                        request.add("arguments", args); request.addProperty("trigger", trigger);
                        return submit(id, request);
                    }, this::save, output -> agentOutput(this, output));
            save(); loop.accept(snapshot);
        }
        void save() { try { store.save(binding, memory); } catch (IOException failure) { throw new UncheckedIOException(failure); } }
    }
    public ClientCoordinator(UUID owner, AgentStore store, AgentClock clock, Consumer<JsonObject> send) {
        this(owner, store, clock, send, binding -> new SimulatedProvider());
    }
    public ClientCoordinator(UUID owner, AgentStore store, AgentClock clock, Consumer<JsonObject> send, Function<AgentBinding, InferenceProvider> providers) {
        this(owner,store,clock,send,providers,()->"SIMULATED provider; account disconnected");
    }
    public ClientCoordinator(UUID owner, AgentStore store, AgentClock clock, Consumer<JsonObject> send, Function<AgentBinding, InferenceProvider> providers,java.util.function.Supplier<String> providerStatus) {
        this(owner,store,clock,send,providers,providerStatus,ignored->{});
    }
    public ClientCoordinator(UUID owner, AgentStore store, AgentClock clock, Consumer<JsonObject> send, Function<AgentBinding, InferenceProvider> providers,java.util.function.Supplier<String> providerStatus,Consumer<Failure> failureSink) {
        this.owner = owner; this.store = store; this.clock = clock; this.send = send; this.providers = providers;this.providerStatus=providerStatus;this.failureSink=failureSink;status="Connecting — "+providerStatus.get();nextPoll = clock.nanoTime();
    }
    public void tick() {
        if (closed || suspended) return;
        try {
            long now = clock.nanoTime();
            if (polling != null && now - pollStarted >= 30_000_000_000L) throw new IllegalStateException("Server snapshot timed out; reconnect the harness");
            for (UUID id : List.copyOf(pending.keySet())) {
                Pending request = pending.get(id);
                if (now - request.started >= 30_000_000_000L) {
                    pending.remove(id); request.future.completeExceptionally(new IllegalStateException("Server tool acknowledgment timed out"));
                }
            }
            for (Companion companion : List.copyOf(companions.values())) {
                try { companion.loop.tick(); }
                catch (RuntimeException failure) { failures.put(companion.binding.avatar(), failure.toString()); }
                if ((!companion.loop.lastError().isEmpty() || failures.containsKey(companion.binding.avatar())) && !companion.faultReported) {
                    companion.faultReported = true; reportFailure(companion.binding.avatar(),companion.snapshot.get("name").getAsString(),failures.getOrDefault(companion.binding.avatar(),companion.loop.lastError()));pause(companion.binding.avatar(), true);
                }
            }
            var activities = new com.google.gson.JsonArray();
            for (Companion c : companions.values()) {
                var activity = AgentActivity.from(c.memory, c.loop.status()).json(); activity.addProperty("avatar", c.binding.avatar().toString()); activities.add(activity);
            }
            // Report inference transitions on the next client tick instead of waiting for a heartbeat.
            // A pending poll coalesces changes; its response releases the next report.
            if (polling == null && (now - nextPoll >= 0 || !activities.equals(reportedActivities))) {
                polling = UUID.randomUUID(); pollStarted = now; nextPoll = now + 2_000_000_000L;
                JsonObject request = envelope("poll", polling); request.addProperty("sequence", ++sequence);
                reportedActivities = activities.deepCopy(); request.add("activities", activities); send.accept(request);
            }
        } catch (RuntimeException failure) { failLink(failure.toString()); }
    }
    private void agentOutput(Companion companion, JsonObject output) {
        JsonObject snapshot = companion.snapshot;
        if (!snapshot.has("debugAgentOutput") || !snapshot.get("debugAgentOutput").getAsBoolean() || !snapshot.has("session")) return;
        UUID id = UUID.randomUUID(); var chunks = AgentOutput.chunks(output.toString());
        for (int i = 0; i < chunks.size(); i++) {
            var request = envelope("agent_output", UUID.randomUUID()); request.addProperty("avatar", companion.binding.avatar().toString());
            request.add("session", snapshot.get("session").deepCopy()); request.addProperty("outputId", id.toString());
            request.addProperty("part", i + 1); request.addProperty("parts", chunks.size()); request.addProperty("data", chunks.get(i)); send.accept(request);
        }
    }
    public void receive(JsonObject message) {
        if (closed || suspended || !message.has("client") || !client.toString().equals(message.get("client").getAsString())) return;
        try {
            if (message.get("protocol").getAsInt() != WireCodec.VERSION) throw new IllegalArgumentException("Unsupported Paracraft protocol");
            String kind = message.get("kind").getAsString(); UUID id = UUID.fromString(message.get("requestId").getAsString());
            if (kind.equals("snapshot")) {
                if (!id.equals(polling)) return;
                polling = null;
                if (!message.get("ok").getAsBoolean()) throw new IllegalStateException(message.get("error").getAsString());
                long revision = message.get("sequence").getAsLong(); if (revision <= acceptedSequence) return;
                applySnapshots(message); acceptedSequence = revision; status = "Connected — "+providerStatus.get();
            } else if (kind.equals("reply")) {
                Pending request = pending.get(id);
                if (request == null || !message.has("attempt") || !request.attempt.toString().equals(message.get("attempt").getAsString())) return;
                pending.remove(id);
                if (message.has("snapshots")) applySnapshots(message);
                request.future.complete(message.deepCopy());
            }
        } catch (RuntimeException failure) { failLink(failure.toString()); }
    }
    private void applySnapshots(JsonObject message) {
        if (!owner.toString().equals(message.get("ownerId").getAsString())) throw new IllegalArgumentException("Snapshot owner does not match this client");
        UUID incomingWorld = UUID.fromString(message.get("worldId").getAsString());
        if (world != null && !world.equals(incomingWorld)) throw new IllegalArgumentException("World binding changed on the same connection");
        long revision = message.get("revision").getAsLong(); if (revision <= acceptedRevision) return;
        acceptedRevision = revision;
        world = incomingWorld; var seen = new java.util.HashSet<UUID>();
        for (var item : message.getAsJsonArray("snapshots")) {
            JsonObject snapshot = item.getAsJsonObject(); UUID id = UUID.fromString(snapshot.get("avatarId").getAsString()); seen.add(id);
            if (failedSnapshots.containsKey(id)) { failedSnapshots.put(id, snapshot.deepCopy()); continue; }
            try {
                Companion companion = companions.get(id);
                if (companion == null) { companion = new Companion(new AgentBinding(world, owner, id), snapshot); companions.put(id, companion); }
                else {
                    boolean ownerResumed = companion.snapshot.get("paused").getAsBoolean() && !snapshot.get("paused").getAsBoolean();
                    companion.snapshot = snapshot.deepCopy(); companion.loop.accept(snapshot);
                    if (ownerResumed) { companion.loop.setPaused(false); companion.faultReported = false; failures.remove(id); }
                }
            } catch (IOException | RuntimeException failure) {
                failures.put(id, failure.toString()); failedSnapshots.put(id, snapshot.deepCopy());
                reportFailure(id,snapshot.get("name").getAsString(),failure.toString());
                manage(id, "pause", new JsonObject()); // Keep damaged memory intact and stop that avatar's execution.
            }
        }
        for (UUID id : List.copyOf(companions.keySet())) if (!seen.contains(id)) { companions.remove(id).loop.close(); failures.remove(id); }
        failedSnapshots.keySet().removeIf(id -> !seen.contains(id));
    }
    public CompletableFuture<JsonObject> pause(UUID avatar, boolean pause) {
        Companion companion = companions.get(avatar);
        if (pause && companion != null) {
            try { companion.loop.setPaused(true); } catch (RuntimeException failure) { failures.put(avatar, failure.toString()); }
        }
        var future = manage(avatar, pause ? "pause" : "resume", new JsonObject());
        future.thenAccept(reply -> {
            if (reply.get("ok").getAsBoolean() && !pause && companion != null) {
                companion.loop.setPaused(false); companion.faultReported = false; failures.remove(avatar);
            }
        });
        return future;
    }
    public CompletableFuture<JsonObject> spawn(String name) {
        JsonObject args = new JsonObject(); args.addProperty("name", name); return manage(null, "spawn", args);
    }
    public CompletableFuture<JsonObject> destroy(UUID avatar) { return manage(avatar, "destroy", new JsonObject()); }
    private CompletableFuture<JsonObject> manage(UUID avatar, String action, JsonObject args) {
        UUID id = UUID.randomUUID(); JsonObject request = envelope("control", id); request.addProperty("action", action);
        if (avatar != null) request.addProperty("avatar", avatar.toString()); request.add("arguments", args); return submit(id, request);
    }
    private CompletableFuture<JsonObject> submit(UUID id, JsonObject request) {
        if (closed || suspended) return CompletableFuture.failedFuture(new IllegalStateException("Client harness is disconnected"));
        Pending existing = pending.get(id); if (existing != null && !existing.future.isDone()) return existing.future;
        CompletableFuture<JsonObject> future = new CompletableFuture<>(); UUID attempt = UUID.randomUUID();
        request.addProperty("attempt", attempt.toString()); pending.put(id, new Pending(future, clock.nanoTime(), attempt));
        try { send.accept(request); } catch (RuntimeException failure) { pending.remove(id); future.completeExceptionally(failure); }
        return future;
    }
    private JsonObject envelope(String kind, UUID id) {
        JsonObject message = new JsonObject(); message.addProperty("protocol", WireCodec.VERSION); message.addProperty("client", client.toString());
        message.addProperty("requestId", id.toString()); message.addProperty("kind", kind); return message;
    }
    private void failLink(String error) {
        if (suspended || closed) return;
        status = "Paused: " + error; suspended = true;
        reportFailure(null,"Client harness",error);
        for (Companion companion : companions.values()) try { companion.loop.close(); } catch (RuntimeException failure) { failures.put(companion.binding.avatar(), failure.toString()); }
        try { send.accept(envelope("release", UUID.randomUUID())); } catch (RuntimeException ignored) { /* Server lease expiry is the independent fallback. */ }
        failPending(error);
    }
    private void reportFailure(UUID avatar,String name,String error){try{failureSink.accept(new Failure(avatar,name,error));}catch(RuntimeException ignored){/* Diagnostics must not prevent pausing failed execution. */}}
    private void failPending(String reason) {
        var waiting = new ArrayList<>(pending.values()); pending.clear();
        waiting.forEach(p -> p.future.completeExceptionally(new IllegalStateException(reason)));
    }
    public List<View> views() {
        List<View> result = new ArrayList<>();
        for (Companion c : companions.values()) result.add(new View(c.binding.avatar(), c.snapshot.get("name").getAsString(),
                failures.getOrDefault(c.binding.avatar(), c.loop.status()), c.snapshot.deepCopy(), c.memory, c.fresh, c.loop.calls(), c.loop.planningCalls()));
        failedSnapshots.forEach((id, snapshot) -> result.add(new View(id, snapshot.get("name").getAsString(), failures.get(id), snapshot.deepCopy(), null, false, 0, 0)));
        return List.copyOf(result);
    }
    public void routes(UUID avatar, String light, String lightEffort, String planning, String planningEffort) {
        new InferenceProvider.ModelRoute(light, lightEffort); new InferenceProvider.ModelRoute(planning, planningEffort);
        Companion c = companions.get(avatar); if (c == null) throw new IllegalArgumentException("No local companion memory");
        c.memory.lightModel = light; c.memory.lightEffort = lightEffort; c.memory.planningModel = planning; c.memory.planningEffort = planningEffort; c.save();
    }
    public String status() { return status; }
    public void transportFailed(String reason) { failLink(reason); }
    public UUID clientId() { return client; }
    @Override public void close() {
        if (closed) return; closed = true;
        for (Companion c : companions.values()) try { c.loop.close(); } catch (RuntimeException failure) { failures.put(c.binding.avatar(), failure.toString()); }
        failPending("Client disconnected"); polling = null;
    }
}
