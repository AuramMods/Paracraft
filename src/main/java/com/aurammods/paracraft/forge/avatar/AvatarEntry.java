package com.aurammods.paracraft.forge.avatar;

import com.aurammods.paracraft.core.ControlAuthority;
import com.aurammods.paracraft.core.agent.ActionHistory;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

public final class AvatarEntry {
    public final UUID id;
    public final String name;
    public final ControlAuthority authority;
    public boolean paused;
    public CompoundTag physical = new CompoundTag();
    public com.google.gson.JsonObject execution = new com.google.gson.JsonObject();
    public com.google.gson.JsonObject control = new com.google.gson.JsonObject();
    public final ActionHistory actionHistory;

    public AvatarEntry(UUID id, UUID owner, String name) {
        this(id, owner, name, new ActionHistory());
    }

    private AvatarEntry(UUID id, UUID owner, String name, ActionHistory actionHistory) {
        this.id = id;
        this.name = name;
        this.actionHistory = actionHistory;
        authority = new ControlAuthority(owner);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("id", id);
        tag.putUUID("owner", authority.owner());
        tag.putString("name", name);
        tag.putBoolean("paused", paused);
        tag.put("physical", physical.copy());
        tag.putByteArray("execution", execution.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        tag.putByteArray("control", control.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (actionHistory.size() > 0) tag.putString("actionHistory", actionHistory.text());
        ListTag trusted = new ListTag();
        authority.trusted().forEach(p -> trusted.add(StringTag.valueOf(p.toString())));
        tag.put("trusted", trusted);
        return tag;
    }

    public static AvatarEntry load(CompoundTag tag) {
        var history = ActionHistory.fromText(tag.getString("actionHistory"));
        AvatarEntry entry = new AvatarEntry(tag.getUUID("id"), tag.getUUID("owner"), tag.getString("name"), history);
        entry.paused = tag.getBoolean("paused");
        entry.physical = tag.getCompound("physical").copy();
        if (tag.contains("execution", Tag.TAG_BYTE_ARRAY)) entry.execution = com.google.gson.JsonParser.parseString(
                new String(tag.getByteArray("execution"), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        if (tag.contains("control", Tag.TAG_BYTE_ARRAY)) entry.control = com.google.gson.JsonParser.parseString(
                new String(tag.getByteArray("control"), java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        ListTag trusted = tag.getList("trusted", Tag.TAG_STRING);
        for (int i = 0; i < trusted.size(); i++) entry.authority.trust(UUID.fromString(trusted.getString(i)));
        // Runtime control sessions are deliberately never restored from disk.
        return entry;
    }
}
