package com.aurammods.paracraft.core.openai;

import com.aurammods.paracraft.core.agent.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class SelectiveContextTest {
    private static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    private static JsonObject full(){
        var snapshot=json("{\"name\":\"Josh\",\"health\":20,\"food\":20,\"position\":[1,64,2],\"serverTick\":10,\"observedAt\":100,\"programs\":[],\"inventory\":[],\"nearbyEntities\":[],\"events\":[],\"inventoryObservations\":[],\"operations\":[\"travel\",\"trade\"],\"agent\":{\"tasks\":[],\"memory\":{\"base\":\"PRIVATE_MEMORY_DETAIL\"},\"history\":[],\"personality\":\"Helpful\",\"workingNote\":\"Finish the current delivery\"}}");
        for(int i=0;i<36;i++){var item=json("{\"slot\":"+i+",\"item\":\"minecraft:diamond\",\"count\":3}");item.addProperty("snbt","EXPLICIT_NBT_DETAIL"+"x".repeat(5000));snapshot.getAsJsonArray("inventory").add(item);}
        for(int i=0;i<64;i++)snapshot.getAsJsonArray("nearbyEntities").add(json("{\"uuid\":\"entity-"+i+"\",\"type\":\"minecraft:villager\",\"name\":\"Farmer "+i+"\",\"merchant\":true}"));
        var job=json("{\"id\":\"current\",\"state\":\"RUNNING\",\"phase\":\"collect_items\",\"result\":{\"collected\":5,\"requested\":12}}");job.getAsJsonObject("result").add("acquiredStacks",snapshot.getAsJsonArray("inventory").deepCopy());snapshot.getAsJsonArray("programs").add(job);return snapshot;
    }
    private static InferenceProvider.Request request(JsonObject snapshot,String message){return new InferenceProvider.Request(InferenceProvider.Route.LIGHT,new InferenceProvider.ModelRoute("fixture","auto"),snapshot,json("{\"type\":\"chat\",\"message\":\""+message+"\"}"),new JsonObject());}
    private static JsonObject current(JsonObject body){var input=body.has("input")?body.getAsJsonArray("input"):body.getAsJsonArray("messages");return json(input.get(input.size()-1).getAsJsonObject().get("content").getAsString());}
    @Test void socialTurnsKeepStatusAndProgressWithoutSendingTheLargeClientSnapshot()throws Exception{
        var snapshot=full();String original=snapshot.toString();var body=DecisionProtocol.openRouterRequest(request(snapshot,"Hello"));String encoded=body.toString();
        assertFalse(encoded.contains("EXPLICIT_NBT_DETAIL"));assertFalse(encoded.contains("PRIVATE_MEMORY_DETAIL"));assertFalse(encoded.contains("Farmer 63"));
        assertTrue(encoded.length()<original.length()/10,encoded.length()+" versus "+original.length());
        var base=current(body);assertEquals(20,base.getAsJsonObject("snapshot").get("health").getAsInt());assertTrue(base.getAsJsonObject("snapshot").getAsJsonArray("programs").toString().contains("collected"));
        assertFalse(base.getAsJsonObject("snapshot").has("inventory"));assertEquals(36,base.getAsJsonObject("contextCatalog").getAsJsonObject("inventory").get("entries").getAsInt());
        assertEquals(original,snapshot.toString(),"Projection never mutates the full client state");
        var another=DecisionProtocol.openRouterRequest(request(snapshot,"How are you?"));assertEquals(body.getAsJsonArray("tools"),another.getAsJsonArray("tools"));assertEquals(body.getAsJsonArray("messages").get(0),another.getAsJsonArray("messages").get(0));assertEquals(body.getAsJsonArray("messages").get(1),another.getAsJsonArray("messages").get(1),"Fixed policy, schema and companion profile precede volatile context");
    }
    @Test void relevantInventoryAndEntitiesAreOnlyPreloadedAsBoundedSummaries()throws Exception{
        var body=DecisionProtocol.request(request(full(),"Give me diamonds near that villager"));var base=current(body).getAsJsonObject("snapshot");
        assertEquals(16,base.getAsJsonArray("inventory").size());assertTrue(base.get("inventoryTruncated").getAsBoolean());assertEquals(8,base.getAsJsonArray("nearbyEntities").size());assertTrue(base.get("nearbyEntitiesTruncated").getAsBoolean());assertFalse(body.toString().contains("EXPLICIT_NBT_DETAIL"));
    }
    @Test void explicitReadsFilterPageAndRevealFullDetailsWithoutMutatingTheSource(){
        var snapshot=full();var args=json("{\"sections\":[\"inventory\",\"memory\"],\"query\":\"diamond\",\"offset\":2,\"limit\":3,\"detail\":\"full\"}");
        var read=DecisionContext.read(snapshot,snapshot.getAsJsonObject("agent"),new JsonObject(),args);var page=read.getAsJsonObject("sections").getAsJsonObject("inventory");
        assertEquals(36,page.get("total").getAsInt());assertEquals(4,page.get("nextOffset").getAsInt());assertEquals(2,page.getAsJsonArray("items").size());assertTrue(page.toString().contains("EXPLICIT_NBT_DETAIL"));assertEquals(0,read.getAsJsonObject("sections").getAsJsonObject("memory").get("total").getAsInt());
        page.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("count",999);assertEquals(3,snapshot.getAsJsonArray("inventory").get(2).getAsJsonObject().get("count").getAsInt());
        args.addProperty("detail","summary");assertFalse(DecisionContext.read(snapshot,snapshot.getAsJsonObject("agent"),new JsonObject(),args).toString().contains("EXPLICIT_NBT_DETAIL"));
        args.addProperty("query","base");args.addProperty("offset",0);assertTrue(DecisionContext.read(snapshot,snapshot.getAsJsonObject("agent"),new JsonObject(),args).toString().contains("PRIVATE_MEMORY_DETAIL"));
        var missing=DecisionContext.read(new JsonObject(),new JsonObject(),new JsonObject(),json("{\"sections\":[\"inventory\"]}"));assertFalse(missing.getAsJsonObject("sections").getAsJsonObject("inventory").get("available").getAsBoolean(),"Unknown is distinct from an observed empty inventory");
    }
    @Test void detailedContextIsSentOnlyAfterItIsExplicitlyRequested()throws Exception{
        var snapshot=full();var observations=new JsonObject();observations.add("context",DecisionContext.read(snapshot,snapshot.getAsJsonObject("agent"),new JsonObject(),json("{\"sections\":[\"inventory\"],\"query\":\"diamond\",\"limit\":1,\"detail\":\"full\"}")));
        var request=new InferenceProvider.Request(InferenceProvider.Route.LIGHT,new InferenceProvider.ModelRoute("fixture","auto"),snapshot,json("{\"type\":\"chat\",\"message\":\"Hello\"}"),observations);
        assertTrue(DecisionProtocol.request(request).toString().contains("EXPLICIT_NBT_DETAIL"));assertTrue(DecisionProtocol.openRouterRequest(request).toString().contains("EXPLICIT_NBT_DETAIL"));
        assertFalse(DecisionProtocol.openRouterRequest(request).toString().contains("PRIVATE_MEMORY_DETAIL"));
    }
    @Test void programWakeShowsExactSearchMatchesAndFailurePositionInSmallPlanningContext() throws Exception {
        var trigger = json("""
                {"type":"program","program":{"id":"one","state":"FAILED","result":{
                  "results":{"tree":{"found":2,"blocks":[{"block":"minecraft:oak_log","position":[-1,69,91],"distance":3}]}},
                  "error":{"message":"No reachable view of block to mine","path":"1",
                    "operation":{"op":"mine_blocks"},"partial":{"broken":2,"target":[-1,69,91],"navigation":{"position":[-0.5,65,90.5]}}}
                }}}
                """);
        var request = new InferenceProvider.Request(InferenceProvider.Route.PLANNING,
                new InferenceProvider.ModelRoute("fixture", "auto"), full(), trigger, new JsonObject());
        var base = current(DecisionProtocol.request(request));
        var outcome = base.getAsJsonObject("programOutcome");
        var partial = outcome.getAsJsonObject("failure").getAsJsonObject("partial");
        assertEquals("[-1,69,91]", partial.get("target").toString());
        assertEquals("[-0.5,65,90.5]", partial.get("position").toString());
        assertEquals(2, partial.get("broken").getAsInt());
        var match = outcome.getAsJsonObject("results").getAsJsonObject("tree").getAsJsonArray("blocks").get(0).getAsJsonObject();
        assertEquals(partial.get("target"), match.get("position"));
        assertFalse(match.has("distance"));
        assertFalse(base.getAsJsonObject("original_trigger").has("program"));
        assertEquals(8, base.getAsJsonObject("snapshot").getAsJsonArray("inventory").size());
        assertFalse(base.toString().contains("EXPLICIT_NBT_DETAIL"));
        trigger.getAsJsonObject("program").addProperty("state", "SUCCEEDED");
        var recovered = DecisionContext.initial(full(), trigger, new JsonObject(), InferenceProvider.Route.PLANNING);
        assertFalse(recovered.getAsJsonObject("programOutcome").has("failure"), "A caught error does not turn success into a failure");
    }

    @Test void typedReadToolsCannotEscalateAuthorityOrAccessArbitrarySections()throws Exception{
        var args=json("{\"sections\":[\"inventory\",\"programs\"],\"query\":null,\"offset\":null,\"limit\":null,\"detail\":null}");var call=json("{\"tool\":\"get_context\",\"arguments\":{}}");call.add("arguments",args);var calls=new JsonArray();calls.add(call);var batch=new JsonObject();batch.add("calls",calls);
        var decoded=DecisionSchema.decode(batch,InferenceProvider.Route.LIGHT);assertEquals("get_context",decoded.calls().get(0).tool());assertFalse(decoded.calls().get(0).arguments().has("query"));
        args.getAsJsonArray("sections").add("accounts");assertThrows(IOException.class,()->DecisionSchema.decode(batch,InferenceProvider.Route.LIGHT));args.getAsJsonArray("sections").remove(2);args.addProperty("offset",-1);assertThrows(IOException.class,()->DecisionSchema.decode(batch,InferenceProvider.Route.LIGHT));args.addProperty("offset",0);args.addProperty("limit",0);assertThrows(IOException.class,()->DecisionSchema.decode(batch,InferenceProvider.Route.LIGHT));
        assertThrows(IllegalArgumentException.class,()->ToolPolicy.validate(InferenceProvider.Route.LIGHT,new InferenceProvider.Decision(java.util.List.of(new InferenceProvider.Call("get_context",json("{\"sections\":[\"inventory\"]}")),new InferenceProvider.Call("chat",json("{\"message\":\"unknown result\"}"))))));
    }
}
