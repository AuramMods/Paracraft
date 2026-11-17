package com.aurammods.paracraft.forge.avatar;

import com.aurammods.paracraft.forge.execution.BedSpawnJob;
import com.aurammods.paracraft.forge.execution.CarriedItems;
import com.aurammods.paracraft.forge.execution.GameJob;
import com.aurammods.paracraft.forge.execution.JobCodec;
import com.aurammods.paracraft.forge.execution.JobJson;
import com.aurammods.paracraft.core.BehaviorSettings;
import com.aurammods.paracraft.forge.execution.AirEscapeJob;
import com.aurammods.paracraft.forge.execution.DefenseJob;
import com.aurammods.paracraft.forge.execution.FleeJob;
import com.aurammods.paracraft.forge.execution.TravelJob;
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
    private GameJob emergency, patrol;
    private UUID threat;
    private int threatTicks, aggressionCooldown, oxygenRetry;
    private boolean eating;
    private int priorSelected, foodRetry;
    private final AmbientIdle ambient;

    SurvivalController(AvatarManager avatars, AvatarEntry entry, CompanionPlayer actor) {
        this.avatars = avatars; this.entry = entry; this.actor = actor;
        if (!entry.control.has("survival")) entry.control.add("survival", new JsonObject());
        state = entry.control.getAsJsonObject("survival");
        if (!state.has("behavior")) state.add("behavior", BehaviorSettings.defaults(actor.serverLevel().dimension().location().toString()));
        if (!state.getAsJsonObject("behavior").has("ambientIdle")) state.getAsJsonObject("behavior").addProperty("ambientIdle",true);
        if (!state.has("idle")) resetIdle();
        ambient=new AmbientIdle(avatars,entry,actor,state);
        actor.observeDamage(this::damage);
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

    boolean busy() { return eating || emergency != null && !emergency.state().terminal() || bedJob != null && bedJob.state() == com.aurammods.paracraft.core.JobState.RUNNING; }
    private JsonObject behavior() { return state.getAsJsonObject("behavior"); }
    void configure(JsonObject next) {
        boolean ambientChanged=!behavior().get("ambientIdle").equals(next.get("ambientIdle"));
        boolean routineChanged=!behavior().get("idle").equals(next.get("idle"));
        boolean reactionsChanged=!behavior().get("players").equals(next.get("players")) || !behavior().get("mobs").equals(next.get("mobs"));
        if(routineChanged){pausePatrol();patrol=null;resetIdle();}
        state.add("behavior", next.deepCopy());
        if(ambientChanged || routineChanged)ambient.configured();
        if (reactionsChanged && emergency != null && !(emergency instanceof AirEscapeJob)) { emergency.cancel("Behavior changed"); state.add("lastReflex", emergency.view()); emergency = null; state.remove("reflex"); }
        if(!eating && emergency==null)state.addProperty("mode","idle");
    }
    private void resetIdle() {
        JsonObject idle = new JsonObject(); idle.addProperty("state", "ready"); idle.addProperty("waypoint",0); idle.addProperty("waitRemaining",0); state.add("idle",idle);
    }
    private void damage(net.minecraft.world.damagesource.DamageSource source) {
        if (actor.isAlive() && source.getEntity() instanceof net.minecraft.world.entity.LivingEntity living && living != actor) {
            threat = living.getUUID(); threatTicks = 200;
        }
    }
    void tick() {
        if (actor.isSpectator()) { close(); state.addProperty("mode", "spectator"); return; }
        ambient.observeWork(avatars.jobs().hasUnfinished(entry.id));
        if (threatTicks > 0) threatTicks--; else threat = null;
        if (aggressionCooldown > 0) aggressionCooldown--;
        if (oxygenRetry > 0) oxygenRetry--; state.addProperty("oxygenRetryTicks",oxygenRetry);
        if (oxygenRetry == 0 && actor.needsAir() && actor.getAirSupply() <= Math.min(100,actor.getMaxAirSupply()/3) && !(emergency instanceof AirEscapeJob))
            beginReflex(new AirEscapeJob(actor), "oxygen_escape");
        if (emergency != null && !(emergency instanceof AirEscapeJob) && threat != null) reactToThreat();
        if (emergency != null) {
            emergency.tick(); state.add("reflex", emergency.view());
            if (!emergency.state().terminal()) return;
            state.add("lastReflex", emergency.view());
            var details = emergency.view(); details.addProperty("kind", emergency.type); avatars.control().event(entry.id,"reflex_result",details);
            aggressionCooldown = emergency.state() == com.aurammods.paracraft.core.JobState.FAILED ? 200 : 40;
            if(emergency.state()==com.aurammods.paracraft.core.JobState.FAILED)avatars.jobs().pause(entry.id);
            if (emergency instanceof AirEscapeJob && emergency.state() == com.aurammods.paracraft.core.JobState.FAILED) {
                // Leave dangerous work paused until planning/owner chooses a recovery.
                oxygenRetry = 100;
            }
            emergency = null; state.remove("reflex"); state.addProperty("mode","idle"); ambient.reanchor(); return;
        }
        if (reactToThreat()) return;
        if (eating) {
            if (!actor.isUsingItem()) finishEating();
            return;
        }
        if (foodRetry > 0) foodRetry--;
        if (foodRetry == 0 && (actor.getFoodData().getFoodLevel() <= 14 || actor.getHealth()<actor.getMaxHealth() && actor.getFoodData().getFoodLevel()<20) && !actor.isSleeping() && !actor.isUsingItem()
                && !actor.isSpectator() && !actor.getAbilities().instabuild && startEating()) return;
        tickBed();
        tickPatrol();
        if(bedJob==null || bedJob.state().terminal())ambient.tick();else ambient.suspend();
    }
    private void beginReflex(GameJob job, String mode) {
        if (emergency != null) emergency.cancel("A higher-priority survival reflex interrupted this response");
        pauseBed(); pausePatrol(); ambient.suspend(); if (eating) { actor.stopUsingItem(); finishEating(); }
        avatars.jobs().holdForReflex(entry.id); emergency = job; state.addProperty("mode",mode);
        state.add("reflex",job.view());
        var details = new JsonObject(); details.addProperty("kind",mode); details.addProperty("wake",mode.equals("oxygen_escape")); avatars.control().event(entry.id,"reflex_started",details);
    }
    private boolean reactToThreat() {
        var target = threat == null ? null : actor.serverLevel().getEntity(threat);
        net.minecraft.world.entity.LivingEntity living = target instanceof net.minecraft.world.entity.LivingEntity found ? found : null;
        if (living == null || !living.isAlive()) {
            threat = null; if (entry.paused || aggressionCooldown > 0) return false;
            if (BehaviorSettings.reaction(behavior(),true) != BehaviorSettings.Reaction.AGGRESSIVE && BehaviorSettings.reaction(behavior(),false) != BehaviorSettings.Reaction.AGGRESSIVE) return false;
            aggressionCooldown = 20;
            living = actor.serverLevel().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,actor.getBoundingBox().inflate(12),candidate -> {
                if (candidate == actor || !candidate.isAlive() || !actor.hasLineOfSight(candidate)) return false;
                boolean player = candidate instanceof net.minecraft.world.entity.player.Player;
                if (BehaviorSettings.reaction(behavior(),player) != BehaviorSettings.Reaction.AGGRESSIVE) return false;
                if (!player) return candidate instanceof net.minecraft.world.entity.monster.Enemy;
                var companion = avatars.entry(candidate.getUUID());
                return !entry.authority.canInstruct(candidate.getUUID()) && (companion == null || !entry.authority.canInstruct(companion.authority.owner()));
            }).stream().min(java.util.Comparator.comparingDouble(actor::distanceToSqr)).orElse(null);
            if (living == null) return false;
        }
        var response = BehaviorSettings.reaction(behavior(),living instanceof net.minecraft.world.entity.player.Player);
        threat = null;
        if (response == BehaviorSettings.Reaction.PASSIVE) {
            if (emergency != null) { emergency.cancel("Passive response to a new threat"); state.add("lastReflex",emergency.view()); emergency = null; state.remove("reflex"); }
            avatars.jobs().pause(entry.id); pausePatrol(); pauseBed(); ambient.suspend(); state.addProperty("mode","passive_stopped");
            var details = new JsonObject(); details.addProperty("reaction","passive"); details.addProperty("threat",living.getUUID().toString()); state.add("lastReaction",details); return true;
        }
        beginReflex(response == BehaviorSettings.Reaction.FLEE ? new FleeJob(actor,living.getUUID())
                : new DefenseJob(actor,living.getUUID()), response == BehaviorSettings.Reaction.FLEE ? "fleeing" : "defending");
        return true;
    }
    private void tickPatrol() {
        var settings = behavior().getAsJsonObject("idle"); var idle = state.getAsJsonObject("idle");
        if (!settings.get("enabled").getAsBoolean() || entry.paused || avatars.jobs().hasUnfinished(entry.id)
                || bedJob != null && !bedJob.state().terminal()) { pausePatrol(); return; }
        if (idle.get("state").getAsString().equals("failed")) return;
        if (!actor.serverLevel().dimension().location().toString().equals(settings.get("dimension").getAsString())) { failPatrol("Idle patrol belongs to another dimension"); return; }
        long remaining = idle.get("waitRemaining").getAsLong();
        if (remaining > 0) { idle.addProperty("waitRemaining",remaining-1); state.addProperty("mode","idle_waiting"); return; }
        int index = Math.floorMod(idle.get("waypoint").getAsInt(),settings.getAsJsonArray("waypoints").size());
        if (patrol == null) patrol = new TravelJob(UUID.randomUUID(),actor,JobJson.vector(settings.getAsJsonArray("waypoints").get(index)),false);
        state.addProperty("mode","idle_patrol"); idle.addProperty("state","traveling"); patrol.resume(); patrol.tick(); idle.add("progress",patrol.view());
        if (!patrol.state().terminal()) return;
        if (patrol.state() != com.aurammods.paracraft.core.JobState.SUCCEEDED) { failPatrol(patrol.view().get("error").getAsString()); return; }
        idle.addProperty("waypoint",(index+1)%settings.getAsJsonArray("waypoints").size()); idle.addProperty("waitRemaining",settings.get("waitTicks").getAsLong()); patrol = null;
        // Routine arrival deliberately emits no wake event and starts no inference.
    }
    private void failPatrol(String error) {
        pausePatrol(); var idle = state.getAsJsonObject("idle"); idle.addProperty("state","failed"); idle.addProperty("error",error);
        state.addProperty("mode","idle_failed");
        var details = new JsonObject(); details.addProperty("kind","idle_patrol"); details.addProperty("error",error); avatars.control().event(entry.id,"reflex_failure",details);
    }
    private void pausePatrol() {
        if (patrol != null) patrol.pause();
        if(state.has("mode") && (state.get("mode").getAsString().equals("idle_patrol") || state.get("mode").getAsString().equals("idle_waiting"))) {
            state.addProperty("mode","idle_paused"); state.getAsJsonObject("idle").addProperty("state","paused");
        }
    }
    void pauseRoutines() { pausePatrol(); pauseBed(); ambient.suspend(); }
    String mode(){return state.has("mode")?state.get("mode").getAsString():"idle";}
    private boolean startEating() {
        int slot = -1, nutrition = 0;
        for (int index = 0; index < actor.getInventory().items.size() + 1; index++) {
            var stack = index == actor.getInventory().items.size() ? actor.getOffhandItem() : actor.getInventory().getItem(index);
            var food = stack.getFoodProperties(actor);
            if (!CarriedItems.suitableFood(actor,stack) || food.getNutrition() <= nutrition) continue;
            nutrition = food.getNutrition(); slot = index;
        }
        foodRetry = 100;
        if (slot < 0) { state.addProperty("foodStatus", "No suitable carried food"); return false; }
        pauseBed(); pausePatrol(); ambient.suspend(); avatars.jobs().holdForReflex(entry.id); priorSelected = actor.getInventory().selected;
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
                var details=initialBed.deepCopy();details.addProperty("wake",false);avatars.control().event(entry.id, "spawn_point", details);
            }
        }
        if (bedJob == null || bedJob.state().terminal()) return;
        if (entry.paused || avatars.jobs().hasUnfinished(entry.id)) { pauseBed(); return; }
        ambient.suspend();pausePatrol();state.addProperty("mode", "establishing_spawn"); bedJob.resume(); bedJob.tick(); initialBed.add("job", bedJob.checkpoint());
        if (bedJob.state().terminal()) {
            initialBed.addProperty("state", bedJob.state().name().toLowerCase(java.util.Locale.ROOT));
            initialBed.add("result", bedJob.result()); initialBed.addProperty("error", bedJob.view().get("error").getAsString());
            state.addProperty("mode", "idle");ambient.reanchor();var details=initialBed.deepCopy();details.addProperty("wake",false);avatars.control().event(entry.id, "spawn_point", details);
        }
    }
    private void pauseBed() {
        if (bedJob != null && !bedJob.state().terminal()) { bedJob.pause(); initialBed.add("job", bedJob.checkpoint()); }
        if (state.has("mode") && state.get("mode").getAsString().equals("establishing_spawn")) state.addProperty("mode", "idle");
    }
    void onDeath() {
        if (bedJob != null && !bedJob.state().terminal()) {
            bedJob.cancel("Avatar died"); initialBed.add("job", bedJob.checkpoint()); initialBed.addProperty("state", "cancelled");
        }
        close();
    }
    @Override public void close() {
        pauseBed(); pausePatrol(); ambient.close();
        if (emergency != null) { emergency.cancel("Survival controller stopped"); state.add("lastReflex",emergency.view()); emergency = null; state.remove("reflex"); }
        if (eating) { actor.stopUsingItem(); finishEating(); }
        threat = null;
    }
}
