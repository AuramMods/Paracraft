package com.aurammods.paracraft.core.program;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockTargetsTest {
    private BlockTargets targets(String json) { return new BlockTargets(JsonParser.parseString(json).getAsJsonObject()); }
    @Test void cuboidsNormalizeEndpointsAndProcessWholeTopLayersBeforeLowerOnes() {
        var box = targets("{\"cuboid\":{\"from\":[5,4,3],\"to\":[3,2,4]}}");
        assertEquals(18, box.size()); assertEquals(new BlockTargets.Position(3,4,3), box.get(0));
        assertEquals(new BlockTargets.Position(5,4,4), box.get(5)); assertEquals(new BlockTargets.Position(3,3,3), box.get(6));
        assertEquals(new BlockTargets.Position(5,2,4), box.get(17));
    }
    @Test void aLargeRegionCanBeAddressedWithoutAllocatingItsBlocks() {
        var box = targets("{\"cuboid\":{\"from\":[0,0,0],\"to\":[30000000,256,30000000]}}");
        assertEquals(30_000_001L * 30_000_001L * 257, box.size());
        assertEquals(new BlockTargets.Position(30_000_000,0,30_000_000), box.get(box.size() - 1));
    }
    @Test void malformedAndUnrepresentableTargetsAreRejectedWithoutSilentlyRounding() {
        assertThrows(IllegalArgumentException.class, () -> targets("{\"block\":[1.5,2,3]}"));
        assertThrows(IllegalArgumentException.class, () -> targets("{\"block\":[1,2,3],\"blocks\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> targets("{\"cuboid\":{\"from\":[-2147483648,-2147483648,-2147483648],\"to\":[2147483647,2147483647,2147483647]}}"));
        var list = targets("{\"blocks\":[[1,2,3],[1,2,3]]}"); assertEquals(2, list.size());
        assertThrows(IndexOutOfBoundsException.class, () -> list.get(2));
    }
}
