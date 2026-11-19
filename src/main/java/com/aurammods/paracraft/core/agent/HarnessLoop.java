package com.aurammods.paracraft.core.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Tick on the owner client thread. Provider callbacks never mutate game/state objects. */
public final class HarnessLoop implements AutoCloseable {
    /** The transport must deduplicate requestId across reconnects and verify its actual sender/lease. */
    public interface Sink {
        CompletableFuture<JsonObject> call(UUID requestId, String tool, JsonObject arguments, long triggerEvent);
    }
    public static final long INTERVAL_NANOS = 5_000_000_000L;
    private final AgentBinding binding;
    private final InferenceProvider provider;
    private final AgentClock clock;
    private final Sink sink;
    private final Runnable save;
    private final java.util.function.Consumer<JsonObject> outputSink;
    private record Output(long generation, JsonObject value) { }
    private final java.util.concurrent.ConcurrentLinkedQueue<Output> outputs = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile long outputGeneration;
    public final AgentState state;
    private JsonObject latest;
    private String session = "", lastError = "";
    private boolean active, paused, closed;
    private CompletableFuture<InferenceProvider.Decision> inference;
    private CompletableFuture<JsonObject> effect;
    private long nextStart, calls, planningCalls;

    public HarnessLoop(AgentBinding binding, AgentState state, InferenceProvider provider, AgentClock clock, Sink sink, Runnable save) {
        this(binding, state, provider, clock, sink, save, output -> { });
    }
    public HarnessLoop(AgentBinding binding, AgentState state, InferenceProvider provider, AgentClock clock, Sink sink, Runnable save,
                       java.util.function.Consumer<JsonObject> outputSink) {
        this.binding = binding; this.state = state; this.provider = provider; this.clock = clock; this.sink = sink; this.save = save;
        this.outputSink = outputSink;
        state.validateTurn();
        paused = state.paused; lastError = state.lastError;
        long remainingMillis = state.lastInferenceMillis == 0 ? 0 : Math.min(5000, Math.max(0, 5000 - (clock.epochMillis() - state.lastInferenceMillis)));
        nextStart = clock.nanoTime() + remainingMillis * 1_000_000L;
    }
    public void accept(JsonObject snapshot) {
        if (closed) return;
        if (!binding.world().toString().equals(AgentState.required(snapshot, "worldId"))
                || !binding.owner().toString().equals(AgentState.required(snapshot, "ownerId"))
                || !binding.avatar().toString().equals(AgentState.required(snapshot, "avatarId")))
            throw new IllegalArgumentException("Snapshot belongs to another companion binding");
        String nextSession = snapshot.has("session") && !snapshot.get("session").isJsonNull() ? snapshot.get("session").getAsString() : "";
        boolean nextActive = !nextSession.isEmpty() && !snapshot.get("paused").getAsBoolean();
        if (!session.equals(nextSession) || !nextActive) abortFutures();
        long life = snapshot.has("lifeRevision") ? snapshot.get("lifeRevision").getAsLong() : 0;
        if (state.lifeRevision != life) {
            if (state.lifeRevision >= 0) {
                abortFutures();
                if (state.turn != null) {
                    boolean pending = !state.turn.has("calls") || state.turn.get("cursor").getAsInt() < state.turn.getAsJsonArray("calls").size();
                    JsonObject original = state.turn.getAsJsonObject("trigger");
                    if (pending && original.get("type").getAsString().equals("chat") && state.inbox.stream().noneMatch(e -> e.get("id").equals(original.get("id")))) state.enqueue(original);
                    state.turn = null;
                }
                state.wakeEvent = ""; state.wakeAtMillis = 0;
                state.history("lifecycle", "Respawn discarded the previous body's unfinished decision; tasks and original pending chat remain for fresh planning.");
            }
            state.lifeRevision = life; save.run();
        }
        session = nextSession; latest = snapshot.deepCopy(); active = nextActive;
        if (!snapshot.has("events")) return;
        var incoming = new ArrayList<JsonObject>();
        for (var item : snapshot.getAsJsonArray("events")) incoming.add(item.getAsJsonObject());
        incoming.sort(Comparator.comparingLong(e -> e.get("id").getAsLong()));
        boolean changed = false;
        for (JsonObject event : incoming) {
            long id = event.get("id").getAsLong(); if (id <= state.eventCursor) continue;
            boolean wakes=event.has("wake")?event.get("wake").getAsBoolean():!Set.of("spawn","spawn_point","response").contains(event.get("type").getAsString());
            if (wakes) state.enqueue(event);
            state.eventCursor = id; state.history("event", event.toString()); changed = true;
        }
        if (changed) save.run();
    }
    public void tick() {
        if (closed || paused || !active || latest == null) return;
        try {
            flushOutputs();
            if (effect != null) {
                if (!effect.isDone()) return;
                JsonObject reply = effect.join();
                effect = null;
                var acknowledged = pendingCall();
                String tool = acknowledged.tool();
                var outcome = identity();
                outcome.addProperty("tool", tool);
                outcome.add("arguments", acknowledged.arguments());
                outcome.add("reply", reply.deepCopy());
                state.history("tool_result", outcome);
                var observations = state.turn.getAsJsonObject("observations");
                var calls = state.turn.getAsJsonArray("calls");
                boolean instructionReply = route() == InferenceProvider.Route.LIGHT && tool.equals("chat")
                        && calls.size() == 2 && calls.get(1).getAsJsonObject().get("tool").getAsString().equals("think_harder");
                if (!reply.get("ok").getAsBoolean()) {
                    String code = reply.has("code") ? reply.get("code").getAsString() : "";
                    if (!Set.of("TOOL_FAILED", "OBSERVATION_FAILED").contains(code))
                        throw new IllegalStateException(reply.get("error").getAsString());
                    var details = new JsonObject();
                    details.addProperty("tool", tool);
                    details.add("arguments", acknowledged.arguments());
                    details.add("reply", reply.deepCopy());
                    var failures = observations.has("tool_failures") ? observations.getAsJsonArray("tool_failures") : new JsonArray();
                    failures.add(details);
                    observations.add("tool_failures", failures);
                    state.history("tool_error", details);
                    if (instructionReply) continueDecision();
                    else continuePlanning();
                } else if (tool.equals("observe") || tool.equals("sample_terrain")) {
                    String section = tool.equals("observe") ? "inventory" : "terrain";
                    observations.add(section, reply.getAsJsonObject("value").deepCopy());
                    continuePlanning();
                } else {
                    if (instructionReply) observations.add("instruction_acknowledgment", acknowledged.arguments().get("message"));
                    if (Set.of("program", "resume_program").contains(tool) && reply.has("value")) {
                        var program = reply.getAsJsonObject("value");
                        mergeProgram(program);
                        state.executingProgram = program.has("id") ? program.get("id").getAsString() : "";
                    }
                    advanceCall();
                }
                save.run();
            }
            if (inference != null) {
                if (!inference.isDone()) return;
                flushOutputs();
                var decision = inference.join(); inference = null;
                ToolPolicy.validate(route(), decision); // Reject the whole reply before any effects.
                if (route() == InferenceProvider.Route.LIGHT && trigger().get("type").getAsString().equals("chat")
                        && decision.calls().size() == 1 && decision.calls().get(0).tool().equals("think_harder")) {
                    throw new CompletionException(new InferenceProvider.DecisionFormatException(
                            "For a player instruction, put a short chat reply restating the request before think_harder in the same calls array"));
                }
                state.turn.remove("formatFailures");
                state.turn.getAsJsonObject("observations").remove("format_feedback");
                JsonArray calls = new JsonArray();
                for (var call : decision.calls()) {
                    JsonObject encoded = new JsonObject(); encoded.addProperty("tool", call.tool()); encoded.add("arguments", call.arguments()); calls.add(encoded);
                }
                state.turn.add("calls", calls); state.turn.addProperty("cursor", 0);
                var remembered=identity();remembered.addProperty("route",route().name());remembered.add("trigger",trigger().deepCopy());remembered.addProperty("note",decision.note());remembered.add("calls",calls.deepCopy());state.history("decision",remembered);
                if(route()==InferenceProvider.Route.PLANNING && !decision.note().isBlank())state.workingNote=decision.note();
                if(decision.calls().isEmpty() && route()==InferenceProvider.Route.PLANNING && (trigger().get("type").getAsString().equals("chat") || state.hasWork() && !mechanicalWorkRunning())){
                    if(trigger().get("type").getAsString().equals("chat")){
                        var source=trigger();boolean recorded=java.util.stream.StreamSupport.stream(state.tasks().spliterator(),false).map(t->t.getAsJsonObject()).anyMatch(t->t.has("request") && t.getAsJsonObject("request").get("id").equals(source.get("id")));
                        if(!recorded){state.workRequest=source.deepCopy();var unfinished=new JsonObject();unfinished.addProperty("id","unresolved-request:"+source.get("id").getAsLong());unfinished.addProperty("goal",source.has("message")?source.get("message").getAsString():"Resolve the pending player request");unfinished.addProperty("status","QUEUED");unfinished.addProperty("priority",-1);state.mutate("task_upsert",unfinished);}
                    }
                    boolean corrective=!state.turn.getAsJsonObject("observations").has("decision_feedback") && state.inbox.stream().noneMatch(e->e.get("type").getAsString().equals("chat"));
                    state.noProgress++;var feedback=new JsonObject();feedback.addProperty("emptyDecisions",state.noProgress);feedback.addProperty("instruction","The previous decision made no calls and the request remains unresolved. Choose the next concrete action, reconcile a terminal task, ask a specific question, or explicitly wait for missing information. Do not silently discard the request.");state.turn.getAsJsonObject("observations").add("decision_feedback",feedback);continuePlanning();
                    if(!corrective){state.turn.add("calls",new JsonArray());state.turn.addProperty("cursor",0);}
                }else if(!decision.calls().isEmpty())state.noProgress=0;
                save.run();
            }
            while (state.turn != null && state.turn.has("calls")) {
                if (state.turn.get("cursor").getAsInt() >= state.turn.getAsJsonArray("calls").size()) {
                    state.turn = null; save.run(); break;
                }
                var call = pendingCall();
                switch (call.tool()) {
                    case "get_context" -> readContextBatch();
                    case "think_harder" -> {if(trigger().get("type").getAsString().equals("chat"))state.workRequest=trigger().deepCopy();continuePlanning();}
                    case "task_upsert", "task_remove", "remember", "forget", "personality" -> {state.mutate(call.tool(), call.arguments());var applied=identity();applied.addProperty("tool",call.tool());applied.add("arguments",call.arguments());applied.addProperty("applied",true);state.history("local_result",applied);advanceCall();}
                    case "wait" -> {
                        JsonObject args = call.arguments(); state.wakeEvent = args.has("event") ? AgentState.required(args, "event") : "any";
                        state.waitProgramState=state.wakeEvent.equals("program")?programWaitSignature():"";
                        long seconds = args.has("seconds") ? args.get("seconds").getAsBigDecimal().longValueExact() : -1;
                        if (args.has("seconds") && seconds < 0) throw new IllegalArgumentException("Wait duration must be nonnegative");
                        state.wakeAtMillis = seconds == -1 ? Long.MAX_VALUE : Math.addExact(clock.epochMillis(), Math.multiplyExact(seconds, 1000));
                        advanceCall();
                    }
                    default -> {
                        var id = requestId();
                        var args = call.arguments();
                        if (call.tool().equals("program") && (!args.has("id") || args.get("id").isJsonNull()))
                            args.addProperty("id", id.toString());
                        effect = sink.call(id, call.tool(), args, trigger().get("id").getAsLong());
                        return;
                    }
                }
                save.run();
            }
            if (clock.nanoTime() - nextStart < 0) return;
            if (state.turn == null) {
                if (!state.wakeEvent.isEmpty()) {
                    boolean timer = state.wakeAtMillis != Long.MAX_VALUE && state.wakeAtMillis <= clock.epochMillis();
                    boolean stopped=state.wakeEvent.equals("program") && !state.executingProgram.isEmpty() && !mechanicalWorkRunning() && !programWaitSignature().equals(state.waitProgramState);
                    boolean wake = stopped || timer || state.inbox.stream().anyMatch(e ->
                            Set.of("chat", "damage", "pickup").contains(e.get("type").getAsString())
                                    || state.wakeEvent.equals("any") || state.wakeEvent.equals(e.get("type").getAsString()));
                    if (!wake) return;
                    state.wakeEvent = ""; state.wakeAtMillis = 0;state.waitProgramState="";
                    if ((timer || stopped) && state.inbox.isEmpty()) {
                        JsonObject event = new JsonObject(); event.addProperty("id", 0); event.addProperty("type", stopped?"program_state":"timer"); state.enqueue(event);
                    }
                }
                if (state.inbox.isEmpty() && !state.hasWork()) return;
                // The acknowledged server job keeps progressing without another model decision.
                // New chat/events still wake immediately, and changed/terminal snapshots fall through.
                if(state.inbox.isEmpty() && mechanicalWorkRunning())return;
                JsonObject event;
                if (!state.inbox.isEmpty()) event = nextEvent();
                else { event = new JsonObject(); event.addProperty("id", 0); event.addProperty("type", "tasks"); }
                state.turn = new JsonObject(); state.turn.addProperty("id", UUID.randomUUID().toString());
                state.turn.add("trigger", event);state.turn.addProperty("route",Set.of("tasks","program","program_state","timer").contains(event.get("type").getAsString())?InferenceProvider.Route.PLANNING.name():InferenceProvider.Route.LIGHT.name());
                state.turn.addProperty("round", 0); state.turn.add("observations", new JsonObject()); state.cycleCounter++;
            }
            JsonObject frame = latest.deepCopy();var agent=state.context();frame.add("agent",agent);
            var observations=DecisionContext.forRequest(state.turn,frame,agent);
            var route = route();
            var model = provider.model(route, route == InferenceProvider.Route.LIGHT ? new InferenceProvider.ModelRoute(state.lightModel, state.lightEffort)
                    : new InferenceProvider.ModelRoute(state.planningModel, state.planningEffort));
            nextStart = clock.nanoTime() + INTERVAL_NANOS; state.lastInferenceMillis = clock.epochMillis();
            save.run(); // Preserve the request before inference can begin.
            calls++; if (route == InferenceProvider.Route.PLANNING) planningCalls++;
            long generation = outputGeneration; String turn = state.turn.get("id").getAsString();String triggerType=trigger().get("type").getAsString();long triggerId=trigger().get("id").getAsLong();
            inference = provider.infer(new InferenceProvider.Request(route, model, frame, trigger(), observations, output -> {
                if (generation != outputGeneration) return;
                var trace = new JsonObject(); trace.addProperty("turn", turn); trace.addProperty("route", route.name());
                trace.addProperty("triggerType",triggerType);trace.addProperty("triggerEvent",triggerId);
                trace.addProperty("model", model.model()); trace.addProperty("effort", model.effort()); trace.add("output", output.deepCopy());
                outputs.add(new Output(generation, trace));
            }));
        } catch (RuntimeException failure) {
            flushOutputs();
            Throwable cause=failure;while((cause instanceof java.util.concurrent.CompletionException || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause()!=null)cause=cause.getCause();
            if(cause instanceof InferenceProvider.DecisionFormatException && state.turn!=null && (!state.turn.has("formatFailures") || state.turn.get("formatFailures").getAsInt()<1)){
                var feedback=new JsonObject();feedback.addProperty("format_error",cause.getMessage());feedback.addProperty("instruction","The previous decision batch was rejected before any of its actions ran. Correct its JSON/arguments and return one paracraft.decide/paracraft_decide batch with note and calls. Optional fields may be omitted. Put game actions inside program.steps; use actual IDs and native argument types. Do not repeat acknowledged actions from earlier decisions.");
                state.turn.getAsJsonObject("observations").add("format_feedback",feedback);state.turn.addProperty("formatFailures",1);state.history("decision_rejected",feedback);inference=null;continueDecision();save.run();return;
            }
            lastError = failure.getCause() == null ? failure.toString() : failure.getCause().toString();
            state.history("error", lastError); abortFutures(); paused = true; state.paused = true; state.lastError = lastError; save.run();
        }
    }
    private InferenceProvider.Route route() { return InferenceProvider.Route.valueOf(state.turn.get("route").getAsString()); }
    private JsonObject nextEvent(){
        var first=state.inbox.remove(0);if(first.get("type").getAsString().equals("chat"))return first;
        var batch=new ArrayList<JsonObject>();batch.add(first);while(!state.inbox.isEmpty() && !state.inbox.get(0).get("type").getAsString().equals("chat"))batch.add(state.inbox.remove(0));
        var primary=batch.stream().filter(e->e.get("type").getAsString().equals("program")).findFirst().orElse(first).deepCopy();
        if(batch.size()>1){var others=new JsonArray();for(var event:batch)if(!event.get("id").equals(primary.get("id")))others.add(HistorySummary.of(event));primary.add("coalescedEvents",others);}return primary;
    }
    private JsonObject identity(){var value=new JsonObject();value.addProperty("turn",state.turn.get("id").getAsString());value.addProperty("round",state.turn.get("round").getAsInt());value.addProperty("atMillis",clock.epochMillis());if(state.turn.has("cursor"))value.add("cursor",state.turn.get("cursor").deepCopy());return value;}
    private String programWaitSignature(){
        if(latest!=null && latest.has("programs"))for(var item:latest.getAsJsonArray("programs")){var job=item.getAsJsonObject();if(job.has("id") && job.get("id").getAsString().equals(state.executingProgram))return job.get("state").getAsString()+"|"+mechanicalWorkRunning();}return "missing";
    }
    private boolean mechanicalWorkRunning(){
        if(state.executingProgram.isEmpty() || latest==null || !latest.has("programs"))return false;
        for(var item:latest.getAsJsonArray("programs")){var job=item.getAsJsonObject();if(job.has("id") && job.get("id").getAsString().equals(state.executingProgram)){
            String status=job.get("state").getAsString();if(status.equals("RUNNING"))return true;
            if(status.equals("QUEUED"))return java.util.stream.StreamSupport.stream(latest.getAsJsonArray("programs").spliterator(),false).anyMatch(p->p.getAsJsonObject().get("state").getAsString().equals("RUNNING"));
        }}return false;
    }
    private JsonObject trigger() { return state.turn.getAsJsonObject("trigger"); }
    private InferenceProvider.Call pendingCall() {
        JsonObject call = state.turn.getAsJsonArray("calls").get(state.turn.get("cursor").getAsInt()).getAsJsonObject();
        return new InferenceProvider.Call(call.get("tool").getAsString(), call.getAsJsonObject("arguments"));
    }
    private UUID requestId() {
        String key = state.turn.get("id").getAsString() + ":" + state.turn.get("round").getAsInt() + ":" + state.turn.get("cursor").getAsInt();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
    private void advanceCall() { state.turn.addProperty("cursor", state.turn.get("cursor").getAsInt() + 1); }
    private void continuePlanning() {
        state.turn.addProperty("route", InferenceProvider.Route.PLANNING.name());
        continueDecision();
    }
    private void readContextBatch() {
        while (state.turn.get("cursor").getAsInt() < state.turn.getAsJsonArray("calls").size()) {
            var args = pendingCall().arguments();
            DecisionContext.select(state.turn, args);
            var read = identity();
            read.add("arguments", args);
            state.history("context_read", read);
            advanceCall();
        }
        continueDecision();
    }
    private void continueDecision(){
        state.turn.addProperty("round", state.turn.get("round").getAsInt() + 1); state.turn.remove("calls"); state.turn.remove("cursor");
    }
    private void mergeProgram(JsonObject program) {
        var list = latest.has("programs") ? latest.getAsJsonArray("programs") : new JsonArray();
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).getAsJsonObject().get("id").equals(program.get("id"))) list.remove(i);
        list.add(program.deepCopy()); latest.add("programs", list);
    }
    private void abortFutures() {
        outputGeneration++; outputs.clear();
        if (inference != null) inference.cancel(true); if (effect != null) effect.cancel(true);
        inference = null; effect = null; // Durable turn/cursor intentionally remain for retry.
    }
    private void flushOutputs() {
        Output output;
        while ((output = outputs.poll()) != null) if (output.generation == outputGeneration)
            try { outputSink.accept(output.value); } catch (RuntimeException ignored) { /* Debug output cannot stop gameplay. */ }
    }
    public String status() {
        return !lastError.isEmpty() ? "Paused: " + lastError : paused ? "Paused" : !active ? "Disconnected / server paused"
                : inference != null ? "Thinking: " + route()
                : state.turn != null && !state.turn.has("calls") ? route() == InferenceProvider.Route.PLANNING ? "Planning" : "Thinking: LIGHT"
                : !state.wakeEvent.isEmpty() ? !state.hasWork() && !mechanicalWorkRunning() && state.wakeAtMillis==Long.MAX_VALUE && Set.of("any","chat").contains(state.wakeEvent)?"Idle":"Waiting: " + state.wakeEvent
                : state.turn != null || !state.inbox.isEmpty() || state.hasWork() || mechanicalWorkRunning() ? "Working" : "Idle";
    }
    public long calls() { return calls; }
    public long planningCalls() { return planningCalls; }
    public String providerLabel() { return provider.label(); }
    public boolean simulated() { return provider.simulated(); }
    public String lastError() { return lastError; }
    public void setPaused(boolean value) {
        paused = value; state.paused = value;
        if (value) abortFutures(); else { lastError = ""; state.lastError = ""; }
        save.run();
    }
    @Override public void close() { abortFutures(); closed = true; active = false; save.run(); }
}
