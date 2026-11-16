package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Local evasive movement; does not attack, teleport, or enter portals. */
public final class FleeJob extends GameJob {
    private final UUID threat;
    private Vec3 destination;
    private int elapsed, routeAttempt;
    public FleeJob(CompanionPlayer actor, UUID threat) { super(UUID.randomUUID(), "flee", actor); this.threat = threat; }
    @Override protected void advance() {
        var entity = actor.serverLevel().getEntity(threat); result.addProperty("threat", threat.toString()); result.addProperty("elapsedTicks", ++elapsed);
        if (!(entity instanceof LivingEntity living) || !living.isAlive()) { result.addProperty("threatGone", true); succeed(result); return; }
        result.addProperty("distance", actor.distanceTo(living));
        if (actor.distanceTo(living) >= 8) { result.addProperty("escaped", true); succeed(result); return; }
        phase = "fleeing";
        if (destination == null) {
            Vec3 away = actor.position().subtract(living.position()).multiply(1,0,1);
            if (away.lengthSqr() < 0.01) away = new Vec3(1,0,0);
            double angle = switch (routeAttempt) { case 0 -> 0; case 1 -> Math.PI/4; case 2 -> -Math.PI/4; case 3 -> Math.PI/2; default -> -Math.PI/2; };
            Vec3 direction = away.normalize(); direction = new Vec3(direction.x*Math.cos(angle)-direction.z*Math.sin(angle),0,direction.x*Math.sin(angle)+direction.z*Math.cos(angle));
            destination = actor.position().add(direction.scale(10)); result.add("destination", JobJson.vector(destination));
        }
        try { if (navigate(destination, 0.8)) { destination = null; routeAttempt = 0; } }
        catch (IllegalStateException blocked) { stopMoving(); destination = null; if (++routeAttempt >= 5) throw new IllegalStateException("No evasive route succeeded: " + blocked.getMessage()); }
    }
}
