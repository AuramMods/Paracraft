package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Advisory native command usage/completion data; execution always makes the actual attempt. */
public final class CommandDiscoveryJob extends GameJob {
    private final String prefix;
    private CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggestions;
    public CommandDiscoveryJob(UUID id,CompanionPlayer actor,String prefix){super(id,"discover_commands",actor);this.prefix=prefix.startsWith("/")?prefix.substring(1):prefix;}
    @Override protected void advance(){
        var dispatcher=actor.server.getCommands().getDispatcher();var source=actor.createCommandSourceStack();
        if(suggestions==null){
            phase="discovering_commands";String[] usages=dispatcher.getAllUsage(dispatcher.getRoot(),source,true);JsonArray forms=new JsonArray();long total=0;
            for(String usage:usages)if(prefix.isEmpty() || usage.startsWith(prefix.stripTrailing())){total++;if(forms.size()<128)forms.add(usage);}
            result.add("forms",forms);result.addProperty("formCount",total);result.addProperty("formsTruncated",total>forms.size());result.addProperty("prefix",prefix);
            suggestions=dispatcher.getCompletionSuggestions(dispatcher.parse(prefix,source));
        }
        if(!suggestions.isDone())return;
        JsonArray options=new JsonArray();var values=suggestions.join().getList();
        values.stream().limit(128).forEach(s->{JsonObject value=new JsonObject();value.addProperty("text",s.getText());value.addProperty("start",s.getRange().getStart());value.addProperty("end",s.getRange().getEnd());if(s.getTooltip()!=null)value.addProperty("tooltip",s.getTooltip().getString());options.add(value);});
        result.add("suggestions",options);result.addProperty("suggestionCount",values.size());result.addProperty("suggestionsTruncated",values.size()>options.size());succeed(result);
    }
    @Override protected void onStop(){if(suggestions!=null && !suggestions.isDone())suggestions.cancel(true);suggestions=null;}
    @Override protected JsonObject writeData(){JsonObject data=new JsonObject();data.addProperty("prefix",prefix);return data;}
}
