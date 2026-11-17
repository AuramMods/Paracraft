package com.aurammods.paracraft.core.openai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UsageLedgerTest {
    private static final ZoneId CHICAGO=ZoneId.of("America/Chicago");
    private static final Instant NOW=Instant.parse("2026-11-17T16:00:00Z");
    private static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    private static void equal(String expected,BigDecimal actual){assertEquals(0,new BigDecimal(expected).compareTo(actual));}

    @Test void apiEquivalentsSeparateCacheReadsWritesAndReasoningWithoutDoubleCounting() {
        var usage=new JsonObject();
        UsageLedger.record(usage,json("{\"input_tokens\":1000000,\"output_tokens\":100000,\"total_tokens\":1100000,\"input_tokens_details\":{\"cached_tokens\":200000,\"cache_write_tokens\":100000},\"output_tokens_details\":{\"reasoning_tokens\":80000}}"),"completed",NOW,CHICAGO);
        var today=UsageLedger.today(usage,NOW,CHICAGO);
        // Long input: (700K ordinary + 200K cache + 100K writes) at 2x, output at 1.5x.
        equal("0.244",today.usd());assertEquals(1_000_000,today.input());assertEquals(100_000,today.output());
        assertEquals(1_100_000,today.total());assertEquals(200_000,today.cached());assertEquals(100_000,today.writes());
        assertTrue(today.tokensLabel().contains("1,000,000"));assertTrue(today.costLabel().contains("$0.244000"));
        assertTrue(String.join(" ",today.details()).contains("not ChatGPT plan charges"));
    }
    @Test void standardRatesAndLongContextThresholdAreAppliedPerRequest() {
        equal("0.0582",UsageLedger.estimate(100000,100000,20000,0));
        equal("0.0272",UsageLedger.estimate(272000,0,0,0));
        equal("0.0544002",UsageLedger.estimate(272001,0,0,0));
        var usage=new JsonObject();for(int i=0;i<2;i++)UsageLedger.record(usage,json("{\"input_tokens\":200000,\"output_tokens\":0}"),"completed",NOW,CHICAGO);
        equal("0.04",UsageLedger.today(usage,NOW,CHICAGO).usd()); // A 400K daily total is not a long-context request.
        assertNull(UsageLedger.estimate(10,1,11,0));assertNull(UsageLedger.estimate(10,1,5,6));
    }
    @Test void localMidnightAndDstResetTodayWithoutAnotherInference() {
        var usage=new JsonObject();Instant before=Instant.parse("2026-11-01T04:59:59Z"),first=before.plusSeconds(1);
        UsageLedger.record(usage,json("{\"input_tokens\":10,\"output_tokens\":2}"),"completed",before,CHICAGO);
        assertEquals(10,UsageLedger.today(usage,before,CHICAGO).input());assertEquals(0,UsageLedger.today(usage,first,CHICAGO).input());
        UsageLedger.record(usage,json("{\"input_tokens\":20,\"output_tokens\":3}"),"completed",first,CHICAGO);
        assertEquals(20,UsageLedger.today(usage,Instant.parse("2026-11-02T05:59:59Z"),CHICAGO).input()); // The fallback day is 25 hours.
        assertEquals(0,UsageLedger.today(usage,Instant.parse("2026-11-02T06:00:00Z"),CHICAGO).input());
        assertEquals("America/Chicago",UsageLedger.today(usage,first,ZoneId.of("UTC")).zone()); // Preserve the ledger's displayed reporting zone.
    }
    @Test void legacyLifetimeTotalsArePreservedWithoutFabricatingDailyHistory() {
        var usage=json("{\"requests\":5,\"completed\":5,\"input_tokens\":500,\"output_tokens\":50,\"total_tokens\":550,\"last_at\":\"2026-11-17T15:00:00Z\"}");
        UsageLedger.initialize(usage,NOW,CHICAGO);var today=UsageLedger.today(usage,NOW,CHICAGO);
        assertEquals(0,today.input());assertTrue(today.olderUsage());assertTrue(today.costLabel().endsWith("*"));assertEquals(550,usage.get("total_tokens").getAsLong());
        assertTrue(String.join(" ",today.details()).contains("earlier lifetime usage"));
        UsageLedger.record(usage,json("{\"input_tokens\":100,\"output_tokens\":10}"),"completed",NOW,CHICAGO);
        assertEquals(110,UsageLedger.today(usage,NOW,CHICAGO).total());assertEquals(660,usage.get("total_tokens").getAsLong());
    }
    @Test void missingAndMalformedUsageRemainExplicitlyPartialWithoutBlockingInference() {
        var usage=new JsonObject();UsageLedger.record(usage,null,"stream_failed",NOW,CHICAGO);
        UsageLedger.record(usage,json("{\"input_tokens\":-10,\"output_tokens\":\"invalid\"}"),"completed",NOW,CHICAGO);
        UsageLedger.record(usage,json("{\"input_tokens\":100,\"output_tokens\":10,\"input_tokens_details\":{\"cached_tokens\":200}}"),"completed",NOW,CHICAGO);
        var today=UsageLedger.today(usage,NOW,CHICAGO);assertEquals(2,today.unreported());assertEquals(1,today.unpricedCalls());
        assertEquals(100,today.input());assertEquals(10,today.output());assertEquals(0,today.usd().signum());assertTrue(today.costLabel().endsWith("*"));
    }
    @Test void tinyNonzeroEstimatesNeverDisplayAsZeroDollars() {
        var usage=new JsonObject();UsageLedger.record(usage,json("{\"input_tokens\":1,\"output_tokens\":0}"),"completed",NOW,CHICAGO);
        assertTrue(UsageLedger.today(usage,NOW,CHICAGO).costLabel().contains("<$0.000001"));
    }
}
