package com.aurammods.paracraft.forge.inventory;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/** Registry IDs and item tags, never ambiguous display-name substring matching. */
public record ItemSelector(String expression) {
    public ItemSelector {
        String id = expression.startsWith("#") ? expression.substring(1) : expression;
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null || (!expression.startsWith("#") && !ForgeRegistries.ITEMS.containsKey(key)))
            throw new IllegalArgumentException("Unknown item selector: " + expression);
    }
    public boolean matches(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return expression.startsWith("#")
                ? stack.is(TagKey.create(Registries.ITEM, new ResourceLocation(expression.substring(1))))
                : stack.is(ForgeRegistries.ITEMS.getValue(new ResourceLocation(expression)));
    }
}
