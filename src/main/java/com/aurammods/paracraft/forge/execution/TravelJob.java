package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public final class TravelJob extends GameJob {
    private final Vec3 destination;
    public TravelJob(UUID id, CompanionPlayer actor, Vec3 destination) { super(id, "travel", actor); this.destination = destination; }
    @Override protected void advance() {
        phase = "traveling";
        if (navigate(destination, 0.7)) succeed(new JsonObject());
    }
    @Override protected JsonObject writeData() { JsonObject value = new JsonObject(); value.add("destination", JobJson.vector(destination)); return value; }
}
