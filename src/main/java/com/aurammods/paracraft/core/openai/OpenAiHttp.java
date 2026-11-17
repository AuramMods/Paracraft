package com.aurammods.paracraft.core.openai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** HTTP stays in the client/provider package. No credentials enter Minecraft transport objects. */
final class OpenAiHttp {
    static final int MAX_BODY=16*1024*1024;
    final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NEVER).build();
    static String form(Map<String,String> values){return values.entrySet().stream().map(e->encode(e.getKey())+"="+encode(e.getValue())).collect(java.util.stream.Collectors.joining("&"));}
    static String encode(String value){return URLEncoder.encode(value,StandardCharsets.UTF_8);}
    JsonObject get(URI uri,String token)throws IOException,InterruptedException{
        var builder=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).header("Accept","application/json");if(token!=null)builder.header("Authorization","Bearer "+token);
        return json(builder.GET().build());
    }
    JsonObject post(URI uri,Map<String,String> form)throws IOException,InterruptedException{try{return json(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(form))).build());}catch(Failure failure){throw new Failure(failure.status,failure.code,failure.parameter,failure.requestId,failure.shape,"Authorization request failed");}}
    JsonObject json(HttpRequest request)throws IOException,InterruptedException{
        var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());byte[] body;
        try(var input=response.body()){body=input.readNBytes(MAX_BODY+1);}if(body.length>MAX_BODY)throw new IOException("OpenAI response exceeded the client response bound");
        String text=new String(body,StandardCharsets.UTF_8);JsonObject value;
        try{value=text.isBlank()?new JsonObject():JsonParser.parseString(text).getAsJsonObject();}catch(RuntimeException malformed){throw new IOException("OpenAI returned HTTP "+response.statusCode()+" with a non-JSON response");}
        if(response.statusCode()<200 || response.statusCode()>=300)throw failure(response.statusCode(),value,response.headers().firstValue("x-request-id").orElse(""));return value;
    }
    static Failure failure(int status,JsonObject body,String requestId){
        String code="",message="Request failed",parameter="";String shape=body.has("error")?"error":body.has("detail")?"detail":"other";
        if(body.has("error") && body.get("error").isJsonObject()){var error=body.getAsJsonObject("error");code=string(error,"code","");message=string(error,"message",message);parameter=string(error,"param","");}
        else if(body.has("error") && body.get("error").isJsonPrimitive()){code=body.get("error").getAsString();message=string(body,"error_description",message);}
        else message=string(body,"detail",message);
        if(status==401)message="Authentication was rejected by OpenAI";
        message=message.replaceAll("(?i)Bearer\\s+[^\\s]+","Bearer [redacted]").replaceAll("[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}","[redacted token]").replaceAll("sk-[A-Za-z0-9_-]+","[redacted key]");
        // Never echo arbitrary OAuth token/code fields or raw response bodies into game/UI logs.
        return new Failure(status,code,parameter,requestId,shape,message.length()>600?message.substring(0,600):message);
    }
    static String string(JsonObject object,String key,String fallback){return object.has(key) && object.get(key).isJsonPrimitive()?object.get(key).getAsString():fallback;}
    static final class Failure extends IOException{
        final int status;final String code,parameter,requestId,shape;
        Failure(int status,String code,String parameter,String requestId,String shape,String message){super("OpenAI HTTP "+status+(code.isEmpty()?"":" ["+code+"]")+": "+message+(parameter.isEmpty()?"":" ("+parameter+")")+(requestId.isEmpty()?"":" · request "+requestId));this.status=status;this.code=code;this.parameter=parameter;this.requestId=requestId;this.shape=shape;}
    }
}
