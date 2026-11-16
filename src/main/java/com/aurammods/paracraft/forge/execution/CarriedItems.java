package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.inventory.ItemSelector;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.block.state.BlockState;

public final class CarriedItems {
    private CarriedItems() { }
    static int find(CompanionPlayer actor, ItemSelector selector) {
        if (selector.matches(actor.getMainHandItem())) return actor.getInventory().selected;
        for (int i = 0; i < actor.getInventory().items.size(); i++) if (selector.matches(actor.getInventory().getItem(i))) return i;
        throw new IllegalStateException("No carried item matches " + selector.expression());
    }
    public static void select(CompanionPlayer actor, int slot) {
        var inventory = actor.getInventory();
        if (slot < 9) inventory.selected = slot;
        else {
            ItemStack previous = inventory.getItem(inventory.selected);
            inventory.setItem(inventory.selected, inventory.getItem(slot)); inventory.setItem(slot, previous);
        }
        inventory.setChanged();
    }
    /** Probe actual Forge harvest/break-speed behavior with each carried candidate, restoring both slots. */
    static void miningTool(CompanionPlayer actor, BlockPos position, BlockState state) {
        var inventory = actor.getInventory(); int hand = inventory.selected, best = -1; float speed = -1;
        ItemStack original = inventory.getItem(hand);
        if (state.canHarvestBlock(actor.serverLevel(), position, actor)) {
            float current = state.getDestroyProgress(actor, actor.serverLevel(), position);
            if (!Float.isNaN(current) && current >= 0) { speed = current; best = hand; }
        }
        try {
            for (int slot = 0; slot < inventory.items.size(); slot++) {
                ItemStack candidate = inventory.getItem(slot);
                if (slot != hand) { inventory.setItem(hand, candidate); inventory.setItem(slot, original); }
                try {
                    if (!state.canHarvestBlock(actor.serverLevel(), position, actor)) continue;
                    float rate = state.getDestroyProgress(actor, actor.serverLevel(), position);
                    if (Float.isFinite(rate) && rate > speed) { speed = rate; best = slot; }
                    else if (rate == Float.POSITIVE_INFINITY && speed != Float.POSITIVE_INFINITY) { speed = rate; best = slot; }
                } finally { if (slot != hand) { inventory.setItem(slot, candidate); inventory.setItem(hand, original); } }
            }
        } finally { inventory.setItem(hand, original); }
        if (best < 0) throw new IllegalStateException("No carried tool can harvest " + state.getBlock().getDescriptionId());
        select(actor, best);
    }
    static void weapon(CompanionPlayer actor, net.minecraft.world.entity.MobType targetType) {
        int best = actor.getInventory().selected; double score = -1;
        for (int slot = 0; slot < actor.getInventory().items.size(); slot++) {
            ItemStack stack = actor.getInventory().getItem(slot); double damage = 1, multiply = 1, multiplyBase = 0;
            for (var modifier : stack.getAttributeModifiers(EquipmentSlot.MAINHAND).get(Attributes.ATTACK_DAMAGE)) {
                if (modifier.getOperation() == AttributeModifier.Operation.ADDITION) damage += modifier.getAmount();
                else if (modifier.getOperation() == AttributeModifier.Operation.MULTIPLY_BASE) multiplyBase += modifier.getAmount();
                else multiply *= 1 + modifier.getAmount();
            }
            damage = damage * (1 + multiplyBase) * multiply + EnchantmentHelper.getDamageBonus(stack, targetType);
            if (damage > score) { score = damage; best = slot; }
        }
        select(actor, best);
    }
}
