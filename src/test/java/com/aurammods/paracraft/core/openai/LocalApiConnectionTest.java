package com.aurammods.paracraft.core.openai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class LocalApiConnectionTest {
    @Test void ipv6FirstLocalhostRetriesTheRealIpv4ListenerWithTheSameRequest()throws Exception{probe(200);}
    @Test void aRealHttpErrorIsReturnedWithoutAnotherRequest()throws Exception{probe(503);}
    private static void probe(int status)throws Exception{
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);var count=new AtomicInteger();var seen=new AtomicReference<String>();
        server.createContext("/",exchange->{
            try{count.incrementAndGet();seen.set(exchange.getRequestMethod()+" "+exchange.getRequestURI()+" "+exchange.getRequestHeaders().getFirst("X-Probe")+" "+new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
                byte[] bytes="loopback response".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
            }finally{exchange.close();}
        });server.start();
        try{
            String classpath=String.join(java.io.File.pathSeparator,List.of(location(Probe.class),location(OpenAiHttp.class),location(com.google.gson.JsonObject.class)));
            var process=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Djava.awt.headless=true","-Djava.net.preferIPv6Addresses=true","-cp",classpath,Probe.class.getName(),Integer.toString(server.getAddress().getPort()),Integer.toString(status)).redirectErrorStream(true).start();
            try{
                assertTrue(process.waitFor(25,TimeUnit.SECONDS),"Windowless localhost probe timed out");String output=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
                assertEquals(0,process.exitValue(),output);assertTrue(output.contains("plain Java connection refused"),output);assertTrue(output.contains("loopback retry received "+status),output);
            }finally{if(process.isAlive())process.destroyForcibly();}
            assertEquals(1,count.get(),"Only one HTTP request reaches the server; a failed TCP connection does not duplicate inference");
            assertEquals("POST /prefix%20path/v1/chat/completions?probe=test%20id preserved {\"model\":\"fixture\"}",seen.get());
        }finally{server.stop(0);}
    }
    private static String location(Class<?> type)throws Exception{return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();}
    public static final class Probe{
        public static void main(String[] args)throws Exception{
            if(!(InetAddress.getAllByName("localhost")[0] instanceof Inet6Address))throw new AssertionError("Probe must resolve IPv6 first");
            URI uri=URI.create("http://localhost:"+args[0]+"/prefix%20path/v1/chat/completions?probe=test%20id");
            var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").header("X-Probe","preserved").POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"fixture\"}")).build();
            try{HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build().send(request,HttpResponse.BodyHandlers.discarding());throw new AssertionError("Plain Java unexpectedly reached the IPv4-only server");}
            catch(ConnectException expected){System.out.println("plain Java connection refused");}
            var response=new OpenAiHttp().send(request);try(var body=response.body()){
                if(response.statusCode()!=Integer.parseInt(args[1]) || !new String(body.readAllBytes(),StandardCharsets.UTF_8).equals("loopback response"))throw new AssertionError("Unexpected loopback response");
            }System.out.println("loopback retry received "+response.statusCode());
        }
    }
}
