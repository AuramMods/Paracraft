package com.aurammods.paracraft.forge.execution;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.ArrayList;
import java.util.List;

public final class JobJson {
    private JobJson() { }
    public static JsonArray vector(Vec3 value) { JsonArray array = new JsonArray(); array.add(value.x); array.add(value.y); array.add(value.z); return array; }
    public static Vec3 vector(JsonElement value) { JsonArray a = value.getAsJsonArray(); return new Vec3(a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()); }
    public static JsonArray position(BlockPos value) { return vector(Vec3.atLowerCornerOf(value)); }
    public static BlockPos position(JsonElement value) { return BlockPos.containing(vector(value)); }
    public static JsonArray stacks(List<ItemStack> stacks) {
        JsonArray array = new JsonArray(); for (ItemStack stack : stacks) array.add(stack.save(new CompoundTag()).toString()); return array;
    }
    public static List<ItemStack> stacks(JsonArray array) {
        List<ItemStack> stacks = new ArrayList<>();
        for (JsonElement value : array) try { stacks.add(ItemStack.of(TagParser.parseTag(value.getAsString()))); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException invalid) { throw new IllegalArgumentException("Invalid saved item stack", invalid); }
        return stacks;
    }
}
