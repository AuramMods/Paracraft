package com.aurammods.paracraft.core.agent;

import com.google.gson.JsonObject;

/** Public aggregate activity; personality, task text and memory remain on the controlling client. */
public record AgentActivity(Phase phase, int tasks, boolean planning) {
    public enum Phase { IDLE, THINKING, BUSY, WAITING, PAUSED, DISCONNECTED }
    public AgentActivity(Phase phase, int tasks) { this(phase, tasks, false); }
    public AgentActivity { if (phase == null || tasks < 0 || planning && phase != Phase.THINKING) throw new IllegalArgumentException("Invalid agent activity"); }
    public boolean thinking() { return phase == Phase.THINKING; }
    public static AgentActivity from(AgentState memory, String status) {
        int tasks = 0;
        for (var task : memory.tasks()) if (!java.util.Set.of("COMPLETED", "FAILED").contains(task.getAsJsonObject().get("status").getAsString())) tasks++;
        Phase phase = status.startsWith("Paused") ? Phase.PAUSED : status.startsWith("Disconnected") ? Phase.DISCONNECTED
                : status.startsWith("Thinking") || status.equals("Planning") ? Phase.THINKING : status.startsWith("Waiting") ? Phase.WAITING
                : status.equals("Idle") ? Phase.IDLE : Phase.BUSY;
        return new AgentActivity(phase, tasks, phase == Phase.THINKING && (status.equals("Thinking: PLANNING") || status.equals("Planning")));
    }
    public JsonObject json() { var value = new JsonObject(); value.addProperty("phase", phase.name()); value.addProperty("tasks", tasks); value.addProperty("planning", planning); return value; }
    public static AgentActivity parse(JsonObject value) { return new AgentActivity(Phase.valueOf(value.get("phase").getAsString()), value.get("tasks").getAsBigDecimal().intValueExact(), value.has("planning") && value.get("planning").getAsBoolean()); }
    public String label(String operation, boolean paused, boolean connected) {
        if (paused || phase == Phase.PAUSED) return "Paused";
        if (!connected || phase == Phase.DISCONNECTED) return "Disconnected";
        String label = thinking() ? (planning ? "Planning" : "Thinking") : operation != null ? switch (operation) {
            case "travel", "follow" -> "Traveling"; case "craft_item" -> "Crafting";
            case "collect_items", "take_items" -> "Collecting items"; case "deliver_items", "deposit_items" -> "Moving items";
            case "mine_blocks" -> "Mining"; case "farm" -> "Farming"; case "attack_entity" -> "Fighting";
            case "interact_block", "interact_entity" -> "Interacting";case "use_item" -> "Using item";
            case "inspect_trades" -> "Inspecting trades";case "trade" -> "Trading";
            case "idle_sleeping" -> "Sleeping";case "idle_restocking" -> "Restocking food";
            case "idle_wandering","idle_looking","idle_owner_glance","idle_resting","idle_bed_approach","idle_thinking" -> "Idling";
            default -> "Busy";
        } : switch (phase) { case THINKING -> "Thinking"; case WAITING -> "Waiting"; case IDLE -> tasks == 0 ? "Idle" : "Busy"; default -> "Busy"; };
        return tasks == 0 ? label : label + " · " + tasks + (tasks == 1 ? " task" : " tasks");
    }
}
