package com.aurammods.paracraft.core;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Instruction delegation never transfers the owner's inference control lease. */
public final class ControlAuthority {
    private final UUID owner;
    private final Set<UUID> trusted = new HashSet<>();
    private UUID session;

    public ControlAuthority(UUID owner) { this.owner = owner; }
    public UUID owner() { return owner; }
    public Set<UUID> trusted() { return Set.copyOf(trusted); }
    public UUID session() { return session; }
    public boolean canInstruct(UUID player) { return owner.equals(player) || trusted.contains(player); }
    public void trust(UUID player) { trusted.add(player); }
    public void untrust(UUID player) { trusted.remove(player); }
    public UUID connect() { return session = UUID.randomUUID(); }
    public void disconnect() { session = null; }
    public boolean controls(UUID sender, UUID suppliedSession) {
        return owner.equals(sender) && session != null && session.equals(suppliedSession);
    }
}
