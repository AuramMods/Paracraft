package com.aurammods.paracraft.forge.client;

import com.aurammods.paracraft.core.openai.ChatGptClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import java.util.concurrent.CompletableFuture;

/** Browser sign-in starts only from an explicit user action, and never during automated QA. */
final class AccountScreen extends Screen {
    private final Screen parent;private int index;private String notice="";private boolean busy;private Button connect,cancel,use,add,simulate,logout;
    AccountScreen(Screen parent){super(Component.literal("Paracraft · Accounts and providers"));this.parent=parent;}
    private ChatGptClient client(){return ForgeClientRuntime.accounts();}
    @Override protected void init(){
        int half=(width-48)/2;
        connect=addRenderableWidget(Button.builder(Component.literal("Continue with ChatGPT"),b->login(false)).bounds(20,62,width-128,20).build());
        addRenderableWidget(Button.builder(Component.literal("OpenRouter"),b->minecraft.setScreen(new OpenRouterScreen(this))).bounds(width-100,62,80,20).build());
        addRenderableWidget(Button.builder(Component.literal("<"),b->index--).bounds(20,90,24,20).build());addRenderableWidget(Button.builder(Component.literal(">"),b->index++).bounds(width-44,90,24,20).build());
        use=addRenderableWidget(Button.builder(Component.literal("Use selected account"),b->{var a=selected();if(a!=null)observe(client().select(a.id()));}).bounds(20,118,half,20).build());
        add=addRenderableWidget(Button.builder(Component.literal("Add account"),b->login(true)).bounds(28+half,118,half,20).build());
        simulate=addRenderableWidget(Button.builder(Component.literal("Use simulator"),b->observe(client().simulate())).bounds(20,146,half,20).build());
        logout=addRenderableWidget(Button.builder(Component.literal("Sign out active"),b->observe(client().mode().equals("openrouter")?client().forgetOpenRouter():client().signOut())).bounds(28+half,146,half,20).build());
        cancel=addRenderableWidget(Button.builder(Component.literal("Cancel sign-in"),b->{client().cancelLogin();busy=false;}).bounds(20,height-28,half,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"),b->onClose()).bounds(28+half,height-28,half,20).build());
        addRenderableWidget(Button.builder(Component.literal("ChatGPT usage"),b->Util.getPlatform().openUri(java.net.URI.create("https://chatgpt.com/settings/usage"))).bounds(20,height-54,half,20).build());
        addRenderableWidget(Button.builder(Component.literal("Reload saved session"),b->{ForgeClientRuntime.reloadAccounts();notice="Reloading local account state";}).bounds(28+half,height-54,half,20).build());tick();
    }
    private ChatGptClient.Account selected(){var c=client();if(c==null || c.accounts().isEmpty())return null;index=Math.floorMod(index,c.accounts().size());return c.accounts().get(index);}
    private void login(boolean another){if(client()==null)return;busy=true;notice="Preparing local sign-in";client().signIn(another).whenComplete((uri,error)->minecraft.execute(()->{busy=false;if(error!=null){notice=ChatGptClient.message(error);return;}notice="Continue in your browser, then return here";client().loginCompletion().whenComplete((result,failure)->minecraft.execute(()->notice=failure==null?client().notice():ChatGptClient.message(failure)));Util.getPlatform().openUri(uri);}));}
    private void observe(CompletableFuture<?> future){busy=true;future.whenComplete((result,error)->minecraft.execute(()->{busy=false;notice=error==null?client().notice():ChatGptClient.message(error);}));}
    @Override public void tick(){var c=client();boolean ready=c!=null && !busy;connect.active=add.active=ready && !c.signingIn();use.active=ready && selected()!=null;simulate.active=logout.active=ready;logout.setMessage(Component.literal(c!=null && c.mode().equals("openrouter")?"Remove API key":"Sign out active"));cancel.active=c!=null && c.signingIn();}
    @Override public void render(GuiGraphics graphics,int x,int y,float partial){renderBackground(graphics);graphics.drawCenteredString(font,title,width/2,12,0xffffff);line(graphics,ForgeClientRuntime.providerLabel(),32);line(graphics,client()==null?ForgeClientRuntime.accountError():client().usage(),44);var selected=selected();line(graphics,selected==null?"No saved accounts":selected.label()+(selected.signedIn()?selected.planEnabled()?" · plan enabled":" · plan permission missing":" · signed out"),96);line(graphics,notice.isEmpty() && client()!=null?client().notice():notice,height-70);super.render(graphics,x,y,partial);}
    private void line(GuiGraphics graphics,String text,int y){graphics.drawCenteredString(font,font.plainSubstrByWidth(text,Math.max(10,width-100)),width/2,y,0xdddddd);}
    @Override public boolean isPauseScreen(){return false;}@Override public void onClose(){minecraft.setScreen(parent);}
}
