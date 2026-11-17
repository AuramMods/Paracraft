package com.aurammods.paracraft.core.openai;

import com.aurammods.paracraft.core.agent.InferenceProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChatGptStreamTest {
    private static final String ITEM = finishedItem();
    private static String finishedItem() {
        var message = new JsonObject(); message.addProperty("message", "Connected");
        var call = new JsonObject(); call.addProperty("tool", "chat"); call.add("arguments", message);
        var calls = new com.google.gson.JsonArray(); calls.add(call);
        var arguments = new JsonObject(); arguments.add("calls", calls);
        var item = new JsonObject(); item.addProperty("type", "function_call"); item.addProperty("namespace", "paracraft");
        item.addProperty("name", "decide"); item.addProperty("arguments", arguments.toString());
        var event = new JsonObject(); event.addProperty("type", "response.output_item.done"); event.addProperty("output_index", 0); event.add("item", item);
        return event.toString();
    }
    @Test void completedStreamRetainsEarlierFinishedItemsWhenTerminalOutputIsEmpty() throws Exception {
        var response = read(ITEM, "{\"type\":\"response.completed\",\"response\":{\"output\":[],\"usage\":{\"total_tokens\":7}}}");
        var decision = DecisionProtocol.decision(response, InferenceProvider.Route.LIGHT);
        assertEquals("Connected", decision.calls().get(0).arguments().get("message").getAsString());
        assertEquals(7, response.getAsJsonObject("usage").get("total_tokens").getAsInt());
    }
    @Test void finishedItemsNeverEscapeFailedIncompleteOrInterruptedStreams() throws Exception {
        assertThrows(IOException.class, () -> read(ITEM));
        assertThrows(IOException.class, () -> read(ITEM, "{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}"));
        assertThrows(IOException.class, () -> read(ITEM, "{\"type\":\"response.incomplete\",\"response\":{}}"));
    }
    @Test void conflictingFinishedItemsCannotReplaceAnEarlierDecision() throws Exception {
        JsonObject conflicting = JsonParser.parseString(ITEM).getAsJsonObject();
        conflicting.getAsJsonObject("item").addProperty("arguments", "{\"calls\":[]}");
        assertThrows(IOException.class, () -> read(ITEM, conflicting.toString()));
    }
    @Test void visibleOutputIsReportedOnceWhileOpaqueReasoningIsExcluded() throws Exception {
        var seen=new java.util.ArrayList<JsonObject>();
        String reasoning="{\"type\":\"response.output_item.done\",\"output_index\":1,\"item\":{\"type\":\"reasoning\",\"encrypted_content\":\"opaque-private-data\"}}";
        read(seen::add,ITEM,ITEM,reasoning,"{\"type\":\"response.completed\",\"response\":{\"output\":[]}}");
        assertEquals(1,seen.size());assertTrue(seen.get(0).has("arguments"));assertFalse(seen.toString().contains("opaque-private-data"));
        var failed=new java.util.ArrayList<JsonObject>();assertThrows(IOException.class,()->read(failed::add,ITEM));assertEquals(1,failed.size());
    }
    @Test void terminalOnlyOutputAndBrokenDebugObserverDoNotChangeDecisionParsing() throws Exception {
        JsonObject item=JsonParser.parseString(ITEM).getAsJsonObject().getAsJsonObject("item");var seen=new java.util.ArrayList<JsonObject>();
        String completed="{\"type\":\"response.completed\",\"response\":{\"output\":["+item+"]}}";
        read(seen::add,completed);assertEquals(1,seen.size());
        assertEquals("chat",DecisionProtocol.decision(read(value->{throw new IllegalStateException();},completed),InferenceProvider.Route.LIGHT).calls().get(0).tool());
    }
    private static JsonObject read(String... events) throws IOException { return read(ignored->{},events); }
    private static JsonObject read(java.util.function.Consumer<JsonObject> observer,String... events) throws IOException {
        var wire = new StringBuilder();
        for (String event : events) wire.append("data: ").append(event.strip()).append("\n\n");
        return ChatGptProvider.readCompletion(new ByteArrayInputStream(wire.toString().getBytes(StandardCharsets.UTF_8)),observer);
    }
}
