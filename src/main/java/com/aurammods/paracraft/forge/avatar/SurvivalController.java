package com.aurammods.paracraft.forge.avatar;

import com.aurammods.paracraft.forge.execution.BedSpawnJob;
import com.aurammods.paracraft.forge.execution.CarriedItems;
import com.aurammods.paracraft.forge.execution.GameJob;
import com.aurammods.paracraft.forge.execution.JobCodec;
import com.aurammods.paracraft.forge.execution.JobJson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.properties.BedPart;
import java.util.UUID;

/** Mechanical survival runs on the server without asking an inference provider. */
final class SurvivalController implements AutoCloseable {
    private final AvatarManager avatars;
    private final AvatarEntry entry;
    private final CompanionPlayer actor;
    private final JsonObject state, initialBed;
    private GameJob bedJob;
    private boolean eating;
    private int priorSelected, foodRetry;

    SurvivalController(AvatarManager avatars, AvatarEntry entry, CompanionPlayer actor) {
        this.avatars = avatars; this.entry = entry; this.actor = actor;
        if (!entry.control.has("survival")) entry.control.add("survival", new JsonObject());
        state = entry.control.getAsJsonObject("survival");
        if (!state.has("initialBed")) {
            var bed = new JsonObject(); bed.addProperty("state", actor.getRespawnPosition() == null ? "searching" : "already_set");
            bed.add("center", JobJson.position(actor.blockPosition())); bed.addProperty("dimension", actor.serverLevel().dimension().location().toString());
            bed.addProperty("cursor", 0); bed.addProperty("occupied", 0); state.add("initialBed", bed);
        }
        initialBed = state.getAsJsonObject("initialBed");
        if (state.has("priorSelected")) actor.getInventory().selected = state.get("priorSelected").getAsInt();
        state.remove("priorSelected"); state.addProperty("mode", "idle");
        if (initialBed.has("job")) {
            try { bedJob = JobCodec.restore(actor, initialBed.getAsJsonObject("job")); }
            catch (IllegalArgumentException | IllegalStateException invalid) {
                initialBed.addProperty("state", "failed"); initialBed.addProperty("error", "Could not restore initial bed operation: " + invalid.getMessage()); initialBed.remove("job");
            }
        }
    }

