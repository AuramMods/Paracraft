package com.aurammods.paracraft.core.agent;

import com.google.gson.*;
import java.util.*;

/** Read-only model views over the full client snapshot. Never reads files, accounts, or the server. */
public final class DecisionContext {
    public static final List<String> SECTIONS=List.of("inventory","equipment","nearby_entities","programs","tasks","memory","history","action_history","survival","inventory_observations","capabilities","events","observations","profile");
    private static final Set<String> TERMINAL=Set.of("COMPLETED","FAILED","SUCCEEDED","CANCELLED");
    private DecisionContext(){}
    public static JsonObject profile(JsonObject snapshot){
        var profile=copy(snapshot,"name","avatarId","ownerId","worldId");var agent=object(snapshot,"agent");
        if(agent.has("personality"))profile.add("personality",preview(agent.get("personality"),512));return profile;
    }
    /** Small base context for every real provider. Detailed data is available by explicit section reads. */
    public static JsonObject initial(JsonObject snapshot, JsonObject trigger, JsonObject observations, InferenceProvider.Route route) {
        var agent = object(snapshot, "agent");
        var result = new JsonObject();
        result.add("continuity", continuity(snapshot, trigger, agent, route));
        result.add("snapshot", snapshotSummary(snapshot, trigger, agent, route));
        var catalog = new JsonObject();
        for (String section : SECTIONS) {
            var value = source(section, snapshot, agent, observations);
            var descriptor = new JsonObject();
            descriptor.addProperty("available", value != null);
            int entries = 0;
            if (value != null) {
                entries = section.equals("action_history") ? actionHistorySize(snapshot, value.getAsString())
                        : value.isJsonArray() ? value.getAsJsonArray().size() : value.isJsonObject() ? value.getAsJsonObject().size() : 1;
            }
            descriptor.addProperty("entries", entries);
            catalog.add(section, descriptor);
        }
        result.add("contextCatalog", catalog);
        return result;
    }

    private static int actionHistorySize(JsonObject snapshot, String text) {
        return snapshot.has("actionHistoryEntries") ? snapshot.get("actionHistoryEntries").getAsInt() : (int) text.lines().count();
    }

    private static JsonObject continuity(JsonObject snapshot, JsonObject trigger, JsonObject agent, InferenceProvider.Route route) {
        var result = copy(agent, "workingNote", "executingProgram", "consecutiveNoProgress");
        if (result.has("workingNote")) result.add("workingNote", preview(result.get("workingNote"), 512));
        var tasks = new JsonArray();
        int unfinished = 0;
        for (var item : array(agent, "tasks")) {
            var task = item.getAsJsonObject();
            if (TERMINAL.contains(string(task, "status"))) continue;
            unfinished++;
            if (tasks.size() < 6) tasks.add(taskSummary(task));
        }
        result.add("tasks", tasks);
        result.addProperty("unfinishedTaskCount", unfinished);
        result.addProperty("tasksTruncated", unfinished > tasks.size());
        if (route == InferenceProvider.Route.PLANNING && agent.has("workRequest")) {
            result.add("workRequest", preview(agent.get("workRequest"), 2048));
        }
        var memory = object(agent, "memory");
        var memoryKeys = new JsonArray();
        memory.keySet().stream().sorted().limit(12).forEach(key -> memoryKeys.add(preview(new JsonPrimitive(key), 128)));
        result.add("memoryKeys", memoryKeys);
        result.addProperty("memoryEntryCount", memory.size());

        var conversation = new JsonArray();
        var events = array(snapshot, "events");
        for (int i = Math.max(0, events.size() - 16); i < events.size(); i++) {
            var event = events.get(i).getAsJsonObject();
            if (!Set.of("chat", "response").contains(string(event, "type")) || Objects.equals(event.get("id"), trigger.get("id"))) continue;
            conversation.add(preview(copy(event, "id", "type", "senderUuid", "message"), 512));
            if (conversation.size() > 4) conversation.remove(0);
        }
        result.add("conversation", conversation);
        return result;
    }

