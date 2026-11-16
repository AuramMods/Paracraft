package com.aurammods.paracraft.core.agent;

public interface AgentClock {
    long nanoTime();
    long epochMillis();
    AgentClock SYSTEM = new AgentClock() {
        public long nanoTime() { return System.nanoTime(); }
        public long epochMillis() { return System.currentTimeMillis(); }
    };
}
