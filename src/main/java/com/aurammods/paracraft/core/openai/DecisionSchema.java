package com.aurammods.paracraft.core.openai;

import com.aurammods.paracraft.core.agent.InferenceProvider;
import com.aurammods.paracraft.core.agent.ToolPolicy;
import com.google.gson.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Strict tool arguments, including recursive action programs. No JSON-encoded argument strings. */
final class DecisionSchema {
    private static final JsonObject LIGHT = build(InferenceProvider.Route.LIGHT);
    private static final JsonObject PLANNING = build(InferenceProvider.Route.PLANNING);
    static JsonObject parameters(InferenceProvider.Route route) { return schema(route).deepCopy(); }
    private static JsonObject schema(InferenceProvider.Route route) { return route == InferenceProvider.Route.LIGHT ? LIGHT : PLANNING; }
    private static JsonObject build(InferenceProvider.Route route) {
        var variants = new JsonArray();
        for (String tool : ToolPolicy.allowed(route).stream().sorted().toList()) {
            JsonObject args = switch (tool) {
                case "think_harder" -> object();
                case "chat" -> object("message", text());
                case "observe" -> object("kind", values("inventory"), "item", text());
                case "task_upsert" -> object("id", text(), "goal", optional(text()), "status", optional(values("QUEUED", "ACTIVE", "INACTIVE", "WAITING", "COMPLETED", "FAILED")),
                        "priority", optional(integer(Integer.MIN_VALUE, Integer.MAX_VALUE)), "programId", optional(uuid()));
                case "task_remove" -> object("id", text());
                case "remember" -> object("key", text(), "text", text());
                case "forget" -> object("key", text());
                case "personality" -> object("text", text());
                case "wait" -> object("event", optional(values("program", "chat", "pickup", "damage", "any")), "seconds", optional(integer(0, Long.MAX_VALUE)));
                case "trust", "untrust" -> object("playerUuid", uuid());
                case "resume_program" -> object("id", uuid());
                case "pause_program", "cancel_program" -> object("id", optional(uuid()));
                case "configure_behavior" -> object("players", optional(reaction()), "mobs", optional(reaction()),
                        "ambientIdle", optional(type("boolean")),
                        "idle", optional(object("enabled", optional(type("boolean")), "dimension", optional(text()),
                                "waypoints", optional(array(position(false))), "waitTicks", optional(integer(0, Long.MAX_VALUE)))));
                case "program" -> object("schema", numberValue(1), "id", uuid(), "taskId", optional(text()),
                        "mode", values("interrupt", "append", "replace"),
                        "observationRefs", array(object("name", text(), "receipt", uuid())), "steps", array(ref("step")));
                default -> throw new IllegalArgumentException("Missing decision schema for " + tool);
            };
            variants.add(object("tool", values(tool), "arguments", args));
        }
        var root = object("calls", array(any(variants)));
        if (route == InferenceProvider.Route.PLANNING) { var defs = new JsonObject(); defs.add("step", steps()); root.add("$defs", defs); }
        return root;
    }
    private static JsonObject steps() {
        var variants = new JsonArray();
        variants.add(operation("travel", "destination", position(false)));
        variants.add(operation("search_recipes", "item", text(), "limit", optional(integer(1, 128))));
        variants.add(operation("craft_item", "item", text(), "quantity", integer(1, Integer.MAX_VALUE), "table", type("null"),
                "recipe", optional(text()), "grid", optional(fixedArray(integer(-1, 35), 4))));
        variants.add(operation("craft_item", "item", text(), "quantity", integer(1, Integer.MAX_VALUE), "table", position(true),
                "recipe", optional(text()), "grid", optional(fixedArray(integer(-1, 35), 9))));
        variants.add(operation("search_containers", "item", text()));
        variants.add(operation("collect_items", "item", text(), "quantity", optional(any(integer(0, Integer.MAX_VALUE), values("all"))), "containersFrom", optional(text())));
        variants.add(operation("deposit_items", "destination", position(true), "item", text(), "quantity", integer(0, Integer.MAX_VALUE)));
        variants.add(operation("deliver_items", "itemsFrom", text(), "recipient", object("playerUuid", uuid())));
        variants.add(operation("open_inventory", "position", position(true), "limit", optional(integer(1, 4096))));
        variants.add(operation("take_items", "position", position(true), "item", text(), "quantity", integer(0, Integer.MAX_VALUE)));
        for (String op : List.of("mine_blocks", "farm")) {
            for (String target : List.of("block", "blocks", "cuboid")) {
                var schema = switch (target) { case "block" -> position(true); case "blocks" -> array(position(true)); default -> object("from", position(true), "to", position(true)); };
                variants.add(op.equals("farm") ? operation(op, target, schema, "plant", optional(text())) : operation(op, target, schema));
            }
        }
        variants.add(operation("place_blocks", "placements", array(object("position", position(true), "item", text()))));
        variants.add(operation("equip", "item", text(), "slot", values("mainhand", "offhand", "head", "chest", "legs", "feet")));
        for (String op : List.of("follow", "attack_entity")) variants.add(operation(op, "target", uuid(), "distance", optional(minimum(type("number"), 0.5)), "ticks", optional(integer(0, Long.MAX_VALUE))));
        variants.add(operation("set_spawn", "bed", position(true)));
        variants.add(operation("search_blocks", "block", text(), "limit", optional(integer(1, 4096))));
        variants.add(operation("use_portal", "position", position(true), "dimension", optional(text()), "timeoutTicks", optional(integer(0, Long.MAX_VALUE))));
        variants.add(operation("execute_command", "command", text(), "await", optional(values("command", "teleport")), "timeoutTicks", optional(integer(0, Long.MAX_VALUE)),
                "dimension", optional(text()), "destination", optional(position(false)), "playerUuid", optional(uuid()), "tolerance", optional(minimum(type("number"), 0))));
        variants.add(operation("discover_commands", "prefix", optional(type("string"))));
        variants.add(operation("wait_ticks", "ticks", integer(0, Long.MAX_VALUE)));
        variants.add(operation("chat", "text", text()));
        variants.add(operation("report_result")); variants.add(operation("report_failure"));
        variants.add(operation("fail", "message", optional(text()))); variants.add(operation("rethrow"));
        variants.add(object("try", array(ref("step")), "catch", array(ref("step"))));
        return any(variants);
    }
    private static JsonObject operation(String name, Object... fields) {
        var all = new ArrayList<Object>(); all.add("op"); all.add(values(name));
        java.util.Collections.addAll(all, fields); all.add("saveAs"); all.add(optional(text()));
        return object(all.toArray());
    }
    private static JsonObject object(Object... fields) {
        var schema = type("object"); var properties = new JsonObject(); var required = new JsonArray();
        for (int i = 0; i < fields.length; i += 2) { String name = (String)fields[i]; properties.add(name, (JsonObject)fields[i + 1]); required.add(name); }
        schema.add("properties", properties); schema.add("required", required); schema.addProperty("additionalProperties", false); return schema;
    }
    private static JsonObject type(String type) { var schema = new JsonObject(); schema.addProperty("type", type); return schema; }
    private static JsonObject text() { var schema = type("string"); schema.addProperty("pattern", "\\S"); return schema; }
    private static JsonObject uuid() { var schema = type("string"); schema.addProperty("format", "uuid"); return schema; }
    private static JsonObject reaction() { return values("passive", "flee", "neutral", "aggressive"); }
    private static JsonObject values(String... values) { var schema = type("string"); var choices = new JsonArray(); for (String value : values) choices.add(value); schema.add("enum", choices); return schema; }
    private static JsonObject numberValue(int value) { var schema = type("integer"); var choices = new JsonArray(); choices.add(value); schema.add("enum", choices); return schema; }
    private static JsonObject minimum(JsonObject schema, Number value) { schema.addProperty("minimum", value); return schema; }
    private static JsonObject integer(long min, long max) { var schema = minimum(type("integer"), min); schema.addProperty("maximum", max); return schema; }
    private static JsonObject array(JsonObject item) { var schema = type("array"); schema.add("items", item); return schema; }
    private static JsonObject fixedArray(JsonObject item, int size) { var schema = array(item); schema.addProperty("minItems", size); schema.addProperty("maxItems", size); return schema; }
    private static JsonObject position(boolean integral) { var schema = array(integral ? integer(Integer.MIN_VALUE, Integer.MAX_VALUE) : type("number")); schema.addProperty("minItems", 3); schema.addProperty("maxItems", 3); return schema; }
    private static JsonObject ref(String name) { var schema = new JsonObject(); schema.addProperty("$ref", "#/$defs/" + name); return schema; }
    private static JsonObject optional(JsonObject schema) { return any(schema, type("null")); }
    private static JsonObject any(JsonObject... schemas) { var choices = new JsonArray(); for (var schema : schemas) choices.add(schema); return any(choices); }
    private static JsonObject any(JsonArray choices) { var schema = new JsonObject(); schema.add("anyOf", choices); return schema; }

