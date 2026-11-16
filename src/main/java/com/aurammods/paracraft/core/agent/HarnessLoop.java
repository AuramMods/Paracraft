package com.aurammods.paracraft.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Tick on the owner client thread. Provider callbacks never mutate game/state objects. */
public final class HarnessLoop implements AutoCloseable {
    /** The transport must deduplicate requestId across reconnects and verify its actual sender/lease. */
    public interface Sink {
        CompletableFuture<JsonObject> call(UUID requestId, String tool, JsonObject arguments, long triggerEvent);
    }
    public static final long INTERVAL_NANOS = 5_000_000_000L;
    private final AgentBinding binding;
    private final InferenceProvider provider;
    private final AgentClock clock;
    private final Sink sink;
    private final Runnable save;
    public final AgentState state;
    private JsonObject latest;
    private String session = "", lastError = "";
    private boolean active, paused, closed;
    private CompletableFuture<InferenceProvider.Decision> inference;
    private CompletableFuture<JsonObject> effect;
    private long nextStart, calls, planningCalls;

    public HarnessLoop(AgentBinding binding, AgentState state, InferenceProvider provider, AgentClock clock, Sink sink, Runnable save) {
        this.binding = binding; this.state = state; this.provider = provider; this.clock = clock; this.sink = sink; this.save = save;
        state.validateTurn();
        paused = state.paused; lastError = state.lastError;
        long remainingMillis = state.lastInferenceMillis == 0 ? 0 : Math.min(5000, Math.max(0, 5000 - (clock.epochMillis() - state.lastInferenceMillis)));
        nextStart = clock.nanoTime() + remainingMillis * 1_000_000L;
    }
    public void accept(JsonObject snapshot) {
        if (closed) return;
        if (!binding.world().toString().equals(AgentState.required(snapshot, "worldId"))
                || !binding.owner().toString().equals(AgentState.required(snapshot, "ownerId"))
                || !binding.avatar().toString().equals(AgentState.required(snapshot, "avatarId")))
            throw new IllegalArgumentException("Snapshot belongs to another companion binding");
        String nextSession = snapshot.has("session") && !snapshot.get("session").isJsonNull() ? snapshot.get("session").getAsString() : "";
        boolean nextActive = !nextSession.isEmpty() && !snapshot.get("paused").getAsBoolean();
        if (!session.equals(nextSession) || !nextActive) abortFutures();
        long life = snapshot.has("lifeRevision") ? snapshot.get("lifeRevision").getAsLong() : 0;
        if (state.lifeRevision != life) {
            if (state.lifeRevision >= 0) {
                abortFutures();
                if (state.turn != null) {
                    boolean pending = !state.turn.has("calls") || state.turn.get("cursor").getAsInt() < state.turn.getAsJsonArray("calls").size();
                    JsonObject original = state.turn.getAsJsonObject("trigger");
                    if (pending && original.get("type").getAsString().equals("chat") && state.inbox.stream().noneMatch(e -> e.get("id").equals(original.get("id")))) state.enqueue(original);
                    state.turn = null;
                }
                state.wakeEvent = ""; state.wakeAtMillis = 0;
                state.history("lifecycle", "Respawn discarded the previous body's unfinished decision; tasks and original pending chat remain for fresh planning.");
            }
            state.lifeRevision = life; save.run();
        }
        session = nextSession; latest = snapshot.deepCopy(); active = nextActive;
        if (!snapshot.has("events")) return;
        var incoming = new ArrayList<JsonObject>();
        for (var item : snapshot.getAsJsonArray("events")) incoming.add(item.getAsJsonObject());
        incoming.sort(Comparator.comparingLong(e -> e.get("id").getAsLong()));
        boolean changed = false;
        for (JsonObject event : incoming) {
            long id = event.get("id").getAsLong(); if (id <= state.eventCursor) continue;
            if (!event.has("wake") || event.get("wake").getAsBoolean()) state.enqueue(event);
            state.eventCursor = id; state.history("event", event.toString()); changed = true;
        }
        if (changed) save.run();
    }
    public void tick() {
        if (closed || paused || !active || latest == null) return;
        try {
            if (effect != null) {
                if (!effect.isDone()) return;
                JsonObject reply = effect.join(); effect = null;
                if (!reply.get("ok").getAsBoolean()) throw new IllegalStateException(reply.get("error").getAsString());
                String tool = pendingCall().tool();
                if (tool.equals("observe")) {
                    state.turn.getAsJsonObject("observations").add("inventory", reply.getAsJsonObject("value").deepCopy());
                    continuePlanning();
                } else {
                    if (Set.of("program", "resume_program").contains(tool) && reply.has("value")) mergeProgram(reply.getAsJsonObject("value"));
                    advanceCall();
                }
                save.run();
            }
            if (inference != null) {
                if (!inference.isDone()) return;
                var decision = inference.join(); inference = null;
                ToolPolicy.validate(route(), decision); // Reject the whole reply before any effects.
                JsonArray calls = new JsonArray();
                for (var call : decision.calls()) {
                    JsonObject encoded = new JsonObject(); encoded.addProperty("tool", call.tool()); encoded.add("arguments", call.arguments()); calls.add(encoded);
                }
                state.turn.add("calls", calls); state.turn.addProperty("cursor", 0);
                state.history("decision", route() + " " + decision.calls().stream().map(InferenceProvider.Call::tool).toList()); save.run();
            }
            while (state.turn != null && state.turn.has("calls")) {
                if (state.turn.get("cursor").getAsInt() >= state.turn.getAsJsonArray("calls").size()) {
                    state.turn = null; save.run(); break;
                }
                var call = pendingCall();
                switch (call.tool()) {
                    case "think_harder" -> continuePlanning();
                    case "task_upsert", "task_remove", "remember", "forget", "personality" -> { state.mutate(call.tool(), call.arguments()); advanceCall(); }
                    case "wait" -> {
                        JsonObject args = call.arguments(); state.wakeEvent = args.has("event") ? AgentState.required(args, "event") : "any";
                        long seconds = args.has("seconds") ? args.get("seconds").getAsBigDecimal().longValueExact() : -1;
                        if (args.has("seconds") && seconds < 0) throw new IllegalArgumentException("Wait duration must be nonnegative");
                        state.wakeAtMillis = seconds == -1 ? Long.MAX_VALUE : Math.addExact(clock.epochMillis(), Math.multiplyExact(seconds, 1000));
                        advanceCall();
                    }
                    default -> {
                        effect = sink.call(requestId(), call.tool(), call.arguments(), trigger().get("id").getAsLong());
                        return;
                    }
                }
                save.run();
            }
            if (clock.nanoTime() - nextStart < 0) return;
            if (state.turn == null) {
                if (!state.wakeEvent.isEmpty()) {
                    boolean timer = state.wakeAtMillis != Long.MAX_VALUE && state.wakeAtMillis <= clock.epochMillis();
                    boolean wake = timer || state.inbox.stream().anyMatch(e ->
                            Set.of("chat", "damage", "pickup").contains(e.get("type").getAsString())
                                    || state.wakeEvent.equals("any") || state.wakeEvent.equals(e.get("type").getAsString()));
                    if (!wake) return;
                    state.wakeEvent = ""; state.wakeAtMillis = 0;
                    if (timer && state.inbox.isEmpty()) {
                        JsonObject event = new JsonObject(); event.addProperty("id", 0); event.addProperty("type", "timer"); state.enqueue(event);
                    }
                }
                if (state.inbox.isEmpty() && !state.hasWork()) return;
                JsonObject event;
                if (!state.inbox.isEmpty()) event = state.inbox.remove(0);
                else { event = new JsonObject(); event.addProperty("id", 0); event.addProperty("type", "tasks"); }
                state.turn = new JsonObject(); state.turn.addProperty("id", UUID.randomUUID().toString());
                state.turn.add("trigger", event); state.turn.addProperty("route", InferenceProvider.Route.LIGHT.name());
                state.turn.addProperty("round", 0); state.turn.add("observations", new JsonObject()); state.cycleCounter++;
            }
            JsonObject frame = latest.deepCopy(); frame.add("agent", state.context());
            var route = route();
            var model = route == InferenceProvider.Route.LIGHT ? new InferenceProvider.ModelRoute(state.lightModel, state.lightEffort)
                    : new InferenceProvider.ModelRoute(state.planningModel, state.planningEffort);
            nextStart = clock.nanoTime() + INTERVAL_NANOS; state.lastInferenceMillis = clock.epochMillis();
            save.run(); // Preserve the request before inference can begin.
            calls++; if (route == InferenceProvider.Route.PLANNING) planningCalls++;
            inference = provider.infer(new InferenceProvider.Request(route, model, frame, trigger(), state.turn.getAsJsonObject("observations")));
        } catch (RuntimeException failure) {
            lastError = failure.getCause() == null ? failure.toString() : failure.getCause().toString();
            state.history("error", lastError); abortFutures(); paused = true; state.paused = true; state.lastError = lastError; save.run();
        }
    }
    private InferenceProvider.Route route() { return InferenceProvider.Route.valueOf(state.turn.get("route").getAsString()); }
    private JsonObject trigger() { return state.turn.getAsJsonObject("trigger"); }
    private InferenceProvider.Call pendingCall() {
        JsonObject call = state.turn.getAsJsonArray("calls").get(state.turn.get("cursor").getAsInt()).getAsJsonObject();
        return new InferenceProvider.Call(call.get("tool").getAsString(), call.getAsJsonObject("arguments"));
    }
    private UUID requestId() {
        String key = state.turn.get("id").getAsString() + ":" + state.turn.get("round").getAsInt() + ":" + state.turn.get("cursor").getAsInt();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
    private void advanceCall() { state.turn.addProperty("cursor", state.turn.get("cursor").getAsInt() + 1); }
    private void continuePlanning() {
        state.turn.addProperty("route", InferenceProvider.Route.PLANNING.name());
        state.turn.addProperty("round", state.turn.get("round").getAsInt() + 1); state.turn.remove("calls"); state.turn.remove("cursor");
    }
    private void mergeProgram(JsonObject program) {
        var list = latest.has("programs") ? latest.getAsJsonArray("programs") : new JsonArray();
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).getAsJsonObject().get("id").equals(program.get("id"))) list.remove(i);
        list.add(program.deepCopy()); latest.add("programs", list);
    }
    private void abortFutures() {
        if (inference != null) inference.cancel(true); if (effect != null) effect.cancel(true);
        inference = null; effect = null; // Durable turn/cursor intentionally remain for retry.
    }
    public String status() {
        return !lastError.isEmpty() ? "Paused: " + lastError : paused ? "Paused" : !active ? "Disconnected / server paused"
                : inference != null ? "Thinking: " + route() : !state.wakeEvent.isEmpty() ? "Waiting: " + state.wakeEvent
                : state.turn != null || !state.inbox.isEmpty() || state.hasWork() ? "Working" : "Idle";
    }
    public long calls() { return calls; }
    public long planningCalls() { return planningCalls; }
    public String providerLabel() { return provider.label(); }
    public boolean simulated() { return provider.simulated(); }
    public String lastError() { return lastError; }
    public void setPaused(boolean value) {
        paused = value; state.paused = value;
        if (value) abortFutures(); else { lastError = ""; state.lastError = ""; }
        save.run();
    }
    @Override public void close() { abortFutures(); closed = true; active = false; save.run(); }
}
