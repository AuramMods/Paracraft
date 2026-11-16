package com.aurammods.paracraft.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorSettingsTest {
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    @Test void partialUpdatesPreserveOtherSettingsAndNeverMutateInputs() {
        var original = BehaviorSettings.defaults("minecraft:overworld"); var patch = json("{\"players\":\"PASSIVE\",\"idle\":{\"enabled\":true,\"waypoints\":[[1,64,2]],\"waitTicks\":0}}");
        var result = BehaviorSettings.merge(original,patch); assertEquals(BehaviorSettings.Reaction.PASSIVE,BehaviorSettings.reaction(result,true));
        assertEquals(BehaviorSettings.Reaction.NEUTRAL,BehaviorSettings.reaction(result,false)); assertFalse(original.getAsJsonObject("idle").get("enabled").getAsBoolean());
        patch.getAsJsonObject("idle").getAsJsonArray("waypoints").remove(0); assertEquals(1,result.getAsJsonObject("idle").getAsJsonArray("waypoints").size());
    }
    @Test void invalidUpdatesAreRejectedWithoutChangingExistingConfiguration() {
        var original = BehaviorSettings.defaults("minecraft:overworld"); var saved = original.deepCopy();
        for (String patch : new String[]{"{\"mobs\":\"friendly\"}","{\"unknown\":true}","{\"idle\":{\"enabled\":true}}","{\"idle\":{\"waitTicks\":0.5}}","{\"idle\":{\"waypoints\":[[1,2]]}}","{\"idle\":{\"dimension\":\"bad dimension\"}}"})
            assertThrows(IllegalArgumentException.class,()->BehaviorSettings.merge(original,json(patch)),patch);
        assertEquals(saved,original);
    }
}
