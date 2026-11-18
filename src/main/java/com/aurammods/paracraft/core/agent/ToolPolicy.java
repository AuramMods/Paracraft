package com.aurammods.paracraft.core.agent;

import java.util.Set;

public final class ToolPolicy {
    private ToolPolicy() { }
    private static final Set<String> LIGHT = Set.of("chat", "think_harder", "get_context");
    private static final Set<String> PLANNING = Set.of("chat", "observe", "get_context", "program", "resume_program", "pause_program", "cancel_program",
            "task_upsert", "task_remove", "remember", "forget", "personality", "wait", "trust", "untrust", "configure_behavior");
    public static Set<String> allowed(InferenceProvider.Route route) { return route == InferenceProvider.Route.LIGHT ? LIGHT : PLANNING; }
    public static void validate(InferenceProvider.Route route, InferenceProvider.Decision decision) {
        Set<String> allowed = allowed(route);
        for (var call : decision.calls()) if (!allowed.contains(call.tool())) throw new IllegalArgumentException("Tool " + call.tool() + " is unavailable on " + route);
        for(var call:decision.calls())if(call.tool().equals("get_context"))DecisionContext.validate(call.arguments());
        long continuations = decision.calls().stream().filter(c -> c.tool().equals("think_harder") || c.tool().equals("observe") || c.tool().equals("get_context")).count();
        if (continuations > 0 && decision.calls().size() != 1) throw new IllegalArgumentException("A continuation must be the sole tool call");
    }
}
