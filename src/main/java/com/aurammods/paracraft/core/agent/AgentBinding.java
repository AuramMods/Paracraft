package com.aurammods.paracraft.core.agent;

import java.util.Objects;
import java.util.UUID;

/** Persistent world identity, not server address alone, scopes cognitive state. */
public record AgentBinding(UUID world, UUID owner, UUID avatar) {
    public AgentBinding { Objects.requireNonNull(world); Objects.requireNonNull(owner); Objects.requireNonNull(avatar); }
}
