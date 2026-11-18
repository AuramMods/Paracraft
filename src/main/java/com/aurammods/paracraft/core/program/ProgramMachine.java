package com.aurammods.paracraft.core.program;

import com.aurammods.paracraft.core.JobState;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Finite structured programs. This interpreter has no Minecraft, filesystem, or shell access. */
public final class ProgramMachine {
    public record Update(JobState state, JsonObject result, String error) { }
    public record Context(JsonObject results, JsonObject caught) { }
    public interface Operation {
        Update tick();
        JsonObject checkpoint();
        default JsonObject view() { return checkpoint(); }
        void pause();
        void cancel(String reason);
    }
    public interface Factory {
        Set<String> operations();
        default void validate(JsonObject specification) { }
        Operation start(UUID id, JsonObject specification, Context context, JsonObject checkpoint);
    }
    private enum Code { ACTION, ENTER_TRY, LEAVE_TRY, LEAVE_CATCH }
    private record Instruction(Code code, JsonObject action, int destination, int after, String path) { }
    private record Handler(int destination, int after, int catchDepth) { }
    private record CatchScope(JsonObject previous) { }

    private final UUID id;
    private final JsonObject program;
    private final Factory factory;
    private final List<Instruction> code = new ArrayList<>();
    private final List<Handler> handlers = new ArrayList<>();
    private final List<CatchScope> catches = new ArrayList<>();
    private JsonObject results = new JsonObject(), caught, error = new JsonObject();
    private JobState state = JobState.RUNNING;
    private int pc;
    private Operation pending;
    private JsonObject pendingCheckpoint;

    public ProgramMachine(UUID id, JsonObject program, Factory factory) {
        this.id = id; this.program = program.deepCopy(); this.factory = factory;
        JsonElement wake = program.get("wakeOnSuccess");
        if (wake != null && !wake.isJsonNull() && !(wake.isJsonPrimitive() && wake.getAsJsonPrimitive().isBoolean()))
            throw new IllegalArgumentException("wakeOnSuccess must be a boolean");
        if (program.has("schema") && program.get("schema").getAsInt() != 1)
            throw new IllegalArgumentException("Unsupported action-program schema");
        if (!program.has("steps") || !program.get("steps").isJsonArray())
            throw new IllegalArgumentException("Program requires a steps array");
        compile(program.getAsJsonArray("steps"), "steps");
    }
    /** Initial results must come from the authoritative adapter, never from client-supplied inventory data. */
    public ProgramMachine(UUID id, JsonObject program, Factory factory, JsonObject initialResults) {
        this(id, program, factory); results = initialResults.deepCopy();
    }
    public JobState state() { return state; }
    public JsonObject error() { return error.deepCopy(); }
    public JsonObject results() { return results.deepCopy(); }
    private boolean hasTask() { return program.has("taskId") && !program.get("taskId").isJsonNull(); }
    public String taskId() { return hasTask() ? program.get("taskId").getAsString() : id.toString(); }
    public boolean wakeOnSuccess() {
        JsonElement wake = program.get("wakeOnSuccess");
        return hasTask() || wake == null || wake.isJsonNull() || wake.getAsBoolean();
    }
    public String currentOperation() { return pc < code.size() && code.get(pc).action != null ? code.get(pc).action.get("op").getAsString() : "control"; }
    public boolean hasPendingOperation(){return pending!=null || pendingCheckpoint!=null;}

