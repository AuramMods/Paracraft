package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonObject;
import java.util.UUID;

public final class WaitJob extends GameJob {
    private long remaining;
    public WaitJob(UUID id, CompanionPlayer actor, long ticks) {
        super(id, "wait_ticks", actor); if (ticks < 0) throw new IllegalArgumentException("Wait ticks must be nonnegative"); remaining = ticks;
    }
    @Override protected void advance() {
        phase = "waiting"; result.addProperty("remainingTicks", remaining);
        if (remaining <= 0) succeed(result); else remaining--;
    }
    @Override protected JsonObject writeData() { JsonObject value = new JsonObject(); value.addProperty("remaining", remaining); return value; }
}
