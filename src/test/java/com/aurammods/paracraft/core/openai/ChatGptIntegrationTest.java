package com.aurammods.paracraft.core.openai;

import com.aurammods.paracraft.core.agent.InferenceProvider;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real local HTTP/OIDC/SSE, fake identities/tokens. Never contacts OpenAI or a user account. */
class ChatGptIntegrationTest {
    @TempDir Path directory;
    @Test void corruptPrivateRecordIsPreservedWithoutEchoingItsContents()throws Exception{
        Path file=directory.resolve("accounts.json");String privateData="\"do_not_echo_private_credential\"";Files.writeString(file,privateData);var failure=assertThrows(IOException.class,()->new ChatGptClient(directory));assertFalse(failure.getMessage().contains("do_not_echo"));assertEquals(privateData,Files.readString(file));
    }
    @Test void browserRegistrationValidatesPkceAndCachesPrivateReusableSession()throws Exception{
        try(var f=new Fixture();var client=new ChatGptClient(directory,f.endpoints())){
            f.login(client,true);assertTrue(client.selected().signedIn());assertTrue(client.selected().planEnabled());assertEquals("chatgpt",client.mode());
            var state=read();String host=state.get("hostId").getAsString(),issued=client.selected().clientId();assertTrue(host.startsWith("urn:uuid:"));assertNotEquals("dynamic_agent_client",issued);assertEquals(1,f.codes.get());
            assertEquals("access-1",client.accessToken(false));assertFalse(client.selected().toString().contains("access-1"));
            assertEquals(PosixFilePermissions.fromString("rw-------"),Files.getPosixFilePermissions(directory.resolve("accounts.json")));assertEquals(PosixFilePermissions.fromString("rwx------"),Files.getPosixFilePermissions(directory));
            try(var restored=new ChatGptClient(directory,f.endpoints())){assertEquals(issued,restored.selected().clientId());assertEquals("access-1",restored.accessToken(false));assertEquals(host,read().get("hostId").getAsString());assertEquals(0,f.refreshes.get());}
        }
    }
    @Test void wrongCallbackStateCannotConsumeTheValidAttempt()throws Exception{
        try(var f=new Fixture();var client=new ChatGptClient(directory,f.endpoints())){
            URI page=client.signIn(true).get(5,TimeUnit.SECONDS);URI authorization=f.authorization(page);var params=ChatGptClient.parseQuery(authorization.getRawQuery());
            var bad=URI.create(params.get("redirect_uri")+"?code=bad&state=wrong&client_id=oaiapp_bad");assertEquals(400,f.browser.send(HttpRequest.newBuilder(bad).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());assertTrue(client.signingIn());assertEquals(0,f.codes.get());
            var completion=client.loginCompletion();assertEquals(200,f.browser.send(HttpRequest.newBuilder(authorization).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());completion.get(5,TimeUnit.SECONDS);assertTrue(client.selected().signedIn());
        }
    }
    @Test void invalidSignedIdentityDoesNotOverwriteExistingCredentials()throws Exception{
        try(var f=new Fixture();var client=new ChatGptClient(directory,f.endpoints())){
            f.login(client,true);String first=client.selected().id();
            for(String fault:List.of("nonce","audience","expired","signature")){
                f.identityFault=fault;var attempt=f.start(client,true);var response=f.browser.send(HttpRequest.newBuilder(attempt.authorization).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(400,response.statusCode(),fault);assertThrows(ExecutionException.class,()->attempt.completion.get(5,TimeUnit.SECONDS));assertEquals(first,client.selected().id());assertEquals(1,client.accounts().size());assertTrue(client.selected().signedIn());
            }
        }
    }
    @Test void declinedPlanPermissionRetainsIdentityAndDoesNotAuthorizeInference()throws Exception{
        try(var f=new Fixture();var client=new ChatGptClient(directory,f.endpoints())){
            f.grantedScopes="openid profile email offline_access";f.login(client,true);assertTrue(client.selected().signedIn());assertFalse(client.selected().planEnabled());
            var result=new ChatGptProvider(client).infer(request(InferenceProvider.Route.LIGHT));assertThrows(ExecutionException.class,()->result.get(5,TimeUnit.SECONDS));assertEquals(0,f.requests.get());assertTrue(client.notice().contains("plan usage"));
        }
    }
    @Test void parallelClientsSerializeRotatingRefreshAndPreserveRegistration()throws Exception{
        try(var f=new Fixture();var client=new ChatGptClient(directory,f.endpoints())){
            f.login(client,true);expire();try(var other=new ChatGptClient(directory,f.endpoints())){
                var work=new ArrayList<CompletableFuture<String>>();for(int i=0;i<8;i++){ChatGptClient chosen=i%2==0?client:other;work.add(CompletableFuture.supplyAsync(()->{try{return chosen.accessToken(false);}catch(Exception e){throw new CompletionException(e);}}));}
                for(var pending:work)assertEquals("access-refreshed-1",pending.get(10,TimeUnit.SECONDS));assertEquals(1,f.refreshes.get());assertEquals("refresh-rotated-1",active().get("refresh_token").getAsString());assertFalse(f.lastRefresh.containsKey("scope"));assertTrue(client.selected().planEnabled());
            }
        }
    }
    @Test void transientRefreshPreservesCredentialsButTerminalGrantIsCleared()throws Exception{
        try(var f=new Fixture();var client=new ChatGptClient(directory,f.endpoints())){
            f.login(client,true);expire();f.refreshError="temporary";var temporary=assertThrows(OpenAiHttp.Failure.class,()->client.accessToken(false));assertFalse(temporary.getMessage().contains("refresh-1"));assertTrue(active().has("refresh_token"));assertTrue(client.selected().signedIn());
            f.refreshError="invalid_grant";assertThrows(OpenAiHttp.Failure.class,()->client.accessToken(false));assertFalse(active().has("access_token"));assertFalse(active().has("refresh_token"));assertFalse(client.selected().signedIn());assertTrue(active().has("client_id"));
        }
    }
    @Test void missingPlanPermissionCanBeEnabledWithExplicitConsent()throws Exception{
        try(var f=new Fixture();var client=new ChatGptClient(directory,f.endpoints())){f.grantedScopes="openid profile email";f.login(client,true);String issued=client.selected().clientId();f.grantedScopes="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";f.login(client,false);assertEquals(issued,client.selected().clientId());assertEquals("consent",f.lastAuthorize.get("prompt"));assertTrue(client.selected().planEnabled());}
    }
    @Test void expiredRegistrationCodeRetainsIssuedClientForFreshAuthorization()throws Exception{
        try(var f=new Fixture();var client=new ChatGptClient(directory,f.endpoints())){f.codeError=true;var first=f.start(client,false);assertEquals(400,f.browser.send(HttpRequest.newBuilder(first.authorization).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());assertThrows(ExecutionException.class,()->first.completion.get(5,TimeUnit.SECONDS));String issued=read().get("pendingClientId").getAsString();f.codeError=false;f.login(client,false);assertEquals(issued,client.selected().clientId());assertEquals(1,f.registrations.get());assertFalse(read().has("pendingClientId"));}
    }
    @Test void planningUsesHighRouteAndThePackagedGameplayContract()throws Exception{
        try(var f=new Fixture();var client=new ChatGptClient(directory,f.endpoints())){f.login(client,true);f.replyTool="task_upsert";var decisions=new ChatGptProvider(client).infer(request(InferenceProvider.Route.PLANNING)).get(5,TimeUnit.SECONDS);assertEquals("task_upsert",decisions.calls().get(0).tool());assertEquals("xhigh",f.lastRequest.getAsJsonObject("reasoning").get("effort").getAsString());assertTrue(f.lastRequest.get("instructions").getAsString().contains("open_inventory"));var input=JsonParser.parseString(f.lastRequest.getAsJsonArray("input").get(0).getAsJsonObject().get("content").getAsString()).getAsJsonObject();assertDoesNotThrow(()->UUID.fromString(input.get("suggested_program_id").getAsString()));assertEquals("Hello Josh",input.getAsJsonObject("original_trigger").get("message").getAsString());}
    }
    @Test void signOutRevokesAndReturningLoginReusesClientAndHost()throws Exception{
        try(var f=new Fixture();var client=new ChatGptClient(directory,f.endpoints())){
            f.login(client,true);String issued=client.selected().clientId(),host=read().get("hostId").getAsString();client.signOut().get(5,TimeUnit.SECONDS);
            assertEquals(1,f.revocations.get());assertFalse(active().has("id_token"));assertEquals("simulation",client.mode());assertEquals("refresh_token",f.lastRevoke.get("token_type_hint"));
            f.login(client,false);assertEquals(issued,client.selected().clientId());assertEquals(issued,f.lastAuthorize.get("client_id"));assertFalse(f.lastAuthorize.containsKey("agent_name_hint"));assertFalse(f.lastAuthorize.containsKey("id_token_hint"));assertEquals(host,f.lastAuthorize.get("ext_agent_host_id"));
            f.login(client,true);assertEquals(2,client.accounts().size());var first=client.accounts().get(0);client.select(first.id()).get(5,TimeUnit.SECONDS);assertEquals(first.id(),client.selected().id());
        }
    }
    @Test void namespacedLiveRequestWaitsForCompletionAndCachesOnlyReportedUsage()throws Exception{
        try(var f=new Fixture();var client=new ChatGptClient(directory,f.endpoints())){
            f.login(client,true);var decisions=new ChatGptProvider(client).infer(request(InferenceProvider.Route.LIGHT)).get(10,TimeUnit.SECONDS);assertEquals("chat",decisions.calls().get(0).tool());assertEquals("Hello Josh",decisions.calls().get(0).arguments().get("message").getAsString());
            var body=f.lastRequest;assertFalse(body.get("store").getAsBoolean());assertTrue(body.get("stream").getAsBoolean());assertTrue(body.get("input").isJsonArray());assertFalse(body.has("previous_response_id"));assertFalse(body.has("max_output_tokens"));assertEquals("namespace",body.getAsJsonArray("tools").get(0).getAsJsonObject().get("type").getAsString());assertEquals("gpt-6-luna",body.get("model").getAsString());assertEquals("none",body.getAsJsonObject("reasoning").get("effort").getAsString());assertFalse(body.toString().contains("access-1") || body.toString().contains("refresh-1"));
            assertEquals(110,active().getAsJsonObject("usage").get("total_tokens").getAsLong());assertEquals(60,active().getAsJsonObject("usage").get("cached_input_tokens").getAsLong());assertTrue(client.usage().contains("1 completed"));
        }
    }
    @Test void catalogDoesNotGateAnActualAttemptWithAnUnlistedRequestedModel()throws Exception{
        try(var f=new Fixture();var client=new ChatGptClient(directory,f.endpoints())){
            f.login(client,true);var models=client.refreshModels().get(5,TimeUnit.SECONDS);assertEquals("another-model",models.get(0).slug());f.streamMode="model_error";
            var failure=assertThrows(ExecutionException.class,()->new ChatGptProvider(client).infer(request(InferenceProvider.Route.LIGHT)).get(5,TimeUnit.SECONDS));assertTrue(failure.getCause().getMessage().contains("model_not_found"));assertEquals(1,f.requests.get());assertEquals("gpt-6-luna",f.lastRequest.get("model").getAsString());assertTrue(client.selected().signedIn());
        }
    }
    @Test void actualUnauthorizedResponseRefreshesOnceAndRetriesWithSavedSession()throws Exception{
        try(var f=new Fixture();var client=new ChatGptClient(directory,f.endpoints())){f.login(client,true);f.streamMode="once_401";var decisions=new ChatGptProvider(client).infer(request(InferenceProvider.Route.LIGHT)).get(5,TimeUnit.SECONDS);assertEquals("chat",decisions.calls().get(0).tool());assertEquals(2,f.requests.get());assertEquals(1,f.refreshes.get());assertEquals(1,active().getAsJsonObject("usage").get("completed").getAsInt());}
    }
    @Test void partialFailedAndIncompleteStreamsNeverYieldActions()throws Exception{
        try(var f=new Fixture();var client=new ChatGptClient(directory,f.endpoints())){
            f.login(client,true);for(String mode:List.of("failed","incomplete","truncated")){f.streamMode=mode;assertThrows(ExecutionException.class,()->new ChatGptProvider(client).infer(request(InferenceProvider.Route.LIGHT)).get(5,TimeUnit.SECONDS));}assertEquals(0,active().getAsJsonObject("usage").has("completed")?active().getAsJsonObject("usage").get("completed").getAsInt():0);assertTrue(client.selected().signedIn());
        }
    }
    @Test void forbiddenLightBatchAndCancelledResponseCannotReachTheHarness()throws Exception{
        try(var f=new Fixture();var client=new ChatGptClient(directory,f.endpoints())){
            f.login(client,true);f.replyTool="program";assertThrows(ExecutionException.class,()->new ChatGptProvider(client).infer(request(InferenceProvider.Route.LIGHT)).get(5,TimeUnit.SECONDS));
            f.replyTool="chat";f.streamMode="slow";var pending=new ChatGptProvider(client).infer(request(InferenceProvider.Route.LIGHT));assertTrue(f.streamStarted.await(5,TimeUnit.SECONDS));assertFalse(pending.isDone());assertTrue(pending.cancel(true));f.release.countDown();assertTrue(pending.isCancelled());
        }
    }
    private InferenceProvider.Request request(InferenceProvider.Route route){var snapshot=new JsonObject();snapshot.addProperty("name","Josh");var trigger=new JsonObject();trigger.addProperty("type","chat");trigger.addProperty("message","Hello Josh");return new InferenceProvider.Request(route,new InferenceProvider.ModelRoute("gpt-6-luna",route==InferenceProvider.Route.LIGHT?"none":"xhigh"),snapshot,trigger,new JsonObject());}
    private JsonObject read()throws IOException{return JsonParser.parseString(Files.readString(directory.resolve("accounts.json"))).getAsJsonObject();}
    private JsonObject active()throws IOException{var state=read();return state.getAsJsonObject("accounts").getAsJsonObject(state.get("selected").getAsString());}
    private void expire()throws IOException{var state=read();state.getAsJsonObject("accounts").getAsJsonObject(state.get("selected").getAsString()).addProperty("expires_at",Instant.now().getEpochSecond()-10);Files.writeString(directory.resolve("accounts.json"),state.toString());}
    record Attempt(URI authorization,CompletableFuture<Void> completion){}
    private static final class Fixture implements AutoCloseable {
        final HttpServer server;final ExecutorService threads=Executors.newCachedThreadPool(r->{var t=new Thread(r);t.setDaemon(true);return t;});final HttpClient browser=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build();
        final KeyPair keys;final String base;final AtomicInteger codes=new AtomicInteger(),registrations=new AtomicInteger(),refreshes=new AtomicInteger(),requests=new AtomicInteger(),revocations=new AtomicInteger();final Map<String,Map<String,String>> authorizations=new ConcurrentHashMap<>();
        volatile Map<String,String> lastAuthorize=Map.of(),lastRefresh=Map.of(),lastRevoke=Map.of();volatile JsonObject lastRequest;volatile boolean codeError;volatile String identityFault="",grantedScopes="openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",refreshError="",streamMode="completed",replyTool="chat";final CountDownLatch streamStarted=new CountDownLatch(1),release=new CountDownLatch(1);
        Fixture()throws Exception{var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);keys=generator.generateKeyPair();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);base="http://127.0.0.1:"+server.getAddress().getPort();server.setExecutor(threads);server.createContext("/",this::handle);server.start();}
        ChatGptClient.Endpoints endpoints(){return new ChatGptClient.Endpoints(URI.create(base+"/authorize"),URI.create(base+"/token"),URI.create(base+"/discovery"),URI.create(base+"/v1/"),"https://api.openai.com/v1");}
        Attempt start(ChatGptClient client,boolean add)throws Exception{URI page=client.signIn(add).get(5,TimeUnit.SECONDS);return new Attempt(authorization(page),client.loginCompletion());}
        void login(ChatGptClient client,boolean add)throws Exception{var attempt=start(client,add);var reply=browser.send(HttpRequest.newBuilder(attempt.authorization).GET().build(),HttpResponse.BodyHandlers.ofString());assertEquals(200,reply.statusCode(),reply.body());attempt.completion.get(5,TimeUnit.SECONDS);}
        URI authorization(URI page)throws Exception{String html=browser.send(HttpRequest.newBuilder(page).GET().build(),HttpResponse.BodyHandlers.ofString()).body();var match=java.util.regex.Pattern.compile("href=\"([^\"]+)\"").matcher(html);assertTrue(match.find());return URI.create(match.group(1).replace("&amp;","&"));}
        void handle(HttpExchange exchange)throws IOException{
            try{switch(exchange.getRequestURI().getPath()){
                case "/discovery"->json(exchange,200,"{\"issuer\":\""+base+"\",\"jwks_uri\":\""+base+"/jwks\",\"revocation_endpoint\":\""+base+"/revoke\"}");
                case "/jwks"->{var rsa=(RSAPublicKey)keys.getPublic();json(exchange,200,"{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"test\",\"alg\":\"RS256\",\"use\":\"sig\",\"n\":\""+unsigned(rsa.getModulus())+"\",\"e\":\""+unsigned(rsa.getPublicExponent())+"\"}]}");}
                case "/authorize"->{var values=new HashMap<>(ChatGptClient.parseQuery(exchange.getRequestURI().getRawQuery()));lastAuthorize=Map.copyOf(values);String client=values.get("client_id");if(client.equals("dynamic_agent_client")){assertEquals("Paracraft",values.get("agent_name_hint"));client="oaiapp_test_"+registrations.incrementAndGet();}values.put("issued",client);String code=UUID.randomUUID().toString();authorizations.put(code,values);exchange.getResponseHeaders().set("Location",values.get("redirect_uri")+"?"+OpenAiHttp.form(Map.of("code",code,"state",values.get("state"),"client_id",client)));exchange.sendResponseHeaders(302,-1);exchange.close();}
                case "/token"->token(exchange);
                case "/revoke"->{lastRevoke=ChatGptClient.parseQuery(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));revocations.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();}
                case "/v1/models"->json(exchange,200,"{\"models\":[{\"slug\":\"another-model\",\"display_name\":\"Another model\",\"visibility\":\"list\"},{\"slug\":\"hidden\",\"visibility\":\"hide\"}]}");
                case "/v1/responses"->responses(exchange);
                default->json(exchange,404,"{}");
            }}catch(Throwable failure){try{json(exchange,500,"{\"error\":\"fixture_failure\"}");}catch(IOException ignored){}throw new RuntimeException(failure);}
        }
        void token(HttpExchange exchange)throws Exception{
            var form=ChatGptClient.parseQuery(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));assertFalse(form.containsKey("client_secret"));assertEquals("https://api.openai.com/v1",form.get("resource"));
            if(form.get("grant_type").equals("refresh_token")){lastRefresh=Map.copyOf(form);int number=refreshes.incrementAndGet();if(!refreshError.isEmpty()){json(exchange,refreshError.equals("temporary")?503:400,"{\"error\":\""+(refreshError.equals("temporary")?"temporarily_unavailable":refreshError)+"\",\"error_description\":\"refresh-1 must not leak\"}");return;}json(exchange,200,"{\"access_token\":\"access-refreshed-"+number+"\",\"refresh_token\":\"refresh-rotated-"+number+"\",\"expires_in\":3600}");return;}
            var pending=authorizations.remove(form.get("code"));assertNotNull(pending);assertEquals(pending.get("issued"),form.get("client_id"));assertEquals(pending.get("redirect_uri"),form.get("redirect_uri"));assertEquals(pending.get("code_challenge"),b64(MessageDigest.getInstance("SHA-256").digest(form.get("code_verifier").getBytes(StandardCharsets.US_ASCII))));
            if(codeError){json(exchange,400,"{\"error\":\"invalid_grant\"}");return;}
            int number=codes.incrementAndGet();var tokens=new JsonObject();tokens.addProperty("access_token","access-"+number);tokens.addProperty("refresh_token","refresh-"+number);tokens.addProperty("id_token",idToken(pending));tokens.addProperty("scope",grantedScopes);tokens.addProperty("expires_in",3600);json(exchange,200,tokens.toString());
        }
        String idToken(Map<String,String> pending)throws Exception{var claims=new JsonObject();claims.addProperty("iss",base);claims.addProperty("aud",identityFault.equals("audience")?"wrong-client":pending.get("issued"));claims.addProperty("sub","test-subject");claims.addProperty("email","owner@example.test");claims.addProperty("nonce",identityFault.equals("nonce")?"wrong-nonce":pending.get("nonce"));claims.addProperty("exp",Instant.now().getEpochSecond()+(identityFault.equals("expired")?-1:3600));String input=b64("{\"alg\":\"RS256\",\"kid\":\"test\"}".getBytes(StandardCharsets.UTF_8))+"."+b64(claims.toString().getBytes(StandardCharsets.UTF_8));var signature=Signature.getInstance("SHA256withRSA");signature.initSign(keys.getPrivate());signature.update(input.getBytes(StandardCharsets.US_ASCII));byte[] signed=signature.sign();if(identityFault.equals("signature"))signed[0]^=1;return input+"."+b64(signed);}
        void responses(HttpExchange exchange)throws Exception{int number=requests.incrementAndGet();assertTrue(exchange.getRequestHeaders().getFirst("Authorization").startsWith("Bearer access-"));lastRequest=JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
            if(streamMode.equals("once_401") && number==1){json(exchange,401,"{\"error\":{\"code\":\"invalid_token\",\"message\":\"Rejected access-1\"}}");return;}
            if(streamMode.equals("model_error")){json(exchange,400,"{\"error\":{\"code\":\"model_not_found\",\"message\":\"Requested model unavailable\",\"param\":\"model\"}}");return;}
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.getResponseHeaders().set("x-request-id","test-response-1");exchange.sendResponseHeaders(200,0);try(var output=exchange.getResponseBody()){
                send(output,"{\"type\":\"response.output_text.delta\",\"delta\":\"not a completed decision\"}");
                if(streamMode.equals("slow")){streamStarted.countDown();release.await(5,TimeUnit.SECONDS);}
                if(streamMode.equals("truncated"))return;
                if(streamMode.equals("incomplete")){send(output,"{\"type\":\"response.incomplete\",\"response\":{\"status\":\"incomplete\"}}");return;}
                if(streamMode.equals("failed")){send(output,"{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\",\"message\":\"Usage limit reached\"}}}");return;}
                var arguments=new JsonObject();var calls=new JsonArray();var call=new JsonObject();call.addProperty("tool",replyTool);call.add("arguments",JsonParser.parseString(replyTool.equals("task_upsert")?"{\"id\":\"fixture-task\",\"goal\":\"Help the owner\",\"status\":\"ACTIVE\",\"priority\":null,\"programId\":null}":"{\"message\":\"Hello Josh\"}"));calls.add(call);arguments.add("calls",calls);var item=new JsonObject();item.addProperty("type","function_call");item.addProperty("namespace","paracraft");item.addProperty("name","decide");item.addProperty("arguments",arguments.toString());var response=new JsonObject();var items=new JsonArray();items.add(item);response.add("output",items);response.addProperty("status","completed");response.add("usage",JsonParser.parseString("{\"input_tokens\":100,\"output_tokens\":10,\"total_tokens\":110,\"input_tokens_details\":{\"cached_tokens\":60}}"));var event=new JsonObject();event.addProperty("type","response.completed");event.add("response",response);send(output,event.toString());
            }
        }
        static void send(OutputStream output,String json)throws IOException{output.write(("data: "+json+"\n\n").getBytes(StandardCharsets.UTF_8));output.flush();}
        static void json(HttpExchange exchange,int status,String body)throws IOException{byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}}
        static String unsigned(java.math.BigInteger value){byte[] bytes=value.toByteArray();if(bytes.length>1 && bytes[0]==0)bytes=Arrays.copyOfRange(bytes,1,bytes.length);return b64(bytes);}static String b64(byte[] bytes){return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
        @Override public void close(){release.countDown();server.stop(0);threads.shutdownNow();}
    }
}