    private static JsonObject snapshotSummary(JsonObject snapshot, JsonObject trigger, JsonObject agent, InferenceProvider.Route route) {
        var frame = copy(snapshot, "alive", "online", "health", "maxHealth", "food", "oxygen", "maxOxygen",
                "dimension", "position", "canBreathe", "canRefillAir", "gameMode", "creativeItems", "canFly", "flying",
                "serverTick", "observedAt");
        var programs = array(snapshot, "programs");
        var selected = relevantPrograms(snapshot, agent, programs);
        var summaries = new JsonArray();
        for (var program : selected) {
            if (summaries.size() >= (route == InferenceProvider.Route.LIGHT ? 2 : 8)) break;
            summaries.add(programSummary(program));
        }
        frame.add("programs", summaries);
        frame.addProperty("omittedTerminalPrograms", terminalCount(programs) - terminalCount(summaries));
        frame.addProperty("programsTruncated", programs.size() > summaries.size());
        if (snapshot.has("survival")) frame.add("survival", copy(object(snapshot, "survival"), "mode"));
        if (route == InferenceProvider.Route.PLANNING && snapshot.has("operations")) {
            frame.add("operations", snapshot.get("operations").deepCopy());
        }

        String text = string(trigger, "message").toLowerCase(Locale.ROOT);
        var inventory = array(snapshot, "inventory");
        var matchingItems = new LinkedHashSet<JsonElement>();
        for (var item : inventory) {
            String id = string(item.getAsJsonObject(), "item");
            String name = id.substring(id.indexOf(':') + 1).replace('_', ' ');
            if (!name.isEmpty() && (text.contains(name) || text.contains(id))) matchingItems.add(item);
        }
        if (snapshot.has("inventory") && (!matchingItems.isEmpty() || text.matches("(?s).*(inventory|carrying|holding|wearing|equipped|craft|trade|drop|give|collect).*"))) {
            var ordered = new ArrayList<>(matchingItems);
            for (var item : inventory) if (!matchingItems.contains(item)) ordered.add(item);
            frame.add("inventory", summaries(ordered, "inventory", 16));
            frame.addProperty("inventoryTruncated", inventory.size() > 16);
        }
        if (snapshot.has("nearbyEntities") && text.matches("(?s).*(follow|come|deliver|give|attack|fight|kill|villager|trade|nearby|around).*")) {
            var entities = array(snapshot, "nearbyEntities");
            frame.add("nearbyEntities", summaries(entities, "nearby_entities", 8));
            frame.addProperty("nearbyEntitiesTruncated", entities.size() > 8
                    || snapshot.has("nearbyEntitiesTruncated") && snapshot.get("nearbyEntitiesTruncated").getAsBoolean());
        }
        return frame;
    }

    private static Set<JsonObject> relevantPrograms(JsonObject snapshot, JsonObject agent, JsonArray programs) {
        var selected = new LinkedHashSet<JsonObject>();
        for (String id : List.of(string(snapshot, "currentProgramId"), string(agent, "executingProgram"))) {
            if (!id.isEmpty()) for (var item : programs) {
                var job = item.getAsJsonObject();
                if (id.equals(string(job, "id"))) selected.add(job);
            }
        }
        var linked = new HashSet<String>();
        for (var item : array(agent, "tasks")) {
            var task = item.getAsJsonObject();
            if (!TERMINAL.contains(string(task, "status")) && task.has("programId")) linked.add(string(task, "programId"));
        }
        for (var item : programs) {
            var job = item.getAsJsonObject();
            if (!TERMINAL.contains(string(job, "state")) || linked.contains(string(job, "id"))) selected.add(job);
        }
        int recent = 0;
        for (int i = programs.size() - 1; i >= 0 && recent < 2; i--) {
            var job = programs.get(i).getAsJsonObject();
            if (TERMINAL.contains(string(job, "state")) && selected.add(job)) recent++;
        }
        return selected;
    }

    private static long terminalCount(JsonArray programs) {
        return programs.asList().stream().filter(job -> TERMINAL.contains(string(job.getAsJsonObject(), "state"))).count();
    }

    public static JsonObject read(JsonObject snapshot, JsonObject agent, JsonObject observations, JsonObject args) {
        validate(args);
        var pages = new JsonObject();
        for (var requested : args.getAsJsonArray("sections")) {
            String section = requested.getAsString();
            pages.add(section, page(section, source(section, snapshot, agent, observations), args));
        }
        return observation(snapshot, pages);
    }

    private static JsonObject page(String section, JsonElement source, JsonObject args) {
        String query = string(args, "query");
        String search = query.toLowerCase(Locale.ROOT);
        int offset = args.has("offset") ? args.get("offset").getAsInt() : 0;
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 16;
        boolean full = string(args, "detail").equals("full");
        var matches = new ArrayList<JsonElement>();
        if (source != null) for (var item : section.equals("action_history") ? actionLines(source.getAsString()) : rows(source)) {
            if (search.isEmpty() || item.toString().toLowerCase(Locale.ROOT).contains(search)) matches.add(item);
        }
        var items = new JsonArray();
        int size = 0;
        for (int i = offset; i < matches.size() && items.size() < limit; i++) {
            var item = full ? matches.get(i) : summary(section, matches.get(i));
            int length = item.toString().length();
            // Keep an individual record recoverable even when it exceeds the normal page size.
            if (!items.isEmpty() && size + length > 12_000) break;
            items.add(item.deepCopy());
            size += length;
        }
        var result = new JsonObject();
        result.addProperty("available", source != null);
        result.addProperty("detail", full ? "full" : "summary");
        if (!query.isEmpty()) result.addProperty("query", query);
        result.addProperty("total", matches.size());
        result.addProperty("offset", offset);
        if (section.equals("action_history")) {
            result.addProperty("text", String.join("\n", items.asList().stream().map(JsonElement::getAsString).toList()));
        } else result.add("items", items);
        boolean hasMore = offset + (long) items.size() < matches.size();
        result.addProperty("hasMore", hasMore);
        if (hasMore) result.addProperty("nextOffset", offset + items.size());
        return result;
    }

