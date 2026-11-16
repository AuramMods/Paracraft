package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Explicit permission to physically enter one selected native portal; transitions remain native. */
public final class PortalJob extends GameJob {
    private final JsonObject specification;
    private final BlockPos portal;
    private String originDimension;
    private long elapsed;
    private boolean entered;
    public PortalJob(UUID id,CompanionPlayer actor,JsonObject specification){super(id,"use_portal",actor);this.specification=specification.deepCopy();portal=JobJson.position(specification.get("position"));}
    @Override protected boolean allowsDimensionChange(){return originDimension!=null;}
    @Override protected boolean allowsRestoredDimension(JsonObject saved){return transitionPending(saved.getAsJsonObject("data"));}
    static boolean transitionPending(JsonObject data){return data.has("originDimension") && !data.get("originDimension").isJsonNull();}
    @Override protected void advance(){
        if(originDimension==null)originDimension=actor.serverLevel().dimension().location().toString();
        elapsed++;refresh();
        if(!originDimension.equals(actor.serverLevel().dimension().location().toString())){
            entered=true;refresh();adoptDimension();
            if(specification.has("dimension") && !specification.get("dimension").getAsString().equals(actor.serverLevel().dimension().location().toString()))throw new IllegalStateException("Portal arrived in a different dimension than requested");
            result.addProperty("completion","portal_transition_observed");succeed(result);return;
        }
        if(actor.wonGame){phase="returning_from_end";return;}
        var state=actor.serverLevel().getBlockState(portal);
        if(!state.is(Blocks.NETHER_PORTAL) && !state.is(Blocks.END_PORTAL))throw new IllegalStateException("Selected block is not a supported native Nether or End portal");
        actor.grantPortal(id,portal);
        if(actor.getBoundingBox().intersects(new AABB(portal))){entered=true;stopMoving();phase=actor.isOnPortalCooldown()?"portal_cooldown":"inside_portal";}
        else{phase="approaching_portal";navigate(Vec3.atBottomCenterOf(portal),0.15);}
        refresh();long timeout=specification.has("timeoutTicks")?specification.get("timeoutTicks").getAsLong():600;
        if(timeout>0 && elapsed>=timeout)throw new IllegalStateException("Selected portal did not produce a dimension transition before timeout");
    }
    private void refresh(){result.add("portal",JobJson.position(portal));result.addProperty("entered",entered);result.addProperty("elapsedTicks",elapsed);result.addProperty("dimension",actor.serverLevel().dimension().location().toString());result.add("position",JobJson.vector(actor.position()));}
    @Override protected void onStop(){actor.revokePortal(id);}
    @Override protected JsonObject writeData(){var data=new JsonObject();data.add("specification",specification.deepCopy());data.addProperty("originDimension",originDimension);data.addProperty("elapsed",elapsed);data.addProperty("entered",entered);return data;}
    @Override protected void readData(JsonObject data){originDimension=data.has("originDimension") && !data.get("originDimension").isJsonNull()?data.get("originDimension").getAsString():null;elapsed=data.get("elapsed").getAsLong();entered=data.get("entered").getAsBoolean();}
}
