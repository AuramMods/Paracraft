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
    private net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;

    protected GameJob(UUID id, String type, CompanionPlayer actor) {
        this.id = id; this.type = type; this.actor = actor; this.dimension = actor.serverLevel().dimension();
    }
    public CompanionPlayer actor() { return actor; }
    public net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> requiredDimension(){return dimension;}
    protected final void adoptDimension(){dimension=actor.serverLevel().dimension();}
    public JobState state() { return state; }
    public String phase() { return phase; }
    public JsonObject result() { return result.deepCopy(); }
    public final void tick() {
        if (state != JobState.RUNNING) return;
        if (invalidateControl()) return;
        try { advance(); invalidateControl(); }
        catch (IllegalStateException | IllegalArgumentException failure) { fail(failure.getMessage()); }
        catch (RuntimeException failure) {
            Paracraft.LOG.error("Unexpected error in {} job {}", type, id, failure);
            fail("Unexpected " + failure.getClass().getSimpleName() + ": " + failure.getMessage());
        }
    }
    protected abstract void advance();
    private boolean invalidateControl() {
        String reason = !actor.isAlive() || (actor.isRemoved() && !(actor.wonGame && allowsDimensionChange())) || actor.retired() || actor.hasDisconnected() ? "Avatar died, disconnected, or was removed"
                : !allowsDimensionChange() && actor.serverLevel().dimension() != dimension ? "Avatar changed dimension" : null;
        if (reason == null) return false;
        // A native hook can change life/dimension during this tick's indivisible action.
        // Preserve its actual result, but stop the program before any catch/next action.
        if (state.terminal() && state != JobState.CANCELLED) state = JobState.RUNNING;
        cancel(reason); return true;
    }
    protected boolean allowsDimensionChange() { return false; }
    protected boolean allowsRestoredDimension(JsonObject checkpoint){return false;}
    protected PlayerNavigator createNavigator(){return new PlayerNavigator(actor);}
    protected boolean navigate(Vec3 target, double reach) {
        if (navigator==null && actor.position().distanceTo(target) <= reach && (reach>=1.5 || actor.onGround() || actor.isInWater())) { stopMoving(); return true; }
        if (navigator == null) navigator = createNavigator();
        boolean arrived;try{arrived=navigator.tick(target,reach);}finally{result.add("navigation",navigator.view());}
        if (!arrived) return false;
        navigator.close(); navigator = null; return true;
    }
    protected void stopMoving() {
        if (navigator != null) { navigator.close(); navigator = null; }
    }
    protected void succeed(JsonObject result) { this.result = result; if (invalidateControl()) return; state = JobState.SUCCEEDED; phase = "complete"; stopMoving(); onStop(); actor.closeContainer(); }
    protected void fail(String error) { if (invalidateControl()) return; this.error = error; state = JobState.FAILED; phase = "failed"; stopMoving(); onStop(); actor.closeContainer(); }
    public void cancel(String reason) { if (state.terminal()) return; error = reason; state = JobState.CANCELLED; phase = "cancelled"; stopMoving(); onStop(); actor.closeContainer(); }
    public void pause() { if (state == JobState.RUNNING) { state = JobState.PAUSED; stopMoving(); onStop(); actor.closeContainer(); onPause(); } }
    protected void onStop() { }
    protected void onPause() { }
    public void resume() { if (state == JobState.PAUSED || state == JobState.QUEUED) state = JobState.RUNNING; }
    public void enqueue() { state = JobState.QUEUED; }
    protected JsonObject writeData() { return new JsonObject(); }
    protected void readData(JsonObject data) { }
    public JsonObject checkpoint() {
        JsonObject value = view(); value.addProperty("schema", 1); value.addProperty("dimension", dimension.location().toString());
        value.add("data", writeData()); return value;
    }
    public final void restore(JsonObject checkpoint) {
        if (checkpoint.get("schema").getAsInt() != 1 || !id.toString().equals(checkpoint.get("id").getAsString())
                || !type.equals(checkpoint.get("type").getAsString())) throw new IllegalArgumentException("Incompatible job checkpoint");
        JobState restoredState = JobState.valueOf(checkpoint.get("state").getAsString());
        if (!restoredState.terminal() && !dimension.location().toString().equals(checkpoint.get("dimension").getAsString()) && !allowsRestoredDimension(checkpoint))
            throw new IllegalStateException("Saved operation belongs to another dimension");
        state = restoredState;
        if (state == JobState.RUNNING) state = JobState.PAUSED;
        phase = checkpoint.get("phase").getAsString(); error = checkpoint.get("error").getAsString();
        result = checkpoint.getAsJsonObject("result").deepCopy(); readData(checkpoint.getAsJsonObject("data"));
    }
    public JsonObject view() {
        JsonObject value = new JsonObject();
        value.addProperty("id", id.toString()); value.addProperty("type", type); value.addProperty("state", state.name());
        value.addProperty("phase", phase); value.addProperty("error", error); value.add("result", result());
        value.addProperty("dimension",dimension.location().toString());
        if(!state.terminal() && actor.serverLevel().dimension()!=dimension && !allowsDimensionChange())value.addProperty("blockedReason","Return to the saved dimension before resuming");
        return value;
    }
}