    static InferenceProvider.Decision decode(JsonObject batch, InferenceProvider.Route route) throws IOException {
        var schema = schema(route);
        if (!matches(batch, schema, schema)) throw new IOException("Model decision did not match the typed companion tool schema");
        var calls = new ArrayList<InferenceProvider.Call>();
        for (var entry : batch.getAsJsonArray("calls")) {
            var call = entry.getAsJsonObject(); String tool = call.get("tool").getAsString();
            var args = clean(call.getAsJsonObject("arguments")).getAsJsonObject();
            if (tool.equals("program")) {
                var refs = new JsonObject();
                for (var element : args.getAsJsonArray("observationRefs")) {
                    var reference = element.getAsJsonObject(); String name = reference.get("name").getAsString();
                    if (refs.has(name)) throw new IOException("Model returned duplicate observation binding names");
                    refs.add(name, reference.get("receipt").deepCopy());
                }
                args.add("observationRefs", refs);
            }
            calls.add(new InferenceProvider.Call(tool, args));
        }
        var decision = new InferenceProvider.Decision(calls); ToolPolicy.validate(route, decision); return decision;
    }
    private static JsonElement clean(JsonElement value) {
        if (value.isJsonObject()) { var result = new JsonObject(); value.getAsJsonObject().entrySet().stream().filter(e -> !e.getValue().isJsonNull()).forEach(e -> result.add(e.getKey(), clean(e.getValue()))); return result; }
        if (value.isJsonArray()) { var result = new JsonArray(); value.getAsJsonArray().forEach(e -> result.add(clean(e))); return result; }
        return value.deepCopy();
    }
    /** Validate actual returned data against the exact emitted schema, before any calls can execute. */
    private static boolean matches(JsonElement value, JsonObject rule, JsonObject root) {
        if (rule.has("$ref")) return matches(value, root.getAsJsonObject("$defs").getAsJsonObject(rule.get("$ref").getAsString().substring("#/$defs/".length())), root);
        if (rule.has("anyOf")) { for (var branch : rule.getAsJsonArray("anyOf")) if (matches(value, branch.getAsJsonObject(), root)) return true; return false; }
        if (rule.has("enum")) { boolean found = false; for (var choice : rule.getAsJsonArray("enum")) found |= choice.equals(value); if (!found) return false; }
        String type = rule.get("type").getAsString();
        if (type.equals("null")) return value.isJsonNull();
        if (value.isJsonNull()) return false;
        if (type.equals("object")) {
            if (!value.isJsonObject()) return false; var object = value.getAsJsonObject(); var properties = rule.getAsJsonObject("properties");
            for (var name : rule.getAsJsonArray("required")) if (!object.has(name.getAsString())) return false;
            for (var entry : object.entrySet()) if (!properties.has(entry.getKey()) || !matches(entry.getValue(), properties.getAsJsonObject(entry.getKey()), root)) return false;
            return true;
        }
        if (type.equals("array")) {
            if (!value.isJsonArray()) return false; var array = value.getAsJsonArray();
            if (rule.has("minItems") && array.size() < rule.get("minItems").getAsInt() || rule.has("maxItems") && array.size() > rule.get("maxItems").getAsInt()) return false;
            for (var item : array) if (!matches(item, rule.getAsJsonObject("items"), root)) return false; return true;
        }
        if (!value.isJsonPrimitive()) return false; var primitive = value.getAsJsonPrimitive();
        if (type.equals("boolean")) return primitive.isBoolean();
        if (type.equals("string")) {
            if (!primitive.isString()) return false; String text = primitive.getAsString();
            if (rule.has("pattern") && !java.util.regex.Pattern.compile(rule.get("pattern").getAsString()).matcher(text).find()) return false;
            if (rule.has("format")) try { if (!java.util.UUID.fromString(text).toString().equalsIgnoreCase(text)) return false; } catch (IllegalArgumentException invalid) { return false; }
            return true;
        }
        if (!primitive.isNumber()) return false;
        try {
            var number = primitive.getAsBigDecimal(); if (type.equals("integer") && number.stripTrailingZeros().scale() > 0) return false;
            return (!rule.has("minimum") || number.compareTo(rule.get("minimum").getAsBigDecimal()) >= 0)
                    && (!rule.has("maximum") || number.compareTo(rule.get("maximum").getAsBigDecimal()) <= 0);
        } catch (NumberFormatException invalid) { return false; }
    }
}
