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
    record Decision(List<Call> calls) { public Decision { calls = List.copyOf(calls); } }
    /** Every invocation is independent. Providers receive explicit context, never an implicit conversation. */
    record Request(Route route, ModelRoute model, JsonObject snapshot, JsonObject trigger, JsonObject observations) {
        public Request { snapshot = snapshot.deepCopy(); trigger = trigger.deepCopy(); observations = observations.deepCopy(); }
        @Override public JsonObject snapshot() { return snapshot.deepCopy(); }
        @Override public JsonObject trigger() { return trigger.deepCopy(); }
        @Override public JsonObject observations() { return observations.deepCopy(); }
        public Set<String> tools() { return ToolPolicy.allowed(route); }
    }
    String label();
    boolean simulated();
    CompletableFuture<Decision> infer(Request request);
}
