package com.aurammods.paracraft.forge.client;

import com.aurammods.paracraft.Paracraft;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Mod.EventBusSubscriber(modid = Paracraft.ID, value = Dist.CLIENT)
public final class AvatarStatusOverlay {
    private static final Map<UUID, String> LABELS = new HashMap<>();
    private AvatarStatusOverlay() { }
    public static void receive(UUID avatar, String label) { LABELS.put(avatar, label); }
    public static void clear() { LABELS.clear(); }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void render(RenderNameTagEvent event) {
        var minecraft = Minecraft.getInstance(); var entity = event.getEntity();
        if (event.getResult() == Event.Result.DENY || minecraft.options.hideGui || entity.isInvisible()
                || !(minecraft.hitResult instanceof EntityHitResult hit) || hit.getEntity() != entity) return;
        String label = LABELS.get(entity.getUUID()); if (label == null || label.isEmpty()) return;
        var pose = event.getPoseStack(); pose.pushPose();
        pose.translate(0, entity.getBbHeight() + 1.0, 0);
        pose.mulPose(minecraft.getEntityRenderDispatcher().cameraOrientation()); pose.scale(-0.025F, -0.025F, 0.025F);
        int background = (int)(minecraft.options.getBackgroundOpacity(0.25F) * 255) << 24;
        minecraft.font.drawInBatch(label, -minecraft.font.width(label) / 2F, 0, 0xFFE0E0E0, false,
                pose.last().pose(), event.getMultiBufferSource(), Font.DisplayMode.NORMAL, background, event.getPackedLight());
        pose.popPose();
    }
}
