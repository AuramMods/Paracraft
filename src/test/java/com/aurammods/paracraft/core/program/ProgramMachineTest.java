package com.aurammods.paracraft.core.program;

import com.aurammods.paracraft.core.JobState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ProgramMachineTest {
    @Test void nestedRecoveryKeepsPartialResultsAndResumesAfterTheConstruct() {
        Harness h = new Harness();
        var machine = h.machine("""
            {"steps":[{"try":[
              {"op":"record","text":"before"},
              {"try":[{"op":"fail","message":"inner","saveAs":"partial"},{"op":"record","text":"skip"}],
               "catch":[{"op":"caught","text":"inner-catch"}]},
              {"op":"fail","message":"outer"}, {"op":"record","text":"skip-too"}
            ],"catch":[{"op":"caught","text":"outer-catch"}]},{"op":"record","text":"after"}]}
            """);
        finish(machine);
        assertEquals(JobState.SUCCEEDED, machine.state());
        assertEquals(List.of("before", "inner-catch:inner", "outer-catch:outer", "after"), h.trace);
        assertEquals(3, machine.results().getAsJsonObject("partial").get("moved").getAsInt());
    }
    @Test void failureInCatchPropagatesAndRethrowPreservesTheOriginalFailure() {
        Harness h = new Harness();
        var machine = h.machine("""
            {"steps":[{"try":[{"try":[{"op":"fail","message":"original"}],
              "catch":[{"op":"rethrow"},{"op":"record","text":"skip"}]}],
              "catch":[{"op":"caught","text":"outer"},{"op":"fail","message":"catch failed"}]}]}
            """);
        finish(machine);
        assertEquals(JobState.FAILED, machine.state());
        assertEquals(List.of("outer:original"), h.trace);
        assertEquals("catch failed", machine.error().get("message").getAsString());
    }
    @Test void cancellationBypassesAllRecoveryAndCannotBeResumed() {
        Harness h = new Harness();
        var machine = h.machine("""
            {"steps":[{"try":[{"op":"delay"}],"catch":[{"op":"record","text":"must-not-run"}]}]}
            """);
        machine.tick(16); machine.cancel("disconnected"); machine.resume(); machine.tick(16);
        assertEquals(JobState.CANCELLED, machine.state()); assertTrue(h.trace.isEmpty()); assertEquals(1, h.cancelled);
    }
    @Test void restoringAPendingOperationDoesNotReplayCompletedActions() {
        Harness h = new Harness();
        var machine = h.machine("""
            {"steps":[{"op":"record","text":"once"},{"op":"delay","saveAs":"elapsed"},{"op":"record","text":"after"}]}
            """);
        machine.tick(16); machine.pause();
        JsonObject checkpoint = JsonParser.parseString(machine.checkpoint().toString()).getAsJsonObject();
        var restored = ProgramMachine.restore(checkpoint, h);
        restored.tick(16); assertEquals(List.of("once"), h.trace);
        assertEquals(JobState.PAUSED, restored.state());
        restored.resume(); finish(restored);
        assertEquals(List.of("once", "after"), h.trace);
        assertEquals(3, restored.results().getAsJsonObject("elapsed").get("elapsed").getAsInt());
        assertEquals(h.started.get(1), h.started.get(2), "Pending action identity survives restore");
    }
    @Test void pausedNestedCatchRetainsItsOuterErrorContextAcrossRestore() {
        Harness h = new Harness();
        var machine = h.machine("""
            {"steps":[{"try":[{"op":"fail","message":"outer"}],"catch":[
              {"try":[{"op":"fail","message":"inner"}],"catch":[{"op":"delay"},{"op":"caught","text":"inside"}]},
              {"op":"caught","text":"outside"}]}]}
            """);
        machine.tick(16);
        var restored = ProgramMachine.restore(JsonParser.parseString(machine.checkpoint().toString()).getAsJsonObject(), h);
        restored.resume(); finish(restored);
        assertEquals(List.of("inside:inner", "outside:outer"), h.trace);
    }
    @Test void workBudgetBoundsImmediateActionsAndUnknownOperationsRejectBeforeExecution() {
        Harness h = new Harness();
        var machine = h.machine("""
            {"steps":[{"op":"record","text":"1"},{"op":"record","text":"2"},{"op":"record","text":"3"}]}
            """);
        machine.tick(1); assertEquals(List.of("1"), h.trace); finish(machine);
        assertThrows(IllegalArgumentException.class, () -> h.machine("{\"steps\":[{\"op\":\"shell\"}]}"));
        assertEquals(List.of("1", "2", "3"), h.trace);
    }
    private static void finish(ProgramMachine machine) {
        for (int i = 0; i < 30 && !machine.state().terminal(); i++) machine.tick(16);
        assertTrue(machine.state().terminal(), "Test program terminated");
    }
    private static final class Harness implements ProgramMachine.Factory {
        final List<String> trace = new ArrayList<>(); final List<UUID> started = new ArrayList<>(); int cancelled;
        ProgramMachine machine(String json) { return new ProgramMachine(UUID.randomUUID(), JsonParser.parseString(json).getAsJsonObject(), this); }
        @Override public Set<String> operations() { return Set.of("record", "fail", "caught", "delay"); }
        @Override public ProgramMachine.Operation start(UUID id, JsonObject spec, ProgramMachine.Context context, JsonObject checkpoint) {
            started.add(id);
            return new ProgramMachine.Operation() {
                int elapsed = checkpoint == null ? 0 : checkpoint.get("elapsed").getAsInt();
                @Override public ProgramMachine.Update tick() {
                    String op = spec.get("op").getAsString(); JsonObject result = new JsonObject();
                    if (op.equals("delay")) { result.addProperty("elapsed", ++elapsed); return new ProgramMachine.Update(elapsed < 3 ? JobState.RUNNING : JobState.SUCCEEDED, result, ""); }
                    if (op.equals("fail")) { result.addProperty("moved", 3); return new ProgramMachine.Update(JobState.FAILED, result, spec.get("message").getAsString()); }
                    trace.add(spec.get("text").getAsString() + (op.equals("caught") ? ":" + context.caught().get("message").getAsString() : ""));
                    return new ProgramMachine.Update(JobState.SUCCEEDED, result, "");
                }
                @Override public JsonObject checkpoint() { JsonObject result = new JsonObject(); result.addProperty("elapsed", elapsed); return result; }
                @Override public void pause() { }
                @Override public void cancel(String reason) { cancelled++; }
            };
        }
    }
}
