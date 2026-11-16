package com.aurammods.paracraft.core;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ControlAuthorityTest {
    @Test
    void delegationCannotTakeControlAndReconnectRevokesOldResults() {
        UUID owner = UUID.randomUUID(), friend = UUID.randomUUID();
        ControlAuthority authority = new ControlAuthority(owner);
        UUID firstSession = authority.connect();
        authority.trust(friend);
        assertTrue(authority.canInstruct(friend));
        assertFalse(authority.controls(friend, firstSession));
        assertTrue(authority.controls(owner, firstSession));
        authority.disconnect();
        assertFalse(authority.controls(owner, firstSession));
        UUID secondSession = authority.connect();
        assertFalse(authority.controls(owner, firstSession));
        assertTrue(authority.controls(owner, secondSession));
        authority.untrust(friend);
        assertFalse(authority.canInstruct(friend));
        assertTrue(authority.canInstruct(owner));
    }
}
