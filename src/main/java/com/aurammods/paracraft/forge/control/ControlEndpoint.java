package com.aurammods.paracraft.forge.control;

import com.aurammods.paracraft.core.protocol.WireCodec;
import com.aurammods.paracraft.core.agent.AgentActivity;
import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Called with the native connection's sender, never an owner identity from JSON. */
public final class ControlEndpoint {
    private static final class Lease {
        final Object connection; final UUID client; long lastTick;
        Lease(Object connection, UUID client, long tick) { this.connection = connection; this.client = client; lastTick = tick; }
    }
    private record Receiver(Object connection, WireCodec.Assembler assembler) { }
    private final AvatarManager avatars;
    private final MinecraftServer server;
    private final Map<UUID, Lease> leases = new HashMap<>();
    private final Map<UUID, Receiver> receivers = new HashMap<>();
    private final Map<UUID, AgentActivity> activities = new HashMap<>();
    private long snapshotRevision;
    public ControlEndpoint(AvatarManager avatars, MinecraftServer server) { this.avatars = avatars; this.server = server; }
    public void receiveFrame(ServerPlayer sender, WireCodec.Frame frame, Consumer<JsonObject> reply) {
        Receiver receiver = receivers.get(sender.getUUID());
        if (receiver == null || receiver.connection != sender.connection) {
            receiver = new Receiver(sender.connection, new WireCodec.Assembler()); receivers.put(sender.getUUID(), receiver);
        }
        receiver.assembler.accept(frame, System.nanoTime()).ifPresent(message -> receive(sender, message, reply));
    }
    public void receive(ServerPlayer sender, JsonObject message, Consumer<JsonObject> send) {
        if (!server.isSameThread()) throw new IllegalStateException("Network control requires the server thread");
        UUID owner = sender.getUUID(); JsonObject response = new JsonObject();
        response.addProperty("protocol", WireCodec.VERSION); response.addProperty("kind", "reply");
        if (message.has("client")) response.add("client", message.get("client").deepCopy());
        if (message.has("requestId")) response.add("requestId", message.get("requestId").deepCopy());
        if (message.has("attempt")) response.add("attempt", message.get("attempt").deepCopy());
        try {
            if (message.get("protocol").getAsInt() != WireCodec.VERSION) throw new IllegalArgumentException("Unsupported protocol");
            UUID client = UUID.fromString(message.get("client").getAsString()), request = UUID.fromString(message.get("requestId").getAsString());
            String kind = message.get("kind").getAsString(); Lease lease = leases.get(owner);
            if (kind.equals("poll")) {
                if (lease == null || lease.connection != sender.connection || !lease.client.equals(client)) {
                    clearActivities(owner);
                    avatars.renewController(owner); lease = new Lease(sender.connection, client, server.getTickCount()); leases.put(owner, lease);
                }
                if (message.has("activities")) for (var element : message.getAsJsonArray("activities")) {
                    var activity = element.getAsJsonObject(); UUID avatar = UUID.fromString(activity.get("avatar").getAsString());
                    // A raw owner command can destroy a body before its client sees the next snapshot.
                    if (avatars.entry(avatar) == null) continue;
                    avatars.owned(owner, avatar); activities.put(avatar, AgentActivity.parse(activity));
                }
                lease.lastTick = server.getTickCount(); response.addProperty("kind", "snapshot");
                response.add("sequence", message.get("sequence").deepCopy()); response.addProperty("ok", true); snapshots(owner, response); send.accept(response); return;
            }
            if (lease == null || lease.connection != sender.connection || !lease.client.equals(client))
                throw new IllegalArgumentException("Client controller is not acquired or has expired");
            if (kind.equals("release")) {
                clearActivities(owner);
                avatars.suspendController(owner, "Client harness released control"); leases.remove(owner);
                response.addProperty("ok", true); send.accept(response); return;
            }
            if (kind.equals("agent_output")) {
                UUID avatar = UUID.fromString(message.get("avatar").getAsString()); var entry = avatars.owned(owner, avatar);
                if (!entry.authority.controls(owner, UUID.fromString(message.get("session").getAsString())))
                    throw new IllegalArgumentException("Agent output session is stale");
                String data = message.get("data").getAsString(); UUID output = UUID.fromString(message.get("outputId").getAsString());
                int part = message.get("part").getAsBigDecimal().intValueExact(), parts = message.get("parts").getAsBigDecimal().intValueExact();
                if (data.length() > 4096 || part < 1 || parts < part) throw new IllegalArgumentException("Invalid agent output chunk");
                if (Paracraft.debugAgentOutput()) Paracraft.LOG.info("[Agent {} output {} {}/{}] {}", entry.name, output, part, parts, data);
                return;
            }
            if (kind.equals("effect")) {
                UUID avatar = UUID.fromString(message.get("avatar").getAsString()), session = UUID.fromString(message.get("session").getAsString());
                Lease captured = lease;
                avatars.control().request(owner, avatar, session, request, message.get("tool").getAsString(),
                        message.getAsJsonObject("arguments"), message.get("trigger").getAsLong()).thenAccept(result -> {
                    if (leases.get(owner) != captured) return;
                    result.entrySet().forEach(e -> response.add(e.getKey(), e.getValue().deepCopy())); send.accept(response);
                });
                return;
            }
            if (kind.equals("control")) {
                String action = message.get("action").getAsString(); JsonObject args = message.getAsJsonObject("arguments");
                if (action.equals("spawn")) avatars.spawn(owner, sender.serverLevel(), sender.position().add(1, 0, 0), args.get("name").getAsString());
                else {
                    UUID avatar = UUID.fromString(message.get("avatar").getAsString()); var entry = avatars.owned(owner, avatar);
                    switch (action) {
                        case "pause" -> { if (!entry.paused) avatars.pause(owner, avatar, true); }
                        case "resume" -> {
                            var job = avatars.jobs().get(avatar);
                            if (entry.paused || entry.authority.session() == null || job != null && job.state() == com.aurammods.paracraft.core.JobState.PAUSED)
                                avatars.pause(owner, avatar, false);
                        }
                        case "destroy" -> avatars.destroy(owner, avatar);
                        default -> throw new IllegalArgumentException("Unknown owner control " + action);
                    }
                }
                response.addProperty("ok", true); snapshots(owner, response); send.accept(response); return;
            }
            throw new IllegalArgumentException("Unknown message kind " + kind);
        } catch (RuntimeException failure) {
            response.addProperty("ok", false); response.addProperty("error", failure.toString()); send.accept(response);
        }
    }
    private void snapshots(UUID owner, JsonObject response) {
        response.addProperty("revision", ++snapshotRevision);
        response.addProperty("ownerId", owner.toString()); response.addProperty("worldId", avatars.worldId().toString());
        response.add("snapshots", avatars.control().snapshots(owner));
    }
    public void tick() {
        activities.keySet().removeIf(id -> avatars.entry(id) == null);
        for (UUID owner : List.copyOf(leases.keySet())) if (server.getTickCount() - leases.get(owner).lastTick >= 200) {
            clearActivities(owner);
            avatars.suspendController(owner, "Client harness heartbeat expired"); leases.remove(owner);
        }
        long now = System.nanoTime(); receivers.values().forEach(r -> r.assembler.expire(now));
    }
    public AgentActivity activity(UUID avatar) { return activities.getOrDefault(avatar, new AgentActivity(AgentActivity.Phase.IDLE, 0)); }
    private void clearActivities(UUID owner) { avatars.entries().stream().filter(e -> e.authority.owner().equals(owner)).forEach(e -> activities.remove(e.id)); }
    public void disconnect(UUID owner) { clearActivities(owner); leases.remove(owner); receivers.remove(owner); }
}
