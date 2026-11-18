package com.aurammods.paracraft.core.agent;

import java.util.ArrayDeque;
import java.util.regex.Pattern;

/** Bounded plain-text record of observed actions, independent of the model's working memory. */
public final class ActionHistory {
    private static final int TOKEN_BUDGET = 10_000;
    private static final int MAX_LINE_LENGTH = 256;
    private static final Pattern WORDS_AND_PUNCTUATION = Pattern.compile("[a-zA-Z]+|[0-9]+|[^\\s]");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s\\p{Cntrl}\\p{Z}]+");
    private record Line(String text, int tokens) { }

    private final ArrayDeque<Line> lines = new ArrayDeque<>();
    private int tokens;
    private String cached;

    public static ActionHistory fromText(String text) {
        var history = new ActionHistory();
        text.lines().forEach(history::append);
        return history;
    }

    public void append(String text) {
        String line = SEPARATORS.matcher(text).replaceAll(" ").strip();
        if (line.isEmpty()) return;
        if (line.length() > MAX_LINE_LENGTH) {
            int end = MAX_LINE_LENGTH - 3;
            if (Character.isHighSurrogate(line.charAt(end - 1))) end--;
            line = line.substring(0, end) + "...";
        }
        int cost = estimate(line);
        lines.addLast(new Line(line, cost));
        tokens += cost;
        while (tokens > TOKEN_BUDGET) tokens -= lines.removeFirst().tokens();
        cached = null;
    }

    public int size() { return lines.size(); }

    public String text() {
        if (cached == null) cached = String.join("\n", lines.stream().map(Line::text).toList());
        return cached;
    }

    public static String recent(String text, int limit) {
        if (limit <= 0 || text.isEmpty()) return "";
        int start = text.length();
        for (int i = 0; i < limit && start > 0; i++) start = text.lastIndexOf('\n', start - 1);
        return text.substring(Math.max(0, start + 1));
    }

    /** Shared approximation for different backends. Count letters, digit groups, punctuation and the newline. */
    static int estimate(String line) {
        var groups = WORDS_AND_PUNCTUATION.matcher(line);
        int count = 1;
        while (groups.find()) {
            char first = line.charAt(groups.start());
            int length = groups.end() - groups.start();
            if (first >= '0' && first <= '9') count += (length + 2) / 3;
            else if (first >= 'a' && first <= 'z' || first >= 'A' && first <= 'Z') count += (length + 3) / 4;
            else count += first < 128 ? 1 : 3;
        }
        return count;
    }
}
