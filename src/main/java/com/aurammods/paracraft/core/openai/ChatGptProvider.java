package com.aurammods.paracraft.core.openai;

import com.aurammods.paracraft.core.agent.InferenceProvider;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Public Responses API with OAuth; every call sends explicit context and waits for completion. */
public final class ChatGptProvider implements InferenceProvider {
    private final ChatGptClient account;
    public ChatGptProvider(ChatGptClient account){this.account=account;}
    @Override public String label(){return account.label();}
    @Override public boolean simulated(){return false;}
    @Override public CompletableFuture<Decision> infer(Request request){
        var future=new CompletableFuture<Decision>();var body=new AtomicReference<InputStream>();long revision=account.revision();
        Future<?> worker=account.inference.submit(()->{
            try{
                var selected=account.selected();if(selected==null)throw new IOException("Continue with ChatGPT in Paracraft Accounts first");String id=selected.id();
                JsonObject specification=DecisionProtocol.request(request);HttpResponse<InputStream> response=null;
                for(int attempt=0;attempt<2;attempt++){
                    if(future.isCancelled() || revision!=account.revision())throw new CancellationException("Account changed or inference cancelled");
                    String bearer=account.accessToken(attempt>0);
                    if(future.isCancelled() || revision!=account.revision())throw new CancellationException("Account changed or inference cancelled");
                    var http=HttpRequest.newBuilder(account.endpoints.api().resolve("responses")).timeout(Duration.ofMinutes(5)).header("Authorization","Bearer "+bearer).header("Content-Type","application/json").header("Accept","text/event-stream").POST(HttpRequest.BodyPublishers.ofString(specification.toString())).build();
                    response=account.http.client.send(http,HttpResponse.BodyHandlers.ofInputStream());body.set(response.body());
                    if(future.isCancelled() || revision!=account.revision()){response.body().close();throw new CancellationException("Account changed or inference cancelled");}
                    if(response.statusCode()!=401 || attempt>0)break;response.body().close();body.set(null);
                }
                String requestId=response.headers().firstValue("x-request-id").orElse("");
                if(response.statusCode()<200 || response.statusCode()>=300){JsonObject error;try(var input=response.body()){byte[] bytes=input.readNBytes(OpenAiHttp.MAX_BODY+1);if(bytes.length>OpenAiHttp.MAX_BODY)throw new IOException("OpenAI error response exceeded the client bound");try{error=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();}catch(RuntimeException malformed){error=new JsonObject();error.addProperty("detail","Non-JSON error response");}}account.recordUsage(id,request.model().model(),null,"http_"+response.statusCode(),requestId);throw OpenAiHttp.failure(response.statusCode(),error,requestId);}
                JsonObject completed;
                try(var stream=response.body()){completed=readCompletion(stream);}
                catch(IOException failure){account.recordUsage(id,request.model().model(),null,"stream_failed",requestId);throw failure;}
                account.recordUsage(id,request.model().model(),completed.has("usage") && completed.get("usage").isJsonObject()?completed.getAsJsonObject("usage"):null,"completed",requestId);
                if(future.isCancelled() || revision!=account.revision())throw new CancellationException("Account changed or inference cancelled");
                future.complete(DecisionProtocol.decision(completed,request.route()));
            }catch(Throwable failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();future.completeExceptionally(failure);}
            finally{var input=body.getAndSet(null);if(input!=null)try{input.close();}catch(IOException ignored){}}
        });
        future.whenComplete((value,error)->{if(future.isCancelled()){worker.cancel(true);var input=body.getAndSet(null);if(input!=null)try{input.close();}catch(IOException ignored){}}});return future;
    }
    static JsonObject readCompletion(InputStream stream)throws IOException{
        var bounded=new FilterInputStream(stream){long count;@Override public int read()throws IOException{int value=super.read();if(value>=0 && ++count>OpenAiHttp.MAX_BODY)throw new IOException("Model stream exceeded the client response bound");return value;}@Override public int read(byte[] b,int off,int length)throws IOException{int read=super.read(b,off,length);if(read>0 && (count+=read)>OpenAiHttp.MAX_BODY)throw new IOException("Model stream exceeded the client response bound");return read;}};
        var reader=new BufferedReader(new InputStreamReader(bounded,StandardCharsets.UTF_8));var data=new StringBuilder();String line;
        while((line=reader.readLine())!=null){
            if(line.isEmpty()){JsonObject done=event(data);data.setLength(0);if(done!=null)return done;}
            else if(line.startsWith("data:")){if(data.length()>0)data.append('\n');data.append(line.substring(5).stripLeading());}
        }
        if(data.length()>0){var done=event(data);if(done!=null)return done;}throw new IOException("Model stream ended without response.completed; no actions were applied");
    }
    private static JsonObject event(StringBuilder data)throws IOException{
        if(data.isEmpty() || data.toString().equals("[DONE]"))return null;JsonObject event;
        try{event=JsonParser.parseString(data.toString()).getAsJsonObject();}catch(RuntimeException malformed){throw new IOException("OpenAI returned a malformed stream event");}
        String type=OpenAiHttp.string(event,"type","");
        if(type.equals("response.completed"))return event.getAsJsonObject("response");
        if(type.equals("response.failed") || type.equals("response.incomplete") || type.equals("error")){
            var detail=event.has("response")?event.getAsJsonObject("response"):event;if(type.equals("response.incomplete"))throw new IOException("Model response was incomplete; no actions were applied");
            throw OpenAiHttp.failure(200,detail,OpenAiHttp.string(event,"request_id",""));
        }return null;
    }
}
