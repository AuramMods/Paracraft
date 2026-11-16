package com.aurammods.paracraft.forge.avatar;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class AvatarSavedData extends SavedData {
    private final Map<UUID, AvatarEntry> entries = new LinkedHashMap<>();
    private UUID worldId = UUID.randomUUID();
    public AvatarSavedData() { setDirty(); }
    public UUID worldId() { return worldId; }

    public Collection<AvatarEntry> entries() { return java.util.List.copyOf(entries.values()); }
    public AvatarEntry get(UUID id) { return entries.get(id); }
    public void put(AvatarEntry entry) { entries.put(entry.id, entry); setDirty(); }
    public void remove(UUID id) { entries.remove(id); setDirty(); }

    public static AvatarSavedData load(CompoundTag tag) {
        if (tag.getInt("schema") != 1) throw new IllegalStateException("Unsupported Paracraft avatar save schema");
        AvatarSavedData data = new AvatarSavedData();
        if (tag.hasUUID("worldId")) data.worldId = tag.getUUID("worldId");
        ListTag entries = tag.getList("avatars", Tag.TAG_COMPOUND);
        for (int i = 0; i < entries.size(); i++) {
            AvatarEntry entry = AvatarEntry.load(entries.getCompound(i));
            if (data.entries.put(entry.id, entry) != null) throw new IllegalStateException("Duplicate saved avatar UUID");
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("schema", 1);
        tag.putUUID("worldId", worldId);
        ListTag avatars = new ListTag();
        entries.values().forEach(entry -> avatars.add(entry.save()));
        tag.put("avatars", avatars);
        return tag;
    }
}
