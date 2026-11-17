package com.aurammods.paracraft.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static com.aurammods.paracraft.core.agent.HarnessFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class HarnessLoopTest {
    @Test void providerRoutesSelectAndTraceActualModelsWithoutChangingSavedAvatarRoutes(){
        var clock=new Clock();var requests=new java.util.ArrayList<InferenceProvider.Request>();var traces=new java.util.ArrayList<JsonObject>();var state=new AgentState();var sink=new Sink();
        InferenceProvider provider=new InferenceProvider(){
            public String label(){return "Provider route fixture";}public boolean simulated(){return false;}
            public ModelRoute model(Route route,ModelRoute configured){return new ModelRoute(route==Route.LIGHT?"vendor/light":"vendor/planning",route==Route.LIGHT?"auto":"high");}
            public CompletableFuture<Decision> infer(Request request){requests.add(request);request.emitOutput(json("{\"type\":\"function_call\",\"name\":\"fixture\"}"));return CompletableFuture.completedFuture(request.route()==Route.LIGHT?decision("think_harder",new JsonObject()):decision("chat",json("{\"message\":\"done\"}")));}
        };
        try(var loop=new HarnessLoop(BINDING,state,provider,clock,sink,()->{},traces::add)){
            loop.accept(frame(chat(1,"do something")));pump(loop);clock.advance(5000);pump(loop);
            assertEquals(List.of("vendor/light","vendor/planning"),requests.stream().map(r->r.model().model()).toList());assertEquals(List.of("vendor/light","vendor/planning"),traces.stream().map(t->t.get("model").getAsString()).toList());assertEquals("gpt-6-luna",state.lightModel);assertEquals("xhigh",state.planningEffort);assertEquals(1,sink.sent.size());
        }
    }
    @Test void diagnosticOutputIsFlushedOnClientTickEvenWhenDecisionFailsAndStaleOutputIsDropped() {
        var clock = new Clock(); var provider = new Provider(clock); var traces = new java.util.ArrayList<com.google.gson.JsonObject>();
        var pending = new Uncancellable<InferenceProvider.Decision>(); provider.answer = request -> pending;
        var loop = new HarnessLoop(BINDING, new AgentState(), provider, clock, new Sink(), () -> {}, traces::add);
        loop.accept(frame(chat(1,"hello"))); loop.tick();
        var request = provider.requests.get(0); request.emitOutput(json("{\"type\":\"message\",\"text\":\"visible output\"}"));
        assertTrue(traces.isEmpty()); loop.tick(); assertEquals(1,traces.size());
        assertEquals("LIGHT",traces.get(0).get("route").getAsString());
        request.emitOutput(json("{\"type\":\"function_call\",\"arguments\":\"invalid decision\"}"));
        pending.completeExceptionally(new IllegalArgumentException("bad tool reply")); loop.tick();
        assertEquals(2,traces.size()); assertTrue(loop.status().startsWith("Paused"));
        request.emitOutput(json("{\"type\":\"message\",\"text\":\"late output\"}")); loop.tick(); assertEquals(2,traces.size()); loop.close();
    }
    @Test void brokenDebugSinkCannotPauseOrPreventChat() {
        var clock = new Clock(); var sink = new Sink();
        var loop = new HarnessLoop(BINDING,new AgentState(),new SimulatedProvider(),clock,sink,()->{}, output->{throw new IllegalStateException("diagnostic sink unavailable");});
        loop.accept(frame(chat(1,"hello")));pump(loop);assertEquals("Idle",loop.status());assertEquals(1,sink.sent.size());loop.close();
    }

    @Test void authoritativeToolFailureReplansDurablyInsteadOfReplayingTheFailedReceipt(){
        Clock clock=new Clock();Provider provider=new Provider(clock);Sink sink=new Sink();AgentState state=new AgentState();
        provider.answer=r->CompletableFuture.completedFuture(decision("chat",json("{\"wrong_field\":\"first attempt\"}")));
        sink.reply=sent->CompletableFuture.completedFuture(json("{\"ok\":false,\"code\":\"TOOL_FAILED\",\"error\":\"Missing string message\"}"));
        try(HarnessLoop loop=new HarnessLoop(BINDING,state,provider,clock,sink,()->{})){loop.accept(frame(chat(1,"Josh, hello")));pump(loop);assertEquals(1,sink.sent.size());assertTrue(loop.lastError().isEmpty());assertEquals(InferenceProvider.Route.PLANNING.name(),state.turn.get("route").getAsString());assertEquals(1,provider.requests.size());}
        var restored=AgentState.load(state.save());sink.reply=ignored->CompletableFuture.completedFuture(ok(new JsonObject()));provider.answer=r->CompletableFuture.completedFuture(decision("chat",json("{\"message\":\"corrected\"}")));clock.advance(5000);
        try(HarnessLoop loop=new HarnessLoop(BINDING,restored,provider,clock,sink,()->{})){loop.accept(frame(chat(1,"Josh, hello")));pump(loop);assertEquals(2,sink.sent.size());assertNotEquals(sink.sent.get(0).id(),sink.sent.get(1).id());var request=provider.requests.get(1);assertEquals(InferenceProvider.Route.PLANNING,request.route());assertTrue(request.observations().getAsJsonArray("tool_failures").toString().contains("Missing string message"));assertEquals("Josh, hello",request.trigger().get("message").getAsString());assertEquals("Idle",loop.status());}
    }
    @Test void authorityFailureStillPausesWithoutModelRecovery(){
        Clock clock=new Clock();Provider provider=new Provider(clock);Sink sink=new Sink();provider.answer=r->CompletableFuture.completedFuture(decision("chat",json("{\"message\":\"hello\"}")));sink.reply=sent->CompletableFuture.completedFuture(json("{\"ok\":false,\"code\":\"AUTHORITY_LOST\",\"error\":\"Revoked\"}"));
        try(HarnessLoop loop=new HarnessLoop(BINDING,new AgentState(),provider,clock,sink,()->{})){loop.accept(frame(chat(1,"hello")));pump(loop);clock.advance(60000);pump(loop);assertTrue(loop.status().startsWith("Paused:"));assertEquals(1,provider.requests.size());assertEquals(1,sink.sent.size());}
    }
    @Test void respawnDiscardsDurableOldBodyEffectsButRetainsRequestAndTasksForFreshPlanning() {
        Clock clock = new Clock(); Provider provider = new Provider(clock); Sink sink = new Sink(); AgentState state = new AgentState();
        state.mutate("task_upsert", json("{\"id\":\"gather\",\"goal\":\"finish gathering\",\"status\":\"INACTIVE\"}"));
        var lostAck = new Uncancellable<JsonObject>(); sink.reply = ignored -> lostAck;
        provider.answer = request -> CompletableFuture.completedFuture(decision("chat", json("{\"message\":\"old body decision\"}")));
        try (HarnessLoop loop = new HarnessLoop(BINDING, state, provider, clock, sink, () -> {})) {
            JsonObject before = frame(chat(1, "Josh, gather diamonds")); before.addProperty("lifeRevision", 0); loop.accept(before); pump(loop);
            assertEquals(1, sink.sent.size()); assertNotNull(state.turn);
        }
        AgentState restored = AgentState.load(state.save()); assertEquals(0, restored.lifeRevision);
        provider.answer = ignored -> CompletableFuture.completedFuture(new InferenceProvider.Decision(List.of())); clock.advance(10_000);
        try (HarnessLoop loop = new HarnessLoop(BINDING, restored, provider, clock, sink, () -> {})) {
            JsonObject after = frame(chat(1, "Josh, gather diamonds"), event(2, "respawn")); after.addProperty("lifeRevision", 1); after.addProperty("session", "new-body-lease");
            loop.accept(after); lostAck.complete(ok(new JsonObject())); pump(loop);
            assertEquals(1, sink.sent.size(), "Old body's pending effect must not be retried under the new lease");
            var replanned = provider.requests.get(provider.requests.size() - 1);
            assertEquals(InferenceProvider.Route.LIGHT, replanned.route()); assertEquals(1, replanned.trigger().get("id").getAsInt());
            assertEquals(1, replanned.snapshot().get("lifeRevision").getAsLong()); assertTrue(restored.hasWork());
            assertEquals(1, AgentState.load(restored.save()).lifeRevision);
        }
    }
    @Test void slowInferenceNeverOverlapsAndEscalationRetainsRequestButUsesFreshPhysicalState() {
        Clock clock = new Clock(); Provider provider = new Provider(clock); Sink sink = new Sink();
        CompletableFuture<InferenceProvider.Decision> slow = new CompletableFuture<>();
        provider.answer = request -> provider.requests.size() == 1 ? slow : CompletableFuture.completedFuture(new InferenceProvider.Decision(List.of()));
        AgentState state = new AgentState();
        try (HarnessLoop loop = new HarnessLoop(BINDING, state, provider, clock, sink, () -> {})) {
            loop.accept(frame(chat(1, "Josh, collect diamonds"))); loop.tick();
            JsonObject newer = frame(chat(1, "Josh, collect diamonds"), chat(2, "Josh, progress?")); newer.addProperty("health", 7);
            loop.accept(newer); clock.advance(20_000); pump(loop);
            assertEquals(1, provider.requests.size());
            slow.complete(decision("think_harder", new JsonObject())); pump(loop);
            assertEquals(2, provider.requests.size()); var high = provider.requests.get(1);
            assertEquals(InferenceProvider.Route.PLANNING, high.route()); assertEquals(1, high.trigger().get("id").getAsInt());
            assertEquals(7, high.snapshot().get("health").getAsInt()); assertFalse(high.tools().contains("think_harder")); assertTrue(high.tools().contains("program"));
            assertEquals(List.of(0L, 20_000_000_000L), provider.starts);
            clock.advance(5_000); pump(loop); assertEquals(2, provider.requests.get(2).trigger().get("id").getAsInt());
        }
    }
    @Test void rejectsAnEntireLightResponseBeforeEvenItsAllowedChatHasEffects() {
        Clock clock = new Clock(); Provider provider = new Provider(clock); Sink sink = new Sink();
        provider.answer = request -> CompletableFuture.completedFuture(new InferenceProvider.Decision(List.of(
                new InferenceProvider.Call("chat", json("{\"message\":\"sneaky\"}")),
                new InferenceProvider.Call("program", new JsonObject()))));
        try (HarnessLoop loop = new HarnessLoop(BINDING, new AgentState(), provider, clock, sink, () -> {})) {
            loop.accept(frame(chat(1, "hello"))); pump(loop);
            assertTrue(sink.sent.isEmpty()); assertTrue(loop.status().startsWith("Paused:")); clock.advance(60_000); pump(loop);
            assertEquals(1, provider.requests.size());
        }
        assertThrows(IllegalArgumentException.class, () -> ToolPolicy.validate(InferenceProvider.Route.LIGHT,
                new InferenceProvider.Decision(List.of(new InferenceProvider.Call("chat", new JsonObject()), new InferenceProvider.Call("think_harder", new JsonObject())))));
    }
    @Test void revokedLeaseAndDisconnectIgnoreLateProviderResultsEvenWhenCancellationIsIgnored() {
        Clock clock = new Clock(); Provider provider = new Provider(clock); Sink sink = new Sink();
        Uncancellable<InferenceProvider.Decision> old = new Uncancellable<>();
        provider.answer = request -> provider.requests.size() == 1 ? old : CompletableFuture.completedFuture(new InferenceProvider.Decision(List.of()));
        try (HarnessLoop loop = new HarnessLoop(BINDING, new AgentState(), provider, clock, sink, () -> {})) {
            JsonObject snapshot = frame(chat(1, "hello")); loop.accept(snapshot); loop.tick();
            snapshot.remove("session"); loop.accept(snapshot); clock.advance(20_000);
            old.complete(decision("chat", json("{\"message\":\"must not arrive\"}"))); pump(loop);
            assertTrue(sink.sent.isEmpty()); assertEquals(1, provider.requests.size());
            snapshot.addProperty("session", "lease-2"); loop.accept(snapshot); pump(loop);
            assertEquals(2, provider.requests.size()); assertEquals(1, provider.requests.get(1).trigger().get("id").getAsInt());
            assertTrue(sink.sent.isEmpty()); assertEquals("Idle", loop.status());
        }
    }
    @Test void pauseOnAnActiveLeaseCancelsInferenceAndResumesTheDurableTurn() {
        Clock clock = new Clock(); Provider provider = new Provider(clock); Sink sink = new Sink();
        CompletableFuture<InferenceProvider.Decision> waiting = new CompletableFuture<>(); provider.answer = ignored -> waiting;
        try (HarnessLoop loop = new HarnessLoop(BINDING, new AgentState(), provider, clock, sink, () -> {})) {
            JsonObject snapshot = frame(chat(1, "hello")); loop.accept(snapshot); loop.tick();
            snapshot.addProperty("paused", true); loop.accept(snapshot); assertTrue(waiting.isCancelled());
            clock.advance(10_000); pump(loop); assertEquals(1, provider.requests.size());
            provider.answer = ignored -> CompletableFuture.completedFuture(new InferenceProvider.Decision(List.of()));
            snapshot.addProperty("paused", false); loop.accept(snapshot); pump(loop); assertEquals(2, provider.requests.size());
        }
    }
    @Test void eventBurstsCoalesceAndDuplicateOrUnsortedSnapshotEventsDoNotReplay() {
        Clock clock = new Clock(); Provider provider = new Provider(clock); Sink sink = new Sink();
        try (HarnessLoop loop = new HarnessLoop(BINDING, new AgentState(), provider, clock, sink, () -> {})) {
            JsonObject snapshot = frame(); JsonArray events = snapshot.getAsJsonArray("events");
            for (int i = 200; i > 0; i--) {
                JsonObject event = event(i, i % 2 == 0 ? "damage" : "pickup"); if (i % 2 == 0) event.addProperty("amount", 0.5); events.add(event);
            }
            loop.accept(snapshot); loop.accept(snapshot); pump(loop);
            assertEquals(1, provider.requests.size());
            assertEquals(100, provider.requests.get(0).trigger().get("occurrences").getAsInt());
            var damage=provider.requests.get(0).trigger().getAsJsonArray("coalescedEvents").get(0).getAsJsonObject();assertEquals(100,damage.get("occurrences").getAsInt());assertEquals(50,damage.get("amount").getAsDouble());
            clock.advance(5_000);pump(loop);assertEquals(1,provider.requests.size());
            clock.advance(60_000); loop.accept(snapshot); pump(loop); assertEquals(1, provider.requests.size()); assertEquals("Idle", loop.status());
        }
    }
    @Test void explicitEventWaitSuppressesTaskPollingUntilWakeAndTimerWaitWorksWithoutTasks() {
        Clock clock = new Clock(); Provider provider = new Provider(clock); Sink sink = new Sink(); AgentState state = new AgentState();
        state.mutate("task_upsert", json("{\"id\":\"inactive\",\"goal\":\"later\",\"status\":\"INACTIVE\"}"));
        provider.answer = request -> CompletableFuture.completedFuture(request.route() == InferenceProvider.Route.LIGHT
                ? decision("think_harder", new JsonObject()) : decision("wait", json("{\"event\":\"weather\"}")));
        try (HarnessLoop loop = new HarnessLoop(BINDING, state, provider, clock, sink, () -> {})) {
            loop.accept(frame()); pump(loop); clock.advance(5_000); pump(loop); assertEquals("Waiting: weather", loop.status());
            clock.advance(600_000); loop.accept(frame(event(1, "unrelated"))); pump(loop); assertEquals(1, provider.requests.size());
            loop.accept(frame(event(1, "unrelated"), event(2, "damage"))); pump(loop); assertEquals(2, provider.requests.size());
        }
        Clock timerClock = new Clock(); Provider timer = new Provider(timerClock);
        timer.answer = request -> CompletableFuture.completedFuture(timer.requests.size() == 1 ? decision("think_harder", new JsonObject())
                : timer.requests.size() == 2 ? decision("wait", json("{\"seconds\":10}")) : new InferenceProvider.Decision(List.of()));
        try (HarnessLoop loop = new HarnessLoop(BINDING, new AgentState(), timer, timerClock, new Sink(), () -> {})) {
            loop.accept(frame(chat(1, "wait"))); pump(loop); timerClock.advance(5_000); pump(loop);
            timerClock.advance(9_999); pump(loop); assertEquals(2, timer.requests.size());
            timerClock.advance(1); pump(loop); assertEquals(3, timer.requests.size()); assertEquals("timer", timer.requests.get(2).trigger().get("type").getAsString());
        }
    }
    @Test void bindingMismatchIsRejectedWithoutMixingWorldMemoryAndProviderInputsAreCopies() {
        Clock clock = new Clock(); Provider provider = new Provider(clock); Sink sink = new Sink();
        try (HarnessLoop loop = new HarnessLoop(BINDING, new AgentState(), provider, clock, sink, () -> {})) {
            JsonObject other = frame(chat(1, "hello")); other.addProperty("worldId", java.util.UUID.randomUUID().toString());
            assertThrows(IllegalArgumentException.class, () -> loop.accept(other)); pump(loop); assertTrue(provider.requests.isEmpty());
            JsonObject original = frame(chat(1, "hello")); loop.accept(original); original.addProperty("health", 0); pump(loop);
            JsonObject exposed = provider.requests.get(0).snapshot(); exposed.addProperty("health", 1);
            assertEquals(20, provider.requests.get(0).snapshot().get("health").getAsInt());
        }
    }
    @Test void historyOnlyEventsNeverWakeAnIdleModel() {
        Clock clock = new Clock(); Provider provider = new Provider(clock); AgentState state = new AgentState();
        try (HarnessLoop loop = new HarnessLoop(BINDING, state, provider, clock, new Sink(), () -> {})) {
            JsonObject response = event(1, "response"); response.addProperty("wake", false);
            loop.accept(frame(response)); clock.advance(60_000); pump(loop);
            assertTrue(provider.requests.isEmpty()); assertEquals(1, state.context().getAsJsonArray("history").size());
            assertEquals(1, state.save().get("eventCursor").getAsInt());
        }
    }
}
