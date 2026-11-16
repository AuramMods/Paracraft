package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.core.program.ProgramMachine;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonObject;
import java.util.UUID;

public final class ProgramJob extends GameJob {
    private ProgramMachine machine;
    public ProgramJob(UUID id, CompanionPlayer actor, JsonObject program) {
        super(id, "program", actor); machine = new ProgramMachine(id, program, new ForgeOperations(actor));
    }
    @Override protected void advance() {
        machine.tick(16); phase = machine.currentOperation(); result = machine.view();
        switch (machine.state()) {
            case SUCCEEDED -> succeed(result);
            case FAILED -> fail(machine.error().get("message").getAsString());
            case CANCELLED -> cancel(machine.error().get("message").getAsString());
            case PAUSED -> pause();
            default -> { }
        }
    }
    @Override public void cancel(String reason) { machine.cancel(reason); result = machine.view(); super.cancel(reason); }
    @Override protected void onPause() { machine.pause(); result = machine.view(); }
    @Override public void resume() { machine.resume(); super.resume(); }
    @Override protected JsonObject writeData() { JsonObject value = new JsonObject(); value.add("machine", machine.checkpoint()); return value; }
    @Override protected void readData(JsonObject data) { machine = ProgramMachine.restore(data.getAsJsonObject("machine"), new ForgeOperations(actor)); result = machine.view(); }
}
