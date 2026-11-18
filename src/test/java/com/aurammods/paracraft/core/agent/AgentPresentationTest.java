package com.aurammods.paracraft.core.agent;

import org.junit.jupiter.api.Test;
import static com.aurammods.paracraft.core.agent.HarnessFixture.json;
import static org.junit.jupiter.api.Assertions.*;

class AgentPresentationTest {
    @Test void unfinishedTasksAreCountedWithoutExposingTheirContentsAndServerStateWins() {
        var memory = new AgentState();
        for (String status : java.util.List.of("QUEUED", "ACTIVE", "INACTIVE", "WAITING", "COMPLETED", "FAILED"))
            memory.mutate("task_upsert", json("{\"id\":\"" + status + "\",\"status\":\"" + status + "\",\"goal\":\"secret goal\"}"));
        var activity = AgentActivity.from(memory, "Thinking: LIGHT"); assertEquals(4, activity.tasks());
        assertEquals("Thinking · 4 tasks", activity.label(null, false, true));
        assertEquals("Thinking · 4 tasks", activity.label("travel", false, true));
        assertEquals("Thinking · 4 tasks", activity.label("idle_wandering", false, true));
        assertEquals("Paused", activity.label("travel", true, true));
        assertEquals("Disconnected", activity.label("travel", false, false));
        assertEquals(activity, AgentActivity.parse(activity.json())); assertFalse(activity.json().toString().contains("secret goal"));
        assertEquals("Idle", AgentActivity.from(new AgentState(), "Idle").label(null, false, true));
        assertThrows(IllegalArgumentException.class, () -> AgentActivity.parse(json("{\"phase\":\"IDLE\",\"tasks\":-1}")));
    }
    @Test void planningOverridesIdleAndWorkLabelsWithoutChangingLegacyThinkingPayloads() {
        var activity = AgentActivity.from(new AgentState(), "Thinking: PLANNING");
        assertTrue(activity.thinking()); assertTrue(activity.planning());
        for (String operation : java.util.List.of("idle_wandering", "idle_restocking", "idle_sleeping", "travel", "attack_entity"))
            assertEquals("Planning", activity.label(operation, false, true));
        assertEquals(activity, AgentActivity.parse(activity.json()));
        assertEquals(activity, AgentActivity.from(new AgentState(), "Planning"));
        var legacy = AgentActivity.parse(json("{\"phase\":\"THINKING\",\"tasks\":1}"));
        assertFalse(legacy.planning()); assertEquals("Thinking · 1 task", legacy.label(null, false, true));
        assertEquals("Traveling", new AgentActivity(AgentActivity.Phase.BUSY, 0).label("travel", false, true));
        assertThrows(IllegalArgumentException.class, () -> new AgentActivity(AgentActivity.Phase.IDLE, 0, true));
    }
    @Test void visibleProjectionKeepsAllTextRefusalsAndToolArgumentsWithoutUnrelatedFields() {
        var item=json("{\"type\":\"message\",\"role\":\"assistant\",\"encrypted_content\":\"secret\",\"content\":[{\"type\":\"output_text\",\"text\":\"hello\",\"annotations\":[]},{\"type\":\"refusal\",\"refusal\":\"no\"},{\"type\":\"reasoning_text\",\"text\":\"private\"}]}");
        var result=AgentOutput.visible(item);assertEquals(2,result.getAsJsonArray("content").size());
        assertTrue(result.toString().contains("hello"));assertTrue(result.toString().contains("no"));assertFalse(result.toString().contains("secret"));assertFalse(result.toString().contains("private"));
        assertNull(AgentOutput.visible(json("{\"type\":\"reasoning\",\"summary\":[]}")));
    }
    @Test void largeOutputsAreSplitLosslesslyWithoutSplittingUnicodePairs() {
        String text="x".repeat(4095)+"😀"+"y".repeat(9000);var chunks=AgentOutput.chunks(text);
        assertEquals(text,String.join("",chunks));assertEquals(4,chunks.size());
        for(String part:chunks){assertTrue(part.length()<=4096);assertFalse(Character.isHighSurrogate(part.charAt(part.length()-1)));assertFalse(Character.isLowSurrogate(part.charAt(0)));}
    }
}
