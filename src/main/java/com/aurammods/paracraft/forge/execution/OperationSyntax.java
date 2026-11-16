package com.aurammods.paracraft.forge.execution;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.UUID;

/** Syntax only. World availability, permissions and inventories are evaluated during execution. */
public final class OperationSyntax {
    private OperationSyntax() { }
    public static void validate(JsonObject spec) {
        String op = string(spec, "op");
        switch (op) {
            case "travel" -> coordinates(spec);
            case "search_containers", "collect_items" -> {
                string(spec, "item");
                if (spec.has("scope")) {
                    if (!spec.get("scope").isJsonObject()) throw new IllegalArgumentException("scope must be an object");
                    var scope = spec.getAsJsonObject("scope");
                    if (!string(scope, "kind").equals("neighbor_chunks") || integer(scope, "radius") != 1)
                        throw new IllegalArgumentException("Inventory scope is neighbor_chunks with radius 1");
                }
                if (spec.has("quantity") && !(spec.get("quantity").isJsonPrimitive()
                        && spec.getAsJsonPrimitive("quantity").isString() && spec.get("quantity").getAsString().equals("all"))) integer(spec, "quantity");
                if (spec.has("containersFrom")) string(spec, "containersFrom");
            }
            case "deposit_items" -> { coordinates(spec); string(spec, "item"); integer(spec, "quantity"); }
            case "deliver_items" -> {
                string(spec, "itemsFrom");
                if (!spec.has("recipient")) throw new IllegalArgumentException("Missing recipient");
                String recipient = spec.get("recipient").isJsonObject() ? string(spec.getAsJsonObject("recipient"), "playerUuid") : string(spec, "recipient");
                UUID.fromString(recipient);
            }
            case "wait_ticks" -> integer(spec, "ticks");
            case "mine_blocks", "farm" -> {
                new com.aurammods.paracraft.core.program.BlockTargets(spec);
                if (spec.has("plant")) string(spec, "plant");
            }
            case "place_blocks" -> {
                if (!spec.has("placements") || !spec.get("placements").isJsonArray()) throw new IllegalArgumentException("placements must be an array");
                for (var element : spec.getAsJsonArray("placements")) {
                    JsonObject placement = element.getAsJsonObject(); string(placement, "item");
                    com.aurammods.paracraft.core.program.BlockTargets.position(placement.get("position"));
                }
            }
            case "equip" -> {
                string(spec, "item");
                if (!java.util.Set.of("mainhand", "offhand", "head", "chest", "legs", "feet").contains(string(spec, "slot")))
                    throw new IllegalArgumentException("Unknown equipment slot");
            }
            case "follow", "attack_entity" -> {
                UUID.fromString(string(spec, "target")); if (spec.has("ticks")) integer(spec, "ticks");
                if (spec.has("distance") && (!spec.get("distance").isJsonPrimitive() || !spec.getAsJsonPrimitive("distance").isNumber()
                        || !Double.isFinite(spec.get("distance").getAsDouble()) || spec.get("distance").getAsDouble() < 0.5))
                    throw new IllegalArgumentException("distance must be finite and at least 0.5");
            }
            case "chat" -> string(spec, "text");
            case "fail" -> { if (spec.has("message")) string(spec, "message"); }
            case "report_result", "report_failure" -> { }
            default -> throw new IllegalArgumentException("Unsupported operation: " + op);
        }
    }
    private static void coordinates(JsonObject spec) {
        if (!spec.has("destination") || !spec.get("destination").isJsonArray() || spec.getAsJsonArray("destination").size() != 3)
            throw new IllegalArgumentException("destination must be [x,y,z]");
        for (JsonElement value : spec.getAsJsonArray("destination"))
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Double.isFinite(value.getAsDouble()))
                throw new IllegalArgumentException("Destination coordinates must be finite numbers");
    }
    private static long integer(JsonObject spec, String key) {
        if (!spec.has(key) || !spec.get(key).isJsonPrimitive() || !spec.getAsJsonPrimitive(key).isNumber())
            throw new IllegalArgumentException(key + " must be a nonnegative integer");
        try {
            long value = spec.get(key).getAsBigDecimal().longValueExact();
            if (value < 0 || (key.equals("quantity") && value > Integer.MAX_VALUE)) throw new ArithmeticException();
            return value;
        } catch (ArithmeticException invalid) { throw new IllegalArgumentException(key + " is outside the supported integer range"); }
    }
    private static String string(JsonObject spec, String key) {
        if (!spec.has(key) || !spec.get(key).isJsonPrimitive() || !spec.getAsJsonPrimitive(key).isString()
                || spec.get(key).getAsString().isBlank()) throw new IllegalArgumentException(key + " must be a nonempty string");
        return spec.get(key).getAsString();
    }
}
