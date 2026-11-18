package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public final class TravelJob extends GameJob {
    private final Vec3 destination;
    private final boolean sprint;
    public TravelJob(UUID id, CompanionPlayer actor, Vec3 destination) { this(id,actor,destination,true); }
    public TravelJob(UUID id, CompanionPlayer actor, Vec3 destination, boolean sprint) { super(id, "travel", actor); this.destination = destination; this.sprint=sprint; }
    static Vec3 programDestination(Vec3 position) {
        return position.x == Math.floor(position.x) && position.y == Math.floor(position.y) && position.z == Math.floor(position.z)
                ? position.add(0.5, 0, 0.5) : position;
    }
    @Override protected PlayerNavigator createNavigator(){return new PlayerNavigator(actor,sprint);}
    @Override protected void advance() {
        phase = "traveling";
        result.add("target", JobJson.vector(destination));
        if (navigate(destination, 0.7)) succeed(result);
    }
    @Override protected JsonObject writeData() { JsonObject value = new JsonObject(); value.add("destination", JobJson.vector(destination)); return value; }
}
