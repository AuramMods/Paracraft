package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonObject;
import java.util.UUID;

/** Terminal acknowledgments need no live inventory, route, or old dimension to remain readable. */
public final class RecordedJob extends GameJob {
    private final JsonObject data;
    public RecordedJob(CompanionPlayer actor, JsonObject checkpoint) {
        super(UUID.fromString(checkpoint.get("id").getAsString()), checkpoint.get("type").getAsString(), actor);
        data = checkpoint.getAsJsonObject("data").deepCopy(); restore(checkpoint);
        if (!state.terminal()) throw new IllegalArgumentException("Recorded result must be terminal");
    }
    @Override protected void advance() { throw new IllegalStateException("Cannot execute a recorded result"); }
    @Override protected JsonObject writeData() { return data.deepCopy(); }
}
