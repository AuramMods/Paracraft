package com.aurammods.paracraft.core.agent;

import com.google.gson.*;
import java.util.ArrayList;
import java.util.List;

/** Only assistant-visible text/refusals and tool calls; never opaque reasoning or auth metadata. */
public final class AgentOutput {
    private AgentOutput() { }
    public static JsonObject visible(JsonObject item) {
        String type = item.has("type") ? item.get("type").getAsString() : "";
        var result = new JsonObject(); result.addProperty("type", type);
        if (type.equals("function_call")) {
            for (String key : List.of("namespace", "name", "arguments")) if (item.has(key)) result.add(key, item.get(key).deepCopy());
            return result;
        }
        if (!type.equals("message") || item.has("role") && !item.get("role").getAsString().equals("assistant")) return null;
        var content = new JsonArray();
        if (item.has("content")) for (var element : item.getAsJsonArray("content")) {
            var part = element.getAsJsonObject(); String kind = part.get("type").getAsString();
            String key = kind.equals("output_text") ? "text" : kind.equals("refusal") ? "refusal" : null;
            if (key != null && part.has(key)) { var text = new JsonObject(); text.addProperty("type", kind); text.add(key, part.get(key).deepCopy()); content.add(text); }
        }
        if (content.isEmpty()) return null;
        result.add("content", content); return result;
    }
    public static List<String> chunks(String text) {
        var chunks = new ArrayList<String>();
        for (int start = 0; start < text.length();) {
            int end = Math.min(start + 4096, text.length());
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1)) && Character.isLowSurrogate(text.charAt(end))) end--;
            chunks.add(text.substring(start, end)); start = end;
        }
        return chunks;
    }
}
