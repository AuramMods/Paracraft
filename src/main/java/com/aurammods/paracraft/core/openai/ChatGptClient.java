package com.aurammods.paracraft.core.openai;

import com.aurammods.paracraft.core.agent.InferenceProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Local ChatGPT sign-in, OpenRouter settings, account selection and client-only provider state. */
public final class ChatGptClient implements AutoCloseable {
    record Endpoints(URI authorize,URI token,URI discovery,URI api,String resource){
        static Endpoints production(){return new Endpoints(URI.create("https://auth.openai.com/api/accounts/authorize"),URI.create("https://auth.openai.com/api/accounts/oauth/token"),URI.create("https://auth.openai.com/.well-known/openid-configuration"),URI.create("https://api.openai.com/v1/"),"https://api.openai.com/v1");}
    }
    public record Account(String id,String email,String clientId,boolean signedIn,boolean planEnabled) {public String label(){return (email.isBlank()?"ChatGPT account":email)+" · "+clientId.substring(Math.max(0,clientId.length()-8));}}
    public record Model(String slug,String name,String details){}
    public record OpenRouterSettings(boolean keySaved,String lightModel,String lightEffort,String planningModel,String planningEffort){
        public InferenceProvider.ModelRoute route(InferenceProvider.Route route){return route==InferenceProvider.Route.LIGHT?new InferenceProvider.ModelRoute(lightModel,lightEffort):new InferenceProvider.ModelRoute(planningModel,planningEffort);}
    }
    record RouterCredential(String id,String key){@Override public String toString(){return "Private OpenRouter credential";}}
    final OpenAiHttp http=new OpenAiHttp();final Endpoints endpoints;
    final ScheduledExecutorService io=Executors.newScheduledThreadPool(4,r->{var t=new Thread(r,"Paracraft ChatGPT client");t.setDaemon(true);return t;});
    final ExecutorService inference=Executors.newCachedThreadPool(r->{var t=new Thread(r,"Paracraft model stream");t.setDaemon(true);return t;});
    private final AccountStore store;private final SecureRandom random=new SecureRandom();private final AtomicLong revision=new AtomicLong();
    private volatile List<Account> accounts=List.of();private volatile List<Model> models=List.of();private volatile String selected="",mode="simulation",notice="Not signed in";
    private volatile Login login;private volatile CompletableFuture<Void> lastLogin;private volatile boolean closed,requestsStopped;private JsonObject discovery;private final Object metadataLock=new Object();
    private volatile String usage="No recorded live usage";
    private record UsageSnapshot(String account,JsonObject value) { }
    private volatile UsageSnapshot usageSnapshot=new UsageSnapshot("",new JsonObject());
    private volatile OpenRouterSettings openRouter=new OpenRouterSettings(false,"","auto","","auto");
    private volatile UsageSnapshot routerUsage=new UsageSnapshot("",new JsonObject());
    private CompletableFuture<Void> usageRefresh;
    private final java.time.Clock usageClock;
    public ChatGptClient(Path directory)throws Exception{this(directory,Endpoints.production());}
    ChatGptClient(Path directory,Endpoints endpoints)throws Exception{this(directory,endpoints,java.time.Clock.systemDefaultZone());}
    ChatGptClient(Path directory,Endpoints endpoints,java.time.Clock usageClock)throws Exception{store=new AccountStore(directory);this.endpoints=endpoints;this.usageClock=usageClock;reload();notice=mode().equals("openrouter")?"Saved OpenRouter settings loaded":selected()!=null && selected().signedIn()?"Saved session loaded; refreshes when needed":"Link ChatGPT or configure OpenRouter to enable live inference";}
    public static Path defaultDirectory(){String override=System.getProperty("paracraft.auth.directory");return override==null?Path.of(System.getProperty("user.home"),".paracraft","chatgpt"):Path.of(override);}
    private void reload()throws Exception{
        store.transact(state->{var list=new ArrayList<Account>();for(var entry:state.getAsJsonObject("accounts").entrySet()){var a=entry.getValue().getAsJsonObject();list.add(new Account(entry.getKey(),OpenAiHttp.string(a,"email",""),a.get("client_id").getAsString(),a.has("access_token"),planEnabled(a)));}accounts=List.copyOf(list);selected=OpenAiHttp.string(state,"selected","");mode=OpenAiHttp.string(state,"mode","simulation");var current=state.getAsJsonObject("accounts").getAsJsonObject(selected);cacheUsage(selected,current);cacheRouter(state.getAsJsonObject("openrouter"));return null;});
    }
    public List<Account> accounts(){return accounts;}public List<Model> models(){return models;}public Account selected(){return accounts.stream().filter(a->a.id.equals(selected)).findFirst().orElse(null);}
    public String mode(){return requestsStopped?"simulation":mode;}public String notice(){return notice;}public long revision(){return revision.get();}public boolean signingIn(){return login!=null;}
    public String label(){if(mode().equals("openrouter"))return openRouter.keySaved()?"OpenRouter · "+openRouter.lightModel()+" / "+openRouter.planningModel():"OpenRouter · API key required";var a=selected();return mode().equals("chatgpt")?a==null || !a.signedIn()?"ChatGPT · sign-in required":!a.planEnabled()?"ChatGPT · plan permission required":"ChatGPT · "+a.label():"SIMULATED · no AI usage";}
    public Path directory(){return store.directory;}
    public String usage(){return mode().equals("openrouter")?usageLabel(routerUsage.value):usage;}
    /** Pure cached reads for rendering; midnight rollover needs no file or network operation. */
    public UsageLedger.Today todayUsage(){if(mode().equals("openrouter"))return UsageLedger.today(routerUsage.value,usageClock.instant(),usageClock.getZone());var snapshot=usageSnapshot;return UsageLedger.today(snapshot.account.equals(selected)?snapshot.value:new JsonObject(),usageClock.instant(),usageClock.getZone());}
    public synchronized CompletableFuture<Void> refreshUsage(){
        if(usageRefresh!=null && !usageRefresh.isDone())return usageRefresh;
        String id=selected;usageRefresh=async(()->{store.transact(state->{if(id.equals(selected))cacheUsage(id,state.getAsJsonObject("accounts").getAsJsonObject(id));var profile=state.getAsJsonObject("openrouter");if(profile!=null && routerUsage.account.equals(OpenAiHttp.string(profile,"id","")))cacheRouterUsage(profile);return null;});return null;});return usageRefresh;
    }
    private void cacheUsage(String id,JsonObject account){
        JsonObject value=account==null?new JsonObject():account.has("usage")?account.getAsJsonObject("usage"):new JsonObject();
        if(account!=null){UsageLedger.initialize(value,usageClock.instant(),usageClock.getZone());account.add("usage",value);}
        usageSnapshot=new UsageSnapshot(id,value.deepCopy());usage=usageLabel(value);
    }
    private void cacheRouter(JsonObject profile){
        openRouter=new OpenRouterSettings(profile!=null && !OpenAiHttp.string(profile,"api_key","").isBlank(),profile==null?"":OpenAiHttp.string(profile,"light_model",""),profile==null?"auto":OpenAiHttp.string(profile,"light_effort","auto"),profile==null?"":OpenAiHttp.string(profile,"planning_model",""),profile==null?"auto":OpenAiHttp.string(profile,"planning_effort","auto"));cacheRouterUsage(profile);
    }
    private void cacheRouterUsage(JsonObject profile){
        var value=profile!=null && profile.has("usage")?profile.getAsJsonObject("usage"):new JsonObject();value.addProperty("provider","openrouter");UsageLedger.initialize(value,usageClock.instant(),usageClock.getZone());if(profile!=null)profile.add("usage",value);routerUsage=new UsageSnapshot(profile==null?"":OpenAiHttp.string(profile,"id",""),value.deepCopy());
    }
    public OpenRouterSettings openRouter(){return openRouter;}
    /** Blank key retains the saved secret; exact model IDs are sent without catalog checks. */
    public CompletableFuture<Void> configureOpenRouter(String key,String light,String lightEffort,String planning,String planningEffort){
        String apiKey=key.strip(),lightModel=light.strip(),planningModel=planning.isBlank()?lightModel:planning.strip(),le=lightEffort.isBlank()?"auto":lightEffort.strip(),pe=planningEffort.isBlank()?"auto":planningEffort.strip();
        new InferenceProvider.ModelRoute(lightModel,le);new InferenceProvider.ModelRoute(planningModel,pe);
        if(apiKey.chars().anyMatch(c->c<=32 || c==127))throw new IllegalArgumentException("API key must not contain whitespace or control characters");
        cancelLogin();
        return async(()->{store.transact(state->{var existing=state.getAsJsonObject("openrouter");var profile=existing==null?new JsonObject():existing.deepCopy();String previous=OpenAiHttp.string(profile,"api_key","");String next=apiKey.isEmpty()?previous:apiKey;if(next.isBlank())throw new IOException("Enter an OpenRouter API key first");
                if(!next.equals(previous) || !profile.has("id")){profile.addProperty("id",UUID.randomUUID().toString());profile.remove("usage");}profile.addProperty("api_key",next);profile.addProperty("light_model",lightModel);profile.addProperty("light_effort",le);profile.addProperty("planning_model",planningModel);profile.addProperty("planning_effort",pe);state.add("openrouter",profile);state.addProperty("mode","openrouter");return null;});reload();requestsStopped=false;revision.incrementAndGet();notice="OpenRouter selected; exact model IDs will be used on the next decision";return null;
            });
    }
    public CompletableFuture<Void> forgetOpenRouter(){
        boolean active=mode().equals("openrouter");if(active)requestsStopped=true;revision.incrementAndGet();return async(()->{store.transact(state->{var profile=state.getAsJsonObject("openrouter");if(profile!=null){profile.remove("api_key");profile.addProperty("id",UUID.randomUUID().toString());profile.remove("usage");}if("openrouter".equals(OpenAiHttp.string(state,"mode","")))state.addProperty("mode","simulation");return null;});reload();if(active)requestsStopped=false;revision.incrementAndGet();notice="OpenRouter key removed from this computer";return null;});
    }
    RouterCredential routerCredential()throws Exception{
        if(!mode().equals("openrouter"))throw new CancellationException("OpenRouter is not selected");return store.transact(state->{if(!"openrouter".equals(OpenAiHttp.string(state,"mode","")))throw new CancellationException("Selected provider changed");var profile=state.getAsJsonObject("openrouter");if(profile==null || OpenAiHttp.string(profile,"api_key","").isBlank())throw new IOException("Configure OpenRouter in Paracraft Accounts first");return new RouterCredential(profile.get("id").getAsString(),profile.get("api_key").getAsString());});
    }
    void recordRouterUsage(String id,JsonObject reported,String outcome)throws Exception{
        store.transact(state->{var profile=state.getAsJsonObject("openrouter");if(profile==null || !id.equals(OpenAiHttp.string(profile,"id","")))return null;var value=profile.has("usage")?profile.getAsJsonObject("usage"):new JsonObject();UsageLedger.recordOpenRouter(value,reported,outcome,usageClock.instant(),usageClock.getZone());profile.add("usage",value);if(routerUsage.account.equals(id))cacheRouterUsage(profile);return null;});
    }
    public CompletableFuture<String> publicMetadata(){return async(()->{var value=discovery();return "Issuer: "+value.get("issuer").getAsString()+"\nSigning keys: "+value.get("jwks_uri").getAsString()+"\nID-token algorithms: "+value.get("id_token_signing_alg_values_supported");});}
    public CompletableFuture<Void> reloadSaved(){return async(()->{reload();requestsStopped=false;models=List.of();revision.incrementAndGet();notice="Reloaded local session";return null;});}
    public CompletableFuture<Void> select(String id){cancelLogin();return async(()->{store.transact(state->{if(!state.getAsJsonObject("accounts").has(id))throw new IllegalArgumentException("No saved account selected");state.addProperty("selected",id);state.addProperty("mode","chatgpt");return null;});models=List.of();reload();requestsStopped=false;revision.incrementAndGet();notice="Account selected";return null;});}
    public CompletableFuture<Void> simulate(){requestsStopped=true;revision.incrementAndGet();return async(()->{store.transact(state->{state.addProperty("mode","simulation");return null;});reload();revision.incrementAndGet();notice="Simulator selected; no live inference";return null;});}
    public CompletableFuture<URI> signIn(boolean addAccount){return async(()->{
        if(login!=null)throw new IllegalStateException("A sign-in is already pending");
        JsonObject saved=store.transact(state->{var a=!addAccount && state.has("selected")?state.getAsJsonObject("accounts").getAsJsonObject(state.get("selected").getAsString()):null;var result=new JsonObject();result.addProperty("hostId",state.get("hostId").getAsString());if(a!=null)result.add("account",a.deepCopy());else if(!addAccount && state.has("pendingClientId"))result.add("pendingClientId",state.get("pendingClientId"));return result;});
        var attempt=new Login(saved);synchronized(this){if(login!=null){attempt.server.stop(0);throw new IllegalStateException("A sign-in is already pending");}login=attempt;lastLogin=attempt.completion;}
        attempt.timeout=io.schedule(()->{synchronized(ChatGptClient.this){if(login==attempt)cancelLogin("Sign-in expired; try again");}},10,TimeUnit.MINUTES);attempt.server.start();notice="Continue in your browser";return attempt.localPage;
    });}
    public void cancelLogin(){cancelLogin("Sign-in cancelled");}
    private synchronized void cancelLogin(String message){var attempt=login;if(attempt!=null){login=null;attempt.server.stop(0);if(attempt.timeout!=null)attempt.timeout.cancel(false);attempt.completion.completeExceptionally(new CancellationException(message));notice=message;}}
    /** For CLI/manual sign-in without opening or focusing a browser automatically. */
    public CompletableFuture<Void> loginCompletion(){var current=lastLogin;return current==null?CompletableFuture.failedFuture(new IllegalStateException("No sign-in attempt has started")):current;}
    private String secret(){byte[] bytes=new byte[32];random.nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private final class Login {
        final String state=secret(),nonce=secret(),verifier=secret(),clientId,subject;
        final URI callback,authorization,localPage;final HttpServer server;final CompletableFuture<Void> completion=new CompletableFuture<>();ScheduledFuture<?> timeout;boolean consumed;
        Login(JsonObject saved)throws Exception{
            var old=saved.has("account")?saved.getAsJsonObject("account"):null;clientId=old!=null?old.get("client_id").getAsString():OpenAiHttp.string(saved,"pendingClientId","dynamic_agent_client");subject=old==null?null:old.get("subject").getAsString();
            server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(io);int port=server.getAddress().getPort();callback=URI.create("http://127.0.0.1:"+port+"/auth/callback");localPage=URI.create("http://127.0.0.1:"+port+"/");
            var values=new LinkedHashMap<String,String>();values.put("client_id",clientId);values.put("ext_agent_host_id",saved.get("hostId").getAsString());if(clientId.equals("dynamic_agent_client"))values.put("agent_name_hint","Paracraft");if(old!=null && !planEnabled(old))values.put("prompt","consent");
            values.put("response_type","code");values.put("redirect_uri",callback.toString());values.put("scope","openid profile email offline_access resource.invoke chatgpt.tokens.use.direct");values.put("resource",endpoints.resource);values.put("state",state);values.put("nonce",nonce);values.put("code_challenge_method","S256");values.put("code_challenge",Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))));
            // No token-valued hint is put in a URL, clipboard, game message or console log.
            authorization=URI.create(endpoints.authorize+"?"+OpenAiHttp.form(values));server.createContext("/",this::handle);
        }
        void handle(HttpExchange exchange)throws IOException{
            String host="127.0.0.1:"+server.getAddress().getPort();
            if(!exchange.getRequestMethod().equals("GET") || !host.equals(exchange.getRequestHeaders().getFirst("Host"))){reply(exchange,400,"Invalid local sign-in request");return;}
            String path=exchange.getRequestURI().getPath();
            if(path.equals("/")){reply(exchange,200,"<h1>Paracraft</h1><p>Sign in on this computer to use your eligible ChatGPT plan. Active companions can use live inference after linking.</p><a href=\""+html(authorization.toString())+"\">Continue with ChatGPT</a>");return;}
            if(!path.equals("/auth/callback")){reply(exchange,404,"Not found");return;}
            Map<String,String> query;
            try{query=parseQuery(exchange.getRequestURI().getRawQuery());}catch(IllegalArgumentException malformed){reply(exchange,400,"Invalid sign-in callback");return;}
            if(!MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),query.getOrDefault("state","").getBytes(StandardCharsets.UTF_8))){reply(exchange,400,"Sign-in state did not match. Return to the original sign-in page.");return;}
            synchronized(ChatGptClient.this){if(login!=this || consumed){reply(exchange,409,"This sign-in attempt is no longer active");return;}consumed=true;}
            try{
                if(query.containsKey("error"))throw new IOException("ChatGPT sign-in was declined or failed: "+safeCode(query.get("error")));
                String issued=query.getOrDefault("client_id",clientId);
                if(issued.isBlank() || issued.equals("dynamic_agent_client") || (!clientId.equals("dynamic_agent_client") && !issued.equals(clientId)))throw new IOException("ChatGPT registration returned an invalid or different client ID");
                String code=query.get("code");if(code==null || code.isBlank())throw new IOException("ChatGPT sign-in did not return an authorization code");
                JsonObject tokens;
                try{tokens=http.post(endpoints.token,Map.of("grant_type","authorization_code","client_id",issued,"code",code,"code_verifier",verifier,"redirect_uri",callback.toString(),"resource",endpoints.resource));}
                catch(OpenAiHttp.Failure failure){if(subject==null && failure.code.equals("invalid_grant"))store.transact(state->{state.addProperty("pendingClientId",issued);return null;});throw failure;}
                var identity=validate(tokens.get("id_token").getAsString(),issued,nonce);
                if(subject!=null && !subject.equals(identity.get("sub").getAsString()))throw new IOException("Returning sign-in used a different ChatGPT account");
                synchronized(ChatGptClient.this){
                    if(login!=this || closed)throw new CancellationException("Sign-in attempt ended before validation completed");
                    store.transact(storeState->{String id=accountId(identity.get("iss").getAsString(),identity.get("sub").getAsString(),issued);var previous=storeState.getAsJsonObject("accounts").getAsJsonObject(id);var account=new JsonObject();account.addProperty("client_id",issued);account.addProperty("issuer",identity.get("iss").getAsString());account.addProperty("subject",identity.get("sub").getAsString());account.addProperty("email",OpenAiHttp.string(identity,"email",""));if(previous!=null && previous.has("usage"))account.add("usage",previous.getAsJsonObject("usage").deepCopy());updateTokens(account,tokens);storeState.getAsJsonObject("accounts").add(id,account);storeState.addProperty("selected",id);storeState.addProperty("mode","chatgpt");if(issued.equals(OpenAiHttp.string(storeState,"pendingClientId","")))storeState.remove("pendingClientId");return null;});
                    reload();requestsStopped=false;models=List.of();revision.incrementAndGet();notice=selected().planEnabled?"Signed in; ChatGPT plan usage enabled":"Signed in; ChatGPT plan usage was not granted";login=null;if(timeout!=null)timeout.cancel(false);
                }
                reply(exchange,200,"<h1>Paracraft is linked</h1><p>Return to Minecraft. Your credentials remain on this computer.</p>");
                completion.complete(null);
            }catch(Exception failure){notice=message(failure);completion.completeExceptionally(failure);if(timeout!=null)timeout.cancel(false);synchronized(ChatGptClient.this){if(login==this)login=null;}reply(exchange,400,"<h1>Sign-in did not complete</h1><p>Return to Paracraft for the error and try again.</p>");}
            finally{try{io.schedule(()->server.stop(0),1,TimeUnit.SECONDS);}catch(RejectedExecutionException stopped){server.stop(0);}}
        }
    }
    private JsonObject discovery()throws Exception{synchronized(metadataLock){if(discovery==null)discovery=http.get(endpoints.discovery,null);return discovery;}}
    private JsonObject validate(String token,String clientId,String nonce)throws Exception{var d=discovery();try{return IdTokenValidator.verify(token,http.get(URI.create(d.get("jwks_uri").getAsString()),null),d.get("issuer").getAsString(),clientId,nonce,Instant.now());}catch(RuntimeException malformed){throw new java.security.GeneralSecurityException("OpenAI ID token was malformed");}}
    private static String accountId(String issuer,String subject,String client)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((issuer+"\0"+subject+"\0"+client).getBytes(StandardCharsets.UTF_8)));}
    private static void updateTokens(JsonObject account,JsonObject tokens)throws IOException{
        if(!tokens.has("access_token") || tokens.get("access_token").getAsString().isBlank())throw new IOException("ChatGPT token response did not contain an access token");
        account.addProperty("access_token",tokens.get("access_token").getAsString());for(String field:List.of("refresh_token","id_token","token_type"))if(tokens.has(field))account.add(field,tokens.get(field).deepCopy());
        if(tokens.has("scope")){var scopes=new JsonArray();for(String scope:tokens.get("scope").getAsString().split(" +"))if(!scope.isBlank())scopes.add(scope);account.add("scopes",scopes);}else if(!account.has("scopes"))account.add("scopes",new JsonArray());
        long now=Instant.now().getEpochSecond();account.addProperty("expires_at",tokens.has("expires_in")?now+tokens.get("expires_in").getAsLong():0);account.addProperty("saved_at",now);
    }
    private static boolean planEnabled(JsonObject account){return account.has("scopes") && java.util.stream.StreamSupport.stream(account.getAsJsonArray("scopes").spliterator(),false).anyMatch(s->s.getAsString().equals("chatgpt.tokens.use.direct"));}
    String accessToken(boolean force)throws Exception{
        if(!mode().equals("chatgpt"))throw new CancellationException("Live provider is not selected");
        final String expected=selected;if(expected.isEmpty())throw new IOException("Continue with ChatGPT in Paracraft Accounts first");
        try{return store.transact(state->{
            if(!expected.equals(OpenAiHttp.string(state,"selected","")) || !"chatgpt".equals(OpenAiHttp.string(state,"mode","")))throw new CancellationException("Selected account or provider changed");
            var saved=state.getAsJsonObject("accounts").getAsJsonObject(expected);if(saved==null || !saved.has("access_token"))throw new IOException("ChatGPT session is signed out; sign in again");
            if(!planEnabled(saved))throw new IOException("ChatGPT plan usage was not granted. Continue with ChatGPT and enable plan usage.");
            long expiry=saved.has("expires_at")?saved.get("expires_at").getAsLong():0;
            if(force || expiry>0 && expiry<=Instant.now().getEpochSecond()+60){
                if(!saved.has("refresh_token"))throw new IOException("ChatGPT session needs sign-in again");
                try{
                    var tokens=http.post(endpoints.token,Map.of("grant_type","refresh_token","client_id",saved.get("client_id").getAsString(),"refresh_token",saved.get("refresh_token").getAsString(),"resource",endpoints.resource));
                    var next=saved.deepCopy();if(tokens.has("id_token")){var identity=validate(tokens.get("id_token").getAsString(),next.get("client_id").getAsString(),null);if(!identity.get("sub").getAsString().equals(next.get("subject").getAsString()))throw new IOException("Refreshed ChatGPT identity changed");}
                    updateTokens(next,tokens);state.getAsJsonObject("accounts").add(expected,next);saved=next;
                }catch(OpenAiHttp.Failure failure){if(Set.of("invalid_grant","invalid_refresh_token","token_expired","refresh_token_expired","refresh_token_invalidated","refresh_token_reused").contains(failure.code)){clearTokens(saved);notice="ChatGPT session expired or was revoked; sign in again";revision.incrementAndGet();}throw failure;}
            }
            if(!planEnabled(saved))throw new IOException("Refreshed account no longer grants ChatGPT plan usage");return saved.get("access_token").getAsString();
        });}finally{reload();}
    }
    public CompletableFuture<List<Model>> refreshModels(){return async(()->{JsonObject catalog;try{catalog=http.get(endpoints.api.resolve("models"),accessToken(false));}catch(OpenAiHttp.Failure failure){if(failure.status!=401)throw failure;catalog=http.get(endpoints.api.resolve("models"),accessToken(true));}
        var list=new ArrayList<Model>();if(!catalog.has("models"))throw new IOException("Model catalog did not contain the documented models array");for(var item:catalog.getAsJsonArray("models")){var model=item.getAsJsonObject();if(!"list".equals(OpenAiHttp.string(model,"visibility","")))continue;String slug=OpenAiHttp.string(model,"slug","");if(!slug.isBlank())list.add(new Model(slug,OpenAiHttp.string(model,"display_name",slug),model.has("supported_reasoning_levels")?model.get("supported_reasoning_levels").toString():""));}models=List.copyOf(list);notice="Loaded "+list.size()+" account models";return models;});}
    public CompletableFuture<Void> signOut(){cancelLogin();requestsStopped=true;mode="simulation";revision.incrementAndGet();return async(()->{String id=selected;boolean[] confirmed={true};store.transact(state->{var account=state.getAsJsonObject("accounts").getAsJsonObject(id);if(account!=null){if(account.has("refresh_token"))try{http.post(URI.create(discovery().get("revocation_endpoint").getAsString()),Map.of("token",account.get("refresh_token").getAsString(),"token_type_hint","refresh_token","client_id",account.get("client_id").getAsString()));}catch(Exception failure){confirmed[0]=false;}clearTokens(account);}state.addProperty("mode","simulation");return null;});reload();models=List.of();notice=confirmed[0]?"Signed out":"Signed out locally; remote revocation was not confirmed. Disconnect Paracraft in ChatGPT settings if needed.";return null;});}
    void recordUsage(String id,String model,JsonObject reported,String outcome,String requestId)throws Exception{
        store.transact(state->{var account=state.getAsJsonObject("accounts").getAsJsonObject(id);if(account==null)return null;var value=account.has("usage")?account.getAsJsonObject("usage"):new JsonObject();
            UsageLedger.record(value,reported,outcome,usageClock.instant(),usageClock.getZone());
            value.addProperty("last_model",model);value.addProperty("last_outcome",outcome);value.addProperty("last_at",usageClock.instant().toString());value.addProperty("last_request_id",requestId);account.add("usage",value);if(id.equals(selected))cacheUsage(id,account);return null;});
    }
    private static String usageLabel(JsonObject value){return (value.has("completed")?value.get("completed").getAsLong():0)+" completed calls · "+(value.has("total_tokens")?value.get("total_tokens").getAsLong():0)+" reported tokens (local log)";}
    private static void clearTokens(JsonObject account){for(String field:List.of("access_token","refresh_token","id_token","expires_at","scopes"))account.remove(field);}
    <T>CompletableFuture<T> async(Callable<T> action){return CompletableFuture.supplyAsync(()->{try{if(closed)throw new CancellationException("ChatGPT client is closed");return action.call();}catch(Exception failure){notice=message(failure);throw new CompletionException(failure);}},io);}
    public static String message(Throwable failure){while((failure instanceof CompletionException || failure instanceof ExecutionException) && failure.getCause()!=null)failure=failure.getCause();return failure.getMessage()==null?failure.getClass().getSimpleName():failure.getMessage();}
    static Map<String,String> parseQuery(String query){if(query==null || query.length()>16384)throw new IllegalArgumentException("Invalid callback");var values=new HashMap<String,String>();for(String pair:query.split("&")){var parts=pair.split("=",2);String key=URLDecoder.decode(parts[0],StandardCharsets.UTF_8),value=parts.length==2?URLDecoder.decode(parts[1],StandardCharsets.UTF_8):"";if(values.putIfAbsent(key,value)!=null)throw new IllegalArgumentException("Duplicate callback field");}return values;}
    private static String safeCode(String value){return value!=null && value.matches("[a-zA-Z0-9_.-]{1,100}")?value:"authorization_error";}
    private static String html(String text){return text.replace("&","&amp;").replace("\"","&quot;").replace("<","&lt;").replace(">","&gt;");}
    private static void reply(HttpExchange exchange,int status,String content)throws IOException{byte[] bytes=("<!doctype html><meta charset=\"utf-8\"><title>Paracraft sign-in</title>"+content).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","text/html; charset=utf-8");exchange.getResponseHeaders().set("Cache-Control","no-store");exchange.getResponseHeaders().set("Content-Security-Policy","default-src 'none'; base-uri 'none'; frame-ancestors 'none'");exchange.sendResponseHeaders(status,bytes.length);try(var output=exchange.getResponseBody()){output.write(bytes);}}
    @Override public void close(){closed=true;cancelLogin();io.shutdownNow();inference.shutdownNow();}
}
