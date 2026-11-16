package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.AvatarEntry;
import com.aurammods.paracraft.forge.avatar.AvatarSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class AvatarGameTests {
    @GameTest(template = "empty")
    public static void disconnectRestoreRetainsItemsAndRevokesSession(GameTestHelper helper) throws Exception {
        var manager = ServerEvents.avatars(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        AvatarEntry entry = manager.spawn(owner, helper.getLevel(),
                helper.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 3, 2.5)), name());
        var actor = manager.actor(entry.id);
        actor.setNoGravity(true);
        UUID oldSession = entry.authority.session();
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 11);
        diamonds.getOrCreateTag().putString("qa_marker", "preserve_me");
        actor.getInventory().setItem(0, diamonds);
        actor.containerMenu.setCarried(diamonds.copyWithCount(3));
        actor.setHealth(13);
        actor.getFoodData().setFoodLevel(7);
        var position = actor.position();
        manager.disconnectOwner(owner);
        helper.assertTrue(manager.actor(entry.id) == null, "Actor was removed on disconnect");
        helper.assertTrue(helper.getLevel().getEntity(entry.id) == null, "World body was removed");
        helper.assertTrue(!entry.authority.controls(owner, oldSession), "Old inference session was revoked");
        var encoded = new ByteArrayOutputStream();
        AvatarSavedData isolated = new AvatarSavedData();
        isolated.put(entry);
        NbtIo.writeCompressed(isolated.save(new CompoundTag()), encoded);
        var decoded = AvatarSavedData.load(NbtIo.readCompressed(new ByteArrayInputStream(encoded.toByteArray())));
        var persisted = decoded.get(entry.id);
        helper.assertTrue(persisted.authority.session() == null, "Control session was not serialized");
        helper.assertTrue(persisted.physical.getList("Inventory", 10).size() == 1, "Inventory survived NBT round trip");
        manager.restoreOwner(owner);
        var restored = manager.actor(entry.id);
        helper.assertTrue(restored != actor && restored.position().distanceTo(position) < 0.001,
                "Restored new body at original saved position");
        helper.assertTrue(restored.getHealth() == 13 && restored.getFoodData().getFoodLevel() == 7,
                "Restored survival state");
        helper.assertTrue(restored.getInventory().getItem(0).getCount() == 14
                && "preserve_me".equals(restored.getInventory().getItem(0).getTag().getString("qa_marker")),
                "Restored inventory, carried cursor items, and custom stack data");
        helper.assertTrue(!entry.authority.controls(owner, oldSession)
                && entry.authority.controls(owner, entry.authority.session()), "New control lease only");
        restored.getInventory().clearContent();
        manager.destroy(owner, entry.id);
        pass("avatar_disconnect_restore");
        helper.succeed();
    }

    @GameTest(template = "empty")
    public static void destructionDropsSavedInventoryOnce(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        var entry = manager.spawn(owner, helper.getLevel(),
                helper.absoluteVec(new net.minecraft.world.phys.Vec3(3.5, 3, 3.5)), name());
        var actor = manager.actor(entry.id);
        actor.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 9));
        var box = actor.getBoundingBox().inflate(3);
        manager.disconnectOwner(owner);
        manager.destroy(owner, entry.id);
        long count = helper.getLevel().getEntitiesOfClass(ItemEntity.class, box).stream()
                .filter(e -> e.getItem().is(Items.DIAMOND)).mapToInt(e -> e.getItem().getCount()).sum();
        helper.assertTrue(count == 9, "Destroying saved avatar dropped nine real diamonds");
        try {
            manager.destroy(owner, entry.id);
            throw new AssertionError("Repeated destruction must not find an avatar");
        } catch (IllegalArgumentException expected) { }
        long again = helper.getLevel().getEntitiesOfClass(ItemEntity.class, box).stream()
                .filter(e -> e.getItem().is(Items.DIAMOND)).mapToInt(e -> e.getItem().getCount()).sum();
        helper.assertTrue(again == 9 && manager.entry(entry.id) == null, "No repeated drops or saved body");
        pass("avatar_destroy_saved_once");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void survivalActorTicksAndTakesDamage(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.STONE);
        var entry = manager.spawn(owner, helper.getLevel(),
                helper.absoluteVec(new net.minecraft.world.phys.Vec3(3.5, 2, 3.5)), name());
        var actor = manager.actor(entry.id);
        helper.runAfterDelay(70, () -> {
            helper.assertTrue(actor.tickCount >= 60, "Real player receives level ticks");
            float before = actor.getHealth();
            boolean hurt = actor.hurt(helper.getLevel().damageSources().generic(), 4);
            helper.assertTrue(hurt && actor.getHealth() < before, "Survival player is damageable after spawn grace");
            helper.assertTrue(actor.createCommandSourceStack().getEntity() == actor
                    && !actor.createCommandSourceStack().hasPermission(2), "Commands use actor identity without op elevation");
            manager.destroy(owner, entry.id);
            pass("avatar_survival_tick_damage");
            helper.succeed();
        });
    }

    private static String name() { return "QA" + UUID.randomUUID().toString().substring(0, 8); }
    private static void pass(String event) {
        Paracraft.LOG.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\"}", event);
    }
}
