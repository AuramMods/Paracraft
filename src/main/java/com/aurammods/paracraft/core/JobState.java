package com.aurammods.paracraft.core;

public enum JobState {
    RUNNING, PAUSED, SUCCEEDED, FAILED, CANCELLED;
    public boolean terminal() { return this == SUCCEEDED || this == FAILED || this == CANCELLED; }
}
