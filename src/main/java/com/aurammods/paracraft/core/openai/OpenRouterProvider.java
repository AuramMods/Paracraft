package com.aurammods.paracraft.core.openai;

import com.aurammods.paracraft.core.agent.AgentOutput;
import com.aurammods.paracraft.core.agent.InferenceProvider;
import com.google.gson.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Client-owned OpenAI-compatible Chat Completions transport; no provider or model fallback. */
public final class OpenRouterProvider implements InferenceProvider {
    private final ChatGptClient account;
    private final URI endpoint;
    public OpenRouterProvider(ChatGptClient account){this(account,null);}
    OpenRouterProvider(ChatGptClient account,URI endpoint){this.account=account;this.endpoint=endpoint;}
    @Override public String label(){return account.label();}
    @Override public boolean simulated(){return false;}
    @Override public ModelRoute model(Route route,ModelRoute configured){return account.openRouter().route(route);}
    @Override public CompletableFuture<Decision> infer(Request request){
        var future=new CompletableFuture<Decision>();var body=new AtomicReference<InputStream>();long revision=account.revision();
        Future<?> worker=account.inference.submit(()->{
            String secret="";URI address=endpoint;
            try{
                current(future,revision);var credential=account.routerCredential();secret=credential.key();var specification=DecisionProtocol.openRouterRequest(request);current(future,revision);
                boolean router=URI.create(credential.baseUrl()).getHost().equalsIgnoreCase("openrouter.ai");
                address=endpoint==null?URI.create(credential.baseUrl()+"/chat/completions"):endpoint;
                var builder=HttpRequest.newBuilder(address).timeout(Duration.ofMinutes(5)).header("Content-Type","application/json").header("Accept","application/json");
                if(!secret.isEmpty())builder.header("Authorization","Bearer "+secret);if(router)builder.header("X-OpenRouter-Title","Paracraft");
                var http=builder.POST(HttpRequest.BodyPublishers.ofString(specification.toString())).build();
                var response=account.http.send(http);body.set(response.body());current(future,revision);
                byte[] bytes;try(var input=response.body()){bytes=input.readNBytes(OpenAiHttp.MAX_BODY+1);}if(bytes.length>OpenAiHttp.MAX_BODY)throw new IOException("API response exceeded the client bound");
                JsonObject completed;try{completed=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}catch(RuntimeException malformed){throw new IOException("API returned HTTP "+response.statusCode()+" with a non-JSON response");}
                boolean failed=response.statusCode()<200 || response.statusCode()>=300 || completed.has("error") && !completed.get("error").isJsonNull();
                account.recordRouterUsage(credential.id(),completed.has("usage") && completed.get("usage").isJsonObject()?completed.getAsJsonObject("usage"):null,failed?"http_"+response.statusCode():"completed");
                current(future,revision);if(failed)throw new IOException(error(response.statusCode(),completed,secret,router));
                report(request,completed,secret);var decision=DecisionProtocol.openRouterDecision(completed,request.route());current(future,revision);future.complete(decision);
            }catch(Throwable failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
                if(failure instanceof CancellationException || future.isCancelled() || revision!=account.revision() || failure instanceof InterruptedException)future.completeExceptionally(new CancellationException("Provider settings changed or inference cancelled"));
                else{
                    String message=failure instanceof java.net.ConnectException?"Cannot connect to API at "+address+". Check the base URL and server listen address.":ChatGptClient.message(failure);
                    message=redact(message,secret);var diagnostic=new JsonObject();diagnostic.addProperty("type","inference_error");diagnostic.addProperty("message",message);request.emitOutput(diagnostic);
                    future.completeExceptionally(new IOException(message));
                }
            }finally{var input=body.getAndSet(null);if(input!=null)try{input.close();}catch(IOException ignored){}}
        });
        future.whenComplete((value,error)->{if(future.isCancelled()){worker.cancel(true);var input=body.getAndSet(null);if(input!=null)try{input.close();}catch(IOException ignored){}}});return future;
    }
    private void current(CompletableFuture<?> future,long revision){if(future.isCancelled() || revision!=account.revision() || !account.mode().equals("openrouter"))throw new CancellationException("Provider settings changed or inference cancelled");}
    private static String error(int status,JsonObject response,String key,boolean router){
        var detail=response.has("error") && response.get("error").isJsonObject()?response.getAsJsonObject("error"):new JsonObject();String message=status==401?"API key was rejected":OpenAiHttp.string(detail,"message","Request failed");return (router?"OpenRouter":"API")+" HTTP "+status+": "+redact(message,key);
    }
    private static String redact(String value,String key){String clean=key.isEmpty()?value:value.replace(key,"[redacted key]");clean=clean.replaceAll("(?i)Bearer\\s+[^\\s]+","Bearer [redacted]").replaceAll("sk-[A-Za-z0-9_-]+","[redacted key]");return clean.length()>600?clean.substring(0,600):clean;}
    private static void report(Request request,JsonObject completed,String key){
        try{for(var choice:completed.getAsJsonArray("choices")){var message=choice.getAsJsonObject().getAsJsonObject("message");
            var content=new JsonArray();for(String field:java.util.List.of("content","refusal"))if(message.has(field) && message.get(field).isJsonPrimitive()){var part=new JsonObject();part.addProperty("type",field.equals("refusal")?"refusal":"output_text");part.add(field.equals("refusal")?"refusal":"text",message.get(field).deepCopy());content.add(part);}if(!content.isEmpty()){var visible=new JsonObject();visible.addProperty("type","message");visible.add("content",content);request.emitOutput(safeOutput(visible,key).getAsJsonObject());}
            if(message.has("tool_calls"))for(var call:message.getAsJsonArray("tool_calls")){var item=call.getAsJsonObject().getAsJsonObject("function").deepCopy();item.addProperty("type","function_call");var visible=AgentOutput.visible(item);if(visible!=null)request.emitOutput(safeOutput(visible,key).getAsJsonObject());}
        }}catch(RuntimeException ignored){} // Diagnostics do not make an invalid batch executable.
    }
    private static JsonElement safeOutput(JsonElement value,String key){
        if(value.isJsonObject()){var result=new JsonObject();value.getAsJsonObject().entrySet().forEach(entry->result.add(entry.getKey(),safeOutput(entry.getValue(),key)));return result;}
        if(value.isJsonArray()){var result=new JsonArray();value.getAsJsonArray().forEach(entry->result.add(safeOutput(entry,key)));return result;}
        return !key.isEmpty() && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()?new JsonPrimitive(value.getAsString().replace(key,"[redacted key]")):value.deepCopy();
    }
}
