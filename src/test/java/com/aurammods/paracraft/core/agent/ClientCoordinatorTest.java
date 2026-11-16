package com.aurammods.paracraft.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.aurammods.paracraft.core.agent.HarnessFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class ClientCoordinatorTest {
    @TempDir Path temp;
    @Test void runtimeCreatesScopedMemorySupportsPauseAndPersistsConfigurableRoutes() throws Exception {
        Clock clock = new Clock(); Remote remote = new Remote(); AgentStore store = new AgentStore(temp);
        try (ClientCoordinator client = connect(remote, store, clock)) {
            pump(client); assertEquals(1, client.views().size()); assertEquals(0, client.views().get(0).decisions());
            client.routes(BINDING.avatar(), "test-light", "low", "test-planning", "xhigh");
            assertTrue(client.pause(BINDING.avatar(), true).join().get("ok").getAsBoolean());
            assertTrue(client.views().get(0).memory().paused()); assertTrue(client.views().get(0).snapshot().get("paused").getAsBoolean());
            assertTrue(client.pause(BINDING.avatar(), false).join().get("ok").getAsBoolean());
            assertFalse(client.views().get(0).memory().paused()); assertEquals("lease-resumed", client.views().get(0).snapshot().get("session").getAsString());
            assertTrue(client.status().contains("SIMULATED")); assertTrue(client.status().contains("disconnected"));
        }
        AgentState loaded = store.load(BINDING).orElseThrow(); assertEquals("test-light", loaded.lightModel); assertEquals("test-planning", loaded.planningModel);
        assertEquals("low", loaded.lightEffort); assertEquals("xhigh", loaded.planningEffort);
    }
    @Test void aSlowProviderDoesNotBlockSnapshotHeartbeatsAndDisconnectCancelsIt() {
        Clock clock = new Clock(); Remote remote = new Remote(); remote.snapshot.getAsJsonArray("events").add(chat(1, "hello"));
        CompletableFuture<InferenceProvider.Decision> thinking = new CompletableFuture<>();
        InferenceProvider provider = new InferenceProvider() {
            public String label() { return "Test"; } public boolean simulated() { return true; }
            public CompletableFuture<Decision> infer(Request request) { return thinking; }
        };
        try (ClientCoordinator client = new ClientCoordinator(BINDING.owner(), new AgentStore(temp), clock, remote::send, binding -> provider)) {
            remote.client = client; pump(client);
            for (int i = 0; i < 5; i++) { clock.advance(2000); pump(client); }
            assertEquals(6, remote.outbound.stream().filter(m -> m.get("kind").getAsString().equals("poll")).count());
            assertEquals(1, client.views().get(0).decisions()); assertFalse(thinking.isDone());
        }
        assertTrue(thinking.isCancelled());
    }
    @Test void anOwnerResumeThroughRawServerControlsClearsTheLocalPause() {
        Clock clock = new Clock(); Remote remote = new Remote();
        try (ClientCoordinator client = connect(remote, new AgentStore(temp), clock)) {
            pump(client); client.pause(BINDING.avatar(), true).join(); assertTrue(client.views().get(0).memory().paused());
            remote.snapshot.addProperty("paused", false); remote.snapshot.addProperty("session", "raw-command-session");
            clock.advance(2000); pump(client); assertFalse(client.views().get(0).memory().paused());
        }
    }
    @Test void canceledSubmissionRetriesItsStableIdWithANewAttemptAndIgnoresTheOldReply() {
        Clock clock = new Clock(); Remote remote = new Remote(); remote.holdEffects = true;
        remote.snapshot.getAsJsonArray("events").add(chat(1, "Hey Josh, how's it going?"));
        try (ClientCoordinator client = connect(remote, new AgentStore(temp), clock)) {
            pump(client); JsonObject old = remote.effects().get(0);
            remote.snapshot.addProperty("session", "lease-2"); clock.advance(2000); pump(client);
            assertEquals(2, remote.effects().size()); JsonObject retry = remote.effects().get(1);
            assertEquals(old.get("requestId"), retry.get("requestId")); assertNotEquals(old.get("attempt"), retry.get("attempt"));
            remote.reply(old, false); pump(client);
            assertFalse(client.views().get(0).status().startsWith("Paused"));
            remote.reply(retry, true); pump(client);
            assertEquals("Idle", client.views().get(0).status()); assertEquals(1, client.views().get(0).decisions());
        }
    }
    @Test void corruptLocalMemoryIsNotOverwrittenAndItsAvatarIsPaused() throws Exception {
        AgentStore store = new AgentStore(temp); Path file = store.path(BINDING); Files.createDirectories(file.getParent()); Files.writeString(file, "{broken");
        Clock clock = new Clock(); Remote remote = new Remote();
        try (ClientCoordinator client = connect(remote, store, clock)) {
            pump(client); assertNull(client.views().get(0).memory()); assertTrue(client.views().get(0).status().contains("Cannot read companion memory"));
            assertEquals(1, remote.outbound.stream().filter(m -> m.get("kind").getAsString().equals("control")).count());
            clock.advance(2000); pump(client); assertEquals("{broken", Files.readString(file));
        }
    }
    @Test void missingLocalMemoryReconstructsKnownServerWorkWithoutInventingItsOriginalGoal() {
        Clock clock = new Clock(); Remote remote = new Remote(); String id = UUID.randomUUID().toString();
        remote.snapshot.getAsJsonArray("programs").add(json("{\"id\":\"" + id + "\",\"state\":\"PAUSED\",\"result\":{}}"));
        try (ClientCoordinator client = connect(remote, new AgentStore(temp), clock)) {
            pump(client); var view = client.views().get(0); assertTrue(view.freshMemory());
            var task = view.memory().tasks().get(0).getAsJsonObject(); assertEquals(id, task.get("programId").getAsString());
            assertTrue(task.get("goal").getAsString().contains("prior local intention is unavailable"));
        }
    }
    @Test void staleConnectionRepliesAreIgnoredAndAnActualWorldMismatchSuspendsTheLink() {
        Clock clock = new Clock(); Remote remote = new Remote(); remote.autoPoll = false;
        try (ClientCoordinator client = connect(remote, new AgentStore(temp), clock)) {
            client.tick(); JsonObject request = remote.outbound.get(0); JsonObject stale = remote.baseline(request); stale.addProperty("client", UUID.randomUUID().toString());
            client.receive(stale); assertTrue(client.views().isEmpty());
            client.receive(remote.baseline(request)); assertEquals(1, client.views().size());
            clock.advance(2000); client.tick(); JsonObject wrongWorld = remote.baseline(remote.outbound.get(1)); wrongWorld.addProperty("worldId", UUID.randomUUID().toString());
            client.receive(wrongWorld); assertTrue(client.status().startsWith("Paused:"));
            long polls = remote.outbound.stream().filter(m -> m.get("kind").getAsString().equals("poll")).count();
            clock.advance(60_000); client.tick(); assertEquals(polls, remote.outbound.stream().filter(m -> m.get("kind").getAsString().equals("poll")).count());
            assertTrue(remote.outbound.stream().anyMatch(m -> m.get("kind").getAsString().equals("release")));
        }
    }
    @Test void aRealSnapshotTimeoutReleasesControlWithoutRepeatedRequests() {
        Clock clock = new Clock(); Remote remote = new Remote(); remote.autoPoll = false;
        try (ClientCoordinator client = connect(remote, new AgentStore(temp), clock)) {
            client.tick(); clock.advance(30_000); client.tick(); assertTrue(client.status().contains("timed out"));
            assertEquals(1, remote.outbound.stream().filter(m -> m.get("kind").getAsString().equals("poll")).count());
            assertEquals(1, remote.outbound.stream().filter(m -> m.get("kind").getAsString().equals("release")).count());
        }
    }
    private static ClientCoordinator connect(Remote remote, AgentStore store, Clock clock) {
        var client = new ClientCoordinator(BINDING.owner(), store, clock, remote::send); remote.client = client; return client;
    }
    private static void pump(ClientCoordinator client) { for (int i = 0; i < 12; i++) client.tick(); }
    private static final class Remote {
        ClientCoordinator client; JsonObject snapshot = frame(); boolean autoPoll = true, holdEffects; long revision;
        final List<JsonObject> outbound = new ArrayList<>();
        List<JsonObject> effects() { return outbound.stream().filter(m -> m.get("kind").getAsString().equals("effect")).toList(); }
        void send(JsonObject message) {
            outbound.add(message.deepCopy()); String kind = message.get("kind").getAsString();
            if (kind.equals("poll") && autoPoll) client.receive(baseline(message));
            if (kind.equals("effect") && !holdEffects) reply(message, true);
            if (kind.equals("control")) {
                boolean paused = message.get("action").getAsString().equals("pause");
                snapshot.addProperty("paused", paused); if (paused) snapshot.remove("session"); else snapshot.addProperty("session", "lease-resumed");
                JsonObject response = baseline(message); response.addProperty("kind", "reply"); client.receive(response);
            }
        }
        JsonObject baseline(JsonObject request) {
            JsonObject response = header(request); response.addProperty("kind", "snapshot"); response.addProperty("ok", true);
            if (request.has("sequence")) response.add("sequence", request.get("sequence")); response.addProperty("revision", ++revision);
            response.addProperty("ownerId", BINDING.owner().toString()); response.addProperty("worldId", BINDING.world().toString());
            JsonArray list = new JsonArray(); list.add(snapshot.deepCopy()); response.add("snapshots", list); return response;
        }
        void reply(JsonObject request, boolean ok) {
            JsonObject response = header(request); response.addProperty("ok", ok); response.addProperty("error", "Old lease result"); response.add("value", new JsonObject()); client.receive(response);
        }
        JsonObject header(JsonObject request) {
            JsonObject response = new JsonObject(); response.addProperty("protocol", 1); response.addProperty("kind", "reply");
            response.add("client", request.get("client")); response.add("requestId", request.get("requestId"));
            if (request.has("attempt")) response.add("attempt", request.get("attempt")); return response;
        }
    }
}
