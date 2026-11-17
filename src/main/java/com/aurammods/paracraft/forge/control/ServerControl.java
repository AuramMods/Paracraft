package com.aurammods.paracraft.forge.control;

import com.aurammods.paracraft.forge.avatar.AvatarEntry;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.aurammods.paracraft.forge.execution.GameJob;
import com.aurammods.paracraft.forge.inventory.InventorySearch;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** Authoritative server-thread boundary. The network adapter supplies the actual connected sender. */
public final class ServerControl {
    public static final double NEARBY_CHAT_DISTANCE = 24;
    private record Pending(UUID avatar, UUID owner, UUID session, UUID id, JsonObject signature,
                           InventorySearch search, CompletableFuture<JsonObject> future) { }
    private final AvatarManager avatars;
    private final MinecraftServer server;
    private final Map<UUID, Pending> searches = new HashMap<>();
    public ServerControl(AvatarManager avatars, MinecraftServer server) { this.avatars = avatars; this.server = server; }
    private void thread() { if (!server.isSameThread()) throw new IllegalStateException("Companion control requires the server thread"); }
    private JsonObject state(AvatarEntry entry) {
        if (!entry.control.has("schema")) {
            entry.control.addProperty("schema", 1); entry.control.addProperty("nextEvent", 1);
            entry.control.add("events", new JsonArray()); entry.control.add("compactedEvents", new JsonObject());
            entry.control.add("receipts", new JsonObject()); entry.control.add("programStates", new JsonObject()); avatars.dirty();
        }
        if (entry.control.get("schema").getAsInt() != 1) throw new IllegalStateException("Unsupported control state");
        return entry.control;
    }
    public long event(UUID avatar, String type, JsonObject details) {
        thread(); AvatarEntry entry = avatars.entry(avatar); if (entry == null) return 0;
        JsonObject state = state(entry); long id = state.get("nextEvent").getAsLong();
        JsonObject event = details.deepCopy(); event.addProperty("id", id); event.addProperty("type", type);
        event.addProperty("atMillis", System.currentTimeMillis()); event.addProperty("serverTick", server.getTickCount());
        state.addProperty("nextEvent", id + 1); JsonArray events = state.getAsJsonArray("events"); events.add(event);
        while (events.size() > 256) {
            String removed = events.remove(0).getAsJsonObject().get("type").getAsString(); JsonObject counts = state.getAsJsonObject("compactedEvents");
            counts.addProperty(removed, counts.has(removed) ? counts.get(removed).getAsLong() + 1 : 1);
        }
        avatars.dirty(); return id;
    }
    public int chat(UUID actualSender, String text) {
        thread();
        return chat(actualSender, text, server.getPlayerList().getPlayer(actualSender));
    }
    public int chat(ServerPlayer actualSender, String text) {
        thread();
        return chat(actualSender.getUUID(), text, actualSender);
    }
    private int chat(UUID actualSender, String text, ServerPlayer sender) {
        int notified = 0, nearbyCount = 0; boolean named = false; AvatarEntry nearby = null;
        for (AvatarEntry entry : avatars.entries()) {
            var actor = avatars.actor(entry.id); if (actor == null) continue;
            boolean mentioned = Pattern.compile("(?i)(?<![A-Za-z0-9_])" + Pattern.quote(entry.name) + "(?![A-Za-z0-9_])").matcher(text).find();
            named |= mentioned;
            if (!entry.authority.canInstruct(actualSender)) continue;
            if (mentioned) { chatEvent(actualSender, entry, text); notified++; }
            if (sender != null && actor.isAlive() && !actor.isRemoved() && actor.serverLevel() == sender.serverLevel()
                    && actor.distanceToSqr(sender) <= NEARBY_CHAT_DISTANCE * NEARBY_CHAT_DISTANCE) {
                nearby = entry; nearbyCount++;
            }
        }
        // A recognized name is explicit addressing, including a name the sender cannot control.
        if (!named && nearbyCount == 1) { chatEvent(actualSender, nearby, text); notified++; }
        return notified;
    }
    private void chatEvent(UUID actualSender, AvatarEntry entry, String text) {
        JsonObject details = new JsonObject(); details.addProperty("senderUuid", actualSender.toString()); details.addProperty("message", text);
        event(entry.id, "chat", details);
    }
    public JsonObject snapshot(UUID actualOwner, UUID avatar) {
        thread(); AvatarEntry entry = avatars.owned(actualOwner, avatar);
        return SnapshotBuilder.build(avatars, entry, state(entry));
    }
    public JsonArray snapshots(UUID actualOwner) {
        thread(); JsonArray result = new JsonArray();
        avatars.entries().stream().filter(e -> e.authority.owner().equals(actualOwner)).forEach(e -> result.add(snapshot(actualOwner, e.id)));
        return result;
    }
    public CompletableFuture<JsonObject> request(UUID actualOwner, UUID avatar, UUID session, UUID id,
                                                  String tool, JsonObject args, long triggerEvent) {
        thread(); AvatarEntry entry;
        try {
            entry = avatars.owned(actualOwner, avatar);
            var body = avatars.actor(avatar);
            if (!entry.authority.controls(actualOwner, session) || entry.paused || body == null || !body.isAlive() || body.isRemoved() || body.deathCommitted())
                return CompletableFuture.completedFuture(error("AUTHORITY_LOST", "Control session is inactive or stale"));
        } catch (IllegalArgumentException denied) { return CompletableFuture.completedFuture(error("AUTHORITY_LOST", denied.getMessage())); }
        JsonObject signature = new JsonObject(); signature.addProperty("tool", tool); signature.add("arguments", args.deepCopy()); signature.addProperty("trigger", triggerEvent);
        JsonObject receipts = state(entry).getAsJsonObject("receipts");
        if (receipts.has(id.toString())) {
            JsonObject receipt = receipts.getAsJsonObject(id.toString());
            return CompletableFuture.completedFuture(receipt.getAsJsonObject("signature").equals(signature)
                    ? receipt.getAsJsonObject("reply").deepCopy() : error("REQUEST_CONFLICT", "Request ID already identifies different content"));
        }
        Pending pending = searches.get(avatar);
        if (pending != null && pending.id.equals(id)) return pending.signature.equals(signature) ? pending.future
                : CompletableFuture.completedFuture(error("REQUEST_CONFLICT", "Pending request ID identifies different content"));
        try {
            JsonObject source = authorizeTrigger(entry, triggerEvent);
            JsonObject result;
            switch (tool) {
                case "configure_behavior" -> result = avatars.configureBehavior(actualOwner,avatar,args);
                case "observe" -> {
                    if (!string(args, "kind").equals("inventory")) throw new IllegalArgumentException("Unsupported observation kind");
                    if (pending != null) throw new IllegalStateException("An inventory observation is already running");
                    var actor = avatars.actor(avatar); var future = new CompletableFuture<JsonObject>();
                    searches.put(avatar, new Pending(avatar, actualOwner, session, id, signature,
                            new InventorySearch(actor.serverLevel(), actor.chunkPosition(), new ItemSelector(string(args, "item"))), future));
                    return future;
                }
                case "program" -> {
                    UUID programId = UUID.fromString(string(args, "id"));
                    JsonObject bound = new JsonObject();
                    if (args.has("observationRefs")) for (var reference : args.getAsJsonObject("observationRefs").entrySet()) {
                        String observationId = reference.getValue().getAsString();
                        JsonObject receipt = receipts.has(observationId) ? receipts.getAsJsonObject(observationId) : null;
                        if (receipt == null || !receipt.getAsJsonObject("signature").get("tool").getAsString().equals("observe")
                                || !receipt.getAsJsonObject("reply").get("ok").getAsBoolean())
                            throw new IllegalArgumentException("No server observation receipt matches " + observationId);
                        bound.add(reference.getKey(), receipt.getAsJsonObject("reply").getAsJsonObject("value").getAsJsonObject("observation").deepCopy());
                    }
                    result = avatars.jobs().submitProgram(actualOwner, avatar, session, programId, args,
                            args.has("mode") ? args.get("mode").getAsString() : "replace", bound).view();
                }
                case "resume_program" -> result = avatars.jobs().resumeRequest(actualOwner, avatar, session, UUID.fromString(string(args, "id"))).view();
                case "pause_program", "cancel_program" -> {
                    GameJob job = args.has("id") ? avatars.jobs().request(avatar, UUID.fromString(string(args, "id"))) : avatars.jobs().get(avatar);
                    if (job != null) { if (tool.equals("pause_program")) avatars.jobs().pauseRequest(avatar, job.id); else job.cancel("Cancelled by client harness"); result = job.view(); }
                    else result = new JsonObject();
                }
                case "chat" -> {
                    String message = string(args, "message");
                    server.getPlayerList().broadcastSystemMessage(Component.literal("<" + entry.name + "> " + message), false);
                    result = new JsonObject(); result.addProperty("sent", true);
                    JsonObject details = new JsonObject(); details.addProperty("message", message); details.addProperty("requestId", id.toString()); details.addProperty("wake", false);
                    event(avatar, "response", details);
                }
                case "trust", "untrust" -> {
                    if (source == null || !source.has("senderUuid") || !entry.authority.owner().toString().equals(source.get("senderUuid").getAsString()))
                        throw new IllegalArgumentException("Permission administration requires an owner's instruction");
                    UUID player = UUID.fromString(string(args, "playerUuid")); avatars.trust(actualOwner, avatar, player, tool.equals("trust"));
                    result = new JsonObject(); result.addProperty("playerUuid", player.toString()); result.addProperty("trusted", tool.equals("trust"));
                }
                default -> throw new IllegalArgumentException("Unknown server tool " + tool);
            }
            return CompletableFuture.completedFuture(remember(entry, id, signature, ok(result)));
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            return CompletableFuture.completedFuture(remember(entry, id, signature, error("TOOL_FAILED", invalid.getMessage())));
        } catch (RuntimeException unexpected) {
            com.aurammods.paracraft.Paracraft.LOG.error("Unexpected companion tool failure in {} for {}", tool, avatar, unexpected);
            return CompletableFuture.completedFuture(remember(entry, id, signature, error("TOOL_FAILED", unexpected.toString())));
        }
    }
    private JsonObject authorizeTrigger(AvatarEntry entry, long id) {
        if (id == 0) return null;
        for (var item : state(entry).getAsJsonArray("events")) {
            JsonObject event = item.getAsJsonObject(); if (event.get("id").getAsLong() != id) continue;
            if (event.get("type").getAsString().equals("chat") && !entry.authority.canInstruct(UUID.fromString(event.get("senderUuid").getAsString())))
                throw new IllegalArgumentException("Instruction sender no longer has permission");
            return event;
        }
        throw new IllegalArgumentException("Instruction event expired or was not issued by this server");
    }
    public void tick() {
        thread();
        for (Pending pending : List.copyOf(searches.values())) {
            AvatarEntry entry = avatars.entry(pending.avatar);
            if (entry == null || !entry.authority.controls(pending.owner, pending.session) || entry.paused) {
                searches.remove(pending.avatar); pending.future.complete(error("AUTHORITY_LOST", "Observation controller disconnected")); continue;
            }
            try { if (!pending.search.done()) pending.search.tick(); }
            catch (RuntimeException failure) {
                com.aurammods.paracraft.Paracraft.LOG.error("Inventory observation failed for {}", pending.avatar, failure);
                JsonObject reply = error("OBSERVATION_FAILED", failure.toString()); reply.add("partial", pending.search.result());
                searches.remove(pending.avatar); pending.future.complete(remember(entry, pending.id, pending.signature, reply)); continue;
            }
            if (pending.search.done()) {
                JsonObject result = new JsonObject(); result.addProperty("id", pending.id.toString()); result.add("observation", pending.search.result());
                searches.remove(pending.avatar); pending.future.complete(remember(entry, pending.id, pending.signature, ok(result)));
            }
        }
        for (AvatarEntry entry : avatars.entries()) {
            JsonObject states = state(entry).getAsJsonObject("programStates");
            for (GameJob job : avatars.jobs().requests(entry.id)) {
                String key = job.id.toString(), current = job.state().name();
                if (states.has(key) && current.equals(states.get(key).getAsString())) continue;
                states.addProperty(key, current); avatars.dirty();
                if (job.state().terminal()) { JsonObject details = new JsonObject(); details.add("program", job.view()); event(entry.id, "program", details); }
            }
        }
    }
    public void disconnect(UUID owner) {
        thread();
        for (Pending pending : List.copyOf(searches.values())) if (pending.owner.equals(owner)) {
            searches.remove(pending.avatar); pending.future.complete(error("AUTHORITY_LOST", "Owner disconnected"));
        }
    }
    private JsonObject remember(AvatarEntry entry, UUID id, JsonObject signature, JsonObject reply) {
        JsonObject receipt = new JsonObject(); receipt.add("signature", signature.deepCopy()); receipt.add("reply", reply.deepCopy());
        state(entry).getAsJsonObject("receipts").add(id.toString(), receipt); avatars.dirty();
        avatars.checkpoint(entry); return reply;
    }
    private static JsonObject ok(JsonObject value) { JsonObject reply = new JsonObject(); reply.addProperty("ok", true); reply.add("value", value); return reply; }
    private static JsonObject error(String code, String message) {
        JsonObject reply = new JsonObject(); reply.addProperty("ok", false); reply.addProperty("code", code); reply.addProperty("error", message); return reply;
    }
    private static String string(JsonObject args, String key) {
        if (!args.has(key) || !args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isString() || args.get(key).getAsString().isBlank())
            throw new IllegalArgumentException("Missing string " + key); return args.get(key).getAsString();
    }
}
