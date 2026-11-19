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
        var calls = decision.calls();
        for (var call : calls) if (!allowed.contains(call.tool())) throw new IllegalArgumentException("Tool " + call.tool() + " is unavailable on " + route);
        for (var call : calls) if (call.tool().equals("get_context")) DecisionContext.validate(call.arguments());
        if (calls.size() < 2 || calls.stream().allMatch(call -> call.tool().equals("get_context"))) return;
        if (route == InferenceProvider.Route.LIGHT && calls.size() == 2
                && calls.get(0).tool().equals("chat") && calls.get(1).tool().equals("think_harder")) {
            AgentState.required(calls.get(0).arguments(), "message");
            return;
        }
        if (calls.stream().anyMatch(call -> Set.of("think_harder", "observe", "sample_terrain", "get_context").contains(call.tool())))
            throw new IllegalArgumentException("Batch get_context reads together, or use chat followed by think_harder on Light. Other continuations must be alone");
    }
}