    private void compile(JsonArray steps, String path) {
        for (int index = 0; index < steps.size(); index++) {
            if (!steps.get(index).isJsonObject()) throw new IllegalArgumentException("Each program step must be an object");
            JsonObject step = steps.get(index).getAsJsonObject(); String location = path + "." + index;
            if (step.has("try")) {
                if (step.has("op") || !step.get("try").isJsonArray() || !step.has("catch") || !step.get("catch").isJsonArray())
                    throw new IllegalArgumentException("try requires a try array and a catch array, without an op");
                int enter = code.size(); code.add(null);
                compile(step.getAsJsonArray("try"), location + ".try");
                int leave = code.size(); code.add(null);
                int handler = code.size(); compile(step.getAsJsonArray("catch"), location + ".catch");
                code.add(new Instruction(Code.LEAVE_CATCH, null, 0, 0, location));
                int after = code.size();
                code.set(enter, new Instruction(Code.ENTER_TRY, null, handler, after, location));
                code.set(leave, new Instruction(Code.LEAVE_TRY, null, after, 0, location));
            } else {
                if (!step.has("op") || !step.get("op").isJsonPrimitive()) throw new IllegalArgumentException("Action requires op");
                String op = step.get("op").getAsString();
                if (!op.equals("rethrow") && !factory.operations().contains(op)) throw new IllegalArgumentException("Unsupported operation: " + op);
                if (!op.equals("rethrow")) factory.validate(step.deepCopy());
                if (step.has("saveAs") && (!step.get("saveAs").isJsonPrimitive() || step.get("saveAs").getAsString().isBlank()))
                    throw new IllegalArgumentException("saveAs must be a nonempty name");
                code.add(new Instruction(Code.ACTION, step.deepCopy(), 0, 0, location));
            }
        }
    }

