package com.aurammods.paracraft.forge.client;

import com.aurammods.paracraft.core.openai.ChatGptClient;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Catalog is advisory; a request is never blocked by a guessed model/effort compatibility list. */
final class ModelScreen extends Screen {
    private final Screen parent;private final Consumer<String> choose;private EditBox search;private int index;private String notice="";
    ModelScreen(Screen parent,Consumer<String> choose){super(Component.literal("Available account models"));this.parent=parent;this.choose=choose;}
    private List<ChatGptClient.Model> filtered(){var c=ForgeClientRuntime.accounts();if(c==null)return List.of();String query=search==null?"":search.getValue().toLowerCase(Locale.ROOT);return c.models().stream().filter(m->(m.slug()+" "+m.name()).toLowerCase(Locale.ROOT).contains(query)).toList();}
    @Override protected void init(){String old=search==null?"":search.getValue();search=new EditBox(font,20,44,width-40,20,Component.literal("Filter models"));search.setHint(Component.literal("Filter models"));search.setValue(old);search.setResponder(value->index=0);addRenderableWidget(search);
        addRenderableWidget(Button.builder(Component.literal("Refresh"),b->refresh()).bounds(width-92,8,72,20).build());addRenderableWidget(Button.builder(Component.literal("<"),b->index--).bounds(20,82,30,20).build());addRenderableWidget(Button.builder(Component.literal(">"),b->index++).bounds(width-50,82,30,20).build());
        if(choose!=null)addRenderableWidget(Button.builder(Component.literal("Use model"),b->{var models=filtered();if(!models.isEmpty()){choose.accept(models.get(Math.floorMod(index,models.size())).slug());onClose();}}).bounds(20,height-28,(width-48)/2,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"),b->onClose()).bounds(width/2+4,height-28,(width-48)/2,20).build());
        if(ForgeClientRuntime.accounts()!=null && ForgeClientRuntime.accounts().models().isEmpty())refresh();
    }
    private void refresh(){var client=ForgeClientRuntime.accounts();if(client==null){notice=ForgeClientRuntime.accountError();return;}notice="Fetching account model catalog";client.refreshModels().whenComplete((models,error)->minecraft.execute(()->notice=error==null?"Select the exact model slug; effort is set in Model routes":ChatGptClient.message(error)));}
    @Override public void tick(){search.tick();}
    @Override public void render(GuiGraphics graphics,int x,int y,float partial){renderBackground(graphics);graphics.drawString(font,font.plainSubstrByWidth(title.getString(),width-120),20,14,0xffffff);var models=filtered();String text="No matching account models";if(!models.isEmpty()){index=Math.floorMod(index,models.size());var model=models.get(index);text=(index+1)+"/"+models.size()+" · "+model.name()+"\n"+model.slug()+"\n"+model.details();}
        int line=86;for(var wrapped:font.split(Component.literal(text),Math.max(40,width-128))){graphics.drawCenteredString(font,wrapped,width/2,line,0xdddddd);line+=12;}graphics.drawString(font,font.plainSubstrByWidth(notice,Math.max(10,width-40)),20,height-46,0xffd56a);super.render(graphics,x,y,partial);}
    @Override public boolean isPauseScreen(){return false;}@Override public void onClose(){minecraft.setScreen(parent);}
}
