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
            case "inspect_trades", "trade" -> {
                UUID.fromString(string(spec,"target"));
                if(op.equals("trade")){
                    if(integer(spec,"offer")>Integer.MAX_VALUE)throw new IllegalArgumentException("offer index is too large");
                    if(!string(spec,"offerId").matches("[0-9a-f]{64}"))throw new IllegalArgumentException("offerId must come from inspect_trades");
                    if(spec.has("quantity") && (integer(spec,"quantity")<1 || integer(spec,"quantity")>Integer.MAX_VALUE))throw new IllegalArgumentException("Trade quantity must be a positive integer");
                }
            }
            case "interact_block", "interact_entity", "use_item" -> {
                if(op.equals("interact_block"))com.aurammods.paracraft.core.program.BlockTargets.position(spec.get("position"));
                if(op.equals("interact_entity"))UUID.fromString(string(spec,"target"));
                if(spec.has("hand") && !java.util.Set.of("mainhand","offhand").contains(string(spec,"hand")))throw new IllegalArgumentException("hand must be mainhand or offhand");
                if(spec.has("sneak") && (!spec.get("sneak").isJsonPrimitive() || !spec.getAsJsonPrimitive("sneak").isBoolean()))throw new IllegalArgumentException("sneak must be a boolean");
                if(spec.has("holdTicks") && integer(spec,"holdTicks")>Integer.MAX_VALUE)throw new IllegalArgumentException("holdTicks is too large");
                if(spec.has("face") && (!op.equals("interact_block") || net.minecraft.core.Direction.byName(string(spec,"face"))==null))throw new IllegalArgumentException("face must be a block face name");
                if(spec.has("hit")){if(op.equals("use_item"))throw new IllegalArgumentException("use_item uses lookAt rather than a target hit");vector(spec.get("hit"));if(op.equals("interact_block"))for(var part:spec.getAsJsonArray("hit"))if(part.getAsDouble()<0 || part.getAsDouble()>1)throw new IllegalArgumentException("Block hit coordinates must be within 0..1");}
                if(spec.has("lookAt")){if(!op.equals("use_item"))throw new IllegalArgumentException("lookAt is only used by use_item");vector(spec.get("lookAt"));}
                if(!op.equals("interact_block") && spec.has("position") || !op.equals("interact_entity") && spec.has("target"))throw new IllegalArgumentException("Interaction target arguments do not match its operation");
            }
            case "search_recipes" -> {
                string(spec, "item");
                if (spec.has("limit") && (integer(spec, "limit") < 1 || integer(spec, "limit") > 128)) throw new IllegalArgumentException("Recipe result limit must be between 1 and 128");
            }
            case "search_creative_items" -> {
                if (spec.has("query") && (!spec.get("query").isJsonPrimitive() || !spec.getAsJsonPrimitive("query").isString()))
                    throw new IllegalArgumentException("Creative search query must be a string");
                if (spec.has("offset") && integer(spec, "offset") > Integer.MAX_VALUE) throw new IllegalArgumentException("Offset is too large");
                if (spec.has("limit") && (integer(spec, "limit") < 1 || integer(spec, "limit") > 64))
                    throw new IllegalArgumentException("Creative result limit must be between 1 and 64");
            }
            case "take_creative_items" -> {
                string(spec, "item");
                if (spec.has("variant") && integer(spec, "variant") > Integer.MAX_VALUE) throw new IllegalArgumentException("Variant is too large");
                if (spec.has("quantity") && (integer(spec, "quantity") < 1 || integer(spec, "quantity") > Integer.MAX_VALUE))
                    throw new IllegalArgumentException("Creative item quantity must be a positive integer");
            }
            case "craft_item" -> {
                string(spec, "item"); if (integer(spec, "quantity") < 1) throw new IllegalArgumentException("Crafting quantity must be positive");
                if (spec.has("table")) com.aurammods.paracraft.core.program.BlockTargets.position(spec.get("table"));
                if (spec.has("recipe") && net.minecraft.resources.ResourceLocation.tryParse(string(spec, "recipe")) == null) throw new IllegalArgumentException("recipe must be a registry ID");
                if (spec.has("grid")) {
                    int cells = spec.has("table") ? 9 : 4;
                    if (!spec.get("grid").isJsonArray() || spec.getAsJsonArray("grid").size() != cells) throw new IllegalArgumentException("Crafting grid must have " + cells + " slot indices");
                    for (var cell : spec.getAsJsonArray("grid")) try {
                        if (!cell.isJsonPrimitive() || !cell.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
                        int slot = cell.getAsBigDecimal().intValueExact(); if (slot < -1 || slot > 35) throw new ArithmeticException();
                    } catch (ArithmeticException invalid) { throw new IllegalArgumentException("Crafting grid values must be -1 or main-inventory slot indices 0..35"); }
                }
            }
            case "open_inventory", "take_items" -> {com.aurammods.paracraft.core.program.BlockTargets.position(spec.get("position"));if(op.equals("take_items")){string(spec,"item");if(integer(spec,"quantity")>Integer.MAX_VALUE)throw new IllegalArgumentException("quantity is too large");}if(spec.has("limit") && (integer(spec,"limit")<1 || integer(spec,"limit")>4096))throw new IllegalArgumentException("Inventory inspection limit must be between 1 and 4096");}
            case "search_blocks" -> {
                string(spec,"block");if(spec.has("limit") && (integer(spec,"limit")<1 || integer(spec,"limit")>4096))throw new IllegalArgumentException("Block result limit must be between 1 and 4096");
                if(spec.has("radius") && (!spec.get("radius").isJsonPrimitive() || !spec.getAsJsonPrimitive("radius").isNumber()
                        || !Double.isFinite(spec.get("radius").getAsDouble()) || spec.get("radius").getAsDouble()<0))throw new IllegalArgumentException("Block search radius must be a finite nonnegative number");
            }
            case "use_portal" -> {com.aurammods.paracraft.core.program.BlockTargets.position(spec.get("position"));if(spec.has("timeoutTicks"))integer(spec,"timeoutTicks");if(spec.has("dimension"))string(spec,"dimension");}
            case "discover_commands" -> {
                if(spec.has("prefix") && (!spec.get("prefix").isJsonPrimitive() || !spec.getAsJsonPrimitive("prefix").isString()))throw new IllegalArgumentException("Command prefix must be a string");
            }
            case "execute_command" -> {
                string(spec,"command");
                if(spec.has("await") && !java.util.Set.of("command","teleport").contains(string(spec,"await")))throw new IllegalArgumentException("await must be command or teleport");
                if(spec.has("timeoutTicks"))integer(spec,"timeoutTicks");
                if(spec.has("dimension"))string(spec,"dimension");
                if(spec.has("destination"))coordinates(spec);
                if(spec.has("playerUuid"))UUID.fromString(string(spec,"playerUuid"));
                if(spec.has("tolerance") && (!spec.get("tolerance").isJsonPrimitive() || !spec.getAsJsonPrimitive("tolerance").isNumber()
                        || !Double.isFinite(spec.get("tolerance").getAsDouble()) || spec.get("tolerance").getAsDouble()<0))throw new IllegalArgumentException("tolerance must be a finite nonnegative number");
            }
            case "set_spawn" -> com.aurammods.paracraft.core.program.BlockTargets.position(spec.get("bed"));
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
            case "drop_items" -> {
                string(spec,"item");if(!(spec.has("quantity") && spec.get("quantity").isJsonPrimitive() && spec.getAsJsonPrimitive("quantity").isString() && spec.get("quantity").getAsString().equals("all")))integer(spec,"quantity");
                if(spec.has("slot"))equipmentSlot(spec);
                if(spec.has("recipient")){var recipient=spec.get("recipient");UUID.fromString(recipient.isJsonObject()?string(recipient.getAsJsonObject(),"playerUuid"):string(spec,"recipient"));}
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
            case "build_layers" -> new com.aurammods.paracraft.core.program.LayerBlueprint(spec);
            case "equip" -> {string(spec,"item");equipmentSlot(spec);}
            case "unequip" -> equipmentSlot(spec);
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
    private static void equipmentSlot(JsonObject spec){if(!java.util.Set.of("mainhand","offhand","head","chest","legs","feet").contains(string(spec,"slot")))throw new IllegalArgumentException("Unknown equipment slot");}
    private static void vector(JsonElement value){
        if(value==null || !value.isJsonArray() || value.getAsJsonArray().size()!=3)throw new IllegalArgumentException("Interaction point must be [x,y,z]");
        for(var part:value.getAsJsonArray())if(!part.isJsonPrimitive() || !part.getAsJsonPrimitive().isNumber() || !Double.isFinite(part.getAsDouble()))throw new IllegalArgumentException("Interaction point coordinates must be finite numbers");
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
