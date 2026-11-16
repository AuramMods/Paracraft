package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.core.program.ProgramMachine;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import com.google.gson.JsonObject;
import java.util.Set;
import java.util.UUID;

/** The interpreter sees only this narrow operation factory, not Minecraft internals. */
public final class ForgeOperations implements ProgramMachine.Factory {
    private final CompanionPlayer actor;
    public ForgeOperations(CompanionPlayer actor) { this.actor = actor; }
    @Override public Set<String> operations() {
        return Set.of("travel", "search_containers", "collect_items", "deposit_items", "deliver_items",
                "chat", "report_result", "report_failure", "fail", "wait_ticks");
    }
    @Override public void validate(JsonObject specification) { OperationSyntax.validate(specification); }
    @Override public ProgramMachine.Operation start(UUID id, JsonObject specification, ProgramMachine.Context context, JsonObject checkpoint) {
        GameJob job = checkpoint == null ? create(id, specification, context) : JobCodec.restore(actor, checkpoint);
        if (!id.equals(job.id) || !specification.get("op").getAsString().equals(job.type))
            throw new IllegalArgumentException("Pending operation checkpoint does not match the program instruction");
        job.resume();
        return new ProgramMachine.Operation() {
            @Override public ProgramMachine.Update tick() {
                job.tick(); JsonObject view = job.view();
                return new ProgramMachine.Update(job.state(), job.result(), view.get("error").getAsString());
            }
            @Override public JsonObject checkpoint() { return job.checkpoint(); }
            @Override public JsonObject view() { return job.view(); }
            @Override public void pause() { job.pause(); }
            @Override public void cancel(String reason) { job.cancel(reason); }
        };
    }
    private GameJob create(UUID id, JsonObject spec, ProgramMachine.Context context) {
        String op = spec.get("op").getAsString();
        return switch (op) {
            case "travel" -> new TravelJob(id, actor, JobJson.vector(spec.get("destination")));
            case "search_containers" -> { checkScope(spec); yield new SearchJob(id, actor, new ItemSelector(spec.get("item").getAsString())); }
            case "collect_items" -> {
                checkScope(spec);
                long quantity = !spec.has("quantity") || spec.get("quantity").getAsString().equals("all") ? -1 : spec.get("quantity").getAsLong();
                CollectJob job = new CollectJob(id, actor, new ItemSelector(spec.get("item").getAsString()), quantity);
                if (spec.has("containersFrom")) job.useObservation(named(context, spec.get("containersFrom").getAsString()));
                yield job;
            }
            case "deposit_items" -> new DepositJob(id, actor, JobJson.position(spec.get("destination")),
                    new ItemSelector(spec.get("item").getAsString()), spec.get("quantity").getAsInt());
            case "deliver_items" -> {
                JsonObject items = named(context, spec.get("itemsFrom").getAsString());
                if (!items.has("acquiredStacks")) throw new IllegalArgumentException("Delivery requires an acquired-stack result");
                String recipient = spec.get("recipient").isJsonObject() ? spec.getAsJsonObject("recipient").get("playerUuid").getAsString() : spec.get("recipient").getAsString();
                yield new DeliverJob(id, actor, UUID.fromString(recipient), JobJson.stacks(items.getAsJsonArray("acquiredStacks")));
            }
            case "wait_ticks" -> new WaitJob(id, actor, spec.get("ticks").getAsLong());
            case "chat", "report_result", "report_failure", "fail" -> new ImmediateJob(id, actor, spec, context);
            default -> throw new IllegalArgumentException("Unsupported operation: " + op);
        };
    }
    private static JsonObject named(ProgramMachine.Context context, String name) {
        if (!context.results().has(name) || !context.results().get(name).isJsonObject()) throw new IllegalArgumentException("Missing named result: " + name);
        return context.results().getAsJsonObject(name);
    }
    private static void checkScope(JsonObject spec) {
        if (!spec.has("scope")) return;
        JsonObject scope = spec.getAsJsonObject("scope");
        if (!scope.get("kind").getAsString().equals("neighbor_chunks") || scope.get("radius").getAsInt() != 1)
            throw new IllegalArgumentException("This inventory operation supports the nine-chunk neighborhood");
    }
}
