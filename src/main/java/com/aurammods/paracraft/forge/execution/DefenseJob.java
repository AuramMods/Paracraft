package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.core.JobState;
import java.util.UUID;

/** Local self-defense has no task timer; it ends when the threat is defeated, gone, or retreats. */
public final class DefenseJob extends GameJob {
    private final UUID target;
    private final EntityWorkJob combat;
    public DefenseJob(CompanionPlayer actor, UUID target) {
        super(UUID.randomUUID(),"defend",actor); this.target=target;
        combat=new EntityWorkJob(UUID.randomUUID(),actor,"attack_entity",target,2,0,false);
    }
    @Override protected void advance() {
        var entity=actor.serverLevel().getEntity(target); result.addProperty("target",target.toString());
        if(entity==null || !entity.isAlive() || actor.distanceTo(entity)>24) {
            result.addProperty("threatGone",entity==null || !entity.isAlive()); result.addProperty("threatRetreated",entity!=null && actor.distanceTo(entity)>24); succeed(result); return;
        }
        combat.tick(); phase=combat.view().get("phase").getAsString(); result.add("combat",combat.result());
        if(combat.state()==JobState.SUCCEEDED)succeed(result);
        else if(combat.state()==JobState.FAILED)fail(combat.view().get("error").getAsString());
        else if(combat.state()==JobState.CANCELLED)cancel(combat.view().get("error").getAsString());
    }
    @Override protected void onStop(){if(!combat.state().terminal())combat.cancel("Self-defense stopped");}
}
