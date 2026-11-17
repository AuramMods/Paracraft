package com.aurammods.paracraft.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Durable cognitive state lives on the owner client, scoped to a world/avatar binding. */
public final class AgentState {
    private static final Set<String> STATUSES = Set.of("QUEUED", "ACTIVE", "INACTIVE", "WAITING", "COMPLETED", "FAILED");
    private JsonObject tasks = new JsonObject(), memory = new JsonObject(), historyCounts = new JsonObject();
    private JsonArray history = new JsonArray();
    final List<JsonObject> inbox = new ArrayList<>();
    JsonObject turn;
    long eventCursor, cycleCounter, lastInferenceMillis;
    long lifeRevision = -1;
    boolean paused;
    String lastError = "";
    public String personality = "Helpful, practical companion.";
    // Live requests send these configured values unchanged; account/model availability is reported by the API.
    public String lightModel = "gpt-6-luna", lightEffort = "none", planningModel = "gpt-6-luna", planningEffort = "xhigh";
    long wakeAtMillis;
    String wakeEvent = "";

    public JsonArray tasks() {
        var sorted = tasks.entrySet().stream().map(e -> e.getValue().getAsJsonObject())
                .sorted(Comparator.comparingInt(t -> t.get("priority").getAsInt())).toList();
        JsonArray value = new JsonArray(); sorted.forEach(t -> value.add(t.deepCopy())); return value;
    }
    public boolean hasWork() {
        return tasks.entrySet().stream().anyMatch(e -> !Set.of("COMPLETED", "FAILED").contains(e.getValue().getAsJsonObject().get("status").getAsString()));
    }
    public boolean paused() { return paused; }
    public void mutate(String tool, JsonObject args) {
        switch (tool) {
            case "task_upsert" -> {
                String id = required(args, "id"); JsonObject task = tasks.has(id) ? tasks.getAsJsonObject(id).deepCopy() : new JsonObject();
                if (!task.has("goal")) task.addProperty("goal", required(args, "goal"));
                if (!task.has("priority")) task.addProperty("priority", tasks.size());
                if (!task.has("status")) task.addProperty("status", "QUEUED");
                args.entrySet().forEach(e -> task.add(e.getKey(), e.getValue().deepCopy())); task.addProperty("id", id);
                required(task, "goal");
                if (!STATUSES.contains(required(task, "status"))) throw new IllegalArgumentException("Unknown task status");
                task.addProperty("priority", task.get("priority").getAsBigDecimal().intValueExact());
                tasks.add(id, task);
            }
            case "task_remove" -> tasks.remove(required(args, "id"));
            case "remember" -> memory.addProperty(required(args, "key"), required(args, "text"));
            case "forget" -> memory.remove(required(args, "key"));
            case "personality" -> personality = required(args, "text");
            default -> throw new IllegalArgumentException("Unknown local tool " + tool);
        }
        history("tool", tool + " " + args);
    }
    /** Damage/pickup notifications coalesce; the latest full physical snapshot stays authoritative. */
    void enqueue(JsonObject event) {
        String type = required(event, "type");
        if (Set.of("damage", "pickup").contains(type)) {
            for (int i = inbox.size() - 1; i >= 0; i--) {
                JsonObject older = inbox.get(i);
                if (!type.equals(older.get("type").getAsString())) continue;
                JsonObject merged = event.deepCopy();
                merged.addProperty("firstId", older.has("firstId") ? older.get("firstId").getAsLong() : older.get("id").getAsLong());
                merged.addProperty("occurrences", (older.has("occurrences") ? older.get("occurrences").getAsInt() : 1) + 1);
                if (type.equals("damage") && event.has("amount") && older.has("amount"))
                    merged.addProperty("amount", event.get("amount").getAsDouble() + older.get("amount").getAsDouble());
                inbox.set(i, merged); return;
            }
        }
        inbox.add(event.deepCopy());
    }
    public void history(String kind, String text) {
        JsonObject entry = new JsonObject(); entry.addProperty("kind", kind); entry.addProperty("text", text); history.add(entry);
        while (history.size() > 128 || history.toString().length() > 65_536) {
            String removed = history.remove(0).getAsJsonObject().get("kind").getAsString();
            historyCounts.addProperty(removed, historyCounts.has(removed) ? historyCounts.get(removed).getAsLong() + 1 : 1);
        }
    }
    public JsonObject context() {
        JsonObject value = new JsonObject(); value.add("tasks", tasks()); value.add("memory", memory.deepCopy());
        value.addProperty("personality", personality); value.add("history", history.deepCopy());
        value.add("compactedHistoryCounts", historyCounts.deepCopy()); return value;
    }
    public JsonObject save() {
        JsonObject value = context(); value.addProperty("schema", 1); value.addProperty("eventCursor", eventCursor); value.addProperty("cycleCounter", cycleCounter);
        value.addProperty("lastInferenceMillis", lastInferenceMillis); value.addProperty("wakeAtMillis", wakeAtMillis); value.addProperty("wakeEvent", wakeEvent);
        value.addProperty("paused", paused); value.addProperty("lastError", lastError);
        value.addProperty("lifeRevision", lifeRevision);
        value.addProperty("lightModel", lightModel); value.addProperty("lightEffort", lightEffort);
        value.addProperty("planningModel", planningModel); value.addProperty("planningEffort", planningEffort);
        if (turn != null) value.add("turn", turn.deepCopy());
        JsonArray pending = new JsonArray(); inbox.forEach(e -> pending.add(e.deepCopy())); value.add("inbox", pending); return value;
    }
    public static AgentState load(JsonObject value) {
        if (!value.has("schema") || value.get("schema").getAsInt() != 1) throw new IllegalArgumentException("Unsupported client memory schema");
        AgentState state = new AgentState();
        for (var task : value.getAsJsonArray("tasks")) state.mutate("task_upsert", task.getAsJsonObject());
        state.memory = value.getAsJsonObject("memory").deepCopy(); state.personality = required(value, "personality");
        state.history = value.getAsJsonArray("history").deepCopy(); state.historyCounts = value.getAsJsonObject("compactedHistoryCounts").deepCopy();
        state.eventCursor = value.get("eventCursor").getAsLong(); state.cycleCounter = value.get("cycleCounter").getAsLong();
        state.lastInferenceMillis = value.get("lastInferenceMillis").getAsLong();
        state.lifeRevision = value.has("lifeRevision") ? value.get("lifeRevision").getAsLong() : -1;
        state.paused = value.get("paused").getAsBoolean(); state.lastError = value.get("lastError").getAsString();
        state.wakeAtMillis = value.get("wakeAtMillis").getAsLong(); state.wakeEvent = value.get("wakeEvent").getAsString();
        state.lightModel = required(value, "lightModel"); state.lightEffort = required(value, "lightEffort");
        state.planningModel = required(value, "planningModel"); state.planningEffort = required(value, "planningEffort");
        if (value.has("turn")) { state.turn = value.getAsJsonObject("turn").deepCopy(); state.validateTurn(); }
        for (var e : value.getAsJsonArray("inbox")) state.inbox.add(e.getAsJsonObject().deepCopy()); return state;
    }
    void validateTurn() {
        if (turn == null) return;
        UUID.fromString(required(turn, "id"));
        InferenceProvider.Route route = InferenceProvider.Route.valueOf(required(turn, "route"));
        if (turn.get("round").getAsInt() < 0 || turn.getAsJsonObject("trigger").get("id").getAsLong() < 0)
            throw new IllegalArgumentException("Invalid saved decision identity");
        required(turn.getAsJsonObject("trigger"), "type");
        if (!turn.get("observations").isJsonObject()) throw new IllegalArgumentException("Invalid saved observations");
        if (turn.has("calls")) {
            int cursor = turn.get("cursor").getAsInt(); JsonArray calls = turn.getAsJsonArray("calls");
            if (cursor < 0 || cursor > calls.size()) throw new IllegalArgumentException("Invalid saved tool cursor");
            List<InferenceProvider.Call> decoded = new ArrayList<>();
            for (var value : calls) {
                JsonObject call = value.getAsJsonObject();
                decoded.add(new InferenceProvider.Call(required(call, "tool"), call.getAsJsonObject("arguments")));
            }
            ToolPolicy.validate(route, new InferenceProvider.Decision(decoded));
        }
    }
    static String required(JsonObject args, String key) {
        if (!args.has(key) || !args.get(key).isJsonPrimitive() || !args.getAsJsonPrimitive(key).isString() || args.get(key).getAsString().isBlank())
            throw new IllegalArgumentException("Missing string " + key); return args.get(key).getAsString();
    }
}
