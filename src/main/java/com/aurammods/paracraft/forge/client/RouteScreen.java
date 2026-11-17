package com.aurammods.paracraft.forge.client;

import com.aurammods.paracraft.core.agent.ClientCoordinator;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.UUID;

final class RouteScreen extends Screen {
    private final Screen parent; private final UUID avatar;
    private final String[] labels = {"Luna Light model", "Light reasoning effort", "Planning model", "Planning reasoning effort"};
    private final String[] draft;
    private final EditBox[] fields = new EditBox[4];
    private String notice = "Choose an account model or enter its exact slug; requests report actual API errors.";
    RouteScreen(Screen parent, ClientCoordinator.View view) {
        super(Component.literal("Model routes · " + view.name())); this.parent = parent; avatar = view.id();
        var state = view.memory(); draft = new String[] {state.lightModel, state.lightEffort, state.planningModel, state.planningEffort};
    }
    @Override protected void init() {
        for (int i = 0; i < fields.length; i++) {
            if (fields[i] != null) draft[i] = fields[i].getValue();
            int y = 45 + i * Math.max(30, (height - 112) / 4);
            boolean model=i==0 || i==2;fields[i] = new EditBox(font, 24, y, Math.max(100, width - (model?98:48)), 20, Component.literal(labels[i]));
            fields[i].setMaxLength(256); fields[i].setValue(draft[i]); addRenderableWidget(fields[i]);
            if(model){int index=i;addRenderableWidget(Button.builder(Component.literal("Pick"),b->minecraft.setScreen(new ModelScreen(this,slug->fields[index].setValue(slug)))).bounds(width-64,y,40,20).build());}
        }
        addRenderableWidget(Button.builder(Component.literal("Save"), b -> {
            try {
                var runtime = ForgeClientRuntime.coordinator(); if (runtime == null) throw new IllegalStateException("Join a world first");
                runtime.routes(avatar, fields[0].getValue().trim(), fields[1].getValue().trim(), fields[2].getValue().trim(), fields[3].getValue().trim());
                notice = "Saved locally. Live requests use this model and reasoning effort.";
            } catch (RuntimeException failure) { notice = failure.toString(); }
        }).bounds(width / 2 - 104, height - 28, 100, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).bounds(width / 2 + 4, height - 28, 100, 20).build());
    }
    @Override public void tick() { for (EditBox field : fields) field.tick(); }
    @Override public void render(GuiGraphics graphics, int x, int y, float partial) {
        renderBackground(graphics); graphics.drawCenteredString(font, title, width / 2, 12, 0xffffff);
        for (int i = 0; i < fields.length; i++) graphics.drawString(font, labels[i], 24, fields[i].getY() - 11, 0xdddddd);
        graphics.drawString(font, font.plainSubstrByWidth(notice, Math.max(10, width - 48)), 24, height - 42, 0xffd56a);
        super.render(graphics, x, y, partial);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
