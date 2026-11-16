package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonObject;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/** Incremental server-thread operation. Failure preserves actual progress for the next decision. */
public abstract class GameJob {
    public final UUID id;
    public final String type;
    protected final CompanionPlayer actor;
    protected JobState state = JobState.RUNNING;
    protected String phase = "starting", error = "";
    protected JsonObject result = new JsonObject();
    private PlayerNavigator navigator;

    protected GameJob(UUID id, String type, CompanionPlayer actor) { this.id = id; this.type = type; this.actor = actor; }
    public CompanionPlayer actor() { return actor; }
    public JobState state() { return state; }
    public JsonObject result() { return result.deepCopy(); }
    public final void tick() {
        if (state != JobState.RUNNING) return;
        if (!actor.isAlive() || actor.isRemoved()) { cancel("Avatar died or was removed"); return; }
        try { advance(); }
        catch (IllegalStateException | IllegalArgumentException failure) { fail(failure.getMessage()); }
        catch (RuntimeException failure) {
            Paracraft.LOG.error("Unexpected error in {} job {}", type, id, failure);
            fail("Unexpected " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }
    protected abstract void advance();
    protected boolean navigate(Vec3 target, double reach) {
        if (actor.position().distanceTo(target) <= reach) { stopMoving(); return true; }
        if (navigator == null) navigator = new PlayerNavigator(actor);
        if (!navigator.tick(target, reach)) return false;
        navigator.close(); navigator = null; return true;
    }
    protected void stopMoving() {
        if (navigator != null) { navigator.close(); navigator = null; }
    }
    protected void succeed(JsonObject result) { this.result = result; state = JobState.SUCCEEDED; phase = "complete"; stopMoving(); actor.closeContainer(); }
    protected void fail(String error) { this.error = error; state = JobState.FAILED; phase = "failed"; stopMoving(); actor.closeContainer(); }
    public void cancel(String reason) { if (state.terminal()) return; error = reason; state = JobState.CANCELLED; phase = "cancelled"; stopMoving(); actor.closeContainer(); }
    public void pause() { if (state == JobState.RUNNING) { state = JobState.PAUSED; stopMoving(); actor.closeContainer(); onPause(); } }
    protected void onPause() { }
    public void resume() { if (state == JobState.PAUSED) state = JobState.RUNNING; }
    public JsonObject view() {
        JsonObject value = new JsonObject();
        value.addProperty("id", id.toString()); value.addProperty("type", type); value.addProperty("state", state.name());
        value.addProperty("phase", phase); value.addProperty("error", error); value.add("result", result());
        return value;
    }
}
