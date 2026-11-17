package com.aurammods.paracraft.forge.client;

import com.aurammods.paracraft.core.agent.ClientCoordinator;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Explicitly opened owner controls. Account state never implies a working sign-in flow. */
public final class CompanionScreen extends Screen {
    private final Screen parent;
    private List<ClientCoordinator.View> views = List.of();
    private UUID selected;
    private int scroll;
    private String notice = "";
    private long nextUsageRefresh;
    private EditBox name;
    private Button previous, next, pause, destroy, routes, spawn, retry;
    public CompanionScreen(Screen parent) { super(Component.literal("Paracraft")); this.parent = parent; }
    @Override protected void init() {
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(width - 62, 8, 50, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Accounts"),b->minecraft.setScreen(new AccountScreen(this))).bounds(width-168,8,98,20).build());
        addRenderableWidget(Button.builder(Component.literal("Usage details"), b -> minecraft.setScreen(new UsageScreen(this))).bounds(width-124,66,108,18).build());
        previous = addRenderableWidget(Button.builder(Component.literal("<"), b -> select(-1)).bounds(16, 88, 24, 20).build());
        next = addRenderableWidget(Button.builder(Component.literal(">"), b -> select(1)).bounds(width - 40, 88, 24, 20).build());
        int column = (width - 44) / 4;
        pause = addRenderableWidget(Button.builder(Component.literal("Pause"), b -> {
            var view = selected(); if (view != null) observe(runtime().pause(view.id(), !paused(view)));
        }).bounds(16, height - 70, column, 20).build());
        destroy = addRenderableWidget(Button.builder(Component.literal("Destroy & drop"), b -> {
            var view = selected(); if (view != null) observe(runtime().destroy(view.id()));
        }).bounds(20 + column, height - 70, column, 20).build());
        routes = addRenderableWidget(Button.builder(Component.literal("Model routes"), b -> {
            var view = selected(); if (view != null) minecraft.setScreen(new RouteScreen(this, view));
        }).bounds(24 + 2 * column, height - 70, column, 20).build());
        retry = addRenderableWidget(Button.builder(Component.literal("Reconnect"), b -> ForgeClientRuntime.retry())
                .bounds(28 + 3 * column, height - 70, column, 20).build());
        String draft = name == null ? "" : name.getValue(); name = new EditBox(font, 16, height - 42, Math.max(80, width - 120), 20, Component.literal("Companion name"));
        name.setMaxLength(16); name.setHint(Component.literal("Companion name")); name.setValue(draft); addRenderableWidget(name);
        spawn = addRenderableWidget(Button.builder(Component.literal("Spawn"), b -> observe(runtime().spawn(name.getValue())))
                .bounds(width - 96, height - 42, 80, 20).build());
        refresh();
    }
    private ClientCoordinator runtime() { return ForgeClientRuntime.coordinator(); }
    private void refresh() {
        views = runtime() == null ? List.of() : runtime().views();
        if (selected == null || views.stream().noneMatch(v -> v.id().equals(selected))) selected = views.isEmpty() ? null : views.get(0).id();
        var view = selected(); boolean connected = runtime() != null;
        previous.active = next.active = views.size() > 1; pause.active = destroy.active = view != null && connected;
        routes.active = view != null && view.memory() != null; spawn.active = retry.active = connected;
        if (view != null) pause.setMessage(Component.literal(paused(view) ? "Resume" : "Pause"));
    }
    private ClientCoordinator.View selected() { return views.stream().filter(v -> v.id().equals(selected)).findFirst().orElse(null); }
    private static boolean paused(ClientCoordinator.View view) { return view.snapshot().get("paused").getAsBoolean() || view.memory() != null && view.memory().paused(); }
    private void select(int direction) {
        if (views.isEmpty()) return; int index = 0; for (int i = 0; i < views.size(); i++) if (views.get(i).id().equals(selected)) index = i;
        selected = views.get(Math.floorMod(index + direction, views.size())).id(); scroll = 0; refresh();
    }
    private void observe(CompletableFuture<JsonObject> future) {
        notice = "Waiting for server...";
        future.whenComplete((reply, failure) -> notice = failure != null ? failure.toString()
                : reply.get("ok").getAsBoolean() ? "Saved." : reply.get("error").getAsString());
    }
    @Override public void tick() {
        name.tick(); refresh();
        if (System.nanoTime() - nextUsageRefresh >= 0) {
            nextUsageRefresh = System.nanoTime() + 5_000_000_000L;
            var account = ForgeClientRuntime.accounts(); if (account != null) account.refreshUsage();
        }
    }
    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawString(font, title, 16, 14, 0xffffff);
        graphics.drawCenteredString(font,font.plainSubstrByWidth(ForgeClientRuntime.providerLabel(),Math.max(10,width-32)),width/2,32,0xffd56a);
        var account=ForgeClientRuntime.accounts();
        var today=account==null?null:account.todayUsage();
        graphics.drawString(font,font.plainSubstrByWidth(today==null?"Usage unavailable":today.tokensLabel(),Math.max(10,width-32)),16,44,0xdddddd);
        graphics.drawString(font,font.plainSubstrByWidth(today==null?ForgeClientRuntime.accountError():today.costLabel(),Math.max(10,width-32)),16,56,0xb5e8b5);
        graphics.drawString(font,"Codex left: unavailable",16,70,0xaaaaaa);
        var view = selected(); graphics.drawCenteredString(font, view == null ? "No companion selected" : view.name(), width / 2, 94, 0xffffff);
        List<String> lines = new ArrayList<>();
        lines.add(runtime() == null ? "Join a Paracraft world to manage companions." : runtime().status());
        if(account!=null)lines.add(account.notice());
        if (view != null) {
            lines.add("Status: " + view.status()); JsonObject frame = view.snapshot();
            if (frame.has("health")) lines.add("Health " + frame.get("health") + " · Food " + frame.get("food") + " · Oxygen " + frame.get("oxygen"));
            if (frame.has("position")) lines.add(frame.get("dimension").getAsString() + " " + frame.get("position"));
            if (frame.has("controllerStatus")) lines.add(frame.get("controllerStatus").getAsString());
            if (frame.has("survival")) {
                var survival = frame.getAsJsonObject("survival"); lines.add("Server behavior: " + survival.get("mode"));
                if (survival.has("behavior")) lines.add("Players: " + survival.getAsJsonObject("behavior").get("players") + " · Mobs: " + survival.getAsJsonObject("behavior").get("mobs"));
                if (survival.has("reflex")) lines.add("Reflex: " + survival.getAsJsonObject("reflex").get("phase"));
            }
            lines.add("Decisions: " + view.decisions() + " · Planning: " + view.planningDecisions());
            if (view.freshMemory()) lines.add("Fresh local memory. Saved server operations are preserved.");
            if (view.memory() != null) {
                lines.add("Personality: " + view.memory().personality);
                for (var task : view.memory().tasks()) {
                    JsonObject value = task.getAsJsonObject(); lines.add(value.get("status").getAsString() + " · " + value.get("goal").getAsString());
                }
                if (view.memory().tasks().isEmpty()) lines.add("No tasks. Mention " + view.name() + " in chat to give an instruction.");
            }
        } else lines.add("Spawn a companion below, then try: Josh, collect the diamonds nearby and give them to me.");
        var wrappedLines = lines.stream().flatMap(line -> font.split(Component.literal(line), Math.max(100, width - 40)).stream()).toList();
        scroll = Math.min(scroll, Math.max(0, wrappedLines.size() * 11 - Math.max(1, height - 194)));
        graphics.enableScissor(16, 114, width - 16, Math.max(115, height - 80)); int y = 116 - scroll;
        for (var wrapped : wrappedLines) {
            graphics.drawString(font, wrapped, 20, y, 0xdddddd); y += 11;
        }
        graphics.disableScissor(); graphics.drawString(font, font.plainSubstrByWidth(notice, Math.max(10, width - 32)), 16, height - 14, 0xffd56a);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
    @Override public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (mouseY < 114 || mouseY >= height - 80) return super.mouseScrolled(mouseX, mouseY, delta);
        scroll = Math.max(0, scroll - (int)(delta * 22)); return true;
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
