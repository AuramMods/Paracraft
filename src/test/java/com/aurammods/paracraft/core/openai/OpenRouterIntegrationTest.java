package com.aurammods.paracraft.core.openai;

import com.aurammods.paracraft.core.agent.InferenceProvider;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual loopback HTTP and private files, fake API keys; never contacts a paid provider. */
class OpenRouterIntegrationTest {
    @TempDir Path directory;
    private static final String KEY="fixture-openrouter-private-key";
    private static final String CHAT="{\"calls\":[{\"tool\":\"chat\",\"arguments\":{\"message\":\"Hello!\"}}]}";
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private static void configure(ChatGptClient client)throws Exception{client.configureOpenRouter(KEY,"vendor/exact-light:free","auto","vendor/exact-planner","high").get(5,TimeUnit.SECONDS);}
    private static InferenceProvider.Request request(OpenRouterProvider provider,InferenceProvider.Route route,java.util.function.Consumer<JsonObject> output){
        var snapshot=json("{\"name\":\"Josh\",\"agent\":{\"tasks\":[],\"memory\":{},\"history\":[]}}");return new InferenceProvider.Request(route,provider.model(route,new InferenceProvider.ModelRoute("gpt-6-luna","xhigh")),snapshot,json("{\"type\":\"chat\",\"message\":\"Hello Josh\"}"),new JsonObject(),output);
    }
    @Test void savesPrivateKeyAndExactRoutesWithoutAlteringChatGptAccounts()throws Exception{
        var store=new AccountStore(directory);store.transact(state->{state.addProperty("selected","old-chatgpt");state.getAsJsonObject("accounts").add("old-chatgpt",json("{\"client_id\":\"oaiapp_fixture\",\"email\":\"fixture@example.invalid\",\"access_token\":\"fixture-chatgpt-token\"}"));return null;});
        try(var client=new ChatGptClient(directory)){
            configure(client);assertEquals("openrouter",client.mode());assertTrue(client.openRouter().keySaved());assertFalse(client.openRouter().toString().contains(KEY));assertFalse(client.label().contains(KEY));assertEquals("old-chatgpt",client.selected().id());assertTrue(client.selected().signedIn());
            assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(directory.resolve("accounts.json")));assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(directory));
            try(var restored=new ChatGptClient(directory)){
                assertEquals("openrouter",restored.mode());assertEquals(KEY,restored.routerCredential().key());assertEquals("vendor/exact-light:free",restored.openRouter().lightModel());assertEquals("vendor/exact-planner",restored.openRouter().planningModel());
                restored.configureOpenRouter("","another/not-in-a-catalog","","","").get(5,TimeUnit.SECONDS);assertEquals(KEY,restored.routerCredential().key());assertEquals("another/not-in-a-catalog",restored.openRouter().planningModel());assertEquals("auto",restored.openRouter().planningEffort());
                restored.select("old-chatgpt").get(5,TimeUnit.SECONDS);assertEquals("chatgpt",restored.mode());assertTrue(restored.selected().signedIn());assertTrue(restored.openRouter().keySaved());
            }
        }
    }
    @Test void realHttpUsesExplicitModelIdsSharedStrictToolsAndReportedUsage()throws Exception{
        try(var fixture=new Fixture();var client=new ChatGptClient(directory)){
            configure(client);var provider=new OpenRouterProvider(client,fixture.uri());var outputs=new CopyOnWriteArrayList<JsonObject>();
            for(var route:InferenceProvider.Route.values()){
                var decision=provider.infer(request(provider,route,outputs::add)).get(5,TimeUnit.SECONDS);assertEquals("Hello!",decision.calls().get(0).arguments().get("message").getAsString());
                var body=fixture.requests.get(fixture.requests.size()-1);assertEquals(route==InferenceProvider.Route.LIGHT?"vendor/exact-light:free":"vendor/exact-planner",body.get("model").getAsString());assertFalse(body.get("stream").getAsBoolean());assertEquals(2,body.getAsJsonArray("messages").size());
                assertEquals("Bearer "+KEY,fixture.authorization);assertFalse(body.toString().contains(KEY));assertEquals("paracraft_decide",body.getAsJsonObject("tool_choice").getAsJsonObject("function").get("name").getAsString());var fn=body.getAsJsonArray("tools").get(0).getAsJsonObject().getAsJsonObject("function");assertTrue(fn.get("strict").getAsBoolean());assertEquals(DecisionSchema.parameters(route),fn.getAsJsonObject("parameters"));
                if(route==InferenceProvider.Route.LIGHT)assertFalse(body.has("reasoning"));else{assertEquals("high",body.getAsJsonObject("reasoning").get("effort").getAsString());assertTrue(body.getAsJsonObject("reasoning").get("exclude").getAsBoolean());}
            }
            assertTrue(outputs.toString().contains("Hello!"));assertFalse(outputs.toString().contains("private chain"));assertFalse(outputs.toString().contains(KEY));assertTrue(outputs.toString().contains("paracraft_decide"));
            var today=client.todayUsage();assertEquals(200,today.input());assertEquals(20,today.output());assertEquals(220,today.total());assertEquals(40,today.cached());assertEquals(0,new java.math.BigDecimal("0.0246").compareTo(today.usd()));assertTrue(today.openRouter());assertTrue(today.costLabel().startsWith("OpenRouter reported"));assertFalse(today.costLabel().contains("Luna"));
            try(var restored=new ChatGptClient(directory)){assertEquals(220,restored.todayUsage().total());assertEquals(today.usd(),restored.todayUsage().usd());}
        }
    }
    @Test void errorsAreActualSingleRequestsAndNeverEchoTheApiKey()throws Exception{
        try(var fixture=new Fixture();var client=new ChatGptClient(directory)){
            configure(client);var provider=new OpenRouterProvider(client,fixture.uri());fixture.status=400;fixture.response=json("{\"error\":{\"message\":\"Unknown exact model, credential "+KEY+"\"}}");
            var failure=assertThrows(ExecutionException.class,()->provider.infer(request(provider,InferenceProvider.Route.LIGHT,ignored->{})).get(5,TimeUnit.SECONDS));assertTrue(ChatGptClient.message(failure).contains("OpenRouter HTTP 400"));assertTrue(ChatGptClient.message(failure).contains("Unknown exact model"));assertFalse(ChatGptClient.message(failure).contains(KEY));assertEquals(1,fixture.requests.size());assertEquals(1,client.todayUsage().unreported());
            fixture.status=401;assertThrows(ExecutionException.class,()->provider.infer(request(provider,InferenceProvider.Route.LIGHT,ignored->{})).get(5,TimeUnit.SECONDS));assertEquals(2,fixture.requests.size());assertTrue(client.openRouter().keySaved());
        }
    }
    @Test void rejectsIncompleteMalformedDuplicateUnknownAndForbiddenToolBatches()throws Exception{
        try(var fixture=new Fixture();var client=new ChatGptClient(directory)){
            configure(client);var provider=new OpenRouterProvider(client,fixture.uri());var valid=completion(CHAT);var length=valid.deepCopy();length.getAsJsonArray("choices").get(0).getAsJsonObject().addProperty("finish_reason","length");var duplicate=valid.deepCopy();var calls=duplicate.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").getAsJsonArray("tool_calls");calls.add(calls.get(0).deepCopy());var unknown=valid.deepCopy();unknown.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function").addProperty("name","arbitrary_execution");
            for(var response:List.of(length,duplicate,unknown,completion("broken JSON"),completion("{\"calls\":[{\"tool\":\"chat\",\"arguments\":{\"message\":\"not applied\"}},{\"tool\":\"remember\",\"arguments\":{\"key\":\"x\",\"text\":\"y\"}}]}"))){fixture.response=response;assertThrows(ExecutionException.class,()->provider.infer(request(provider,InferenceProvider.Route.LIGHT,ignored->{})).get(5,TimeUnit.SECONDS));}
            fixture.response=completion("{\"calls\":[{\"tool\":\"program\",\"arguments\":{\"schema\":1,\"id\":\"5680d971-0dcf-425a-94a7-2778b8e98258\",\"taskId\":null,\"mode\":\"replace\",\"observationRefs\":[],\"steps\":[{\"op\":\"search_blocks\",\"block\":\"minecraft:crafting_table\",\"limit\":3,\"radius\":16,\"saveAs\":\"found\"}]}}]}");
            var planned=provider.infer(request(provider,InferenceProvider.Route.PLANNING,ignored->{})).get(5,TimeUnit.SECONDS);assertEquals("search_blocks",planned.calls().get(0).arguments().getAsJsonArray("steps").get(0).getAsJsonObject().get("op").getAsString());
        }
    }
    @Test void cancellationClosesThePendingRequestWithoutEmittingLateDecisions()throws Exception{
        try(var fixture=new Fixture();var client=new ChatGptClient(directory)){
            configure(client);fixture.hold=true;var provider=new OpenRouterProvider(client,fixture.uri());var output=new CopyOnWriteArrayList<JsonObject>();var pending=provider.infer(request(provider,InferenceProvider.Route.LIGHT,output::add));assertTrue(fixture.started.await(5,TimeUnit.SECONDS));assertTrue(pending.cancel(true));fixture.release.countDown();assertThrows(CancellationException.class,()->pending.get(5,TimeUnit.SECONDS));assertTrue(output.isEmpty());
        }
    }
    @Test void changingSettingsInvalidatesInFlightWorkAndKeyReplacementSeparatesUsage()throws Exception{
        try(var fixture=new Fixture();var client=new ChatGptClient(directory)){
            configure(client);var oldId=client.routerCredential().id();client.recordRouterUsage(oldId,json("{\"prompt_tokens\":10,\"completion_tokens\":1,\"cost\":0.01}"),"completed");
            client.configureOpenRouter("","updated/exact-model","none","","").get(5,TimeUnit.SECONDS);assertEquals(11,client.todayUsage().total());assertEquals(oldId,client.routerCredential().id());
            fixture.hold=true;var provider=new OpenRouterProvider(client,fixture.uri());var output=new CopyOnWriteArrayList<JsonObject>();var pending=provider.infer(request(provider,InferenceProvider.Route.LIGHT,output::add));assertTrue(fixture.started.await(5,TimeUnit.SECONDS));
            client.configureOpenRouter("replacement-fixture-key","new/model","auto","","").get(5,TimeUnit.SECONDS);assertNotEquals(oldId,client.routerCredential().id());fixture.release.countDown();assertThrows(CancellationException.class,()->pending.get(5,TimeUnit.SECONDS));assertTrue(output.isEmpty());assertEquals(0,client.todayUsage().total());
            client.recordRouterUsage(oldId,json("{\"prompt_tokens\":900,\"completion_tokens\":100,\"cost\":99}"),"completed");assertEquals(0,client.todayUsage().total());
            client.simulate().get(5,TimeUnit.SECONDS);assertTrue(client.openRouter().keySaved());assertThrows(CancellationException.class,()->provider.infer(request(provider,InferenceProvider.Route.LIGHT,ignored->{})).get(5,TimeUnit.SECONDS));assertEquals(1,fixture.requests.size());
            client.forgetOpenRouter().get(5,TimeUnit.SECONDS);assertFalse(client.openRouter().keySaved());assertFalse(Files.readString(directory.resolve("accounts.json")).contains("replacement-fixture-key"));
        }
    }
    @Test void blankNewCredentialsDoNotCreateALiveProviderAndInvalidKeyTextIsNeverEchoed()throws Exception{
        try(var client=new ChatGptClient(directory)){
            assertThrows(ExecutionException.class,()->client.configureOpenRouter("","exact/model","auto","","").get(5,TimeUnit.SECONDS));assertEquals("simulation",client.mode());assertFalse(client.openRouter().keySaved());
            var error=assertThrows(IllegalArgumentException.class,()->client.configureOpenRouter("bad\r\nsecret","exact/model","auto","","").join());assertFalse(error.getMessage().contains("secret"));
        }
    }
    @Test void customBaseUrlUsesProductionTransportWithoutAuthenticationAndSurvivesReload()throws Exception{
        try(var fixture=new Fixture();var client=new ChatGptClient(directory)){
            String base=fixture.base()+"/proxy/v1";
            client.configureOpenRouter("","local-model:latest","auto","local-planner:latest","auto",base+"/").get(5,TimeUnit.SECONDS);
            assertTrue(fixture.requests.isEmpty(),"Saving makes no model, discovery, or connectivity requests");
            assertFalse(client.openRouter().keySaved());assertTrue(client.openRouter().ready());assertFalse(client.openRouter().isOpenRouter());assertTrue(client.label().contains("API · 127.0.0.1:"));
            var provider=new OpenRouterProvider(client);var outputs=new CopyOnWriteArrayList<JsonObject>();
            for(var route:InferenceProvider.Route.values()){
                var decision=provider.infer(request(provider,route,outputs::add)).get(5,TimeUnit.SECONDS);
                assertEquals("Hello!",decision.calls().get(0).arguments().get("message").getAsString());
                assertEquals("/proxy/v1/chat/completions",fixture.path);assertNull(fixture.authorization);assertNull(fixture.routerTitle);
                var body=fixture.requests.get(fixture.requests.size()-1);assertEquals(route==InferenceProvider.Route.LIGHT?"local-model:latest":"local-planner:latest",body.get("model").getAsString());assertFalse(body.has("reasoning"));
                assertEquals("paracraft_decide",body.getAsJsonObject("tool_choice").getAsJsonObject("function").get("name").getAsString());
            }
            assertTrue(outputs.toString().contains("Visible hello"));assertFalse(outputs.toString().contains("[redacted key]"),"An absent key must not corrupt every character of output");
            assertTrue(client.todayUsage().costLabel().startsWith("API reported"));assertTrue(client.todayUsage().customEndpoint());assertTrue(client.todayUsage().details().stream().noneMatch(line->line.contains("OpenRouter Activity")));
            try(var restored=new ChatGptClient(directory)){
                assertEquals(base,restored.openRouter().baseUrl());assertTrue(restored.openRouter().ready());assertEquals(220,restored.todayUsage().total());
                new OpenRouterProvider(restored).infer(request(new OpenRouterProvider(restored),InferenceProvider.Route.LIGHT,ignored->{})).get(5,TimeUnit.SECONDS);
                assertEquals("/proxy/v1/chat/completions",fixture.path);assertNull(fixture.authorization);
            }
            fixture.status=404;fixture.response=json("{\"error\":{\"message\":\"Local model is not installed\"}}");
            var failure=assertThrows(ExecutionException.class,()->provider.infer(request(provider,InferenceProvider.Route.LIGHT,ignored->{})).get(5,TimeUnit.SECONDS));
            assertTrue(ChatGptClient.message(failure).contains("API HTTP 404"));assertEquals(4,fixture.requests.size(),"Actual failures get one request without fallback");
        }
    }
    @Test void endpointChangesNeverReuseAnotherEndpointsKeyOrUsageAndInvalidateLateDecisions()throws Exception{
        try(var first=new Fixture();var second=new Fixture();var client=new ChatGptClient(directory)){
            configure(client);String originalId=client.routerCredential().id();
            client.recordRouterUsage(originalId,json("{\"prompt_tokens\":10,\"completion_tokens\":1,\"cost\":0.01}"),"completed");
            client.configureOpenRouter("","local-model","auto","","",first.base()+"/v1").get(5,TimeUnit.SECONDS);
            assertEquals("",client.routerCredential().key());assertNotEquals(originalId,client.routerCredential().id());assertEquals(0,client.todayUsage().total());
            var provider=new OpenRouterProvider(client);provider.infer(request(provider,InferenceProvider.Route.LIGHT,ignored->{})).get(5,TimeUnit.SECONDS);assertNull(first.authorization);
            client.configureOpenRouter("private-local-key","local-model","auto","","",first.base()+"/v1").get(5,TimeUnit.SECONDS);
            String firstId=client.routerCredential().id();client.configureOpenRouter("","other-local-model","auto","","",first.base()+"/v1/").get(5,TimeUnit.SECONDS);
            assertEquals("private-local-key",client.routerCredential().key());assertEquals(firstId,client.routerCredential().id());
            first.hold=true;var output=new CopyOnWriteArrayList<JsonObject>();var pending=provider.infer(request(provider,InferenceProvider.Route.LIGHT,output::add));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(first.requests.size()<2 && System.nanoTime()<until)Thread.sleep(5);assertEquals(2,first.requests.size());
            client.configureOpenRouter("","local-model","auto","","",second.base()+"/v1").get(5,TimeUnit.SECONDS);first.release.countDown();
            assertThrows(CancellationException.class,()->pending.get(5,TimeUnit.SECONDS));assertTrue(output.isEmpty());assertEquals(0,client.todayUsage().total());assertNotEquals(firstId,client.routerCredential().id());
            provider.infer(request(provider,InferenceProvider.Route.LIGHT,ignored->{})).get(5,TimeUnit.SECONDS);assertNull(second.authorization);assertEquals(1,second.requests.size());
            client.recordRouterUsage(firstId,json("{\"prompt_tokens\":900,\"completion_tokens\":100,\"cost\":99}"),"completed");assertEquals(110,client.todayUsage().total());
            assertThrows(ExecutionException.class,()->client.configureOpenRouter("","exact/model","auto","","",ChatGptClient.OPENROUTER_BASE_URL).get(5,TimeUnit.SECONDS));
            assertEquals(second.base()+"/v1",client.openRouter().baseUrl(),"A failed save preserves the working configuration");
        }
    }
    @Test void legacyProfilesDefaultToOpenRouterAndBaseUrlsNormalizeWithoutEchoingInvalidText()throws Exception{
        configureLegacyProfile();
        try(var client=new ChatGptClient(directory)){
            assertEquals(ChatGptClient.OPENROUTER_BASE_URL,client.openRouter().baseUrl());assertEquals(KEY,client.routerCredential().key());
            assertEquals("http://localhost:11434/v1",ChatGptClient.normalizeApiBaseUrl(" http://localhost:11434/v1/chat/completions/ "));
            assertEquals("http://[::1]:11434/v1",ChatGptClient.normalizeApiBaseUrl("http://[::1]:11434/v1/"));
            assertEquals("http://localhost:11434/v1",ChatGptClient.normalizeApiBaseUrl("HTTP://localhost:11434/v1/"));
            assertEquals(ChatGptClient.OPENROUTER_BASE_URL,ChatGptClient.normalizeApiBaseUrl(""));
            for(String bad:List.of("/v1","file:///private-secret","http://user:private-secret@localhost/v1","http://localhost/v1?key=private-secret","http://localhost/v1#private-secret","not a private-secret URL")){
                var failure=assertThrows(IllegalArgumentException.class,()->client.configureOpenRouter("","local-model","auto","","",bad));assertFalse(failure.getMessage().contains("private-secret"));
            }
            assertEquals(KEY,client.routerCredential().key());
        }
    }
    @Test void localUsageWithoutCostStaysUnpricedInsteadOfApplyingLunaRates()throws Exception{
        try(var fixture=new Fixture();var client=new ChatGptClient(directory)){
            client.configureOpenRouter("","local-model","auto","","",fixture.base()+"/v1").get(5,TimeUnit.SECONDS);
            fixture.response.getAsJsonObject("usage").remove("cost");var provider=new OpenRouterProvider(client);
            provider.infer(request(provider,InferenceProvider.Route.LIGHT,ignored->{})).get(5,TimeUnit.SECONDS);
            assertEquals(110,client.todayUsage().total());assertEquals(1,client.todayUsage().unpricedCalls());assertEquals(0,client.todayUsage().estimatedCalls());assertTrue(client.todayUsage().costLabel().endsWith("*"));
        }
    }
    @Test void connectionFailureNamesTheEndpointAndEmitsRedactedDiagnostics()throws Exception{
        int port;try(var socket=new java.net.ServerSocket(0,0,InetAddress.getByName("127.0.0.1"))){port=socket.getLocalPort();}
        try(var client=new ChatGptClient(directory)){
            String base="http://localhost:"+port+"/v1";client.configureOpenRouter(KEY,"local-model","auto","","",base).get(5,TimeUnit.SECONDS);
            var provider=new OpenRouterProvider(client);var output=new CopyOnWriteArrayList<JsonObject>();
            var failure=assertThrows(ExecutionException.class,()->provider.infer(request(provider,InferenceProvider.Route.LIGHT,output::add)).get(5,TimeUnit.SECONDS));
            String message=ChatGptClient.message(failure);assertTrue(message.contains("Cannot connect to API at "+base+"/chat/completions"));assertFalse(message.contains(KEY));
            assertEquals(1,output.size());assertEquals("inference_error",output.get(0).get("type").getAsString());assertEquals(message,output.get(0).get("message").getAsString());assertEquals(0,client.todayUsage().total());
        }
    }
    private void configureLegacyProfile()throws Exception{
        var store=new AccountStore(directory);store.transact(state->{state.addProperty("mode","openrouter");var profile=json("{\"id\":\"legacy\",\"light_model\":\"exact/model\",\"planning_model\":\"exact/model\"}");profile.addProperty("api_key",KEY);state.add("openrouter",profile);return null;});
    }
    private static JsonObject completion(String arguments){
        var value=json("{\"id\":\"fixture-generation\",\"choices\":[{\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\",\"content\":\"Visible hello\",\"reasoning\":\"private chain\",\"tool_calls\":[{\"type\":\"function\",\"function\":{\"name\":\"paracraft_decide\",\"arguments\":\"\"}}]}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10,\"total_tokens\":110,\"prompt_tokens_details\":{\"cached_tokens\":20},\"cost\":0.0123}}");value.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").getAsJsonArray("tool_calls").get(0).getAsJsonObject().getAsJsonObject("function").addProperty("arguments",arguments);return value;
    }
    private static final class Fixture implements AutoCloseable{
        final HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        final ExecutorService executor=Executors.newCachedThreadPool(r->{var thread=new Thread(r,"OpenRouter HTTP fixture");thread.setDaemon(true);return thread;});
        final List<JsonObject> requests=new CopyOnWriteArrayList<>();final CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
        volatile boolean hold;volatile int status=200;volatile String authorization,routerTitle,path;volatile JsonObject response=completion(CHAT);
        Fixture()throws IOException{server.setExecutor(executor);server.createContext("/",exchange->{try{authorization=exchange.getRequestHeaders().getFirst("Authorization");routerTitle=exchange.getRequestHeaders().getFirst("X-OpenRouter-Title");path=exchange.getRequestURI().getPath();requests.add(json(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)));started.countDown();if(hold)try{release.await(10,TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}byte[] bytes=response.toString().getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}}finally{exchange.close();}});server.start();}
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        URI uri(){return URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions");}
        @Override public void close(){release.countDown();server.stop(0);executor.shutdownNow();}
    }
}