    boolean busy() { return eating || bedJob != null && bedJob.state() == com.aurammods.paracraft.core.JobState.RUNNING; }
    void tick() {
        if (actor.isSpectator()) { close(); state.addProperty("mode", "spectator"); return; }
        if (eating) {
            if (!actor.isUsingItem()) finishEating();
            return;
        }
        if (foodRetry > 0) foodRetry--;
        if (foodRetry == 0 && actor.getFoodData().getFoodLevel() <= 14 && !actor.isSleeping() && !actor.isUsingItem()
                && !actor.isSpectator() && !actor.getAbilities().instabuild && startEating()) return;
        tickBed();
    }
    private boolean startEating() {
        int slot = -1, nutrition = 0;
        for (int index = 0; index < actor.getInventory().items.size() + 1; index++) {
            var stack = index == actor.getInventory().items.size() ? actor.getOffhandItem() : actor.getInventory().getItem(index);
            var food = stack.getFoodProperties(actor);
            if (stack.isEmpty() || food == null || food.getNutrition() <= nutrition) continue;
            if (food.getEffects().stream().anyMatch(effect -> effect.getFirst() != null && !effect.getFirst().getEffect().isBeneficial())) continue;
            nutrition = food.getNutrition(); slot = index;
        }
        foodRetry = 100;
        if (slot < 0) { state.addProperty("foodStatus", "No suitable carried food"); return false; }
        pauseBed(); avatars.jobs().holdForReflex(entry.id); priorSelected = actor.getInventory().selected;
        state.addProperty("priorSelected", priorSelected);
        InteractionHand hand = slot == actor.getInventory().items.size() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        if (hand == InteractionHand.MAIN_HAND) CarriedItems.select(actor, slot);
        actor.gameMode.useItem(actor, actor.serverLevel(), actor.getItemInHand(hand), hand);
        eating = actor.isUsingItem(); state.addProperty("mode", eating ? "eating" : "idle");
        state.addProperty("foodStatus", eating ? "Using carried food through native item behavior" : "Native item use ended immediately or was refused");
        if (!eating) finishEating();
        return eating;
    }
    private void finishEating() {
        eating = false; actor.getInventory().selected = priorSelected; state.remove("priorSelected"); state.addProperty("mode", "idle");
        state.addProperty("foodAfterUse", actor.getFoodData().getFoodLevel()); avatars.dirty();
    }
    private void tickBed() {
        if (initialBed.get("state").getAsString().equals("searching")) {
            // One initial local scan, over loaded blocks only. Never scan/generate a world to find a bed.
            BlockPos center = JobJson.position(initialBed.get("center")); int cursor = initialBed.get("cursor").getAsInt();
            if (!actor.serverLevel().dimension().location().toString().equals(initialBed.get("dimension").getAsString())) {
                initialBed.addProperty("state", "failed"); initialBed.addProperty("error", "Avatar left its initial bed-search dimension"); return;
            }
            for (int budget = 0; budget < 128 && cursor < 17 * 17 * 9; budget++, cursor++) {
                BlockPos pos = center.offset(cursor % 17 - 8, cursor / (17 * 17) - 4, cursor / 17 % 17 - 8);
                if (!actor.serverLevel().hasChunkAt(pos)) continue;
                var block = actor.serverLevel().getBlockState(pos);
                if (!(block.getBlock() instanceof BedBlock) || block.getValue(BedBlock.PART) != BedPart.HEAD) continue;
                if (block.getValue(BedBlock.OCCUPIED)) { initialBed.addProperty("occupied", initialBed.get("occupied").getAsInt() + 1); continue; }
                if (!BedBlock.canSetSpawn(actor.serverLevel())) continue;
                bedJob = new BedSpawnJob(UUID.randomUUID(), actor, pos); bedJob.pause(); initialBed.addProperty("state", "ready"); break;
            }
            initialBed.addProperty("cursor", cursor);
            if (bedJob == null && cursor >= 17 * 17 * 9) {
                initialBed.addProperty("state", "not_found"); initialBed.addProperty("error", "No available bed in the loaded 17x9x17 initial neighborhood; using normal fallback spawn");
                avatars.control().event(entry.id, "spawn_point", initialBed);
            }
        }
        if (bedJob == null || bedJob.state().terminal()) return;
        GameJob work = avatars.jobs().get(entry.id);
        if (entry.paused || work != null && !work.state().terminal()) { pauseBed(); return; }
        state.addProperty("mode", "establishing_spawn"); bedJob.resume(); bedJob.tick(); initialBed.add("job", bedJob.checkpoint());
        if (bedJob.state().terminal()) {
            initialBed.addProperty("state", bedJob.state().name().toLowerCase(java.util.Locale.ROOT));
            initialBed.add("result", bedJob.result()); initialBed.addProperty("error", bedJob.view().get("error").getAsString());
            state.addProperty("mode", "idle"); avatars.control().event(entry.id, "spawn_point", initialBed);
        }
    }
    private void pauseBed() {
        if (bedJob != null && !bedJob.state().terminal()) { bedJob.pause(); initialBed.add("job", bedJob.checkpoint()); }
        if (!eating) state.addProperty("mode", "idle");
    }
    void onDeath() {
        if (bedJob != null && !bedJob.state().terminal()) {
            bedJob.cancel("Avatar died"); initialBed.add("job", bedJob.checkpoint()); initialBed.addProperty("state", "cancelled");
        }
        close();
    }
    @Override public void close() { pauseBed(); if (eating) { actor.stopUsingItem(); finishEating(); } }
}
