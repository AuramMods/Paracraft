package com.aurammods.paracraft.core.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ActionHistoryTest {
    @Test void evictsWholeOldLinesAtTheEstimatedTokenBudget() {
        var history = new ActionHistory();
        for (int i = 0; i < 2_000; i++) history.append("placed minecraft:deepslate_tiles -120 64 " + i);
        var lines = history.text().lines().toList();
        assertTrue(lines.size() < 2_000);
        assertEquals(history.size(), lines.size());
        assertFalse(lines.contains("placed minecraft:deepslate_tiles -120 64 0"));
        assertEquals("placed minecraft:deepslate_tiles -120 64 1999", lines.get(lines.size() - 1));
        int tokens = lines.stream().mapToInt(ActionHistory::estimate).sum();
        assertTrue(tokens <= 10_000 && tokens > 9_960);
        assertEquals(history.text(), ActionHistory.fromText(history.text()).text());
    }

    @Test void savesOnePlainLinePerActionAndReadsTheRecentTail() {
        var history = new ActionHistory();
        history.append("traveled 1 64 2");
        history.append("placed minecraft:stone    10 64 -20");
        history.append("equipped\n\tminecraft:shield offhand");
        assertEquals("placed minecraft:stone 10 64 -20\nequipped minecraft:shield offhand",
                ActionHistory.recent(history.text(), 2));
    }
}
