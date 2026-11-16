package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import java.util.UUID;

/** Native server dispatch, with optional observation of a later real teleport. */
public final class CommandJob extends GameJob {
    private final JsonObject specification;
    private boolean issued, callbackSuccess;
    private int nativeResult, callbacks;
    private long originSerial, elapsed;
    private String originDimension;
    private JsonArray messages=new JsonArray();
    private long omitted;
    private Runnable detach;
    public CommandJob(UUID id,CompanionPlayer actor,JsonObject specification){super(id,"execute_command",actor);this.specification=specification.deepCopy();}
    @Override protected boolean allowsDimensionChange(){return issued;}
    @Override protected boolean allowsRestoredDimension(JsonObject saved){return saved.getAsJsonObject("data").get("issued").getAsBoolean();}
    private void capture(String channel,Component message){
        if(messages.size()>=64){omitted++;return;}
        String text=message.getString(2049);JsonObject value=new JsonObject();value.addProperty("channel",channel);value.addProperty("text",text.length()>2048?text.substring(0,2048):text);value.addProperty("truncated",text.length()>2048);messages.add(value);
    }
    private void observe(){if(detach==null)detach=actor.observeMessages(message->capture("player",message));}
    @Override protected void advance(){
        observe();
        if(!issued){
            originSerial=actor.teleportSerial();originDimension=actor.serverLevel().dimension().location().toString();issued=true;
            result.addProperty("command",specification.get("command").getAsString());
            result.addProperty("actorUuid",actor.getUUID().toString());result.addProperty("actorName",actor.getGameProfile().getName());
            var output=new CommandSource(){
                @Override public void sendSystemMessage(Component message){capture("command",message);}
                @Override public boolean acceptsSuccess(){return true;}
                @Override public boolean acceptsFailure(){return true;}
                @Override public boolean shouldInformAdmins(){return actor.shouldInformAdmins();}
            };
            var source=actor.createCommandSourceStack().withSource(output).withCallback((context,success,value)->{callbacks++;callbackSuccess|=success;});
            // Do not gate execution on discovery, guessed permissions, or guessed future world state.
            nativeResult=actor.server.getCommands().performPrefixedCommand(source,specification.get("command").getAsString());
            refresh();
            if(!(callbacks>0?callbackSuccess:nativeResult>0))throw new IllegalStateException("Native command failed"+(messages.isEmpty()?" without output":": "+messages.get(0).getAsJsonObject().get("text").getAsString()));
            if(!specification.has("await") || specification.get("await").getAsString().equals("command")){result.addProperty("completion","command_return");succeed(result);return;}
        }
        elapsed++;phase="awaiting_teleport";refresh();
        if(actor.teleportSerial()>originSerial){
            boolean dimensionMatches=!specification.has("dimension") || actor.serverLevel().dimension().location().toString().equals(specification.get("dimension").getAsString());
            boolean positionMatches=!specification.has("destination") || actor.position().distanceTo(JobJson.vector(specification.get("destination")))<=tolerance();
            if(specification.has("playerUuid")){
                var player=actor.server.getPlayerList().getPlayer(UUID.fromString(specification.get("playerUuid").getAsString()));
                positionMatches&=player!=null && player.serverLevel()==actor.serverLevel() && actor.distanceTo(player)<=tolerance();
            }
            if(dimensionMatches && positionMatches){result.addProperty("completion","teleport_observed");succeed(result);return;}
        }
        if(!actor.serverLevel().dimension().location().toString().equals(originDimension) && actor.teleportSerial()==originSerial){cancel("Avatar changed dimension outside a recorded native teleport");return;}
        long timeout=specification.has("timeoutTicks")?specification.get("timeoutTicks").getAsLong():400;
        if(timeout>0 && elapsed>=timeout)throw new IllegalStateException("Command returned, but its requested teleport outcome was not observed before timeout");
    }
    private double tolerance(){return specification.has("tolerance")?specification.get("tolerance").getAsDouble():2;}
    private void refresh(){
        result.addProperty("issued",issued);result.addProperty("nativeResult",nativeResult);result.addProperty("callbackCount",callbacks);result.addProperty("callbackSuccess",callbackSuccess);
        result.addProperty("elapsedTicks",elapsed);result.addProperty("teleportObserved",issued && actor.teleportSerial()>originSerial);
        result.addProperty("dimension",actor.serverLevel().dimension().location().toString());result.add("position",JobJson.vector(actor.position()));
        result.add("messages",messages.deepCopy());result.addProperty("omittedMessages",omitted);
    }
    @Override protected void onStop(){if(detach!=null){detach.run();detach=null;}}
    @Override protected JsonObject writeData(){
        JsonObject data=new JsonObject();data.add("specification",specification.deepCopy());data.addProperty("issued",issued);data.addProperty("originSerial",originSerial);
        data.addProperty("originDimension",originDimension);data.addProperty("elapsed",elapsed);data.addProperty("nativeResult",nativeResult);
        data.addProperty("callbacks",callbacks);data.addProperty("callbackSuccess",callbackSuccess);data.add("messages",messages.deepCopy());data.addProperty("omitted",omitted);return data;
    }
    @Override protected void readData(JsonObject data){
        issued=data.get("issued").getAsBoolean();originSerial=data.get("originSerial").getAsLong();originDimension=data.has("originDimension") && !data.get("originDimension").isJsonNull()?data.get("originDimension").getAsString():null;
        elapsed=data.get("elapsed").getAsLong();nativeResult=data.get("nativeResult").getAsInt();callbacks=data.get("callbacks").getAsInt();callbackSuccess=data.get("callbackSuccess").getAsBoolean();messages=data.getAsJsonArray("messages").deepCopy();omitted=data.get("omitted").getAsLong();
    }
}