    /** At most budget instructions/updates. A running operation yields immediately. */
    public void tick(int budget) {
        if (state != JobState.RUNNING) return;
        for (int count = 0; count < budget && state == JobState.RUNNING; count++) {
            if (pc >= code.size()) { state = JobState.SUCCEEDED; break; }
            Instruction instruction = code.get(pc);
            switch (instruction.code) {
                case ENTER_TRY -> { handlers.add(new Handler(instruction.destination, instruction.after, catches.size())); pc++; }
                case LEAVE_TRY -> { handlers.remove(handlers.size() - 1); pc = instruction.destination; }
                case LEAVE_CATCH -> { caught = catches.remove(catches.size() - 1).previous; pc++; }
                case ACTION -> {
                    if (instruction.action.get("op").getAsString().equals("rethrow")) {
                        routeFailure(caught == null ? failure("No caught error to rethrow", instruction, new JsonObject()) : caught.deepCopy());
                        continue;
                    }
                    try {
                        if (pending == null) pending = factory.start(actionId(instruction), instruction.action.deepCopy(),
                                new Context(results.deepCopy(), caught == null ? null : caught.deepCopy()), pendingCheckpoint);
                        Update update = pending.tick();
                        if (update.state == JobState.RUNNING || update.state == JobState.QUEUED) return;
                        if (update.state == JobState.PAUSED) { pause(); return; }
                        if (update.state == JobState.CANCELLED) { cancel(update.error); return; }
                        if (instruction.action.has("saveAs")) results.add(instruction.action.get("saveAs").getAsString(), update.result.deepCopy());
                        pending = null; pendingCheckpoint = null;
                        if (update.state == JobState.FAILED) routeFailure(failure(update.error, instruction, update.result));
                        else pc++;
                    } catch (IllegalArgumentException | IllegalStateException invalid) {
                        JsonObject partial = pendingCheckpoint != null && pendingCheckpoint.has("result")
                                ? pendingCheckpoint.getAsJsonObject("result").deepCopy() : new JsonObject();
                        if (pending != null) pending.cancel("Operation failed");
                        pending = null; pendingCheckpoint = null;
                        routeFailure(failure(invalid.getMessage(), instruction, partial));
                    }
                }
            }
        }
    }
    private UUID actionId(Instruction instruction) {
        return UUID.nameUUIDFromBytes((id + "/" + instruction.path).getBytes(StandardCharsets.UTF_8));
    }
    private JsonObject failure(String message, Instruction instruction, JsonObject partial) {
        JsonObject value = new JsonObject(); value.addProperty("message", message);
        value.addProperty("actionId", actionId(instruction).toString()); value.addProperty("path", instruction.path);
        value.add("operation", instruction.action.deepCopy()); value.add("partial", partial.deepCopy()); return value;
    }
    private void routeFailure(JsonObject failure) {
        error = failure.deepCopy();
        if (handlers.isEmpty()) { state = JobState.FAILED; return; }
        Handler handler = handlers.remove(handlers.size() - 1);
        while (catches.size() > handler.catchDepth) caught = catches.remove(catches.size() - 1).previous;
        catches.add(new CatchScope(caught == null ? null : caught.deepCopy()));
        caught = failure.deepCopy(); pc = handler.destination;
    }
    public void pause() {
        if (state != JobState.RUNNING) return;
        if (pending != null) pending.pause(); state = JobState.PAUSED;
    }
    public void resume() {
        if (state != JobState.PAUSED && state != JobState.QUEUED) return;
        if (pending != null) { pendingCheckpoint = pending.checkpoint(); pending = null; }
        state = JobState.RUNNING;
    }
    public void cancel(String reason) {
        if (state.terminal()) return;
        if (pending != null) pending.cancel(reason);
        state = JobState.CANCELLED; error = new JsonObject(); error.addProperty("message", reason);
    }
    public JsonObject view() {
        JsonObject view = new JsonObject(); view.addProperty("id", id.toString()); view.addProperty("taskId", taskId());
        view.addProperty("state", state.name()); view.addProperty("pc", pc); view.addProperty("operation", currentOperation());
        view.add("results", results.deepCopy()); view.add("error", error.deepCopy());
        if (pending != null) view.add("pending", pending.view());
        else if (pendingCheckpoint != null) { JsonObject summary = pendingCheckpoint.deepCopy(); summary.remove("data"); view.add("pending", summary); }
        return view;
    }
    public JsonObject checkpoint() {
        JsonObject value = view(); value.addProperty("schema", 1); value.add("program", program.deepCopy());
        if (pending != null) value.add("pending", pending.checkpoint());
        else if (pendingCheckpoint != null) value.add("pending", pendingCheckpoint.deepCopy());
        value.add("caught", caught == null ? JsonNull.INSTANCE : caught.deepCopy());
        JsonArray savedHandlers = new JsonArray();
        for (Handler h : handlers) { JsonObject entry = new JsonObject(); entry.addProperty("destination", h.destination); entry.addProperty("after", h.after); entry.addProperty("depth", h.catchDepth); savedHandlers.add(entry); }
        JsonArray savedCatches = new JsonArray();
        for (CatchScope c : catches) savedCatches.add(c.previous == null ? JsonNull.INSTANCE : c.previous.deepCopy());
        value.add("handlers", savedHandlers); value.add("catches", savedCatches); return value;
    }
    public static ProgramMachine restore(JsonObject checkpoint, Factory factory) {
        if (checkpoint.get("schema").getAsInt() != 1) throw new IllegalArgumentException("Unsupported interpreter checkpoint");
        ProgramMachine machine = new ProgramMachine(UUID.fromString(checkpoint.get("id").getAsString()), checkpoint.getAsJsonObject("program"), factory);
        machine.pc = checkpoint.get("pc").getAsInt();
        if (machine.pc < 0 || machine.pc > machine.code.size()) throw new IllegalArgumentException("Invalid saved instruction position");
        machine.results = checkpoint.getAsJsonObject("results").deepCopy(); machine.error = checkpoint.getAsJsonObject("error").deepCopy();
        machine.state = JobState.valueOf(checkpoint.get("state").getAsString());
        if (!machine.state.terminal()) machine.state = JobState.PAUSED;
        machine.caught = checkpoint.get("caught").isJsonNull() ? null : checkpoint.getAsJsonObject("caught").deepCopy();
        for (JsonElement e : checkpoint.getAsJsonArray("handlers")) { JsonObject h = e.getAsJsonObject(); machine.handlers.add(new Handler(h.get("destination").getAsInt(), h.get("after").getAsInt(), h.get("depth").getAsInt())); }
        for (JsonElement e : checkpoint.getAsJsonArray("catches")) machine.catches.add(new CatchScope(e.isJsonNull() ? null : e.getAsJsonObject().deepCopy()));
        if (checkpoint.has("pending")) machine.pendingCheckpoint = checkpoint.getAsJsonObject("pending").deepCopy();
        return machine;
    }
}
