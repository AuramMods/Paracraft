package com.aurammods.paracraft.core.agent;

import com.google.gson.*;

/** Bounded, explicitly labelled historical previews. Execution always uses the original arguments. */
public final class HistorySummary {
    private HistorySummary() { }
    public static JsonElement of(JsonElement value){return copy(value,0,new int[]{12_000});}
    private static JsonElement copy(JsonElement value,int depth,int[] budget){
        if(budget[0]<=0 || depth>12)return new JsonPrimitive("[history preview omitted]");
        if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()){
            String text=value.getAsString();int take=Math.min(text.length(),Math.max(0,Math.min(2048,budget[0]-40)));JsonPrimitive preview;
            do{preview=new JsonPrimitive(take==text.length()?text:text.substring(0,take)+" [history text truncated]");if(preview.toString().length()<=budget[0] || take==0)break;take/=2;}while(true);budget[0]-=preview.toString().length();return preview;
        }
        if(value.isJsonObject()){
            var result=new JsonObject();var source=value.getAsJsonObject();int kept=0;
            for(var entry:source.entrySet()){int keySize=new JsonPrimitive(entry.getKey()).toString().length()+8;if(budget[0]<keySize || kept>=48)break;budget[0]-=keySize;result.add(entry.getKey(),copy(entry.getValue(),depth+1,budget));kept++;}
            if(kept<source.size())result.addProperty("_historyOmittedFields",source.size()-kept);return result;
        }
        if(value.isJsonArray()){
            var result=new JsonArray();var source=value.getAsJsonArray();int kept=0;
            for(var entry:source){if(budget[0]<=0 || kept>=24)break;result.add(copy(entry,depth+1,budget));kept++;}
            if(kept<source.size()){var omitted=new JsonObject();omitted.addProperty("_historyOmittedItems",source.size()-kept);result.add(omitted);}return result;
        }
        budget[0]-=value.toString().length()+1;return value.deepCopy();
    }
}
