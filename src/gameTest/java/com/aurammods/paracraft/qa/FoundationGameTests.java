package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class FoundationGameTests {
    @GameTest(template = "empty")
    public static void chestCapabilityUsesRealWorld(GameTestHelper helper) {
        BlockPos pos = helper.absolutePos(new BlockPos(2, 2, 2));
        helper.getLevel().setBlockAndUpdate(pos, Blocks.CHEST.defaultBlockState());
        var blockEntity = helper.getLevel().getBlockEntity(pos);
        helper.assertTrue(blockEntity != null, "Chest block entity was created");
        var handler = blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).orElseThrow(
                () -> new AssertionError("Chest must expose Forge item handling"));
        ItemStack remainder = handler.insertItem(0, new ItemStack(Items.DIAMOND, 7), false);
        helper.assertTrue(remainder.isEmpty(), "Chest accepted diamonds");
        ItemStack extracted = handler.extractItem(0, 3, false);
        helper.assertTrue(extracted.is(Items.DIAMOND) && extracted.getCount() == 3,
                "Actual extraction returned three diamonds");
        helper.assertTrue(handler.getStackInSlot(0).getCount() == 4,
                "Actual chest retained four diamonds");
        Paracraft.LOG.info("QA_EVT {\"event\":\"real_chest_capability\",\"status\":\"pass\",\"details\":\"7 inserted, 3 extracted, 4 retained in Minecraft chest\"}");
        helper.succeed();
    }
}
