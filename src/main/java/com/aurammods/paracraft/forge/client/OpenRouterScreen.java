package com.aurammods.paracraft.forge.client;

import com.aurammods.paracraft.core.openai.ChatGptClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;

/** API keys are entered locally, masked, and never submitted as Minecraft commands. */
final class OpenRouterScreen extends Screen {
    private final Screen parent;
    private final String[] labels={"API base URL (Ollama: http://127.0.0.1:11434/v1)","API key (optional locally; blank keeps key for this URL)","Light model ID","Light effort (auto = default)","Planning model ID (blank uses Light)","Planning effort (auto = default)"};
    private final EditBox[] fields=new EditBox[6];
    private final String[] draft;
    private String notice="Use OpenRouter or a compatible API. Model routes apply to all local companions.";
    private boolean busy;
    private Button save,remove;
    OpenRouterScreen(Screen parent){
        super(Component.literal("Paracraft · OpenRouter / compatible API"));this.parent=parent;var client=ForgeClientRuntime.accounts();var settings=client==null?null:client.openRouter();draft=new String[]{settings==null?ChatGptClient.OPENROUTER_BASE_URL:settings.baseUrl(),"",settings==null?"":settings.lightModel(),settings==null?"auto":settings.lightEffort(),settings==null?"":settings.planningModel(),settings==null?"auto":settings.planningEffort()};
    }
    @Override protected void init(){
        for(int i=0;i<fields.length;i++){
            if(fields[i]!=null)draft[i]=fields[i].getValue();int row=i<2?i:2+(i-2)/2,column=(width-48)/2;
            int y=46+row*Math.max(32,(height-115)/4),x=i>=2 && i%2==1?28+column:20,fieldWidth=i<2?width-40:column;
            fields[i]=new EditBox(font,x,y,fieldWidth,20,Component.literal(labels[i]));fields[i].setMaxLength(i==1?4096:i==0?2048:256);fields[i].setValue(draft[i]);
            if(i==1){fields[i].setFormatter((value,index)->FormattedCharSequence.forward("*".repeat(value.length()),Style.EMPTY));var client=ForgeClientRuntime.accounts();fields[i].setHint(Component.literal(client!=null && client.openRouter().keySaved()?"API key saved for current URL":"Optional for local servers"));}
            if(i==2)fields[i].setHint(Component.literal("Exact installed model ID"));addRenderableWidget(fields[i]);
        }
        int column=(width-48)/3;
        save=addRenderableWidget(Button.builder(Component.literal("Save & use"),b->save()).bounds(16,height-28,column,20).build());
        remove=addRenderableWidget(Button.builder(Component.literal("Remove key"),b->{var client=ForgeClientRuntime.accounts();if(client!=null)observe(client.forgetOpenRouter());}).bounds(24+column,height-28,column,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"),b->onClose()).bounds(32+column*2,height-28,column,20).build());
    }
    private void save(){
        var client=ForgeClientRuntime.accounts();if(client==null){notice=ForgeClientRuntime.accountError();return;}
        try{observe(client.configureOpenRouter(fields[1].getValue(),fields[2].getValue(),fields[3].getValue(),fields[4].getValue(),fields[5].getValue(),fields[0].getValue()));}catch(RuntimeException error){notice=ChatGptClient.message(error);}
    }
    private void observe(java.util.concurrent.CompletableFuture<?> future){busy=true;future.whenComplete((value,error)->minecraft.execute(()->{busy=false;notice=error==null?ForgeClientRuntime.accounts().notice():ChatGptClient.message(error);if(error==null){fields[0].setValue(ForgeClientRuntime.accounts().openRouter().baseUrl());fields[1].setValue("");draft[1]="";fields[1].setHint(Component.literal(ForgeClientRuntime.accounts().openRouter().keySaved()?"API key saved for current URL":"Optional for local servers"));}}));}
    @Override public void tick(){for(var field:fields)field.tick();save.active=!busy && ForgeClientRuntime.accounts()!=null;remove.active=save.active && ForgeClientRuntime.accounts().openRouter().keySaved();}
    @Override public void render(GuiGraphics graphics,int x,int y,float partial){
        renderBackground(graphics);graphics.drawCenteredString(font,title,width/2,12,0xffffff);for(int i=0;i<fields.length;i++)graphics.drawString(font,font.plainSubstrByWidth(labels[i],fields[i].getWidth()),fields[i].getX(),fields[i].getY()-10,0xdddddd);
        graphics.drawString(font,font.plainSubstrByWidth(notice,Math.max(10,width-40)),20,height-45,0xffd56a);super.render(graphics,x,y,partial);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){fields[1].setValue("");draft[1]="";minecraft.setScreen(parent);}
}
