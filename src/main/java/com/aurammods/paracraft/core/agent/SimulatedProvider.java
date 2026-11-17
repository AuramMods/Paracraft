package com.aurammods.paracraft.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** A finite QA script interpreter, explicitly not an LLM or a natural-language capability claim. */
public final class SimulatedProvider implements InferenceProvider {
    private static final Pattern TRAVEL = Pattern.compile("(?i)\\btravel to (-?\\d+(?:\\.\\d+)?) (-?\\d+(?:\\.\\d+)?) (-?\\d+(?:\\.\\d+)?)\\b");
    private static final Pattern BEHAVIOR = Pattern.compile("(?i)\\bbehavior (players|mobs) (passive|flee|neutral|aggressive)\\b");
    private static final Pattern COMMAND=Pattern.compile("(?i)\\b(execute|teleport) command (.+)$");
    private static final Pattern DISCOVER=Pattern.compile("(?i)\\bdiscover commands(?: (.*))?$");
    private static final Pattern PORTAL=Pattern.compile("(?i)\\buse portal (-?\\d+) (-?\\d+) (-?\\d+)\\b");
    private static final Pattern BLOCKS=Pattern.compile("(?i)\\bsearch blocks (#[a-z0-9_]+:[a-z0-9_/.-]+|[a-z0-9_]+:[a-z0-9_/.-]+)(?:\\s+(?:nearest|limit)\\s+(\\d+))?(?:\\s+radius\\s+(\\d+(?:\\.\\d+)?))?\\b");
    private static final Set<String> TERMINAL_TASK = Set.of("COMPLETED", "FAILED");
    @Override public String label() { return "SIMULATED — deterministic QA scripts, no AI usage"; }
    @Override public boolean simulated() { return true; }
    @Override public CompletableFuture<Decision> infer(Request request) {
        try {
            Decision reply = decide(request);
            ToolPolicy.validate(request.route(), reply);
            var output = new JsonObject(); output.addProperty("type", "decision"); var calls = new JsonArray();
            for (var call : reply.calls()) { var encoded = new JsonObject(); encoded.addProperty("tool", call.tool()); encoded.add("arguments", call.arguments()); calls.add(encoded); }
            output.add("calls", calls); request.emitOutput(output);
            return CompletableFuture.completedFuture(reply);
        } catch (RuntimeException invalid) { return CompletableFuture.failedFuture(invalid); }
    }
    private Decision decide(Request request) {
        JsonObject frame = request.snapshot(), event = request.trigger();
        String text = event.has("message") ? event.get("message").getAsString() : "";
        String lower = text.toLowerCase(Locale.ROOT);
        boolean chat = event.has("type") && event.get("type").getAsString().equals("chat");
        JsonObject task = nextTask(frame);
        if (request.route() == Route.LIGHT) {
            if (chat) {
                if(COMMAND.matcher(text).find() || DISCOVER.matcher(text).find() || PORTAL.matcher(text).find() || BLOCKS.matcher(text).find())return decision(call("think_harder",new JsonObject()));
                if (lower.contains("progress") || lower.contains("status")) return say(progress(frame));
                if (scriptedAction(text)) return decision(call("think_harder", new JsonObject()));
                if (lower.contains("hello") || lower.contains("hey") || lower.contains("how's it going"))
                    return say("I'm here. This companion is running the deterministic QA simulator.");
                return say("Simulation scripts: collect diamonds, progress, pause/resume work, travel to X Y Z, execute command TEXT, teleport command TEXT, discover commands PREFIX, behavior players|mobs REACTION, wait for EVENT, remember KEY=TEXT, personality TEXT, trust/untrust UUID.");
            }
            if (task == null) return decision();
            JsonObject job = program(frame, task);
            if (!task.get("status").getAsString().equals("ACTIVE") || job == null || !job.get("state").getAsString().equals("RUNNING"))
                return decision(call("think_harder", new JsonObject()));
            return decision();
        }
        if (chat) {
            if(idleOff(lower) || idleOn(lower)) {
                var patch=new JsonObject();patch.addProperty("ambientIdle",idleOn(lower));
                if(idleOff(lower)){var idle=new JsonObject();idle.addProperty("enabled",false);patch.add("idle",idle);}
                return decision(call("configure_behavior",patch),speech(idleOn(lower)?"Ambient idling enabled.":"Ambient idling disabled; staying here."));
            }
            var portal=PORTAL.matcher(text);var blocks=BLOCKS.matcher(text);boolean usePortal=portal.find(),searchBlocks=blocks.find();
            if(usePortal || searchBlocks){
                String id=identity(frame,event,usePortal?"portal":"blocks");var program=baseProgram(id);var step=new JsonObject();step.addProperty("op",usePortal?"use_portal":"search_blocks");step.addProperty("saveAs",usePortal?"portal":"blocks");
                if(usePortal){var position=new JsonArray();for(int i=1;i<=3;i++)position.add(Integer.parseInt(portal.group(i)));step.add("position",position);}
                else {step.addProperty("block",blocks.group(1).toLowerCase(Locale.ROOT));if(blocks.group(2)!=null)step.addProperty("limit",Integer.parseInt(blocks.group(2)));if(blocks.group(3)!=null)step.addProperty("radius",Double.parseDouble(blocks.group(3)));}
                var steps=new JsonArray();steps.add(step);program.add("steps",steps);return startTask(frame,task(id,text,"ACTIVE",-1),program);
            }
            var command=COMMAND.matcher(text);var discover=DISCOVER.matcher(text);boolean execute=command.find(),discovery=discover.find();
            if(execute || discovery){
                String id=identity(frame,event,execute?"command":"commands");JsonObject program=baseProgram(id);JsonObject step=new JsonObject();
                step.addProperty("op",execute?"execute_command":"discover_commands");step.addProperty("saveAs","command");
                if(execute){step.addProperty("command",command.group(2));if(command.group(1).equalsIgnoreCase("teleport"))step.addProperty("await","teleport");}
                else step.addProperty("prefix",discover.group(1)==null?"":discover.group(1));
                JsonArray steps=new JsonArray();steps.add(step);program.add("steps",steps);return startTask(frame,task(id,text,"ACTIVE",-1),program);
            }
            var behavior = BEHAVIOR.matcher(text);
            if (behavior.find()) {
                JsonObject patch = new JsonObject(); patch.addProperty(behavior.group(1).toLowerCase(Locale.ROOT),behavior.group(2).toLowerCase(Locale.ROOT));
                return decision(call("configure_behavior",patch),speech("Server behavior updated."));
            }
            var travel = TRAVEL.matcher(text);
            if (lower.contains("diamond") && (lower.contains("collect") || lower.contains("find"))) {
                if (!request.observations().has("inventory")) {
                    JsonObject query = new JsonObject(); query.addProperty("kind", "inventory"); query.addProperty("item", "minecraft:diamond");
                    return decision(call("observe", query));
                }
                JsonObject observed = request.observations().getAsJsonObject("inventory");
                String id = identity(frame, event, "diamonds");
                JsonObject program = baseProgram(id);
                JsonObject refs = new JsonObject(); refs.addProperty("found", AgentState.required(observed, "id")); program.add("observationRefs", refs);
                JsonObject collect = new JsonObject(); collect.addProperty("op", "collect_items"); collect.addProperty("item", "minecraft:diamond");
                collect.addProperty("quantity", "all"); collect.addProperty("containersFrom", "found"); collect.addProperty("saveAs", "loot");
                JsonObject deliver = new JsonObject(); deliver.addProperty("op", "deliver_items"); deliver.addProperty("itemsFrom", "loot");
                deliver.addProperty("recipient", AgentState.required(event, "senderUuid")); deliver.addProperty("saveAs", "delivery");
                JsonArray steps = new JsonArray(); steps.add(collect); steps.add(deliver); program.add("steps", steps);
                return startTask(frame, task(id, text, "ACTIVE", 0), program);
            }
            if (travel.find()) {
                String id = identity(frame, event, "travel"); JsonObject program = baseProgram(id);
                JsonObject step = new JsonObject(); step.addProperty("op", "travel"); JsonArray destination = new JsonArray();
                for (int i = 1; i <= 3; i++) destination.add(Double.parseDouble(travel.group(i)));
                step.add("destination", destination); JsonArray steps = new JsonArray(); steps.add(step); program.add("steps", steps);
                return startTask(frame, task(id, text, "ACTIVE", -1), program);
            }
            if (lower.contains("pause work")) {
                if (task == null) return say("There is no unfinished task.");
                JsonObject update = status(task, "INACTIVE");
                JsonObject args = new JsonObject(); args.addProperty("id", task.get("programId").getAsString());
                return decision(call("task_upsert", update), call("pause_program", args), speech("Task set inactive; it remains available for resumption."));
            }
            if (lower.contains("resume work")) return manageTask(frame, task);
            int memory = lower.indexOf("remember ");
            if (memory >= 0) {
                String value = text.substring(memory + 9); int separator = value.indexOf('=');
                if (separator < 1) return say("Use remember KEY=TEXT in this simulation.");
                JsonObject args = new JsonObject(); args.addProperty("key", value.substring(0, separator).trim());
                args.addProperty("text", value.substring(separator + 1).trim()); return decision(call("remember", args), speech("Memory updated."));
            }
            int personality = lower.indexOf("personality ");
            if (personality >= 0) {
                JsonObject args = new JsonObject(); args.addProperty("text", text.substring(personality + 12).trim());
                return decision(call("personality", args), speech("Personality updated."));
            }
            var trust = Pattern.compile("(?i)\\b(untrust|trust) ([0-9a-f-]{36})\\b").matcher(text);
            if (trust.find()) {
                JsonObject args = new JsonObject(); args.addProperty("playerUuid", UUID.fromString(trust.group(2)).toString());
                return decision(call(trust.group(1).toLowerCase(Locale.ROOT), args), speech("Instruction permission updated."));
            }
            var wait = Pattern.compile("(?i)\\bwait for ([a-z_]+)\\b").matcher(text);
            if (wait.find()) {
                JsonObject args = new JsonObject(); args.addProperty("event", wait.group(1)); return decision(call("wait", args));
            }
            return say("Unsupported or malformed simulation command.");
        }
        return manageTask(frame, task);
    }
    private static boolean scriptedAction(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        return idleOff(lower) || idleOn(lower) || (lower.contains("diamond") && (lower.contains("collect") || lower.contains("find"))) || TRAVEL.matcher(text).find()
                || lower.contains("pause work") || lower.contains("resume work") || lower.contains("remember ") || lower.contains("personality ")
                || lower.contains("wait for ") || lower.contains("trust ") || BEHAVIOR.matcher(text).find() || COMMAND.matcher(text).find() || DISCOVER.matcher(text).find();
    }
    private static boolean idleOff(String text){return text.contains("stop moving") || text.contains("stop wandering") || text.contains("stay still") || text.contains("idle off");}
    private static boolean idleOn(String text){return text.contains("resume idling") || text.contains("wander again") || text.contains("idle on");}
    private static Decision startTask(JsonObject frame, JsonObject task, JsonObject program) {
        List<Call> calls = new ArrayList<>();
        for (var item : frame.getAsJsonObject("agent").getAsJsonArray("tasks")) {
            JsonObject older = item.getAsJsonObject();
            if (older.get("status").getAsString().equals("ACTIVE") && !older.get("id").equals(task.get("id")))
                calls.add(call("task_upsert", status(older, "INACTIVE")));
        }
        calls.add(call("task_upsert", task)); calls.add(call("program", program)); return new Decision(calls);
    }
    private static Decision manageTask(JsonObject frame, JsonObject task) {
        if (task == null) return decision();
        JsonObject job = program(frame, task);
        if (job == null) {
            JsonObject wait = new JsonObject(); wait.addProperty("event", "program");
            return decision(call("task_upsert", status(task, "WAITING")), speech("The unfinished task's program is absent from the snapshot. Waiting for server state."), call("wait", wait));
        }
        String state = job.get("state").getAsString();
        if (state.equals("SUCCEEDED")) {
            String outcome = "Task completed.";
            JsonObject result = job.getAsJsonObject("result");
            if (result != null && result.has("results") && result.getAsJsonObject("results").has("delivery")) {
                JsonObject delivery = result.getAsJsonObject("results").getAsJsonObject("delivery");
                outcome = "Dropped " + (delivery.has("delivered") ? delivery.get("delivered").getAsLong() : 0) + " acquired diamonds at the recipient's feet.";
            }
            return decision(call("task_upsert", status(task, "COMPLETED")), speech(outcome));
        }
        if (state.equals("FAILED") || state.equals("CANCELLED")) {
            JsonObject update = status(task, "FAILED"); update.addProperty("failure", job.get("error").getAsString());
            return decision(call("task_upsert", update), speech("Task " + state.toLowerCase(Locale.ROOT) + ": " + job.get("error").getAsString()));
        }
        if (!task.get("status").getAsString().equals("ACTIVE") || state.equals("PAUSED") || state.equals("QUEUED")) {
            JsonObject args = new JsonObject(); args.addProperty("id", task.get("programId").getAsString());
            return decision(call("task_upsert", status(task, "ACTIVE")), call("resume_program", args));
        }
        return decision();
    }
    private static JsonObject nextTask(JsonObject frame) {
        for (var entry : frame.getAsJsonObject("agent").getAsJsonArray("tasks"))
            if (!TERMINAL_TASK.contains(entry.getAsJsonObject().get("status").getAsString())) return entry.getAsJsonObject();
        return null;
    }
    private static JsonObject program(JsonObject frame, JsonObject task) {
        if (!task.has("programId") || !frame.has("programs")) return null;
        for (var job : frame.getAsJsonArray("programs"))
            if (job.getAsJsonObject().get("id").equals(task.get("programId"))) return job.getAsJsonObject();
        return null;
    }
    private static String progress(JsonObject frame) {
        JsonObject task = nextTask(frame); if (task == null) return "There are no unfinished tasks.";
        JsonObject job = program(frame, task); if (job == null) return "The unfinished task has no execution status in this snapshot.";
        JsonObject execution=job.getAsJsonObject("result"),current=execution;
        String phase=job.has("phase")?job.get("phase").getAsString():"status unavailable";
        if(execution.has("pending")){var pending=execution.getAsJsonObject("pending");if(pending.has("phase"))phase=pending.get("phase").getAsString();if(pending.has("result"))current=pending.getAsJsonObject("result");}
        var counts=new ArrayList<String>();
        if(current.has("acquired") && current.has("requested"))counts.add("collected "+current.get("acquired").getAsLong()+" of "+current.get("requested").getAsLong()+" items");
        for(String key:List.of("deposited","delivered","broken","replanted","placed"))if(current.has(key))counts.add(key+" "+current.get(key).getAsLong());
        if(current.has("remainingSources")){long remaining=current.get("remainingSources").getAsLong();counts.add(remaining+" container"+(remaining==1?"":"s")+" remaining");}
        if(counts.size()>3)counts.subList(3,counts.size()).clear();
        if(phase.length()>60)phase=phase.substring(0,60);
        return "Task "+job.get("state").getAsString()+": "+phase.replace('_',' ')+". Actual progress: "+(counts.isEmpty()?"no item or block counts reported yet":String.join(", ",counts))+".";
    }
    private static JsonObject task(String id, String goal, String status, int priority) {
        JsonObject task = new JsonObject(); task.addProperty("id", id); task.addProperty("programId", id);
        task.addProperty("goal", goal); task.addProperty("status", status); task.addProperty("priority", priority); return task;
    }
    private static JsonObject status(JsonObject task, String status) {
        JsonObject update = task.deepCopy(); update.addProperty("status", status); return update;
    }
    private static JsonObject baseProgram(String id) {
        JsonObject value = new JsonObject(); value.addProperty("schema", 1); value.addProperty("id", id); value.addProperty("taskId", id);
        value.addProperty("mode", "interrupt"); return value;
    }
    private static String identity(JsonObject frame, JsonObject event, String purpose) {
        return UUID.nameUUIDFromBytes((AgentState.required(frame, "avatarId") + ":" + event.get("id").getAsLong() + ":" + purpose).getBytes(StandardCharsets.UTF_8)).toString();
    }
    private static Call call(String tool, JsonObject args) { return new Call(tool, args); }
    private static Call speech(String text) { JsonObject args = new JsonObject(); args.addProperty("message", "[SIMULATED] " + text); return call("chat", args); }
    private static Decision say(String text) { return decision(speech(text)); }
    private static Decision decision(Call... calls) { return new Decision(List.of(calls)); }
}
