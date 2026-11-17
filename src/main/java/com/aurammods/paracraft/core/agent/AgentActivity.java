package com.aurammods.paracraft.core.agent;

import com.google.gson.JsonObject;

/** Public aggregate activity; personality, task text and memory remain on the controlling client. */
public record AgentActivity(Phase phase, int tasks) {
    public enum Phase { IDLE, THINKING, BUSY, WAITING, PAUSED, DISCONNECTED }
    public AgentActivity { if (phase == null || tasks < 0) throw new IllegalArgumentException("Invalid agent activity"); }
    public static AgentActivity from(AgentState memory, String status) {
        int tasks = 0;
        for (var task : memory.tasks()) if (!java.util.Set.of("COMPLETED", "FAILED").contains(task.getAsJsonObject().get("status").getAsString())) tasks++;
        Phase phase = status.startsWith("Paused") ? Phase.PAUSED : status.startsWith("Disconnected") ? Phase.DISCONNECTED
                : status.startsWith("Thinking") ? Phase.THINKING : status.startsWith("Waiting") ? Phase.WAITING
                : status.equals("Idle") ? Phase.IDLE : Phase.BUSY;
        return new AgentActivity(phase, tasks);
    }
    public JsonObject json() { var value = new JsonObject(); value.addProperty("phase", phase.name()); value.addProperty("tasks", tasks); return value; }
    public static AgentActivity parse(JsonObject value) { return new AgentActivity(Phase.valueOf(value.get("phase").getAsString()), value.get("tasks").getAsBigDecimal().intValueExact()); }
    public String label(String operation, boolean paused, boolean connected) {
        if (paused || phase == Phase.PAUSED) return "Paused";
        if (!connected || phase == Phase.DISCONNECTED) return "Disconnected";
        String label = operation != null ? switch (operation) {
            case "travel", "follow" -> "Traveling"; case "craft_item" -> "Crafting";
            case "collect_items", "take_items" -> "Collecting items"; case "deliver_items", "deposit_items" -> "Moving items";
            case "mine_blocks" -> "Mining"; case "farm" -> "Farming"; case "attack_entity" -> "Fighting";
            default -> "Busy";
        } : switch (phase) { case THINKING -> "Thinking"; case WAITING -> "Waiting"; case IDLE -> tasks == 0 ? "Idle" : "Busy"; default -> "Busy"; };
        return tasks == 0 ? label : label + " · " + tasks + (tasks == 1 ? " task" : " tasks");
    }
}
