package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonObject;
import java.util.UUID;

/** Retains a foreign-dimension checkpoint without binding world-specific runtime objects there. */
final class ParkedJob extends GameJob {
    private final JsonObject saved;
    ParkedJob(CompanionPlayer actor,JsonObject saved){
        super(UUID.fromString(saved.get("id").getAsString()),saved.get("type").getAsString(),actor);this.saved=saved.deepCopy();state=JobState.PAUSED;
        result=saved.getAsJsonObject("result").deepCopy();phase="parked_in_dimension";error=saved.get("error").getAsString();
    }
    boolean canBind(){return actor.serverLevel().dimension().location().toString().equals(saved.get("dimension").getAsString());}
    GameJob bind(){return JobCodec.restore(actor,checkpoint());}
    @Override protected void advance(){throw new IllegalStateException("Return to "+saved.get("dimension").getAsString()+" before resuming this operation");}
    @Override public void resume(){throw new IllegalStateException("Parked world work must be rebound before resumption");}
    @Override public JsonObject checkpoint(){JsonObject value=saved.deepCopy();value.addProperty("state",state.name());if(state.terminal()){value.addProperty("error",error);value.addProperty("phase",phase);}return value;}
    @Override public JsonObject view(){JsonObject view=super.view();view.addProperty("dimension",saved.get("dimension").getAsString());if(!state.terminal())view.addProperty("blockedReason",canBind()?"Ready to rebind; resume explicitly":"Return to the saved dimension before resuming");return view;}
}