    private static JsonArray actionLines(String text) {
        var lines = text.lines().toList();
        var newestFirst = new JsonArray();
        for (int index = lines.size() - 1; index >= 0; index--) newestFirst.add(lines.get(index));
        return newestFirst;
    }

    /** Persist selectors. Resolve their pages from the latest snapshot on the next decision. */
    public static void select(JsonObject turn, JsonObject args) {
        validate(args);
        var selections = object(turn, "contextSelections");
        for (var item : args.getAsJsonArray("sections")) {
            var selector = copy(args, "query", "offset", "limit", "detail");
            selections.add(item.getAsString(), selector);
        }
        turn.add("contextSelections", selections);
    }

    public static JsonObject forRequest(JsonObject turn, JsonObject snapshot, JsonObject agent) {
        var observations = turn.getAsJsonObject("observations").deepCopy();
        if (!turn.has("contextSelections")) return observations;
        var pages = new JsonObject();
        for (var selection : turn.getAsJsonObject("contextSelections").entrySet()) {
            String section = selection.getKey();
            pages.add(section, page(section, source(section, snapshot, agent, observations), selection.getValue().getAsJsonObject()));
        }
        observations.add("context", observation(snapshot, pages));
        return observations;
    }

    private static JsonObject observation(JsonObject snapshot, JsonObject pages) {
        var result = copy(snapshot, "serverTick", "observedAt");
        result.add("sections", pages);
        return result;
    }

