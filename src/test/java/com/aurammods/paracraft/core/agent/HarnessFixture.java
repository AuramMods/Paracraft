package com.aurammods.paracraft.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Virtual time and a service stub; these tests do not claim Minecraft or network execution. */
final class HarnessFixture {
    static final AgentBinding BINDING = new AgentBinding(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    static final class Clock implements AgentClock {
        long nanos, millis = 1_800_000_000_000L;
        public long nanoTime() { return nanos; }
        public long epochMillis() { return millis; }
        void advance(long milliseconds) { nanos += milliseconds * 1_000_000L; millis += milliseconds; }
    }
    static final class Provider implements InferenceProvider {
        final List<Request> requests = new ArrayList<>();
        final List<Long> starts = new ArrayList<>();
        final Clock clock;
        Function<Request, CompletableFuture<Decision>> answer = ignored -> CompletableFuture.completedFuture(new Decision(List.of()));
        Provider(Clock clock) { this.clock = clock; }
        public String label() { return "Test fixture"; }
        public boolean simulated() { return true; }
        public CompletableFuture<Decision> infer(Request request) { requests.add(request); starts.add(clock.nanoTime()); return answer.apply(request); }
    }
    record Sent(UUID id, String tool, JsonObject args, long event) { }
    static final class Sink implements HarnessLoop.Sink {
        final List<Sent> sent = new ArrayList<>();
        Function<Sent, CompletableFuture<JsonObject>> reply = ignored -> CompletableFuture.completedFuture(ok(new JsonObject()));
        public CompletableFuture<JsonObject> call(UUID id, String tool, JsonObject args, long event) {
            Sent call = new Sent(id, tool, args.deepCopy(), event); sent.add(call); return reply.apply(call);
        }
    }
    static JsonObject frame(JsonObject... events) {
        JsonObject frame = new JsonObject();
        frame.addProperty("worldId", BINDING.world().toString()); frame.addProperty("ownerId", BINDING.owner().toString());
        frame.addProperty("avatarId", BINDING.avatar().toString()); frame.addProperty("session", "lease-1"); frame.addProperty("paused", false);
        frame.addProperty("name", "Josh"); frame.addProperty("health", 20);
        JsonArray journal = new JsonArray(); for (JsonObject event : events) journal.add(event); frame.add("events", journal);
        frame.add("programs", new JsonArray()); return frame;
    }
    static JsonObject event(long id, String type) {
        JsonObject event = new JsonObject(); event.addProperty("id", id); event.addProperty("type", type); return event;
    }
    static JsonObject chat(long id, String text) {
        JsonObject event = event(id, "chat"); event.addProperty("message", text); event.addProperty("senderUuid", BINDING.owner().toString()); return event;
    }
    static JsonObject json(String value) { return JsonParser.parseString(value).getAsJsonObject(); }
    static JsonObject ok(JsonObject value) { JsonObject reply = new JsonObject(); reply.addProperty("ok", true); reply.add("value", value); return reply; }
    static InferenceProvider.Decision decision(String tool, JsonObject args) { return new InferenceProvider.Decision(List.of(new InferenceProvider.Call(tool, args))); }
    static void pump(HarnessLoop loop) { for (int i = 0; i < 12; i++) loop.tick(); }
    static final class Uncancellable<T> extends CompletableFuture<T> { @Override public boolean cancel(boolean interrupt) { return false; } }
}
