package com.aurammods.paracraft.forge.execution;

import com.mojang.brigadier.StringReader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ItemSelectorArgumentTest {
    @Test void parsesNamespacedIdsAndTagsWithoutConsumingTheNextArgument() throws Exception {
        var parser = new ItemSelectorArgument();
        var item = new StringReader("minecraft:diamond 12");
        assertEquals("minecraft:diamond", parser.parse(item));
        assertEquals(" 12", item.getRemaining());
        assertEquals("#forge:gems/diamond", parser.parse(new StringReader("#forge:gems/diamond")));
    }
}
