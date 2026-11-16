package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonObject;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import java.util.UUID;

/** Follow and basic melee work use actual entity movement, native reach, cooldowns and attack hooks. */
public final class EntityWorkJob extends GameJob {
    private final UUID target;
    private final double distance;
    private final long duration;
    private long elapsed;
    private int attempts, hits, refused, sinceAttack = 20;
    private boolean equipped, armed;
    public EntityWorkJob(UUID id, CompanionPlayer actor, String type, UUID target, double distance, long duration) {
        super(id, type, actor); this.target = target; this.distance = distance; this.duration = duration;
        if (target.equals(actor.getUUID())) throw new IllegalArgumentException("An avatar cannot target itself");
    }
    @Override protected void advance() {
        var entity = actor.serverLevel().getEntity(target);
        if (entity == null || entity.isRemoved()) throw new IllegalStateException("Target is unavailable in this dimension");
        elapsed++; sinceAttack++; result.addProperty("elapsedTicks", elapsed); result.addProperty("target", target.toString());
        if (type.equals("follow")) {
            if (!entity.isAlive()) throw new IllegalStateException("Follow target is no longer alive");
            phase = "following"; navigate(entity.position(), distance); actor.lookAt(EntityAnchorArgument.Anchor.EYES, entity.getEyePosition());
            if (duration > 0 && elapsed >= duration) succeed(result); return;
        }
        if (!(entity instanceof LivingEntity living)) throw new IllegalStateException("Melee target is not a living entity");
        result.addProperty("targetHealth", living.getHealth());
        if (!living.isAlive()) { result.addProperty("targetDefeated", true); succeed(result); return; }
        if (duration > 0 && elapsed >= duration) throw new IllegalStateException("Combat duration elapsed before the target was defeated");
        if (!equipped || armed && actor.getMainHandItem().isEmpty()) {
            CarriedItems.weapon(actor, living.getMobType()); equipped = true; armed = !actor.getMainHandItem().isEmpty(); phase = "equipping weapon"; return;
        }
        phase = "approaching melee target";
        if (!navigate(living.position(), Math.min(distance, 2.4))) return;
        if (!actor.canReach(living, 0) || !actor.hasLineOfSight(living)) throw new IllegalStateException("Melee target is obstructed or out of reach");
        actor.lookAt(EntityAnchorArgument.Anchor.EYES, living.getEyePosition()); phase = "waiting for attack cooldown";
        if (actor.getAttackStrengthScale(0.5F) < 1 || sinceAttack < Math.ceil(actor.getCurrentItemAttackStrengthDelay())) return;
        float before = living.getHealth(); actor.attack(living); actor.swing(InteractionHand.MAIN_HAND); sinceAttack = 0; attempts++;
        if (living.getHealth() < before || !living.isAlive()) { hits++; refused = 0; } else refused++;
        result.addProperty("attempts", attempts); result.addProperty("hits", hits); result.addProperty("targetHealth", living.getHealth());
        if (refused >= 10) throw new IllegalStateException("Ten native attack attempts caused no observable damage");
    }
    @Override protected void onStop() { equipped = false; }
    @Override protected JsonObject writeData() {
        JsonObject data = new JsonObject(); data.addProperty("target", target.toString()); data.addProperty("distance", distance);
        data.addProperty("duration", duration); data.addProperty("elapsed", elapsed); data.addProperty("attempts", attempts);
        data.addProperty("hits", hits); data.addProperty("refused", refused); data.addProperty("sinceAttack", sinceAttack); return data;
    }
    @Override protected void readData(JsonObject data) {
        elapsed = data.get("elapsed").getAsLong(); attempts = data.get("attempts").getAsInt(); hits = data.get("hits").getAsInt();
        refused = data.get("refused").getAsInt(); sinceAttack = data.get("sinceAttack").getAsInt();
    }
}
