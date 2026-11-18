package com.aurammods.paracraft.core.openai;

import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** Account-local reported usage. Luna API equivalents are estimates, never plan quota or invoices. */
public final class UsageLedger {
    public static final String PRICE_DATE = "2026-11-17";
    public static final String PRICING_URL = "https://developers.openai.com/api/docs/pricing";
    public static final String MANAGE_USAGE_URL = "https://chatgpt.com/settings/usage";
    private UsageLedger() { }

    public record Today(LocalDate date, String zone, Instant trackedSince, boolean olderUsage,
                        long input, long output, long total, long cached, long writes, long completed,
                        long unreported, long estimatedCalls, long unpricedCalls, BigDecimal usd, boolean openRouter, boolean customEndpoint) {
        public String tokensLabel() { return "Today · Input " + count(input) + " · Output " + count(output); }
        public String costLabel() { return (openRouter?(customEndpoint?"API reported ":"OpenRouter reported "):"Luna API est. ") + dollars(usd) + " USD" + (unreported + unpricedCalls > 0 || olderUsage && trackedSince.atZone(ZoneId.of(zone)).toLocalDate().equals(date) ? "*" : ""); }
        public List<String> details() {
            var lines = new java.util.ArrayList<String>();
            lines.add("Paracraft · "+(openRouter?(customEndpoint?"configured API endpoint":"saved OpenRouter key"):"selected account")+" · " + date + " (" + zone + ")");
            lines.add("Input " + count(input) + " · Output " + count(output) + " · Total " + count(total));
            lines.add("Cached input " + count(cached) + " · Cache writes " + count(writes) + " · Completed " + count(completed));
            if(openRouter){
                lines.add((customEndpoint?"API-reported Paracraft usage for this endpoint.":"OpenRouter-reported Paracraft usage for the saved API key.")+" Costs use response usage.cost, never Luna token estimates. Reasoning tokens are included in output.");
                lines.add("Changing models keeps daily totals; changing the endpoint or replacing/removing its key starts a separate ledger.");
                if(unreported+unpricedCalls>0)lines.add("Partial totals: "+count(unreported)+" requests without full token usage; "+count(unpricedCalls)+" responses without reported cost.");
                lines.add(customEndpoint?"Use your provider's controls for billing/limits. Local servers may omit cost; missing cost is unknown, not a billed estimate. No ChatGPT plan quota applies.":"OpenRouter Activity shows account-wide usage. Separately billed upstream BYOK charges are outside these totals; no ChatGPT plan quota applies.");return List.copyOf(lines);
            }
            lines.add("All reported Paracraft tokens priced as Luna API equivalents, not ChatGPT plan charges. Reasoning is included in output tokens.");
            lines.add("USD/1M: input 0.10 · cached 0.01 · writes 0.125 · output 0.50 (" + PRICE_DATE + ").");
            lines.add("Standard/30m-cache estimate; long-context rates apply above 272K input. Unreported cache writes and regional/tier premiums are excluded.");
            if (unreported + unpricedCalls > 0) lines.add("Partial totals: " + count(unreported) + " requests without full token usage; " + count(unpricedCalls) + " reports without a cost estimate.");
            if (olderUsage && trackedSince.atZone(ZoneId.of(zone)).toLocalDate().equals(date))
                lines.add("Daily tracking since " + DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of(zone)).format(trackedSince) + "; earlier lifetime usage cannot be assigned to today.");
            lines.add("Codex/ChatGPT % left is unavailable through this sign-in flow. Manage usage shows your account's current limits.");
            return List.copyOf(lines);
        }
    }
    public static Today today(JsonObject usage, Instant now, ZoneId defaultZone) {
        ZoneId zone = usage.has("daily_zone") ? ZoneId.of(usage.get("daily_zone").getAsString()) : defaultZone;
        LocalDate date = now.atZone(zone).toLocalDate();
        JsonObject day = usage.has("days") ? usage.getAsJsonObject("days").getAsJsonObject(date.toString()) : null;
        if (day == null) day = new JsonObject();
        Instant since = usage.has("daily_since") ? Instant.parse(usage.get("daily_since").getAsString()) : now;
        return new Today(date, zone.getId(), since, usage.has("daily_older_usage") && usage.get("daily_older_usage").getAsBoolean(),
                number(day,"input_tokens"), number(day,"output_tokens"), number(day,"total_tokens"), number(day,"cached_input_tokens"), number(day,"cache_write_tokens"),
                number(day,"completed"), number(day,"unreported"), number(day,"estimated_calls"), number(day,"unpriced_calls"),
                day.has("usd_estimate") ? day.get("usd_estimate").getAsBigDecimal() : BigDecimal.ZERO,"openrouter".equals(OpenAiHttp.string(usage,"provider","")),usage.has("custom_endpoint") && usage.get("custom_endpoint").getAsBoolean());
    }
    static void initialize(JsonObject usage, Instant now, ZoneId zone) {
        if (usage.has("days")) return;
        usage.add("days", new JsonObject()); usage.addProperty("daily_zone", zone.getId()); usage.addProperty("daily_since", now.toString());
        usage.addProperty("daily_older_usage", number(usage,"requests") > 0 || number(usage,"total_tokens") > 0);
    }
    static void record(JsonObject usage, JsonObject reported, String outcome, Instant now, ZoneId defaultZone) {
        record(usage,reported,outcome,now,defaultZone,false);
    }
    static void recordOpenRouter(JsonObject usage,JsonObject reported,String outcome,Instant now,ZoneId zone){
        var normalized=new JsonObject();if(reported!=null){for(String[] fields:List.of(new String[]{"prompt_tokens","input_tokens"},new String[]{"completion_tokens","output_tokens"},new String[]{"total_tokens","total_tokens"},new String[]{"prompt_tokens_details","input_tokens_details"},new String[]{"cost","cost"}))if(reported.has(fields[0]))normalized.add(fields[1],reported.get(fields[0]).deepCopy());}
        usage.addProperty("provider","openrouter");record(usage,normalized,outcome,now,zone,true);
    }
    private static void record(JsonObject usage,JsonObject reported,String outcome,Instant now,ZoneId defaultZone,boolean router){
        initialize(usage, now, defaultZone);
        String date = now.atZone(ZoneId.of(usage.get("daily_zone").getAsString())).toLocalDate().toString();
        JsonObject days = usage.getAsJsonObject("days"), day = days.getAsJsonObject(date);
        if (day == null) { day = new JsonObject(); days.add(date,day); }
        Long input = token(reported,"input_tokens"), output = token(reported,"output_tokens"), total = token(reported,"total_tokens");
        JsonObject details = reported != null && reported.has("input_tokens_details") && reported.get("input_tokens_details").isJsonObject() ? reported.getAsJsonObject("input_tokens_details") : null;
        Long cached = token(details,"cached_tokens"), writes = token(details,"cache_write_tokens");
        boolean complete = input != null && output != null;
        if (total == null && complete) total = Math.addExact(input,output);
        for (JsonObject aggregate : List.of(usage,day)) {
            add(aggregate,"requests",1L); if (outcome.equals("completed")) add(aggregate,"completed",1L);
            add(aggregate,"input_tokens",input); add(aggregate,"output_tokens",output); add(aggregate,"total_tokens",total);
            add(aggregate,"cached_input_tokens",cached); add(aggregate,"cache_write_tokens",writes);
            if (!complete) add(aggregate,"unreported",1L);
        }
        if (complete || router) {
            BigDecimal price = router?reportedCost(reported):estimate(input,output,cached == null ? 0 : cached,writes == null ? 0 : writes);
            if (price == null) add(day,"unpriced_calls",1L);
            else { day.addProperty("usd_estimate", (day.has("usd_estimate") ? day.get("usd_estimate").getAsBigDecimal() : BigDecimal.ZERO).add(price)); add(day,"estimated_calls",1L); }
        }
    }
    private static BigDecimal reportedCost(JsonObject reported){
        try{if(reported==null || !reported.has("cost") || !reported.get("cost").isJsonPrimitive() || !reported.getAsJsonPrimitive("cost").isNumber())return null;var cost=reported.get("cost").getAsBigDecimal();return cost.signum()<0?null:cost;}catch(RuntimeException invalid){return null;}
    }
    /** Standard Luna rates, default 30m writes. Each input token is charged exactly once. */
    static BigDecimal estimate(long input, long output, long cached, long writes) {
        if (input < 0 || output < 0 || cached < 0 || writes < 0 || cached > input || writes > input - cached) return null;
        BigDecimal inputMultiplier = input > 272_000 ? new BigDecimal("2") : BigDecimal.ONE;
        BigDecimal outputMultiplier = input > 272_000 ? new BigDecimal("1.5") : BigDecimal.ONE;
        return BigDecimal.valueOf(input - cached - writes).multiply(new BigDecimal("0.10"))
                .add(BigDecimal.valueOf(cached).multiply(new BigDecimal("0.01")))
                .add(BigDecimal.valueOf(writes).multiply(new BigDecimal("0.125"))).multiply(inputMultiplier)
                .add(BigDecimal.valueOf(output).multiply(new BigDecimal("0.50")).multiply(outputMultiplier)).movePointLeft(6);
    }
    private static Long token(JsonObject object, String key) {
        try { if (object == null || !object.has(key)) return null; long value = object.get(key).getAsBigDecimal().longValueExact(); return value >= 0 ? value : null; }
        catch (RuntimeException invalid) { return null; }
    }
    private static long number(JsonObject object,String key) { Long value = token(object,key); return value == null ? 0 : value; }
    private static void add(JsonObject object,String key,Long amount) { if (amount != null) object.addProperty(key,Math.addExact(number(object,key),amount)); }
    private static String count(long value) { return String.format(Locale.US,"%,d",value); }
    private static String dollars(BigDecimal value) {
        if (value.signum() > 0 && value.compareTo(new BigDecimal("0.000001")) < 0) return "<$0.000001";
        return "$" + value.setScale(6,java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
