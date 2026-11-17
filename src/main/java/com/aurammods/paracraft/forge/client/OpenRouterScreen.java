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
    private final String[] labels={"API key (blank keeps saved key)","Light model ID","Light reasoning effort (auto = provider default)","Planning model ID (blank uses Light)","Planning reasoning effort (auto = provider default)"};
    private final EditBox[] fields=new EditBox[5];
    private final String[] draft;
    private String notice="Model routes apply to all local companions while OpenRouter is selected.";
    private boolean busy;
    private Button save,remove;
    OpenRouterScreen(Screen parent){
        super(Component.literal("Paracraft · OpenRouter"));this.parent=parent;var client=ForgeClientRuntime.accounts();var settings=client==null?null:client.openRouter();draft=new String[]{"",settings==null?"":settings.lightModel(),settings==null?"auto":settings.lightEffort(),settings==null?"":settings.planningModel(),settings==null?"auto":settings.planningEffort()};
    }
    @Override protected void init(){
        for(int i=0;i<fields.length;i++){
            if(fields[i]!=null)draft[i]=fields[i].getValue();int y=46+i*Math.max(31,(height-115)/5);fields[i]=new EditBox(font,20,y,width-40,20,Component.literal(labels[i]));fields[i].setMaxLength(i==0?4096:256);fields[i].setValue(draft[i]);
            if(i==0){fields[i].setFormatter((value,index)->FormattedCharSequence.forward("*".repeat(value.length()),Style.EMPTY));var client=ForgeClientRuntime.accounts();fields[i].setHint(Component.literal(client!=null && client.openRouter().keySaved()?"API key saved locally":"Paste your OpenRouter API key"));}
            if(i==1)fields[i].setHint(Component.literal("provider/exact-model-id"));addRenderableWidget(fields[i]);
        }
        int column=(width-48)/3;
        save=addRenderableWidget(Button.builder(Component.literal("Save & use"),b->save()).bounds(16,height-28,column,20).build());
        remove=addRenderableWidget(Button.builder(Component.literal("Remove key"),b->{var client=ForgeClientRuntime.accounts();if(client!=null)observe(client.forgetOpenRouter());}).bounds(24+column,height-28,column,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"),b->onClose()).bounds(32+column*2,height-28,column,20).build());
    }
    private void save(){
        var client=ForgeClientRuntime.accounts();if(client==null){notice=ForgeClientRuntime.accountError();return;}
        try{observe(client.configureOpenRouter(fields[0].getValue(),fields[1].getValue(),fields[2].getValue(),fields[3].getValue(),fields[4].getValue()));}catch(RuntimeException error){notice=ChatGptClient.message(error);}
    }
    private void observe(java.util.concurrent.CompletableFuture<?> future){busy=true;future.whenComplete((value,error)->minecraft.execute(()->{busy=false;notice=error==null?ForgeClientRuntime.accounts().notice():ChatGptClient.message(error);if(error==null){fields[0].setValue("");draft[0]="";fields[0].setHint(Component.literal(ForgeClientRuntime.accounts().openRouter().keySaved()?"API key saved locally":"Paste your OpenRouter API key"));}}));}
    @Override public void tick(){for(var field:fields)field.tick();save.active=!busy && ForgeClientRuntime.accounts()!=null;remove.active=save.active && ForgeClientRuntime.accounts().openRouter().keySaved();}
    @Override public void render(GuiGraphics graphics,int x,int y,float partial){
        renderBackground(graphics);graphics.drawCenteredString(font,title,width/2,12,0xffffff);for(int i=0;i<fields.length;i++)graphics.drawString(font,font.plainSubstrByWidth(labels[i],width-40),20,fields[i].getY()-10,0xdddddd);
        graphics.drawString(font,font.plainSubstrByWidth(notice,Math.max(10,width-40)),20,height-45,0xffd56a);super.render(graphics,x,y,partial);
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){fields[0].setValue("");draft[0]="";minecraft.setScreen(parent);}
}
