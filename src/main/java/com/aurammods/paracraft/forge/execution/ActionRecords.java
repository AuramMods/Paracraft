package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.core.JobState;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;

/** Render observed effects without copying request JSON, inventory NBT, or game chat. */
final class ActionRecords {
    private static final Set<String> OMIT = Set.of("program", "chat", "wait_ticks", "report_result", "report_failure");
    private ActionRecords() { }

    static String block(String verb, BlockState state, BlockPos pos) {
        return verb + " " + BuiltInRegistries.BLOCK.getKey(state.getBlock()) + " " + coordinates(pos);
    }

    static String coordinates(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }

    static void finished(GameJob job) {
        if (OMIT.contains(job.type)) return;
        var actor = job.actor;
        String here = coordinates(actor.blockPosition());
        JsonObject result = job.result;
        String line;
        if (job.state() != JobState.SUCCEEDED) {
            String status = job.state() == JobState.CANCELLED ? "cancelled" : "failed";
            line = status + " " + job.type + position(result, "target", here);
        } else {
            line = switch (job.type) {
                // Each confirmed block effect has already been recorded with its own coordinates.
                case "place_blocks", "build_layers", "mine_blocks", "farm" -> "";
                case "travel" -> "traveled " + here;
                case "follow" -> "followed" + field(result, "target") + " " + here;
                case "attack_entity" -> "defeated" + field(result, "target") + " " + here;
                case "craft_item" -> "crafted" + field(result, "crafted") + field(result, "item") + position(result, "table", here);
                case "take_creative_items" -> "took creative stock" + field(result, "acquired") + field(result, "item");
                case "open_inventory" -> "opened inventory" + position(result, "position", here);
                case "take_items" -> "took" + field(result, "acquired") + field(result, "item") + position(result, "position", here);
                case "collect_items" -> "collected" + field(result, "acquired") + field(result, "item") + " " + here;
                case "deposit_items" -> "deposited" + field(result, "deposited") + field(result, "item") + position(result, "destination", here);
                case "deliver_items" -> "delivered" + field(result, "delivered") + " items near " + here;
                case "drop_items" -> "dropped" + field(result, "dropped") + field(result, "item") + " near " + here;
                case "equip" -> (result.has("alreadyEquipped") && result.get("alreadyEquipped").getAsBoolean() ? "confirmed equipped" : "equipped")
                        + field(result, "item") + field(result, "slot");
                case "unequip" -> "unequipped" + field(result, "moved") + field(result, "slot");
                case "use_portal" -> "used portal arrived " + here;
                case "set_spawn" -> "checked spawn bed" + position(result, "bed", here) + " set" + field(result, "spawnSet");
                case "trade" -> "traded" + field(result, "trades") + " times " + here;
                case "interact_block" -> "interacted" + position(result, "position", here) + field(result, "outcome");
                case "interact_entity" -> "interacted" + field(result, "target") + field(result, "outcome") + " " + here;
                case "use_item" -> "used" + field(result, "item") + field(result, "outcome") + " " + here;
                case "execute_command" -> "ran command" + field(result, "completion") + " " + here;
                default -> "finished " + job.type + " " + here;
            };
        }
        if (!line.isEmpty()) actor.recordAction(line, actor.serverLevel());
    }

    private static String field(JsonObject result, String key) {
        var value = result.get(key);
        return value != null && value.isJsonPrimitive() ? " " + value.getAsString() : "";
    }

    private static String position(JsonObject result, String key, String fallback) {
        var value = result.get(key);
        return " " + (value != null && value.isJsonArray() ? coordinates(JobJson.position(value)) : fallback);
    }
}
