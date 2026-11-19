package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.MobType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.ToolActions;
import java.util.function.Predicate;

public final class CarriedItems {
    private CarriedItems() { }
    public static boolean suitableFood(CompanionPlayer actor, ItemStack stack) {
        var food = stack.getFoodProperties(actor);
        return !stack.isEmpty() && food != null && food.getNutrition() > 0
                && food.getEffects().stream().noneMatch(effect -> effect.getFirst() != null && !effect.getFirst().getEffect().isBeneficial());
    }
    public static boolean hasFood(CompanionPlayer actor) {
        return actor.getInventory().items.stream().anyMatch(stack -> suitableFood(actor,stack)) || suitableFood(actor,actor.getOffhandItem());
    }
    static int find(CompanionPlayer actor, ItemSelector selector) {
        return find(actor, selector::matches);
    }
    public static int find(CompanionPlayer actor, Predicate<ItemStack> matches) {
        var inventory = actor.getInventory();
        if (matches.test(actor.getMainHandItem())) return inventory.selected;
        for (int i = 0; i < inventory.items.size(); i++)
            if (matches.test(inventory.getItem(i))) return i;
        return matches.test(actor.getOffhandItem()) ? Inventory.SLOT_OFFHAND : -1;
    }
    static ItemStack creativeStock(CompanionPlayer actor, ItemSelector selector) {
        return actor.isCreative() ? ServerEvents.avatars(actor.server).creativeItems().find(actor, selector) : ItemStack.EMPTY;
    }
    static IllegalStateException missing(ItemSelector selector) {
        return new IllegalStateException("No available item matches " + selector.expression());
    }
    static int place(CompanionPlayer actor, BlockPos position, ItemSelector selector) {
        int slot = find(actor, selector);
        if (slot >= 0) {
            select(actor, slot);
            return NativePlacement.place(actor, position);
        }
        ItemStack stock = creativeStock(actor, selector);
        if (stock.isEmpty()) throw missing(selector);
        ItemStack previous = actor.getMainHandItem();
        actor.setItemInHand(InteractionHand.MAIN_HAND, stock);
        try { return NativePlacement.place(actor, position); }
        finally { actor.setItemInHand(InteractionHand.MAIN_HAND, previous); }
    }
    public static void select(CompanionPlayer actor, int slot) {
        var inventory = actor.getInventory();
        if (slot == Inventory.SLOT_OFFHAND) EquipmentAccess.swapOffhand(actor, inventory.selected);
        else if (Inventory.isHotbarSlot(slot)) inventory.selected = slot;
        else {
            ItemStack previous = inventory.getItem(inventory.selected);
            inventory.setItem(inventory.selected, inventory.getItem(slot)); inventory.setItem(slot, previous);
        }
        inventory.setChanged();
    }
    public static Runnable selectForUse(CompanionPlayer actor, int slot) {
        var inventory = actor.getInventory();
        int originalSlot = inventory.selected;
        var original = inventory.getSelected();
        select(actor, slot);
        if (Inventory.isHotbarSlot(slot))
            return () -> { if (inventory.selected == slot) select(actor, originalSlot); };
        return () -> {
            if (inventory.selected == originalSlot && inventory.getItem(slot) == original) select(actor, slot);
        };
    }
    public static Runnable holdOffhand(CompanionPlayer actor, int slot) {
        var inventory = actor.getInventory();
        var held = inventory.getItem(slot);
        var previous = actor.getOffhandItem();
        if (!EquipmentAccess.trySwapOffhand(actor, slot)) return null;
        return () -> {
            var now = actor.getOffhandItem();
            if (now != held && !(now.isEmpty() && held.isEmpty())) return;
            var displaced = inventory.getItem(slot);
            if (displaced == previous || displaced.isEmpty() && previous.isEmpty()) EquipmentAccess.trySwapOffhand(actor, slot);
            else if (previous.isEmpty() && !now.isEmpty()) {
                int free = inventory.getFreeSlot();
                if (free >= 0) EquipmentAccess.trySwapOffhand(actor, free);
            }
        };
    }
    /** Probe actual Forge harvest/break-speed behavior with each carried candidate, restoring both slots. */
    static void miningTool(CompanionPlayer actor, BlockPos position, BlockState state) {
        if (actor.isCreative()) return;
        var inventory = actor.getInventory(); int hand = inventory.selected, best = -1; float speed = -1;
        ItemStack original = inventory.getItem(hand);
        if (state.canHarvestBlock(actor.serverLevel(), position, actor)) {
            float current = state.getDestroyProgress(actor, actor.serverLevel(), position);
            if (!Float.isNaN(current) && current >= 0) { speed = current; best = hand; }
        }
        try {
            for (int i = 0; i <= inventory.items.size(); i++) {
                int slot = i == inventory.items.size() ? Inventory.SLOT_OFFHAND : i;
                if (slot == hand) continue;
                ItemStack candidate = inventory.getItem(slot);
                inventory.setItem(hand, candidate);
                inventory.setItem(slot, original);
                try {
                    if (!state.canHarvestBlock(actor.serverLevel(), position, actor)) continue;
                    float rate = state.getDestroyProgress(actor, actor.serverLevel(), position);
                    if (Float.isFinite(rate) && rate > speed) { speed = rate; best = slot; }
                    else if (rate == Float.POSITIVE_INFINITY && speed != Float.POSITIVE_INFINITY) { speed = rate; best = slot; }
                } finally { inventory.setItem(slot, candidate); inventory.setItem(hand, original); }
            }
        } finally { inventory.setItem(hand, original); }
        if (best < 0) throw new IllegalStateException("No carried tool can harvest " + state.getBlock().getDescriptionId());
        select(actor, best);
    }
    static void weapon(CompanionPlayer actor, MobType targetType) {
        var inventory = actor.getInventory();
        int best = inventory.selected;
        double damageBase = actor.getAttributeBaseValue(Attributes.ATTACK_DAMAGE);
        double speedBase = actor.getAttributeBaseValue(Attributes.ATTACK_SPEED);
        double score = meleeScore(inventory.getSelected(), targetType, damageBase, speedBase);
        for (int i = 0; i <= inventory.items.size(); i++) {
            int slot = i == inventory.items.size() ? Inventory.SLOT_OFFHAND : i;
            var stack = inventory.getItem(slot);
            if (slot == inventory.selected || stack.isEmpty() || !stack.isItemEnabled(actor.level().enabledFeatures())
                    || slot == Inventory.SLOT_OFFHAND && stack.canPerformAction(ToolActions.SHIELD_BLOCK)) continue;
            double candidate = meleeScore(stack, targetType, damageBase, speedBase);
            if (candidate > score) { score = candidate; best = slot; }
        }
        select(actor, best);
    }
    private static double meleeScore(ItemStack stack, MobType targetType, double damageBase, double speedBase) {
        double damage = attackAttribute(stack, Attributes.ATTACK_DAMAGE, damageBase)
                + EnchantmentHelper.getDamageBonus(stack, targetType);
        double speed = attackAttribute(stack, Attributes.ATTACK_SPEED, speedBase);
        // Ordinary native hurt resistance limits the value of swings above two per second.
        return Math.max(0, damage) * Math.max(0, Math.min(speed, 2));
    }
    private static double attackAttribute(ItemStack stack, Attribute attribute, double base) {
        double additive = base, multiplyBase = 0, multiplyTotal = 1;
        for (var modifier : stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(attribute)) {
            if (modifier.getOperation() == AttributeModifier.Operation.ADDITION) additive += modifier.getAmount();
            else if (modifier.getOperation() == AttributeModifier.Operation.MULTIPLY_BASE) multiplyBase += modifier.getAmount();
            else multiplyTotal *= 1 + modifier.getAmount();
        }
        return additive * (1 + multiplyBase) * multiplyTotal;
    }
}
