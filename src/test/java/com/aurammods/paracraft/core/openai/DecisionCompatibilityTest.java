package com.aurammods.paracraft.core.openai;

import com.aurammods.paracraft.core.agent.InferenceProvider;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class DecisionCompatibilityTest {
    private static final String ID="252f0652-6445-4be5-b7a2-f1ceadae0099";
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private static JsonObject completion(JsonElement arguments){var value=json("{\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"paracraft_decide\"}}]}}]}");value.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function").add("arguments",arguments);return value;}
    @Test void optionalFieldsAndEncodedStructuredArgumentsNormalizeToNativeObjects()throws Exception{
        var program=json("{\"id\":\""+ID+"\",\"mode\":\"append\",\"steps\":[{\"op\":\"craft_item\",\"item\":\"minecraft:oak_planks\",\"quantity\":4}]} ");
        var call=new JsonObject();call.addProperty("tool","program");call.addProperty("arguments",program.toString());var calls=new JsonArray();calls.add(call);var batch=new JsonObject();batch.addProperty("calls",calls.toString());
        var decision=DecisionSchema.decode(batch,InferenceProvider.Route.PLANNING);var args=decision.calls().get(0).arguments();assertEquals(ID,args.get("id").getAsString());assertEquals(1,args.get("schema").getAsInt());assertEquals(0,args.getAsJsonObject("observationRefs").size());assertEquals("",decision.note());
        var craft=args.getAsJsonArray("steps").get(0).getAsJsonObject();assertFalse(craft.has("table"));assertFalse(craft.has("grid"));assertFalse(craft.has("saveAs"));assertEquals(4,craft.get("quantity").getAsInt());
    }
    @Test void apiFunctionArgumentsCanBeObjectsEncodedStringsOrOneFencedDocument()throws Exception{
        var batch=json("{\"calls\":[{\"tool\":\"chat\",\"arguments\":{\"message\":\"Hello\"}}]}");
        for(JsonElement args:java.util.List.of(batch,new JsonPrimitive(batch.toString()),new JsonPrimitive(new JsonPrimitive(batch.toString()).toString()),new JsonPrimitive("```json\n"+batch+"\n```"))){
            assertEquals("Hello",DecisionProtocol.openRouterDecision(completion(args),InferenceProvider.Route.LIGHT).calls().get(0).arguments().get("message").getAsString());
        }
        var text=json("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"\"}}]}");text.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").addProperty("content",batch.toString());assertEquals(1,DecisionProtocol.openRouterDecision(text,InferenceProvider.Route.LIGHT).calls().size());
        text.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").addProperty("content","Some ordinary prose");assertThrows(InferenceProvider.DecisionFormatException.class,()->DecisionProtocol.openRouterDecision(text,InferenceProvider.Route.LIGHT));
    }
    @Test void relaxedGenerationRetainsRequiredActionFieldsAndLocalValidation(){
        var strict=DecisionSchema.parameters(InferenceProvider.Route.LIGHT);var relaxed=DecisionSchema.parameters(InferenceProvider.Route.LIGHT,false);assertTrue(strict.getAsJsonArray("required").toString().contains("note"));assertFalse(relaxed.getAsJsonArray("required").toString().contains("note"));assertTrue(relaxed.getAsJsonArray("required").toString().contains("calls"));
        for(String invalid:java.util.List.of(
                "{\"calls\":[{\"tool\":\"chat\",\"arguments\":{\"message\":\"Hello\",\"hidden_action\":\"destroy\"}}]}",
                "{\"calls\":[{\"tool\":\"program\",\"arguments\":{\"mode\":\"append\",\"steps\":[]}}]}",
                "{\"calls\":[{\"tool\":\"program\",\"arguments\":{\"id\":\""+ID+"\",\"mode\":\"append\",\"steps\":[{\"op\":\"craft_item\",\"item\":\"minecraft:chest\",\"quantity\":\"1\"}]}}]}"))
            assertThrows(IOException.class,()->DecisionSchema.decode(json(invalid),InferenceProvider.Route.PLANNING));
        var unknown=assertThrows(InferenceProvider.DecisionFormatException.class,()->DecisionSchema.decode(json("{\"calls\":[{\"tool\":\"search_blocks\",\"arguments\":{\"block\":\"minecraft:chest\"}}]}"),InferenceProvider.Route.PLANNING));assertTrue(unknown.getMessage().contains("program.steps"));
    }
    @Test void brokenJsonOrMultipleDecisionFunctionsNeverBecomeAnExecutablePartialBatch(){
        assertThrows(InferenceProvider.DecisionFormatException.class,()->DecisionProtocol.openRouterDecision(completion(new JsonPrimitive("{\"calls\":[{")),InferenceProvider.Route.PLANNING));
        var response=completion(new JsonPrimitive("{\"calls\":[{\"tool\":\"chat\",\"arguments\":{\"message\":\"Hello\"}}]}"));var calls=response.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").getAsJsonArray("tool_calls");calls.add(calls.get(0).deepCopy());assertThrows(InferenceProvider.DecisionFormatException.class,()->DecisionProtocol.openRouterDecision(response,InferenceProvider.Route.LIGHT));
    }
}
