package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.AvatarEntry;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.aurammods.paracraft.forge.execution.GameJob;
import com.aurammods.paracraft.forge.execution.JobJson;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class ActionHistoryGameTests {
    @GameTest(batch = "action_history", template = "arena", timeoutTicks = 200)
    public static void nativeBlockEffectsSurvivePartialFailureAndReconnect(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        var entry = manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(48.5, 2, 46.5)),
                "QH" + UUID.randomUUID().toString().substring(0, 8));
        manager.actor(entry.id).setGameMode(GameType.CREATIVE);
        BlockPos first = helper.absolutePos(new BlockPos(48, 2, 48));
        BlockPos occupied = helper.absolutePos(new BlockPos(49, 2, 48));
        helper.getLevel().setBlockAndUpdate(occupied, Blocks.GOLD_BLOCK.defaultBlockState());
        GameJob place = submit(manager, owner, entry, """
                {"steps":[{"op":"place_blocks","placements":[
                    {"position":%s,"item":"minecraft:cobblestone"},
                    {"position":%s,"item":"minecraft:cobblestone"}]}]}
                """.formatted(JobJson.position(first), JobJson.position(occupied)));
        GameJob[] mine = {null};
        helper.onEachTick(() -> {
            if (mine[0] == null) {
                if (!place.state().terminal()) return;
                helper.assertTrue(place.state() == JobState.FAILED, "The occupied target stops the second placement");
                helper.assertTrue(helper.getLevel().getBlockState(first).is(Blocks.COBBLESTONE), "The first placement took effect");
                var history = history(manager, owner, entry);
                helper.assertTrue(history.equals(List.of("placed minecraft:cobblestone " + coordinates(first),
                                "failed place_blocks " + coordinates(occupied))),
                        "Only the actual placement and failure are recorded at their exact positions: " + history);
                manager.disconnectOwner(owner);
                var restoredEntry = AvatarEntry.load(entry.save());
                helper.assertTrue(restoredEntry.actionHistory.text().lines().toList().equals(history), "The text survives NBT round-trip");
                helper.assertTrue(manager.restoreOwner(owner).isEmpty(), "The avatar rebinds after disconnect");
                manager.actor(entry.id).getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
                mine[0] = submit(manager, owner, entry, """
                        {"steps":[{"op":"mine_blocks","block":%s}]}
                        """.formatted(JobJson.position(first)));
                return;
            }
            if (!mine[0].state().terminal()) return;
            helper.assertTrue(mine[0].state() == JobState.SUCCEEDED && helper.getLevel().getBlockState(first).isAir(),
                    "The restored body mines the actual block: " + mine[0].view());
            var history = history(manager, owner, entry);
            helper.assertTrue(history.size() == 3 && history.get(2).equals("mined minecraft:cobblestone " + coordinates(first)),
                    "New native effects append exactly once after rebind: " + history);
            manager.actor(entry.id).getInventory().clearContent();
            manager.destroy(owner, entry.id);
            helper.succeed();
        });
    }

    private static List<String> history(AvatarManager manager, UUID owner, AvatarEntry entry) {
        return manager.control().snapshot(owner, entry.id).get("actionHistory").getAsString().lines().toList();
    }

    private static GameJob submit(AvatarManager manager, UUID owner, AvatarEntry entry, String program) {
        return manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), UUID.randomUUID(),
                JsonParser.parseString(program).getAsJsonObject(), "replace");
    }

    private static String coordinates(BlockPos pos) { return pos.getX() + " " + pos.getY() + " " + pos.getZ(); }
}
