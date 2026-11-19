package com.aurammods.paracraft.core.agent;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.aurammods.paracraft.core.agent.HarnessFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentPersistenceTest {
    @TempDir Path temp;
    @Test void restartDuringInferenceReconstructsThePlanningRequestAndPreservesCooldown() throws Exception {
        Clock clock = new Clock(); Provider provider = new Provider(clock); AgentState state = new AgentState();
        state.mutate("remember", json("{\"key\":\"base\",\"text\":\"use the south door\"}"));
        state.mutate("personality", json("{\"text\":\"Brief and cheerful\"}"));
        CompletableFuture<InferenceProvider.Decision> pending = new CompletableFuture<>();
        provider.answer = request -> request.route() == InferenceProvider.Route.LIGHT
                ? CompletableFuture.completedFuture(acknowledge("I’ll do the requested work.")) : pending;
        try (HarnessLoop loop = new HarnessLoop(BINDING, state, provider, clock, new Sink(), () -> {})) {
            loop.accept(frame(chat(17, "Josh, collect diamonds"))); pump(loop); clock.advance(5_000); pump(loop);
        }
        assertTrue(pending.isCancelled());
        AgentStore store = new AgentStore(temp); store.save(BINDING, state);
        AgentState restored = store.load(BINDING).orElseThrow(); Provider freshProvider = new Provider(clock);
        freshProvider.answer=request->CompletableFuture.completedFuture(decision("chat",json("{\"message\":\"I need the destination before I can continue.\"}")));
        try (HarnessLoop fresh = new HarnessLoop(BINDING, restored, freshProvider, clock, new Sink(), () -> {})) {
            JsonObject snapshot = frame(chat(17, "Josh, collect diamonds")); snapshot.addProperty("session", "new-lease"); snapshot.addProperty("health", 8);
            fresh.accept(snapshot); clock.advance(4_999); pump(fresh); assertTrue(freshProvider.requests.isEmpty());
            clock.advance(1); pump(fresh);
            assertEquals(1, freshProvider.requests.size()); var request = freshProvider.requests.get(0);
            assertEquals(InferenceProvider.Route.PLANNING, request.route()); assertEquals(17, request.trigger().get("id").getAsInt());
            assertEquals(8, request.snapshot().get("health").getAsInt());
            assertEquals("use the south door", request.snapshot().getAsJsonObject("agent").getAsJsonObject("memory").get("base").getAsString());
            assertEquals("Brief and cheerful", request.snapshot().getAsJsonObject("agent").get("personality").getAsString());
            clock.advance(10_000); pump(fresh); assertEquals(1, freshProvider.requests.size());
        }
    }
    @Test void unacknowledgedEffectReplaysTheSameRequestIdWithoutRepeatingAcceptedSideEffects() {
        Clock clock = new Clock(); Provider provider = new Provider(clock); Sink sink = new Sink(); AgentState state = new AgentState();
        provider.answer = request -> CompletableFuture.completedFuture(request.route() == InferenceProvider.Route.LIGHT
                ? acknowledge("I’ll do the requested work.") : new InferenceProvider.Decision(List.of(
                    new InferenceProvider.Call("chat", json("{\"message\":\"once\"}")),
                    new InferenceProvider.Call("remember", json("{\"key\":\"ack\",\"text\":\"done\"}")))));
        var accepted = new HashMap<UUID, JsonObject>();
        sink.reply = sent -> {
            JsonObject previous = accepted.putIfAbsent(sent.id(), ok(new JsonObject()));
            if (previous == null && sent.args().get("message").getAsString().equals("once")) return new Uncancellable<>();
            return CompletableFuture.completedFuture(accepted.get(sent.id()));
        };
        try (HarnessLoop loop = new HarnessLoop(BINDING, state, provider, clock, sink, () -> {})) {
            loop.accept(frame(chat(1, "fixture"))); pump(loop); clock.advance(5_000); pump(loop); assertEquals(2, accepted.size());
        }
        AgentState restored = AgentState.load(state.save()); Provider freshProvider = new Provider(clock);
        try (HarnessLoop loop = new HarnessLoop(BINDING, restored, freshProvider, clock, sink, () -> {})) {
            JsonObject snapshot = frame(chat(1, "fixture")); snapshot.addProperty("session", "new-lease"); loop.accept(snapshot); pump(loop);
            assertEquals(3, sink.sent.size()); assertEquals(sink.sent.get(1).id(), sink.sent.get(2).id());
            assertEquals(2, accepted.size()); assertTrue(freshProvider.requests.isEmpty(), "Recorded calls replay without re-inference");
            assertEquals("done", restored.context().getAsJsonObject("memory").get("ack").getAsString()); assertEquals("Idle", loop.status());
        }
    }
    @Test void historyCompactionPreservesActiveTasksMemoryPersonalityAndPriorityAcrossAtomicStorage() throws Exception {
        AgentStore store = new AgentStore(temp); assertTrue(store.load(BINDING).isEmpty());
        AgentState state = new AgentState();
        state.mutate("task_upsert", json("{\"id\":\"gather\",\"goal\":\"diamonds\",\"status\":\"INACTIVE\",\"priority\":9}"));
        state.mutate("task_upsert", json("{\"id\":\"help\",\"goal\":\"owner\",\"status\":\"ACTIVE\",\"priority\":-1}"));
        state.mutate("remember", json("{\"key\":\"home\",\"text\":\"server-specific home\"}"));
        state.mutate("personality", json("{\"text\":\"Patient\"}"));
        for (int i = 0; i < 300; i++) state.history("chat", "history " + i);
        store.save(BINDING, state); state.mutate("task_upsert", json("{\"id\":\"help\",\"status\":\"COMPLETED\"}")); store.save(BINDING, state);
        AgentState restored = store.load(BINDING).orElseThrow();
        assertTrue(restored.hasWork(), "Inactive unfinished work remains schedulable");
        assertEquals("help", restored.tasks().get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("COMPLETED", restored.tasks().get(0).getAsJsonObject().get("status").getAsString());
        assertEquals("INACTIVE", restored.tasks().get(1).getAsJsonObject().get("status").getAsString());
        assertEquals("Patient", restored.personality);
        assertEquals("server-specific home", restored.context().getAsJsonObject("memory").get("home").getAsString());
        assertEquals(128, restored.context().getAsJsonArray("history").size());
        assertTrue(restored.context().getAsJsonObject("compactedHistoryCounts").get("chat").getAsInt() >= 172);
        AgentBinding anotherWorld = new AgentBinding(UUID.randomUUID(), BINDING.owner(), BINDING.avatar());
        assertTrue(store.load(anotherWorld).isEmpty());
        JsonObject bad = com.google.gson.JsonParser.parseString(Files.readString(store.path(BINDING))).getAsJsonObject();
        bad.addProperty("world", anotherWorld.world().toString()); Files.writeString(store.path(BINDING), bad.toString());
        assertThrows(java.io.IOException.class, () -> store.load(BINDING));
        assertEquals(bad.toString(), Files.readString(store.path(BINDING)), "Invalid local data is preserved for diagnosis");
        try (var paths = Files.walk(temp)) { assertFalse(paths.anyMatch(p -> p.getFileName().toString().endsWith(".tmp"))); }
    }
    @Test void malformedTaskUpdatesLeaveThePreviousTaskIntact() {
        AgentState state = new AgentState(); state.mutate("task_upsert", json("{\"id\":\"x\",\"goal\":\"work\",\"priority\":4}"));
        assertThrows(IllegalArgumentException.class, () -> state.mutate("task_upsert", json("{\"id\":\"x\",\"status\":\"MAYBE\"}")));
        assertThrows(ArithmeticException.class, () -> state.mutate("task_upsert", json("{\"id\":\"x\",\"priority\":1.5}")));
        assertEquals("QUEUED", state.tasks().get(0).getAsJsonObject().get("status").getAsString());
        assertEquals(4, state.tasks().get(0).getAsJsonObject().get("priority").getAsInt());
    }
    @Test void restoringSavedCallsCannotBypassLightToolRestrictions() {
        Clock clock = new Clock(); Provider provider = new Provider(clock); AgentState state = new AgentState();
        provider.answer = ignored -> new CompletableFuture<>();
        try (HarnessLoop loop = new HarnessLoop(BINDING, state, provider, clock, new Sink(), () -> {})) {
            loop.accept(frame(chat(1, "hello"))); loop.tick();
        }
        JsonObject saved = state.save(); JsonObject turn = saved.getAsJsonObject("turn");
        turn.add("calls", com.google.gson.JsonParser.parseString("[{\"tool\":\"program\",\"arguments\":{}}]"));
        turn.addProperty("cursor", 0);
        assertThrows(IllegalArgumentException.class, () -> AgentState.load(saved));
    }
    @Test void localPauseSurvivesReloadAndResumeDoesNotLoseTheInstruction() {
        Clock clock = new Clock(); Provider provider = new Provider(clock); AgentState state = new AgentState();
        provider.answer = ignored -> new CompletableFuture<>();
        try (HarnessLoop loop = new HarnessLoop(BINDING, state, provider, clock, new Sink(), () -> {})) {
            loop.accept(frame(chat(1, "hello"))); loop.tick(); loop.setPaused(true);
        }
        AgentState restored = AgentState.load(state.save()); Provider freshProvider = new Provider(clock);
        try (HarnessLoop loop = new HarnessLoop(BINDING, restored, freshProvider, clock, new Sink(), () -> {})) {
            loop.accept(frame(chat(1, "hello"))); clock.advance(10_000); pump(loop); assertTrue(freshProvider.requests.isEmpty());
            assertEquals("Paused", loop.status()); loop.setPaused(false); pump(loop);
            assertEquals(1, freshProvider.requests.size()); assertEquals(1, freshProvider.requests.get(0).trigger().get("id").getAsInt());
        }
    }
}
