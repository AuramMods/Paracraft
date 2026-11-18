package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Follow and basic melee work use actual entity movement, native reach, cooldowns and attack hooks. */
public final class EntityWorkJob extends GameJob {
    private static final int MAX_PICKUP_TICKS = 100;
    private final UUID target;
    private final double distance;
    private final long duration;
    private final boolean collectLoot;
    private final Set<UUID> drops = new LinkedHashSet<>();
    private final List<ItemStack> acquired = new ArrayList<>();
    private long elapsed;
    private int attempts, hits, refused, sinceAttack = 20, pickupTicks;
    private boolean equipped, armed, defeated;
    private Runnable unobservePickups;

    public EntityWorkJob(UUID id, CompanionPlayer actor, String type, UUID target, double distance, long duration) {
        this(id, actor, type, target, distance, duration, type.equals("attack_entity"));
    }
    EntityWorkJob(UUID id, CompanionPlayer actor, String type, UUID target, double distance, long duration, boolean collectLoot) {
        super(id, type, actor); this.target = target; this.distance = distance; this.duration = duration;
        this.collectLoot = collectLoot;
        if (target.equals(actor.getUUID())) throw new IllegalArgumentException("An avatar cannot target itself");
    }
    @Override protected void advance() {
        if (defeated) { collectDrops(); return; }
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
        if (!living.isAlive()) { finishCombat(); return; }
        if (duration > 0 && elapsed >= duration) throw new IllegalStateException("Combat duration elapsed before the target was defeated");
        if (!equipped || armed && actor.getMainHandItem().isEmpty()) {
            CarriedItems.weapon(actor, living.getMobType()); equipped = true; armed = !actor.getMainHandItem().isEmpty(); phase = "equipping weapon"; return;
        }
        phase = "approaching melee target";
        if (!navigate(living.position(), Math.min(distance, 2.4))) return;
        if (!actor.canReach(living, 0) || !actor.hasLineOfSight(living)) throw new IllegalStateException("Melee target is obstructed or out of reach");
        actor.lookAt(EntityAnchorArgument.Anchor.EYES, living.getEyePosition()); phase = "waiting for attack cooldown";
        if (actor.getAttackStrengthScale(0.5F) < 1 || sinceAttack < Math.ceil(actor.getCurrentItemAttackStrengthDelay())) return;
        float before = living.getHealth();
        if (collectLoot) attackAndTrackDrops(living);
        else actor.attack(living);
        actor.swing(InteractionHand.MAIN_HAND); sinceAttack = 0; attempts++;
        if (living.getHealth() < before || !living.isAlive()) { hits++; refused = 0; } else refused++;
        result.addProperty("attempts", attempts); result.addProperty("hits", hits); result.addProperty("targetHealth", living.getHealth());
        result.addProperty("targetEntityTicks", living.tickCount); result.addProperty("targetInvulnerableTicks", living.invulnerableTime);
        result.addProperty("targetHurtTicks", living.hurtTime); result.addProperty("targetOnGround", living.onGround());
        result.addProperty("actorAttackDamage", actor.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE));
        if (collectLoot && !living.isAlive()) { finishCombat(); return; }
        if (refused >= 10) throw new IllegalStateException("Ten native attack attempts caused no observable damage");
    }
    private void attackAndTrackDrops(LivingEntity living) {
        List<LivingDropsEvent> events = new ArrayList<>();
        actor.attackObservingDrops(living, event -> {
            if (event.getEntity() == living) events.add(event);
        });
        for (LivingDropsEvent event : events) if (!event.isCanceled())
            for (ItemEntity drop : event.getDrops()) drops.add(drop.getUUID());
    }
    private void finishCombat() {
        defeated = true;
        result.addProperty("targetDefeated", true);
        if (collectLoot) collectDrops();
        else succeed(result);
    }
    private void collectDrops() {
        phase = "collecting combat drops";
        drops.removeIf(id -> !(actor.serverLevel().getEntity(id) instanceof ItemEntity item) || !item.isAlive());
        updateLootResult();
        if (drops.isEmpty()) { succeed(result); return; }
        if (++pickupTicks > MAX_PICKUP_TICKS) throw new IllegalStateException("Timed out collecting combat drops");
        if (unobservePickups == null) unobservePickups = actor.observeMeleePickups((item, stack) -> {
            if (!drops.contains(item.getUUID())) return false;
            if (!stack.isEmpty()) acquired.add(stack.copy());
            updateLootResult();
            return true;
        });
        var nearest = drops.stream().map(actor.serverLevel()::getEntity).map(ItemEntity.class::cast)
                .min(Comparator.comparingDouble(actor::distanceToSqr)).orElseThrow();
        if (!actor.getBoundingBox().inflate(1, 0.5, 1).intersects(nearest.getBoundingBox())) {
            navigate(nearest.position(), 0.7);
            return;
        }
        stopMoving();
        if (nearest.hasPickUpDelay()) return;
        int before = nearest.getItem().getCount();
        nearest.playerTouch(actor);
        if (nearest.isAlive() && nearest.getItem().getCount() == before)
            throw new IllegalStateException("Combat drop pickup was refused or inventory is full");
    }
    private void updateLootResult() {
        result.addProperty("acquired", acquired.stream().mapToLong(ItemStack::getCount).sum());
        result.add("acquiredStacks", JobJson.stacks(acquired));
        result.addProperty("dropsRemaining", drops.size());
    }
    @Override protected void onStop() {
        equipped = false;
        if (unobservePickups != null) { unobservePickups.run(); unobservePickups = null; }
    }
    @Override protected JsonObject writeData() {
        JsonObject data = new JsonObject(); data.addProperty("target", target.toString()); data.addProperty("distance", distance);
        data.addProperty("duration", duration); data.addProperty("elapsed", elapsed); data.addProperty("attempts", attempts);
        data.addProperty("hits", hits); data.addProperty("refused", refused); data.addProperty("sinceAttack", sinceAttack);
        data.addProperty("defeated", defeated); data.addProperty("pickupTicks", pickupTicks);
        JsonArray savedDrops = new JsonArray();
        drops.forEach(drop -> savedDrops.add(drop.toString()));
        data.add("drops", savedDrops);
        data.add("acquired", JobJson.stacks(acquired));
        return data;
    }
    @Override protected void readData(JsonObject data) {
        elapsed = data.get("elapsed").getAsLong(); attempts = data.get("attempts").getAsInt(); hits = data.get("hits").getAsInt();
        refused = data.get("refused").getAsInt(); sinceAttack = data.get("sinceAttack").getAsInt();
        if (data.has("defeated")) defeated = data.get("defeated").getAsBoolean();
        if (data.has("pickupTicks")) pickupTicks = data.get("pickupTicks").getAsInt();
        if (data.has("drops")) data.getAsJsonArray("drops").forEach(drop -> drops.add(UUID.fromString(drop.getAsString())));
        if (data.has("acquired")) acquired.addAll(JobJson.stacks(data.getAsJsonArray("acquired")));
    }
}
