package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.item.ItemStack;
import java.util.UUID;

public final class PlaceBlocksJob extends GameJob {
    private final JsonArray placements;
    private final BlockApproach approach = new BlockApproach();
    private int cursor, placed, consumed, existing;
    public PlaceBlocksJob(UUID id, CompanionPlayer actor, JsonArray placements) {
        super(id, "place_blocks", actor); this.placements = placements.deepCopy();
    }
    @Override protected void advance() {
        if (cursor >= placements.size()) { NativePlacement.finish(this); return; }
        JsonObject placement = placements.get(cursor).getAsJsonObject(); var position = JobJson.position(placement.get("position"));
        result.add("target", JobJson.position(position));
        result.add("target", JobJson.position(position));
        if (actor.serverLevel().isOutsideBuildHeight(position) || !actor.serverLevel().getWorldBorder().isWithinBounds(position))
            throw new IllegalStateException("Placement target is outside world bounds");
        phase = "placing " + (cursor + 1) + "/" + placements.size();
        var selector = new ItemSelector(placement.get("item").getAsString());
        var level = actor.serverLevel();
        if (level.hasChunkAt(position) && selector.matches(new ItemStack(level.getBlockState(position).getBlock().asItem()))) {
            stopMoving();
            approach.reset();
            existing++;
        } else {
            try {
                if (!NativePlacement.approach(this, approach, position)) return;
                consumed += CarriedItems.place(actor, position, selector);
                placed++;
            } catch (BlockApproach.Unreachable noRoute) {
                NativePlacement.skipUnreachable(this, position);
            }
        }
        cursor++;
        result.addProperty("placed", placed);
        result.addProperty("alreadyPresent", existing);
        result.addProperty("consumed", consumed);
    }
    @Override protected JsonObject writeData() {
        JsonObject data = new JsonObject(); data.add("placements", placements.deepCopy()); data.addProperty("cursor", cursor); data.addProperty("consumed", consumed); data.addProperty("existing", existing); data.addProperty("placed", placed); return data;
    }
    @Override protected void readData(JsonObject data) {
        cursor = data.get("cursor").getAsInt();
        consumed = data.get("consumed").getAsInt();
        existing = data.has("existing") ? data.get("existing").getAsInt() : 0;
        placed = data.has("placed") ? data.get("placed").getAsInt() : cursor - existing;
    }
}
