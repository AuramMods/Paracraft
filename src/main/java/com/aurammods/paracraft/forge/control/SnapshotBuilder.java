package com.aurammods.paracraft.forge.control;

import com.aurammods.paracraft.forge.avatar.AvatarEntry;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

final class SnapshotBuilder {
    private SnapshotBuilder() { }
    static JsonObject build(AvatarManager avatars, AvatarEntry entry, JsonObject control) {
        JsonObject frame = new JsonObject(); frame.addProperty("schema", 1);
        JsonArray operations = new JsonArray();
        // Capabilities are adapter-owned; a provider cannot invent executable operations.
        new com.aurammods.paracraft.forge.execution.ForgeOperations(avatars.actor(entry.id)).operations().stream().sorted().forEach(operations::add);
        frame.add("operations", operations);
        frame.addProperty("worldId", avatars.worldId().toString()); frame.addProperty("ownerId", entry.authority.owner().toString());
        frame.addProperty("avatarId", entry.id.toString()); frame.addProperty("name", entry.name); frame.addProperty("paused", entry.paused);
        frame.addProperty("lifeRevision", control.has("lifeRevision") ? control.get("lifeRevision").getAsLong() : 0);
        if (entry.authority.session() != null) frame.addProperty("session", entry.authority.session().toString());
        frame.add("events", control.getAsJsonArray("events").deepCopy()); frame.add("compactedEvents", control.getAsJsonObject("compactedEvents").deepCopy());
        if (control.has("controllerStatus")) frame.add("controllerStatus", control.get("controllerStatus").deepCopy());
        if (control.has("survival")) frame.add("survival", control.get("survival").deepCopy());
        if (control.has("lifecycle")) frame.add("lifecycle", control.get("lifecycle").deepCopy());
        JsonArray programs = new JsonArray(); avatars.jobs().requests(entry.id).forEach(job -> programs.add(job.view())); frame.add("programs", programs);
        JsonArray trusted = new JsonArray(); entry.authority.trusted().stream().sorted().forEach(p -> trusted.add(p.toString())); frame.add("trustedPlayers", trusted);
        JsonArray observations = new JsonArray();
        for (var receipt : control.getAsJsonObject("receipts").entrySet()) {
            JsonObject value = receipt.getValue().getAsJsonObject();
            if (value.getAsJsonObject("signature").get("tool").getAsString().equals("observe") && value.getAsJsonObject("reply").get("ok").getAsBoolean())
                observations.add(value.getAsJsonObject("reply").getAsJsonObject("value").deepCopy());
        }
        while (observations.size() > 4) observations.remove(0); frame.add("inventoryObservations", observations);
        var actor = avatars.actor(entry.id); frame.addProperty("online", actor != null); if (actor == null) return frame;
        frame.addProperty("serverTick", actor.server.getTickCount()); frame.addProperty("observedAt", actor.level().getGameTime());
        frame.addProperty("dimension", actor.serverLevel().dimension().location().toString()); frame.add("position", vector(actor.position()));
        frame.addProperty("health", actor.getHealth()); frame.addProperty("maxHealth", actor.getMaxHealth()); frame.addProperty("alive", actor.isAlive());
        frame.addProperty("food", actor.getFoodData().getFoodLevel()); frame.addProperty("saturation", actor.getFoodData().getSaturationLevel());
        frame.addProperty("oxygen", actor.getAirSupply()); frame.addProperty("maxOxygen", actor.getMaxAirSupply());
        frame.addProperty("canBreathe", !actor.needsAir()); frame.addProperty("canRefillAir", actor.canRefillAir());
        frame.addProperty("experienceLevel", actor.experienceLevel); frame.addProperty("experienceProgress", actor.experienceProgress);
        frame.addProperty("selectedSlot", actor.getInventory().selected);
        JsonArray inventory = new JsonArray();
        for (int slot = 0; slot < actor.getInventory().getContainerSize(); slot++) {
            ItemStack item = actor.getInventory().getItem(slot); if (item.isEmpty()) continue;
            JsonObject stack = stack(item); stack.addProperty("slot", slot); inventory.add(stack);
        }
        frame.add("inventory", inventory); JsonObject equipment = new JsonObject();
        for (EquipmentSlot slot : EquipmentSlot.values()) equipment.add(slot.getName(), stack(actor.getItemBySlot(slot)));
        frame.add("equipment", equipment); JsonArray effects = new JsonArray();
        actor.getActiveEffects().forEach(effect -> {
            JsonObject value = new JsonObject(); value.addProperty("id", BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect()).toString());
            value.addProperty("amplifier", effect.getAmplifier()); value.addProperty("duration", effect.getDuration()); effects.add(value);
        });
        frame.add("effects", effects);
        JsonObject spawn = new JsonObject(); spawn.addProperty("dimension", actor.getRespawnDimension().location().toString());
        if (actor.getRespawnPosition() != null) spawn.add("position", vector(Vec3.atCenterOf(actor.getRespawnPosition()))); frame.add("spawn", spawn);
        var nearby = actor.serverLevel().getEntities(actor, actor.getBoundingBox().inflate(24));
        JsonArray entities = new JsonArray();
        nearby.stream().sorted(java.util.Comparator.comparingDouble(actor::distanceToSqr)).limit(64).forEach(entity -> {
            JsonObject value = new JsonObject(); value.addProperty("uuid", entity.getUUID().toString());
            value.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()); value.addProperty("name", entity.getName().getString());
            value.add("position", vector(entity.position())); if (entity instanceof LivingEntity living) value.addProperty("health", living.getHealth()); entities.add(value);
        });
        frame.add("nearbyEntities", entities); frame.addProperty("nearbyEntityTotal", nearby.size()); frame.addProperty("nearbyEntitiesTruncated", nearby.size() > entities.size());
        return frame;
    }
    private static JsonObject stack(ItemStack stack) {
        JsonObject value = new JsonObject(); if (stack.isEmpty()) { value.addProperty("empty", true); return value; }
        value.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()); value.addProperty("count", stack.getCount());
        value.addProperty("damage", stack.getDamageValue()); value.addProperty("maxDamage", stack.getMaxDamage());
        value.addProperty("snbt", stack.save(new CompoundTag()).toString()); return value;
    }
    private static JsonArray vector(Vec3 position) { JsonArray value = new JsonArray(); value.add(position.x); value.add(position.y); value.add(position.z); return value; }
}
