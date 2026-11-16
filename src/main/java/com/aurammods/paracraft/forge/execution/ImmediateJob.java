package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.core.program.ProgramMachine;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonObject;
import java.util.UUID;

public final class ImmediateJob extends GameJob {
    private final JsonObject specification;
    private final ProgramMachine.Context context;
    public ImmediateJob(UUID id, CompanionPlayer actor, JsonObject specification, ProgramMachine.Context context) {
        super(id, specification.get("op").getAsString(), actor); this.specification = specification.deepCopy(); this.context = context;
    }
    @Override protected void advance() {
        switch (type) {
            case "chat" -> {
                actor.server.getPlayerList().broadcastSystemMessage(net.minecraft.network.chat.Component.literal(
                        "[" + actor.getGameProfile().getName() + "] " + specification.get("text").getAsString()), false);
                succeed(new JsonObject());
            }
            case "report_result" -> succeed(context.results().deepCopy());
            case "report_failure" -> succeed(context.caught() == null ? new JsonObject() : context.caught().deepCopy());
            case "fail" -> fail(specification.has("message") ? specification.get("message").getAsString() : "Program requested failure");
            default -> throw new IllegalArgumentException("Unknown immediate operation " + type);
        }
    }
    @Override protected JsonObject writeData() {
        JsonObject value = new JsonObject(); value.add("specification", specification.deepCopy()); value.add("results", context.results().deepCopy());
        if (context.caught() != null) value.add("caught", context.caught().deepCopy()); return value;
    }
}
