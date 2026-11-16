package com.aurammods.paracraft.forge.control;

import com.aurammods.paracraft.core.protocol.WireCodec;
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
                    avatars.renewController(owner); lease = new Lease(sender.connection, client, server.getTickCount()); leases.put(owner, lease);
                }
                lease.lastTick = server.getTickCount(); response.addProperty("kind", "snapshot");
                response.add("sequence", message.get("sequence").deepCopy()); response.addProperty("ok", true); snapshots(owner, response); send.accept(response); return;
            }
            if (lease == null || lease.connection != sender.connection || !lease.client.equals(client))
                throw new IllegalArgumentException("Client controller is not acquired or has expired");
            if (kind.equals("release")) {
                avatars.suspendController(owner, "Client harness released control"); leases.remove(owner);
                response.addProperty("ok", true); send.accept(response); return;
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
        for (UUID owner : List.copyOf(leases.keySet())) if (server.getTickCount() - leases.get(owner).lastTick >= 200) {
            avatars.suspendController(owner, "Client harness heartbeat expired"); leases.remove(owner);
        }
        long now = System.nanoTime(); receivers.values().forEach(r -> r.assembler.expire(now));
    }
    public void disconnect(UUID owner) { leases.remove(owner); receivers.remove(owner); }
}
