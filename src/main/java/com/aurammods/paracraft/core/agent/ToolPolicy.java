package com.aurammods.paracraft.core.agent;

import java.util.Set;

public final class ToolPolicy {
    private ToolPolicy() { }
    private static final Set<String> LIGHT = Set.of("chat", "think_harder", "get_context");
    private static final Set<String> PLANNING = Set.of("chat", "observe", "sample_terrain", "get_context", "program", "resume_program", "pause_program", "cancel_program",
            "task_upsert", "task_remove", "remember", "forget", "personality", "wait", "trust", "untrust", "configure_behavior");
    public static Set<String> allowed(InferenceProvider.Route route) { return route == InferenceProvider.Route.LIGHT ? LIGHT : PLANNING; }
    public static void validate(InferenceProvider.Route route, InferenceProvider.Decision decision) {
        Set<String> allowed = allowed(route);
        for (var call : decision.calls()) if (!allowed.contains(call.tool())) throw new IllegalArgumentException("Tool " + call.tool() + " is unavailable on " + route);
        for(var call:decision.calls())if(call.tool().equals("get_context"))DecisionContext.validate(call.arguments());
        if (decision.calls().size() < 2 || decision.calls().stream().allMatch(call -> call.tool().equals("get_context"))) return;
        if (decision.calls().stream().anyMatch(call -> Set.of("think_harder", "observe", "sample_terrain", "get_context").contains(call.tool())))
            throw new IllegalArgumentException("Use only get_context calls together for read-only context, or send other continuations alone");
    }
}
