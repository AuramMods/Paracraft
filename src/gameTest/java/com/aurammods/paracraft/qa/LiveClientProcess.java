package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.core.agent.InferenceProvider;
import com.aurammods.paracraft.core.openai.ChatGptClient;
import com.aurammods.paracraft.core.openai.ChatGptProvider;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.concurrent.*;

/** Opt-in QA client process. Credentials stay here; only snapshots and decisions cross the pipe. */
public final class LiveClientProcess {
    public static void main(String[] args) throws Exception {
        try (var client = new ChatGptClient(ChatGptClient.defaultDirectory());
             var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            var provider = new ChatGptProvider(client);
            String line;
            while ((line = input.readLine()) != null) {
                var result = new JsonObject();
                try {
                    var encoded = JsonParser.parseString(line).getAsJsonObject();
                    var request = new InferenceProvider.Request(InferenceProvider.Route.valueOf(encoded.get("route").getAsString()),
                            new InferenceProvider.ModelRoute(encoded.get("model").getAsString(), encoded.get("effort").getAsString()),
                            encoded.getAsJsonObject("snapshot"), encoded.getAsJsonObject("trigger"), encoded.getAsJsonObject("observations"), item -> {
                                var diagnostic=new JsonObject();diagnostic.add("agentOutput",item);System.out.println(diagnostic);System.out.flush();
                            });
                    var decision = provider.infer(request).get(5, TimeUnit.MINUTES);
                    var calls = new JsonArray();
                    for (var call : decision.calls()) {
                        var value = new JsonObject(); value.addProperty("tool", call.tool()); value.add("arguments", call.arguments()); calls.add(value);
                    }
                    result.add("calls", calls);
                    var event = new JsonObject(); event.addProperty("event", "live_luna_decision"); event.addProperty("status", "pass");
                    var context = new JsonObject(); context.addProperty("model", request.model().model()); context.addProperty("effort", request.model().effort());
                    context.addProperty("route", request.route().name()); context.addProperty("clientPid", ProcessHandle.current().pid());
                    context.addProperty("tools", decision.calls().stream().map(InferenceProvider.Call::tool).toList().toString());
                    event.add("context", context); System.err.println("QA_EVT " + event);
                } catch (Exception failure) { result.addProperty("error", ChatGptClient.message(failure)); }
                System.out.println(result); System.out.flush();
            }
        }
    }

    static final class Remote implements InferenceProvider, AutoCloseable {
        private final Process process;
        private final BufferedReader input;
        private final BufferedWriter output;
        private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "QA local client bridge"); t.setDaemon(true); return t; });
        private volatile boolean closed;
        Remote() throws IOException {
            var launch = JsonParser.parseString(Files.readString(Path.of(System.getProperty("paracraft.qa.clientLaunch")))) .getAsJsonArray();
            var command = new ArrayList<String>(); launch.forEach(value -> command.add(value.getAsString()));
            process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.appendTo(Path.of("client.log").toFile())).start();
            input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            Runtime.getRuntime().addShutdownHook(new Thread(process::destroy, "QA client cleanup"));
        }
        public String label() { return "LIVE Luna · separate local QA client"; }
        public boolean simulated() { return false; }
        public CompletableFuture<Decision> infer(Request request) {
            var result = CompletableFuture.supplyAsync(() -> {
                try {
                    if (closed) throw new IOException("QA client closed");
                    var encoded = new JsonObject(); encoded.addProperty("route", request.route().name()); encoded.addProperty("model", request.model().model());
                    encoded.addProperty("effort", request.model().effort()); encoded.add("snapshot", request.snapshot()); encoded.add("trigger", request.trigger()); encoded.add("observations", request.observations());
                    output.write(encoded.toString()); output.newLine(); output.flush();
                    JsonObject reply;
                    while(true) {
                        String line = input.readLine(); if (line == null) throw new IOException("QA client exited; see run/live-qa/client.log");
                        reply = JsonParser.parseString(line).getAsJsonObject();
                        if(reply.has("agentOutput")){request.emitOutput(reply.getAsJsonObject("agentOutput"));continue;}break;
                    }
                    if (reply.has("error")) throw new IOException(reply.get("error").getAsString());
                    var calls = new ArrayList<Call>();
                    for (var item : reply.getAsJsonArray("calls")) { var value = item.getAsJsonObject(); calls.add(new Call(value.get("tool").getAsString(), value.getAsJsonObject("arguments"))); }
                    return new Decision(calls);
                } catch (Exception failure) { throw new CompletionException(failure); }
            }, worker);
            result.whenComplete((value, failure) -> { if (result.isCancelled()) close(); });
            return result;
        }
        public void close() { if (!closed) { closed = true; process.destroy(); worker.shutdownNow(); } }
    }
}
