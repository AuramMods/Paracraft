package com.aurammods.paracraft.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static com.aurammods.paracraft.core.agent.HarnessFixture.*;
import static org.junit.jupiter.api.Assertions.*;

/** Script QA against a stubbed service; physical inventory behavior is covered separately by GameTests. */
class SimulatedProviderTest {
    @Test void commandScriptsEscalateAndSelectExplicitCompletionSemantics(){
        for(String instruction:List.of("Josh, execute command qa_identity","Josh, teleport command qa_home","Josh, discover commands qa_","Josh, use portal 12 64 -30","Josh, search blocks #minecraft:portals")){
            Clock clock=new Clock();Sink sink=new Sink();sink.reply=sent->CompletableFuture.completedFuture(ok(job(sent.args().get("id").getAsString(),"RUNNING")));
            try(HarnessLoop loop=new HarnessLoop(BINDING,new AgentState(),new SimulatedProvider(),clock,sink,()->{})){
                loop.accept(frame(chat(1,instruction)));pump(loop);assertTrue(sink.sent.isEmpty());clock.advance(5000);pump(loop);
                assertEquals(1,loop.planningCalls());assertEquals("program",sink.sent.get(0).tool());var step=sink.sent.get(0).args().getAsJsonArray("steps").get(0).getAsJsonObject();
                if(instruction.contains("discover")){assertEquals("discover_commands",step.get("op").getAsString());assertEquals("qa_",step.get("prefix").getAsString());}
                else if(instruction.contains("use portal")){assertEquals("use_portal",step.get("op").getAsString());assertEquals("[12,64,-30]",step.getAsJsonArray("position").toString());}
                else if(instruction.contains("search blocks")){assertEquals("search_blocks",step.get("op").getAsString());assertEquals("#minecraft:portals",step.get("block").getAsString());}
                else{assertEquals("execute_command",step.get("op").getAsString());assertEquals(instruction.contains("teleport"),step.has("await"));}
            }
        }
    }
    @Test void behaviorChangeEscalatesAndCannotUseTheLightToolSurface() {
        Clock clock = new Clock(); Sink sink = new Sink();
        assertThrows(IllegalArgumentException.class,()->ToolPolicy.validate(InferenceProvider.Route.LIGHT,decision("configure_behavior",json("{\"players\":\"passive\"}"))));
        try (HarnessLoop loop = new HarnessLoop(BINDING,new AgentState(),new SimulatedProvider(),clock,sink,()->{})) {
            loop.accept(frame(chat(1,"Josh, behavior players passive"))); pump(loop); assertTrue(sink.sent.isEmpty());
            clock.advance(5000); pump(loop);
            assertEquals(List.of("configure_behavior","chat"),sink.sent.stream().map(Sent::tool).toList());
            assertEquals("passive",sink.sent.get(0).args().get("players").getAsString()); assertEquals(1,loop.planningCalls());
        }
    }
    @Test void socialChatHasASimulationLabelAndIdleInvokesNothing() {
        Clock clock = new Clock(); Sink sink = new Sink();
        try (HarnessLoop loop = new HarnessLoop(BINDING, new AgentState(), new SimulatedProvider(), clock, sink, () -> {})) {
            loop.accept(frame()); clock.advance(120_000); pump(loop); assertEquals(0, loop.calls());
            loop.accept(frame(chat(1, "Hey Josh, how's it going?"))); pump(loop);
            assertEquals(1, loop.calls()); assertEquals(0, loop.planningCalls()); assertEquals(1, sink.sent.size());
            assertTrue(sink.sent.get(0).args().get("message").getAsString().startsWith("[SIMULATED]"));
            assertTrue(loop.simulated()); assertTrue(loop.providerLabel().contains("no AI usage"));
            clock.advance(120_000); pump(loop); assertEquals(1, loop.calls()); assertEquals("Idle", loop.status());
        }
    }
    @Test void diamondScriptEscalatesObservesSubmitsOnceReportsProgressAndReturnsToIdle() {
        Clock clock = new Clock(); Provider provider = scripted(clock); Sink sink = new Sink(); AgentState state = new AgentState();
        JsonObject snapshot = frame(chat(1, "Josh, collect all the diamonds nearby and give them to me."));
        JsonObject[] program = {null};
        sink.reply = sent -> {
            if (sent.tool().equals("observe")) return CompletableFuture.completedFuture(ok(json("{\"id\":\"search-receipt\",\"observation\":{\"total\":6,\"complete\":true}}")));
            if (sent.tool().equals("program")) {
                program[0] = job(sent.args().get("id").getAsString(), "RUNNING"); return CompletableFuture.completedFuture(ok(program[0]));
            }
            return CompletableFuture.completedFuture(ok(new JsonObject()));
        };
        try (HarnessLoop loop = new HarnessLoop(BINDING, state, provider, clock, sink, () -> {})) {
            loop.accept(snapshot); pump(loop); assertEquals(0, sink.sent.size());
            clock.advance(4_999); pump(loop); assertEquals(1, provider.requests.size());
            clock.advance(1); pump(loop); assertEquals(List.of("observe"), sink.sent.stream().map(Sent::tool).toList());
            clock.advance(5_000); pump(loop); assertEquals(List.of("observe", "program"), sink.sent.stream().map(Sent::tool).toList());
            assertEquals(List.of(0L, 5_000_000_000L, 10_000_000_000L), provider.starts);
            JsonObject submitted = sink.sent.get(1).args();
            assertEquals("search-receipt", submitted.getAsJsonObject("observationRefs").get("found").getAsString());
            assertEquals("collect_items", submitted.getAsJsonArray("steps").get(0).getAsJsonObject().get("op").getAsString());
            assertEquals(BINDING.owner().toString(), submitted.getAsJsonArray("steps").get(1).getAsJsonObject().get("recipient").getAsString());
            assertEquals(1, state.tasks().size());
            snapshot.getAsJsonArray("programs").add(program[0]); snapshot.getAsJsonArray("events").add(chat(2, "Josh, progress?"));
            loop.accept(snapshot); clock.advance(5_000); pump(loop);
            assertEquals("chat", sink.sent.get(2).tool()); assertTrue(sink.sent.get(2).args().get("message").getAsString().contains("RUNNING"));
            assertEquals(1, sink.sent.stream().filter(s -> s.tool().equals("program")).count());
            program[0].addProperty("state", "SUCCEEDED"); program[0].add("result", json("{\"results\":{\"delivery\":{\"delivered\":6}}}"));
            loop.accept(snapshot); clock.advance(5_000); pump(loop); clock.advance(5_000); pump(loop);
            assertEquals("COMPLETED", state.tasks().get(0).getAsJsonObject().get("status").getAsString());
            assertTrue(sink.sent.get(3).args().get("message").getAsString().contains("Dropped 6 acquired diamonds"));
            long calls = loop.calls(); clock.advance(120_000); pump(loop); assertEquals(calls, loop.calls()); assertEquals("Idle", loop.status());
        }
    }
    @Test void anotherActionSetsGatheringInactiveAndLaterResumesItsExistingProgram() {
        Clock clock = new Clock(); Provider provider = scripted(clock); Sink sink = new Sink(); AgentState state = new AgentState();
        String gathering = UUID.randomUUID().toString();
        state.mutate("task_upsert", json("{\"id\":\"" + gathering + "\",\"programId\":\"" + gathering + "\",\"goal\":\"collect diamonds\",\"status\":\"ACTIVE\",\"priority\":0}"));
        JsonObject snapshot = frame(chat(1, "Josh, travel to 12 64 30")); JsonObject original = job(gathering, "RUNNING");
        snapshot.getAsJsonArray("programs").add(original); String[] travelId = {null};
        sink.reply = sent -> {
            if (sent.tool().equals("program")) {
                travelId[0] = sent.args().get("id").getAsString(); original.addProperty("state", "PAUSED");
                JsonObject travel = job(travelId[0], "RUNNING"); snapshot.getAsJsonArray("programs").add(travel); return CompletableFuture.completedFuture(ok(travel));
            }
            if (sent.tool().equals("resume_program")) { original.addProperty("state", "RUNNING"); return CompletableFuture.completedFuture(ok(original)); }
            return CompletableFuture.completedFuture(ok(new JsonObject()));
        };
        try (HarnessLoop loop = new HarnessLoop(BINDING, state, provider, clock, sink, () -> {})) {
            loop.accept(snapshot); pump(loop); clock.advance(5_000); pump(loop);
            assertEquals("INACTIVE", state.tasks().get(1).getAsJsonObject().get("status").getAsString());
            assertEquals("interrupt", sink.sent.get(0).args().get("mode").getAsString());
            snapshot.getAsJsonArray("programs").get(1).getAsJsonObject().addProperty("state", "SUCCEEDED"); loop.accept(snapshot);
            clock.advance(5_000); pump(loop); clock.advance(5_000); pump(loop);
            assertEquals("COMPLETED", state.tasks().get(0).getAsJsonObject().get("status").getAsString());
            clock.advance(5_000); pump(loop); clock.advance(5_000); pump(loop);
            assertEquals("ACTIVE", state.tasks().get(1).getAsJsonObject().get("status").getAsString());
            Sent resume = sink.sent.stream().filter(s -> s.tool().equals("resume_program")).findFirst().orElseThrow();
            assertEquals(gathering, resume.args().get("id").getAsString());
            assertEquals(1, sink.sent.stream().filter(s -> s.tool().equals("program")).count());
        }
    }
    @Test void permissionAndMemoryChangesRequireEscalationAndFailurePreservesActualReason() {
        Clock clock = new Clock(); Provider provider = scripted(clock); Sink sink = new Sink(); AgentState state = new AgentState();
        String id = UUID.randomUUID().toString();
        try (HarnessLoop loop = new HarnessLoop(BINDING, state, provider, clock, sink, () -> {})) {
            loop.accept(frame(chat(1, "Josh, remember base=South door"))); pump(loop);
            assertFalse(state.context().getAsJsonObject("memory").has("base")); clock.advance(5_000); pump(loop);
            assertEquals("South door", state.context().getAsJsonObject("memory").get("base").getAsString());
            loop.accept(frame(chat(1, "Josh, remember base=South door"), chat(2, "Josh, trust " + id)));
            clock.advance(5_000); pump(loop); assertTrue(sink.sent.stream().noneMatch(s -> s.tool().equals("trust")));
            clock.advance(5_000); pump(loop); assertEquals(id, sink.sent.stream().filter(s -> s.tool().equals("trust")).findFirst().orElseThrow().args().get("playerUuid").getAsString());
            state.mutate("task_upsert", json("{\"id\":\"" + id + "\",\"programId\":\"" + id + "\",\"goal\":\"work\",\"status\":\"ACTIVE\"}"));
            JsonObject snapshot = frame(); JsonObject failed = job(id, "FAILED"); failed.addProperty("error", "Destination rejected 3 remaining items"); snapshot.getAsJsonArray("programs").add(failed); loop.accept(snapshot);
            clock.advance(5_000); pump(loop); clock.advance(5_000); pump(loop);
            assertEquals("FAILED", state.tasks().get(0).getAsJsonObject().get("status").getAsString());
            assertTrue(state.tasks().get(0).getAsJsonObject().get("failure").getAsString().contains("3 remaining"));
        }
    }
    private static Provider scripted(Clock clock) {
        Provider provider = new Provider(clock);
        // A brand-new provider per call proves that all script context comes from the request.
        provider.answer = request -> new SimulatedProvider().infer(request); return provider;
    }
    private static JsonObject job(String id, String state) {
        JsonObject job = new JsonObject(); job.addProperty("id", id); job.addProperty("state", state); job.addProperty("phase", "collecting");
        job.addProperty("error", ""); job.add("result", new JsonObject()); return job;
    }
}
