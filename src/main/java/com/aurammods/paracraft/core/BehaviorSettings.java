package com.aurammods.paracraft.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.Set;

/** Portable behavior schema; runtime targets and world conditions belong to the server adapter. */
public final class BehaviorSettings {
    public enum Reaction { PASSIVE, FLEE, NEUTRAL, AGGRESSIVE }
    private BehaviorSettings() { }
    public static JsonObject defaults(String dimension) {
        JsonObject value = new JsonObject(); value.addProperty("players", "flee"); value.addProperty("mobs", "neutral");
        JsonObject idle = new JsonObject(); idle.addProperty("enabled", false); idle.addProperty("dimension", dimension);
        idle.add("waypoints", new JsonArray()); idle.addProperty("waitTicks", 40); value.add("idle", idle); return value;
    }
    public static Reaction reaction(JsonObject value, boolean player) { return Reaction.valueOf(value.get(player ? "players" : "mobs").getAsString().toUpperCase(Locale.ROOT)); }
    public static JsonObject merge(JsonObject current, JsonObject patch) {
        JsonObject value = current.deepCopy();
        for (var field : patch.entrySet()) {
            if (!Set.of("players", "mobs", "idle").contains(field.getKey())) throw new IllegalArgumentException("Unknown behavior field: " + field.getKey());
            if (field.getKey().equals("idle")) {
                JsonObject idle = value.getAsJsonObject("idle");
                for (var item : field.getValue().getAsJsonObject().entrySet()) {
                    if (!Set.of("enabled", "dimension", "waypoints", "waitTicks").contains(item.getKey())) throw new IllegalArgumentException("Unknown idle field: " + item.getKey());
                    idle.add(item.getKey(), item.getValue().deepCopy());
                }
            } else value.add(field.getKey(), field.getValue().deepCopy());
        }
        for (String key : new String[]{"players", "mobs"}) {
            var field = value.get(key);
            if (!field.isJsonPrimitive() || !field.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(key + " must name a reaction");
            try { Reaction.valueOf(field.getAsString().toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Reaction must be passive, flee, neutral, or aggressive"); }
            value.addProperty(key, field.getAsString().toLowerCase(Locale.ROOT));
        }
        var idle = value.getAsJsonObject("idle");
        if (!idle.get("enabled").isJsonPrimitive() || !idle.getAsJsonPrimitive("enabled").isBoolean()) throw new IllegalArgumentException("idle.enabled must be boolean");
        if (!idle.get("dimension").isJsonPrimitive() || !idle.getAsJsonPrimitive("dimension").isString()
                || !idle.get("dimension").getAsString().matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IllegalArgumentException("idle.dimension must be a dimension ID");
        if (!idle.get("waitTicks").isJsonPrimitive() || !idle.getAsJsonPrimitive("waitTicks").isNumber()) throw new IllegalArgumentException("idle.waitTicks must be a nonnegative integer");
        try { if (idle.get("waitTicks").getAsBigDecimal().longValueExact() < 0) throw new ArithmeticException(); }
        catch (ArithmeticException invalid) { throw new IllegalArgumentException("idle.waitTicks must be a nonnegative integer"); }
        if (!idle.get("waypoints").isJsonArray()) throw new IllegalArgumentException("idle.waypoints must be an array");
        for (var point : idle.getAsJsonArray("waypoints")) {
            if (!point.isJsonArray() || point.getAsJsonArray().size() != 3) throw new IllegalArgumentException("Waypoint must be [x,y,z]");
            for (var coordinate : point.getAsJsonArray()) if (!coordinate.isJsonPrimitive() || !coordinate.getAsJsonPrimitive().isNumber() || !Double.isFinite(coordinate.getAsDouble())) throw new IllegalArgumentException("Waypoint coordinates must be finite numbers");
        }
        if (idle.get("enabled").getAsBoolean() && idle.getAsJsonArray("waypoints").isEmpty()) throw new IllegalArgumentException("Enabled idle patrol requires at least one waypoint");
        return value;
    }
}
