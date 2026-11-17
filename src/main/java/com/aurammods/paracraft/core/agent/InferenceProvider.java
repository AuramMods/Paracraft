package com.aurammods.paracraft.core.agent;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Client-owned inference boundary. No Minecraft or credential types cross this interface. */
public interface InferenceProvider {
    enum Route { LIGHT, PLANNING }
    record ModelRoute(String model, String effort) {
        public ModelRoute { if (model == null || model.isBlank() || effort == null || effort.isBlank()) throw new IllegalArgumentException("A model route needs a model and effort"); }
    }
    record Call(String tool, JsonObject arguments) {
        public Call {
            if (tool == null || tool.isBlank()) throw new IllegalArgumentException("Missing tool name");
            arguments = arguments.deepCopy();
        }
        @Override public JsonObject arguments() { return arguments.deepCopy(); }
    }
    record Decision(List<Call> calls,String note) {
        public Decision(List<Call> calls){this(calls,"");}
        public Decision { calls = List.copyOf(calls);note=note==null?"":note;if(note.length()>2048)throw new IllegalArgumentException("Decision note must be concise (at most 2048 characters)"); }
    }
    /** Every invocation is independent. Providers receive explicit context, never an implicit conversation. */
    record Request(Route route, ModelRoute model, JsonObject snapshot, JsonObject trigger, JsonObject observations, java.util.function.Consumer<JsonObject> onOutput) {
        public Request(Route route, ModelRoute model, JsonObject snapshot, JsonObject trigger, JsonObject observations) { this(route, model, snapshot, trigger, observations, ignored -> {}); }
        public Request { snapshot = snapshot.deepCopy(); trigger = trigger.deepCopy(); observations = observations.deepCopy(); java.util.Objects.requireNonNull(onOutput); }
        public void emitOutput(JsonObject output) { try { onOutput.accept(output.deepCopy()); } catch (RuntimeException ignored) { /* Debugging must not fail inference. */ } }
        @Override public JsonObject snapshot() { return snapshot.deepCopy(); }
        @Override public JsonObject trigger() { return trigger.deepCopy(); }
        @Override public JsonObject observations() { return observations.deepCopy(); }
        public Set<String> tools() { return ToolPolicy.allowed(route); }
    }
    String label();
    boolean simulated();
    /** Provider-specific routes can use a separate model namespace without changing saved avatar memory. */
    default ModelRoute model(Route route, ModelRoute configured) { return configured; }
    CompletableFuture<Decision> infer(Request request);
}
