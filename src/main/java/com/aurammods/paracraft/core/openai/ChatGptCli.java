package com.aurammods.paracraft.core.openai;

import com.aurammods.paracraft.core.agent.InferenceProvider;
import com.google.gson.JsonObject;
import java.util.concurrent.TimeUnit;

/** Explicit local CLI; no automatic browser or game-window launch, and no token output. */
public final class ChatGptCli {
    public static void main(String[] args)throws Exception{
        String action=args.length==0?"status":args[0];
        try(var client=new ChatGptClient(ChatGptClient.defaultDirectory())){
            switch(action){
                case "login"->{var page=client.signIn(args.length>1 && args[1].equals("--add")).get(30,TimeUnit.SECONDS);System.out.println("Open this local sign-in page in your browser: "+page);System.out.println("Credentials are kept on this computer. Waiting up to ten minutes...");client.loginCompletion().get(10,TimeUnit.MINUTES);System.out.println(client.notice());}
                case "models"->{for(var model:client.refreshModels().get(90,TimeUnit.SECONDS))System.out.println(model.slug()+" · "+model.name()+" "+model.details());}
                case "metadata"->System.out.println(client.publicMetadata().get(60,TimeUnit.SECONDS));
                case "logout"->{client.signOut().get(90,TimeUnit.SECONDS);System.out.println(client.notice());}
                case "simulate"->{client.simulate().get(30,TimeUnit.SECONDS);System.out.println(client.notice());}
                case "smoke"->{String model=args.length>1?args[1]:"gpt-6-luna";String effort=args.length>2?args[2]:"none";var snapshot=new JsonObject();var trigger=new JsonObject();trigger.addProperty("id",0);trigger.addProperty("type","chat");trigger.addProperty("message","Reply with one brief greeting confirming this connection works. Do not request gameplay actions.");System.out.println("Making one live LIGHT request using "+model+" / "+effort);var result=new ChatGptProvider(client).infer(new InferenceProvider.Request(InferenceProvider.Route.LIGHT,new InferenceProvider.ModelRoute(model,effort),snapshot,trigger,new JsonObject())).get(5,TimeUnit.MINUTES);for(var call:result.calls())if(call.tool().equals("chat"))System.out.println(call.arguments().get("message").getAsString());else System.out.println("Returned tool: "+call.tool());System.out.println(client.usage());System.out.println(client.todayUsage().tokensLabel());System.out.println(client.todayUsage().costLabel());}
                case "status"->{System.out.println(client.label());System.out.println(client.usage());System.out.println(client.todayUsage().tokensLabel());System.out.println(client.todayUsage().costLabel());for(var line:client.todayUsage().details())System.out.println(line);for(var account:client.accounts())System.out.println(account.label()+" · saved session="+account.signedIn()+" · plan permission="+account.planEnabled());}
                default->throw new IllegalArgumentException("Use login, status, models, logout, simulate, metadata, or smoke [model] [effort]");
            }
        }catch(Exception failure){System.err.println(ChatGptClient.message(failure));System.exit(1);}
    }
}