    public static void validate(JsonObject args) {
        if (!args.has("sections") || !args.get("sections").isJsonArray() || args.getAsJsonArray("sections").isEmpty()) {
            throw new IllegalArgumentException("get_context requires sections");
        }
        for (var item : args.getAsJsonArray("sections")) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || !SECTIONS.contains(item.getAsString())) {
                throw new IllegalArgumentException("Unknown context section");
            }
        }
        for (String name : List.of("offset", "limit")) if (args.has(name)) {
            try {
                if (!args.get(name).isJsonPrimitive() || !args.getAsJsonPrimitive(name).isNumber()) throw new ArithmeticException();
                int value = args.get(name).getAsBigDecimal().intValueExact();
                if (value < 0 || name.equals("limit") && (value < 1 || value > 64)) throw new ArithmeticException();
            } catch (ArithmeticException invalid) {
                throw new IllegalArgumentException("Invalid context " + name);
            }
        }
        if (args.has("query") && (!args.get("query").isJsonPrimitive() || !args.getAsJsonPrimitive("query").isString())) {
            throw new IllegalArgumentException("Context query must be text");
        }
        if (args.has("detail") && !Set.of("summary", "full").contains(string(args, "detail"))) {
            throw new IllegalArgumentException("Context detail must be summary or full");
        }
    }
    public static JsonElement preview(JsonElement value,int budget){return compact(value,0,new int[]{budget});}
    private static JsonElement compact(JsonElement value,int depth,int[] budget){
        if(budget[0]<=0 || depth>10)return new JsonPrimitive("[context preview omitted; use get_context]");
        if(value.isJsonObject()){var result=new JsonObject();int kept=0;for(var entry:value.getAsJsonObject().entrySet()){if(budget[0]<=0 || kept++>=16)break;budget[0]-=entry.getKey().length()+8;result.add(entry.getKey(),compact(entry.getValue(),depth+1,budget));}if(result.size()<value.getAsJsonObject().size())result.addProperty("_contextOmittedFields",value.getAsJsonObject().size()-result.size());return result;}
        if(value.isJsonArray()){var result=new JsonArray();for(var item:value.getAsJsonArray()){if(budget[0]<=0 || result.size()>=8)break;result.add(compact(item,depth+1,budget));}if(result.size()<value.getAsJsonArray().size()){var marker=new JsonObject();marker.addProperty("_contextOmittedItems",value.getAsJsonArray().size()-result.size());result.add(marker);}return result;}
        if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()){String text=value.getAsString();int length=Math.min(text.length(),Math.max(0,Math.min(512,budget[0])));String cut=text.substring(0,length);budget[0]-=new JsonPrimitive(cut).toString().length();return new JsonPrimitive(length<text.length()?cut+" [context text preview; use get_context]":cut);}
        budget[0]-=value.toString().length();return value.deepCopy();
    }
    private static JsonElement source(String section,JsonObject snapshot,JsonObject agent,JsonObject observations){
        JsonElement value=switch(section){
            case "inventory","equipment","programs","survival","events"->snapshot.get(section);
            case "action_history"->snapshot.get("actionHistory");
            case "nearby_entities"->snapshot.get("nearbyEntities");case "inventory_observations"->snapshot.get("inventoryObservations");case "capabilities"->snapshot.get("operations");
            case "tasks","memory","history"->agent.get(section);case "profile"->{var profile=profile(snapshot);if(agent.has("personality"))profile.add("personality",agent.get("personality").deepCopy());yield profile;}
            case "observations"->{var copy=observations.deepCopy();copy.remove("context");yield copy;}
            default->throw new IllegalArgumentException("Unknown context section");
        };return value==null || value.isJsonNull()?null:value;
    }
    private static JsonArray rows(JsonElement source){
        if(source.isJsonArray())return source.getAsJsonArray();var values=new JsonArray();if(source.isJsonObject())source.getAsJsonObject().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry->{var row=new JsonObject();row.addProperty("key",entry.getKey());row.add("value",entry.getValue().deepCopy());values.add(row);});else values.add(source.deepCopy());return values;
    }
    private static JsonArray summaries(Iterable<JsonElement> source,String section,int limit){var result=new JsonArray();for(var item:source){if(result.size()>=limit)break;result.add(summary(section,item));}return result;}
    private static JsonElement summary(String section,JsonElement value){
        if(value.isJsonObject()){
            var object=value.getAsJsonObject();if(section.equals("inventory"))return copy(object,"slot","item","count","damage","maxDamage","empty");
            if(section.equals("programs"))return programSummary(object);if(section.equals("tasks"))return taskSummary(object);
            if(section.equals("nearby_entities")){var result=copy(object,"uuid","type","name","position","health","merchant","pickupDelayed");if(object.has("stack"))result.add("stack",copy(object.getAsJsonObject("stack"),"item","count","damage"));return result;}
        }return preview(value,1024);
    }
    private static JsonObject taskSummary(JsonObject task){var result=copy(task,"id","status","priority","programId");if(task.has("goal"))result.add("goal",preview(task.get("goal"),256));return result;}
    public static JsonElement observationSummary(JsonElement value){return metrics(value,0,new int[]{32});}
    private static JsonElement metrics(JsonElement value,int depth,int[] budget){
        if(!value.isJsonObject())return value.isJsonArray()?new JsonPrimitive("[details available with get_context]"):preview(value,256);
        var result=new JsonObject();if(depth>5)return result;
        for(var entry:value.getAsJsonObject().entrySet()){
            if(budget[0]<=0)break;var child=entry.getValue();String key=entry.getKey();
            if(child.isJsonObject()){var nested=metrics(child,depth+1,budget);if(nested.getAsJsonObject().size()>0){budget[0]--;result.add(key,nested);}}
            else if(child.isJsonPrimitive() && (!child.getAsJsonPrimitive().isString() || Set.of("id","state","phase","operation","item","selector","message","error","taskId","blockedReason","outcome","dimension","kind").contains(key))){budget[0]--;result.add(key,preview(child,256));}
        }
        if(result.size()<value.getAsJsonObject().size())result.addProperty("_contextDetailsAvailable",true);return result;
    }
    private static JsonObject programSummary(JsonObject job){var result=copy(job,"id","type","state","phase","dimension","taskId");if(job.has("error"))result.add("error",preview(job.get("error"),512));if(job.has("result")){var pending=object(object(job,"result"),"pending");if(pending.size()>0)result.add("current",observationSummary(pending));result.add("progress",observationSummary(job.get("result")));}return result;}
    private static JsonObject copy(JsonObject source,String... keys){var result=new JsonObject();for(String key:keys)if(source.has(key))result.add(key,source.get(key).deepCopy());return result;}
    private static String string(JsonObject source,String key){return source.has(key) && source.get(key).isJsonPrimitive()?source.get(key).getAsString():"";}
    private static JsonObject object(JsonObject source,String key){return source.has(key) && source.get(key).isJsonObject()?source.getAsJsonObject(key):new JsonObject();}
    private static JsonArray array(JsonObject source,String key){return source.has(key) && source.get(key).isJsonArray()?source.getAsJsonArray(key):new JsonArray();}
}
