package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public final class PlaceBlocksJob extends GameJob {
    private final JsonArray placements; private int cursor, consumed;
    public PlaceBlocksJob(UUID id, CompanionPlayer actor, JsonArray placements) {
        super(id, "place_blocks", actor); this.placements = placements.deepCopy();
    }
    @Override protected void advance() {
        if (cursor >= placements.size()) { succeed(result); return; }
        JsonObject placement = placements.get(cursor).getAsJsonObject(); var position = JobJson.position(placement.get("position"));
        result.add("target", JobJson.position(position));
        if (actor.serverLevel().isOutsideBuildHeight(position) || !actor.serverLevel().getWorldBorder().isWithinBounds(position))
            throw new IllegalStateException("Placement target is outside world bounds");
        phase = "placing " + (cursor + 1) + "/" + placements.size();
        if (!navigate(Vec3.atCenterOf(position), 2.6)) return;
        consumed += CarriedItems.place(actor, position, new ItemSelector(placement.get("item").getAsString())); cursor++;
        result.addProperty("placed", cursor); result.addProperty("consumed", consumed);
    }
    @Override protected JsonObject writeData() {
        JsonObject data = new JsonObject(); data.add("placements", placements.deepCopy()); data.addProperty("cursor", cursor); data.addProperty("consumed", consumed); return data;
    }
    @Override protected void readData(JsonObject data) { cursor = data.get("cursor").getAsInt(); consumed = data.get("consumed").getAsInt(); }
}
