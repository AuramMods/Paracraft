package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.InventoryAccess;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Normal bed interaction, followed by a brief sleep only when the game permits it. */
public final class BedSpawnJob extends GameJob {
    private BlockPos bed;
    private boolean interacted;
    private int sleepingTicks;
    public BedSpawnJob(UUID id, CompanionPlayer actor, BlockPos bed) { super(id, "set_spawn", actor); this.bed = bed.immutable(); }
    @Override protected void advance() {
        if (actor.isSpectator()) throw new IllegalStateException("Spectators cannot establish a bed spawn");
        if (!interacted) {
            phase = "approaching_bed";
            if (!navigate(Vec3.atBottomCenterOf(bed), 2.5)) return;
            var level = actor.serverLevel(); var state = level.getBlockState(bed);
            if (!(state.getBlock() instanceof BedBlock)) throw new IllegalStateException("Target is no longer a bed");
            if (state.getValue(BedBlock.PART) == BedPart.FOOT) { bed = bed.relative(state.getValue(BedBlock.FACING)); state = level.getBlockState(bed); }
            if (!(state.getBlock() instanceof BedBlock)) throw new IllegalStateException("Bed head is missing");
            if (!BedBlock.canSetSpawn(level)) throw new IllegalStateException("Beds cannot establish a spawn in this dimension");
            if (state.getValue(BedBlock.OCCUPIED)) throw new IllegalStateException("Bed is occupied");
            if (actor.blockActionRestricted(level, bed, actor.gameMode.getGameModeForPlayer())) throw new IllegalStateException("Game mode refused bed interaction");
            actor.clearSleepProblem(); InventoryAccess.open(actor, bed); interacted = true;
            result.add("bed", JobJson.position(bed)); result.addProperty("sleepEntered", actor.isSleeping());
            result.addProperty("sleepProblem", actor.sleepProblem());
            boolean set = bed.equals(actor.getRespawnPosition()) && actor.getRespawnDimension() == level.dimension();
            result.addProperty("spawnSet", set);
            if (!set) throw new IllegalStateException("Bed did not establish spawn" + (actor.sleepProblem().isEmpty() ? " (interaction or spawn hook refused)" : ": " + actor.sleepProblem()));
        }
        phase = "brief_sleep";
        if (actor.isSleeping() && ++sleepingTicks < 20) return;
        if (actor.isSleeping()) actor.stopSleepInBed(true, true);
        result.addProperty("sleepTicks", sleepingTicks); succeed(result);
    }
    @Override protected void onStop() { if (actor.isSleeping()) actor.stopSleepInBed(true, true); }
    @Override protected JsonObject writeData() {
        JsonObject data = new JsonObject(); data.add("bed", JobJson.position(bed)); data.addProperty("interacted", interacted); data.addProperty("sleepingTicks", sleepingTicks); return data;
    }
    @Override protected void readData(JsonObject data) {
        bed = JobJson.position(data.get("bed")); interacted = data.get("interacted").getAsBoolean(); sleepingTicks = data.get("sleepingTicks").getAsInt();
    }
}
