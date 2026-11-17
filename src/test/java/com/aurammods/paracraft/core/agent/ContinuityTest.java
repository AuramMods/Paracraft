package com.aurammods.paracraft.core.agent;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import static com.aurammods.paracraft.core.agent.HarnessFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class ContinuityTest {
    @Test void anEmptyNewInstructionIsRetainedEvenWhileOlderMechanicalWorkRuns(){
        var clock=new Clock();var state=new AgentState();state.executingProgram=PROGRAM;state.mutate("task_upsert",json("{\"id\":\"old-work\",\"goal\":\"Collect diamonds\",\"status\":\"ACTIVE\",\"programId\":\""+PROGRAM+"\"}"));var provider=new Provider(clock);provider.answer=r->CompletableFuture.completedFuture(r.route()==InferenceProvider.Route.LIGHT?decision("think_harder",new JsonObject()):new InferenceProvider.Decision(List.of()));var snapshot=frame(chat(1,"Come here instead"));snapshot.getAsJsonArray("programs").add(job("RUNNING"));
        try(var loop=new HarnessLoop(BINDING,state,provider,clock,new Sink(),()->{})){loop.accept(snapshot);pump(loop);clock.advance(5000);pump(loop);assertEquals(2,state.tasks().size());assertTrue(state.tasks().toString().contains("Come here instead"));assertTrue(state.turn.getAsJsonObject("observations").has("decision_feedback"));assertEquals(PROGRAM,state.executingProgram);}
    }
    @Test void spawningIsHistoryOnlyAndChatPrecedesOneCoalescedBackgroundPlanningTurn(){
        var clock=new Clock();var provider=new Provider(clock);provider.answer=r->CompletableFuture.completedFuture(r.trigger().get("type").getAsString().equals("chat")?decision("chat",json("{\"message\":\"Hello\"}")):new InferenceProvider.Decision(List.of()));
        try(var loop=new HarnessLoop(BINDING,new AgentState(),provider,clock,new Sink(),()->{})){
            loop.accept(frame(event(1,"spawn"),event(5,"spawn_point")));pump(loop);clock.advance(60_000);pump(loop);assertEquals(0,loop.calls());
            loop.accept(frame(event(1,"spawn"),event(5,"spawn_point"),event(6,"pickup"),event(7,"program"),event(8,"damage"),chat(9,"Hello")));pump(loop);assertEquals(1,loop.calls());assertEquals(9,provider.requests.get(0).trigger().get("id").getAsInt());assertEquals(InferenceProvider.Route.LIGHT,provider.requests.get(0).route());
            clock.advance(5000);pump(loop);assertEquals(2,loop.calls());var background=provider.requests.get(1);assertEquals(InferenceProvider.Route.PLANNING,background.route());assertEquals("program",background.trigger().get("type").getAsString());assertEquals(2,background.trigger().getAsJsonArray("coalescedEvents").size());clock.advance(60_000);pump(loop);assertEquals(2,loop.calls());
        }
    }
    @Test void completedIndefiniteWaitIsIdleAndStillWakesOnNewChat(){
        var clock=new Clock();var provider=new Provider(clock);provider.answer=r->CompletableFuture.completedFuture(r.route()==InferenceProvider.Route.PLANNING?decision("wait",json("{\"event\":\"any\"}")):decision("chat",json("{\"message\":\"Ready\"}")));
        try(var loop=new HarnessLoop(BINDING,new AgentState(),provider,clock,new Sink(),()->{})){loop.accept(frame(event(1,"program")));pump(loop);assertEquals("Idle",loop.status());clock.advance(60_000);pump(loop);assertEquals(1,loop.calls());loop.accept(frame(event(1,"program"),chat(2,"Hello")));pump(loop);assertEquals(2,loop.calls());assertEquals("Idle",loop.status());}
    }
    private static final String PROGRAM="5680d971-0dcf-425a-94a7-2778b8e98258";
    private static JsonObject job(String status){return json("{\"id\":\""+PROGRAM+"\",\"state\":\""+status+"\",\"phase\":\"collecting\",\"result\":{\"acquired\":3}}");}
    private static InferenceProvider.Decision start(){return new InferenceProvider.Decision(List.of(new InferenceProvider.Call("task_upsert",json("{\"id\":\"get-diamonds\",\"goal\":\"Collect and deliver diamonds\",\"status\":\"ACTIVE\",\"programId\":\""+PROGRAM+"\"}")),new InferenceProvider.Call("program",json("{\"id\":\""+PROGRAM+"\",\"steps\":[{\"op\":\"collect_items\",\"item\":\"minecraft:diamond\",\"quantity\":12}]}"))),"Collect twelve diamonds, then deliver them to the requester; the server is doing the collection.");}
    @Test void actualArgumentsRepliesGoalAndHandoffSurviveRestartIntoDirectCompletionPlanning(){
        var clock=new Clock();var state=new AgentState();var provider=new Provider(clock);var sink=new Sink();sink.reply=sent->CompletableFuture.completedFuture(ok(job("RUNNING")));
        provider.answer=r->CompletableFuture.completedFuture(r.route()==InferenceProvider.Route.LIGHT?decision("think_harder",new JsonObject()):start());
        try(var loop=new HarnessLoop(BINDING,state,provider,clock,sink,()->{})){loop.accept(frame(chat(1,"Collect twelve diamonds and give them to me")));pump(loop);clock.advance(5000);pump(loop);assertEquals(2,loop.calls());}
        var saved=AgentState.load(state.save());assertEquals(PROGRAM,saved.executingProgram);assertTrue(saved.workingNote.contains("deliver"));assertEquals("Collect twelve diamonds and give them to me",saved.tasks().get(0).getAsJsonObject().getAsJsonObject("request").get("message").getAsString());
        var fresh=new Provider(clock);fresh.answer=r->{var agent=r.snapshot().getAsJsonObject("agent");String history=agent.getAsJsonArray("history").toString();assertTrue(history.contains("minecraft:diamond") && history.contains("tool_result") && history.contains("acquired") && history.contains(PROGRAM));return CompletableFuture.completedFuture(decision("task_upsert",json("{\"id\":\"get-diamonds\",\"status\":\"COMPLETED\"}")));};
        var snapshot=frame(event(2,"program"));snapshot.getAsJsonArray("programs").add(job("SUCCEEDED"));
        try(var loop=new HarnessLoop(BINDING,saved,fresh,clock,sink,()->{})){loop.accept(snapshot);clock.advance(5000);pump(loop);assertEquals(1,fresh.requests.size());assertEquals(InferenceProvider.Route.PLANNING,fresh.requests.get(0).route());assertEquals("Idle",loop.status());assertEquals(1,sink.sent.stream().filter(s->s.tool().equals("program")).count());}
    }
    @Test void runningMechanicalWorkUsesNoPollingInferenceAndFreshChatStillUsesLight(){
        var clock=new Clock();var state=new AgentState();var provider=new Provider(clock);var sink=new Sink();sink.reply=sent->CompletableFuture.completedFuture(ok(job("RUNNING")));provider.answer=r->CompletableFuture.completedFuture(r.route()==InferenceProvider.Route.LIGHT?decision("think_harder",new JsonObject()):start());
        try(var loop=new HarnessLoop(BINDING,state,provider,clock,sink,()->{})){
            var snapshot=frame(chat(1,"Get diamonds"));loop.accept(snapshot);pump(loop);clock.advance(5000);pump(loop);snapshot.getAsJsonArray("programs").add(job("RUNNING"));loop.accept(snapshot);
            for(int i=0;i<60;i++){clock.advance(5000);loop.accept(snapshot);pump(loop);}assertEquals(2,loop.calls());
            provider.answer=r->CompletableFuture.completedFuture(decision("chat",json("{\"message\":\"The server is still collecting; three acquired so far.\"}")));snapshot.getAsJsonArray("events").add(chat(2,"Progress?"));loop.accept(snapshot);pump(loop);assertEquals(3,loop.calls());assertEquals(InferenceProvider.Route.LIGHT,provider.requests.get(2).route());clock.advance(60_000);pump(loop);assertEquals(3,loop.calls());
        }
    }
    @Test void emptyPlanningRetainsTheRequestAndSuppliesCorrectiveContextInsteadOfForgettingIt(){
        var clock=new Clock();var state=new AgentState();var provider=new Provider(clock);var sink=new Sink();provider.answer=r->CompletableFuture.completedFuture(r.route()==InferenceProvider.Route.LIGHT?decision("think_harder",new JsonObject()):new InferenceProvider.Decision(List.of(),"I still need to choose a concrete route."));
        try(var loop=new HarnessLoop(BINDING,state,provider,clock,sink,()->{})){loop.accept(frame(chat(1,"Find diamonds and give them to me")));pump(loop);clock.advance(5000);pump(loop);assertTrue(state.hasWork());assertEquals(1,state.noProgress);assertEquals(1,state.turn.getAsJsonObject("trigger").get("id").getAsInt());}
        var restored=AgentState.load(state.save());var fresh=new Provider(clock);fresh.answer=r->{assertTrue(r.observations().has("decision_feedback"));assertTrue(r.snapshot().getAsJsonObject("agent").get("workingNote").getAsString().contains("concrete route"));assertEquals("Find diamonds and give them to me",r.trigger().get("message").getAsString());return CompletableFuture.completedFuture(new InferenceProvider.Decision(List.of(new InferenceProvider.Call("chat",json("{\"message\":\"Which base should I search?\"}")),new InferenceProvider.Call("wait",json("{\"event\":\"chat\"}"))),"Waiting for the player to identify the base; delivery is still required."));};
        try(var loop=new HarnessLoop(BINDING,restored,fresh,clock,sink,()->{})){loop.accept(frame(chat(1,"Find diamonds and give them to me")));clock.advance(5000);pump(loop);assertEquals(1,fresh.requests.size());assertEquals(InferenceProvider.Route.PLANNING,fresh.requests.get(0).route());clock.advance(60_000);pump(loop);assertEquals(1,fresh.requests.size());assertTrue(restored.hasWork());assertEquals("Waiting: chat",loop.status());}
    }
    @Test void programWaitWakesWhenItsActualStateIsPausedEvenWithoutATerminalEvent(){
        var clock=new Clock();var state=new AgentState();state.executingProgram=PROGRAM;state.wakeEvent="program";state.wakeAtMillis=Long.MAX_VALUE;var provider=new Provider(clock);provider.answer=r->CompletableFuture.completedFuture(decision("wait",json("{\"event\":\"chat\"}")));var snapshot=frame();snapshot.getAsJsonArray("programs").add(job("PAUSED"));
        try(var loop=new HarnessLoop(BINDING,state,provider,clock,new Sink(),()->{})){loop.accept(snapshot);pump(loop);assertEquals(1,provider.requests.size());assertEquals(InferenceProvider.Route.PLANNING,provider.requests.get(0).route());assertEquals("program_state",provider.requests.get(0).trigger().get("type").getAsString());}
    }
    @Test void veryLargeEscapedHistoryKeepsNewestStructuredEntriesWithoutMutatingExecutionData(){
        var state=new AgentState();var source=new JsonObject();source.addProperty("turn","last-turn");var rows=new JsonArray();for(int i=0;i<100;i++){var row=new JsonObject();row.addProperty("item","minecraft:diamond");row.addProperty("nbt","\u0000\"".repeat(20_000));rows.add(row);}source.add("results",rows);String before=source.toString();state.history("tool_result",source);
        var history=state.context().getAsJsonArray("history");assertEquals(1,history.size());assertTrue(history.toString().length()<65_536);assertTrue(history.toString().contains("last-turn") && history.toString().contains("truncated") && history.toString().contains("_historyOmitted"));assertEquals(before,source.toString());
        for(int i=0;i<40;i++)state.history("tool_result",source);assertTrue(state.context().getAsJsonArray("history").size()>0);assertTrue(state.context().getAsJsonObject("compactedHistoryCounts").has("tool_result"));assertTrue(state.context().getAsJsonArray("history").toString().length()<=65_536);
    }
    @Test void explicitlyWaitingOnAlreadyPausedWorkDoesNotSpinUntilItsStateActuallyChanges(){
        var clock=new Clock();var state=new AgentState();state.executingProgram=PROGRAM;state.mutate("task_upsert",json("{\"id\":\"waiting\",\"goal\":\"Resume later\",\"status\":\"ACTIVE\"}"));var provider=new Provider(clock);provider.answer=r->CompletableFuture.completedFuture(decision("wait",json("{\"event\":\"program\"}")));var snapshot=frame();snapshot.getAsJsonArray("programs").add(job("PAUSED"));
        try(var loop=new HarnessLoop(BINDING,state,provider,clock,new Sink(),()->{})){loop.accept(snapshot);pump(loop);assertEquals(1,loop.calls());clock.advance(60_000);loop.accept(snapshot);pump(loop);assertEquals(1,loop.calls());snapshot.getAsJsonArray("programs").set(0,job("FAILED"));loop.accept(snapshot);pump(loop);assertEquals(2,loop.calls());assertEquals(InferenceProvider.Route.PLANNING,provider.requests.get(1).route());clock.advance(60_000);pump(loop);assertEquals(2,loop.calls());}
    }
}
