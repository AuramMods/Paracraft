package com.aurammods.paracraft.forge.client;

import com.aurammods.paracraft.core.openai.UsageLedger;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.Util;
import java.net.URI;
import java.util.List;

/** Read-only local accounting; refresh does not make model requests or inspect Codex credentials. */
final class UsageScreen extends Screen {
    private final Screen parent;
    private int scroll;
    private long nextRefresh;
    UsageScreen(Screen parent) { super(Component.literal("Paracraft · Usage today")); this.parent=parent; }
    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Back"),b->onClose()).bounds(width-68,8,52,20).build());
        addRenderableWidget(Button.builder(Component.literal("Manage usage"),b->Util.getPlatform().openUri(URI.create(UsageLedger.MANAGE_USAGE_URL)))
                .bounds(16,height-28,120,20).build());
        addRenderableWidget(Button.builder(Component.literal("Luna API pricing"),b->Util.getPlatform().openUri(URI.create(UsageLedger.PRICING_URL)))
                .bounds(width-144,height-28,128,20).build());
        nextRefresh=0;tick();
    }
    @Override public void tick() {
        if(System.nanoTime()-nextRefresh<0)return;nextRefresh=System.nanoTime()+5_000_000_000L;
        var account=ForgeClientRuntime.accounts();if(account!=null)account.refreshUsage();
    }
    @Override public void render(GuiGraphics graphics,int mouseX,int mouseY,float partial) {
        renderBackground(graphics);graphics.drawString(font,title,16,14,0xffffff);
        graphics.drawString(font,font.plainSubstrByWidth(ForgeClientRuntime.providerLabel(),Math.max(10,width-32)),16,36,0xffd56a);
        var account=ForgeClientRuntime.accounts();var today=account==null?null:account.todayUsage();
        List<String> lines=today==null?List.of(ForgeClientRuntime.accountError()):java.util.stream.Stream.concat(
                java.util.stream.Stream.of(today.tokensLabel(),today.costLabel()),today.details().stream()).toList();
        var wrapped=lines.stream().flatMap(line->font.split(Component.literal(line),Math.max(100,width-40)).stream()).toList();
        scroll=Math.min(scroll,Math.max(0,wrapped.size()*12-Math.max(1,height-100)));
        graphics.enableScissor(16,58,width-16,Math.max(59,height-40));int y=60-scroll;
        for(var line:wrapped){graphics.drawString(font,line,20,y,0xdddddd);y+=12;}graphics.disableScissor();
        super.render(graphics,mouseX,mouseY,partial);
    }
    @Override public boolean mouseScrolled(double x,double y,double delta) {
        if(y<58 || y>=height-40)return super.mouseScrolled(x,y,delta);scroll=Math.max(0,scroll-(int)(delta*24));return true;
    }
    @Override public boolean isPauseScreen(){return false;}
    @Override public void onClose(){minecraft.setScreen(parent);}
}
