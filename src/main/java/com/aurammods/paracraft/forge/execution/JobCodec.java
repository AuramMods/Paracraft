package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.core.program.ProgramMachine;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import com.google.gson.JsonObject;
import java.util.UUID;

public final class JobCodec {
    private JobCodec() { }
    public static GameJob restore(CompanionPlayer actor, JsonObject checkpoint) {
        if (com.aurammods.paracraft.core.JobState.valueOf(checkpoint.get("state").getAsString()).terminal())
            return new RecordedJob(actor, checkpoint);
        UUID id = UUID.fromString(checkpoint.get("id").getAsString());
        String type = checkpoint.get("type").getAsString(); JsonObject data = checkpoint.getAsJsonObject("data");
        if(!actor.serverLevel().dimension().location().toString().equals(checkpoint.get("dimension").getAsString())
                && !(type.equals("execute_command") && data.get("issued").getAsBoolean()) && !(type.equals("use_portal") && PortalJob.transitionPending(data)) && !(type.equals("program") && pendingCommand(data.getAsJsonObject("machine"))))return new ParkedJob(actor,checkpoint);
        GameJob job = switch (type) {
            case "craft_item" -> new CraftJob(id, actor, data.getAsJsonObject("specification"));
            case "search_recipes" -> new RecipeSearchJob(id, actor, data.getAsJsonObject("specification"));
            case "open_inventory", "take_items" -> new ContainerJob(id,actor,type,data.getAsJsonObject("specification"));
            case "search_blocks" -> new BlockSearchJob(id,actor,data.getAsJsonObject("specification"));
            case "use_portal" -> new PortalJob(id,actor,data.getAsJsonObject("specification"));
            case "execute_command" -> new CommandJob(id,actor,data.getAsJsonObject("specification"));
            case "discover_commands" -> new CommandDiscoveryJob(id,actor,data.get("prefix").getAsString());
            case "set_spawn" -> new BedSpawnJob(id, actor, JobJson.position(data.get("bed")));
            case "travel" -> new TravelJob(id, actor, JobJson.vector(data.get("destination")));
            case "search_containers" -> new SearchJob(id, actor, new ItemSelector(data.getAsJsonObject("search").get("selector").getAsString()));
            case "collect_items" -> new CollectJob(id, actor, new ItemSelector(data.get("selector").getAsString()), data.get("quantity").getAsLong());
            case "deposit_items" -> new DepositJob(id, actor, JobJson.position(data.get("destination")), new ItemSelector(data.get("selector").getAsString()), data.get("quantity").getAsInt());
            case "deliver_items" -> new DeliverJob(id, actor, UUID.fromString(data.get("recipient").getAsString()), JobJson.stacks(data.getAsJsonArray("remaining")));
            case "wait_ticks" -> new WaitJob(id, actor, data.get("remaining").getAsLong());
            case "program" -> new ProgramJob(id, actor, data.getAsJsonObject("machine").getAsJsonObject("program"));
            case "mine_blocks", "farm" -> new BlockWorkJob(id, actor, type, data.getAsJsonObject("specification"));
            case "place_blocks" -> new PlaceBlocksJob(id, actor, data.getAsJsonArray("placements"));
            case "equip" -> new EquipJob(id, actor, data.get("item").getAsString(), data.get("slot").getAsString());
            case "unequip" -> new UnequipJob(id,actor,data.get("slot").getAsString());
            case "drop_items" -> new DropItemsJob(id,actor,data.getAsJsonObject("specification"));
            case "follow", "attack_entity" -> new EntityWorkJob(id, actor, type, UUID.fromString(data.get("target").getAsString()), data.get("distance").getAsDouble(), data.get("duration").getAsLong());
            case "chat", "report_result", "report_failure", "fail" -> new ImmediateJob(id, actor, data.getAsJsonObject("specification"),
                    new ProgramMachine.Context(data.getAsJsonObject("results"), data.has("caught") ? data.getAsJsonObject("caught") : null));
            default -> throw new IllegalArgumentException("Unsupported saved operation: " + type);
        };
        job.restore(checkpoint); return job;
    }
    static boolean pendingCommand(JsonObject machine){
        if(!machine.has("pending"))return false;var pending=machine.getAsJsonObject("pending");
        String type=pending.get("type").getAsString();return type.equals("execute_command") && pending.getAsJsonObject("data").get("issued").getAsBoolean()
                || type.equals("use_portal") && PortalJob.transitionPending(pending.getAsJsonObject("data"));
    }
}
