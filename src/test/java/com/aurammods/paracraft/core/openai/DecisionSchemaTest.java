package com.aurammods.paracraft.core.openai;

import com.aurammods.paracraft.core.agent.*;
import com.google.gson.*;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DecisionSchemaTest {
    private static final String ID = "5680d971-0dcf-425a-94a7-2778b8e98258";
    @Test void bothRoutesEmitStrictTypedSchemasWithoutEncodedJsonFields() {
        for (var route : InferenceProvider.Route.values()) {
            var schema = DecisionSchema.parameters(route); assertStrict(schema);
            assertFalse(schema.toString().contains("arguments_json"));
            var actual = new HashSet<String>();
            for (var variant : schema.getAsJsonObject("properties").getAsJsonObject("calls").getAsJsonObject("items").getAsJsonArray("anyOf")) {
                var properties = variant.getAsJsonObject().getAsJsonObject("properties");
                actual.add(properties.getAsJsonObject("tool").getAsJsonArray("enum").get(0).getAsString());
                assertEquals("object", properties.getAsJsonObject("arguments").get("type").getAsString());
            }
            assertEquals(ToolPolicy.allowed(route), actual);
        }
    }
    @Test void chatQuotesAndNewlinesStayTypedInsteadOfBeingParsedAgain() throws Exception {
        var args = new JsonObject(); String message = "I'll say \"hello\".\nNext line."; args.addProperty("message", message);
        var encoded = batch("chat", args); var wire = JsonParser.parseString(encoded.toString()).getAsJsonObject();
        assertEquals(message, DecisionSchema.decode(wire, InferenceProvider.Route.LIGHT).calls().get(0).arguments().get("message").getAsString());
        wire.getAsJsonArray("calls").get(0).getAsJsonObject().addProperty("arguments", args.toString());
        assertThrows(IOException.class, () -> DecisionSchema.decode(wire, InferenceProvider.Route.LIGHT));
    }
    @Test void nullablePartialTaskUpdatesPreserveExistingMemory() throws Exception {
        var memory = new AgentState(); memory.mutate("task_upsert", json("{\"id\":\"task\",\"goal\":\"Collect diamonds\",\"status\":\"ACTIVE\",\"priority\":3}"));
        var update = DecisionSchema.decode(batch("task_upsert", json("{\"id\":\"task\",\"goal\":null,\"status\":\"COMPLETED\",\"priority\":null,\"programId\":null}")), InferenceProvider.Route.PLANNING).calls().get(0);
        memory.mutate(update.tool(), update.arguments()); var task = memory.tasks().get(0).getAsJsonObject();
        assertEquals("Collect diamonds", task.get("goal").getAsString()); assertEquals(3, task.get("priority").getAsInt());
        assertEquals("COMPLETED", task.get("status").getAsString()); assertFalse(task.has("programId"));
    }
    @Test void recursiveProgramsAndObservationBindingsBecomeNativeObjects() throws Exception {
        var args = json("""
            {"schema":1,"id":"%s","taskId":"gather","mode":"append",
             "observationRefs":[{"name":"found","receipt":"%s"}],"steps":[
               {"try":[{"op":"collect_items","item":"minecraft:diamond","quantity":"all","containersFrom":"found","saveAs":"loot"}],
                "catch":[{"op":"report_failure","saveAs":null}]},
               {"op":"deliver_items","itemsFrom":"loot","recipient":{"playerUuid":"%s"},"saveAs":null}]}
            """.formatted(ID, ID, ID));
        var nativeArgs = DecisionSchema.decode(batch("program", args), InferenceProvider.Route.PLANNING).calls().get(0).arguments();
        assertEquals(ID, nativeArgs.getAsJsonObject("observationRefs").get("found").getAsString());
        assertFalse(nativeArgs.getAsJsonArray("steps").get(1).getAsJsonObject().has("saveAs"));
        var machine = new com.aurammods.paracraft.core.program.ProgramMachine(java.util.UUID.fromString(ID), nativeArgs,
                new com.aurammods.paracraft.core.program.ProgramMachine.Factory() {
                    public Set<String> operations() { return Set.of("collect_items", "deliver_items", "report_failure"); }
                    public com.aurammods.paracraft.core.program.ProgramMachine.Operation start(java.util.UUID id, JsonObject spec, com.aurammods.paracraft.core.program.ProgramMachine.Context context, JsonObject checkpoint) { throw new AssertionError("Syntax test must not start world actions"); }
                });
        assertNotNull(machine);
        args.getAsJsonArray("observationRefs").add(args.getAsJsonArray("observationRefs").get(0).deepCopy());
        assertThrows(IOException.class, () -> DecisionSchema.decode(batch("program", args), InferenceProvider.Route.PLANNING));
    }
    @Test void malformedOrUnauthorizedCallsRejectTheEntireBatch() {
        var invalid = batch("chat", json("{\"message\":\"Hello\"}"));
        invalid.getAsJsonArray("calls").add(batch("task_remove", json("{\"id\":\"work\"}")).getAsJsonArray("calls").get(0));
        assertThrows(IOException.class, () -> DecisionSchema.decode(invalid, InferenceProvider.Route.LIGHT));
        var extra = json("{\"message\":\"Hello\",\"hidden_action\":\"destroy\"}");
        assertThrows(IOException.class, () -> DecisionSchema.decode(batch("chat", extra), InferenceProvider.Route.LIGHT));
        assertThrows(IOException.class, () -> DecisionSchema.decode(batch("think_harder", json("{\"unexpected\":true}")), InferenceProvider.Route.LIGHT));
        assertThrows(IOException.class, () -> DecisionSchema.decode(batch("trust", json("{\"playerUuid\":\"not-a-uuid\"}")), InferenceProvider.Route.PLANNING));
    }
    @Test void typedPatrolSettingsPreserveServerDefaultsAndRejectStringBooleans() throws Exception {
        var args = json("{\"players\":null,\"mobs\":null,\"ambientIdle\":null,\"idle\":{\"enabled\":true,\"dimension\":null,\"waypoints\":[[1.5,64,2.5]],\"waitTicks\":null}}");
        var patch = DecisionSchema.decode(batch("configure_behavior", args), InferenceProvider.Route.PLANNING).calls().get(0).arguments();
        var behavior = com.aurammods.paracraft.core.BehaviorSettings.merge(com.aurammods.paracraft.core.BehaviorSettings.defaults("minecraft:overworld"), patch);
        assertTrue(behavior.getAsJsonObject("idle").get("enabled").getAsBoolean());
        assertEquals(40, behavior.getAsJsonObject("idle").get("waitTicks").getAsInt());
        assertEquals("minecraft:overworld", behavior.getAsJsonObject("idle").get("dimension").getAsString());
        args.getAsJsonObject("idle").addProperty("enabled", "true");
        assertThrows(IOException.class, () -> DecisionSchema.decode(batch("configure_behavior", args), InferenceProvider.Route.PLANNING));
    }
    @Test void planningCanToggleAmbientIdlingWithAStrictBoolean() throws Exception {
        var args=json("{\"players\":null,\"mobs\":null,\"ambientIdle\":false,\"idle\":null}");
        var patch=DecisionSchema.decode(batch("configure_behavior",args),InferenceProvider.Route.PLANNING).calls().get(0).arguments();
        assertFalse(patch.get("ambientIdle").getAsBoolean());assertEquals(1,patch.size());
        args.addProperty("ambientIdle","false");assertThrows(IOException.class,()->DecisionSchema.decode(batch("configure_behavior",args),InferenceProvider.Route.PLANNING));
    }
    @Test void typedCraftingSupportsInternalAndTableProgramsWithoutEncodedArguments() throws Exception {
        var args = craftingProgram();
        var decision = DecisionSchema.decode(batch("program", args), InferenceProvider.Route.PLANNING);
        var steps = decision.calls().get(0).arguments().getAsJsonArray("steps");
        assertEquals("search_recipes", steps.get(0).getAsJsonObject().get("op").getAsString());
        assertFalse(steps.get(1).getAsJsonObject().has("table"));
        assertEquals(9, steps.get(2).getAsJsonObject().getAsJsonArray("grid").size());
        assertEquals(3, steps.get(2).getAsJsonObject().getAsJsonArray("table").size());
    }
    @Test void typedCraftingRejectsZeroQuantitiesAndWrongGridShapes() {
        var args = craftingProgram(); var craft = args.getAsJsonArray("steps").get(1).getAsJsonObject();
        craft.addProperty("quantity", 0);
        assertThrows(IOException.class, () -> DecisionSchema.decode(batch("program", args), InferenceProvider.Route.PLANNING));
        craft.addProperty("quantity", 1); craft.add("grid", JsonParser.parseString("[0,1,-1]"));
        assertThrows(IOException.class, () -> DecisionSchema.decode(batch("program", args), InferenceProvider.Route.PLANNING));
        craft.add("grid", JsonParser.parseString("[0,36,-1,-1]"));
        assertThrows(IOException.class, () -> DecisionSchema.decode(batch("program", args), InferenceProvider.Route.PLANNING));
    }
    private static JsonObject craftingProgram() {
        return json("""
            {"schema":1,"id":"%s","taskId":null,"mode":"append","observationRefs":[],"steps":[
             {"op":"search_recipes","item":"minecraft:wooden_pickaxe","limit":null,"saveAs":"recipes"},
             {"op":"craft_item","item":"minecraft:oak_planks","quantity":8,"table":null,"recipe":null,"grid":null,"saveAs":"planks"},
             {"op":"craft_item","item":"minecraft:wooden_pickaxe","quantity":1,"table":[1,64,2],"recipe":null,"grid":[0,0,0,-1,1,-1,-1,1,-1],"saveAs":"pickaxe"}]}
            """.formatted(ID));
    }
    private static void assertStrict(JsonElement value) {
        if (value.isJsonObject()) {
            var object = value.getAsJsonObject();
            if (object.has("type") && object.get("type").getAsString().equals("object")) {
                assertFalse(object.get("additionalProperties").getAsBoolean()); var names = new HashSet<String>();
                object.getAsJsonArray("required").forEach(name -> names.add(name.getAsString()));
                assertEquals(object.getAsJsonObject("properties").keySet(), names);
            }
            object.entrySet().forEach(entry -> assertStrict(entry.getValue()));
        } else if (value.isJsonArray()) value.getAsJsonArray().forEach(DecisionSchemaTest::assertStrict);
    }
    private static JsonObject batch(String tool, JsonObject arguments) { var call = new JsonObject(); call.addProperty("tool", tool); call.add("arguments", arguments); var calls = new JsonArray(); calls.add(call); var batch = new JsonObject(); batch.add("calls", calls); return batch; }
    private static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
}
