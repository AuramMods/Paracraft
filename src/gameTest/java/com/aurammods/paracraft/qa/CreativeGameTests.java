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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class CreativeGameTests {
    @GameTest(template = "empty", batch = "creative", timeoutTicks = 160)
    public static void nativeCreativeCatalogSuppliesEmptyInventoryAndRespectsGameMode(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
        var entry = spawn(helper, manager, owner, new Vec3(2.5, 2, 2.5));
        var actor = manager.actor(entry.id);
        var denied = submit(manager, owner, entry, """
                {"steps":[{"op":"take_creative_items","item":"minecraft:diamond","quantity":1}]}
                """);
        BlockPos placed = new BlockPos(4, 2, 2), chestPosition = new BlockPos(2, 2, 4);
        GameJob[] creative = {null};
        helper.onEachTick(() -> {
            if (creative[0] == null) {
                if (!denied.state().terminal()) return;
                helper.assertTrue(denied.state() == JobState.FAILED && actor.getInventory().countItem(Items.DIAMOND) == 0,
                        "Survival cannot take creative stock: " + denied.view());
                var server = helper.getLevel().getServer();
                server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "gamemode creative " + entry.name);
                var snapshot = manager.control().snapshot(owner, entry.id);
                helper.assertTrue(snapshot.get("gameMode").getAsString().equals("creative") && snapshot.get("creativeItems").getAsBoolean()
                        && snapshot.get("canFly").getAsBoolean(), "Native gamemode changes are visible in the snapshot");
                actor.getInventory().setItem(0, new ItemStack(Items.STICK));
                helper.setBlock(chestPosition, Blocks.CHEST);
                creative[0] = submit(manager, owner, entry, """
                        {"steps":[
                          {"op":"search_creative_items","query":"enchanted_book","limit":2,"saveAs":"catalog"},
                          {"op":"take_creative_items","item":"minecraft:enchanted_book","variant":1,"quantity":1},
                          {"try":[{"op":"take_creative_items","item":"minecraft:command_block"}],
                           "catch":[{"op":"report_failure","saveAs":"hidden"}]},
                          {"op":"equip","item":"minecraft:diamond_helmet","slot":"head"},
                          {"op":"place_blocks","placements":[{"position":%s,"item":"minecraft:cobblestone"}],"saveAs":"placement"},
                          {"op":"deposit_items","destination":%s,"item":"minecraft:diamond","quantity":70,"saveAs":"deposit"},
                          {"op":"drop_items","item":"minecraft:emerald","quantity":3,"saveAs":"dropped"}]}
                        """.formatted(JobJson.position(helper.absolutePos(placed)), JobJson.position(helper.absolutePos(chestPosition))));
                return;
            }
            var job = creative[0];
            helper.assertTrue(job.state() != JobState.FAILED, job.view().toString());
            if (job.state() != JobState.SUCCEEDED) return;
            var results = job.result().getAsJsonObject("results");
            var catalog = results.getAsJsonObject("catalog");
            var stack = actor.getInventory().items.stream().filter(item -> item.is(Items.ENCHANTED_BOOK)).findFirst().orElse(ItemStack.EMPTY);
            helper.assertTrue(catalog.getAsJsonArray("entries").size() == 2 && catalog.has("nextOffset")
                    && stack.hasTag() && results.has("hidden") && actor.getInventory().countItem(Items.COMMAND_BLOCK) == 0,
                    "Search returns real stack variants and rejects inaccessible operator stock");
            helper.assertTrue(actor.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET) && actor.getMainHandItem().is(Items.STICK),
                    "Creative equipment and placement preserve the carried hand item");
            helper.assertBlockPresent(Blocks.COBBLESTONE, placed);
            var chest = (ChestBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(chestPosition));
            helper.assertTrue(chest.countItem(Items.DIAMOND) == 70 && actor.getInventory().countItem(Items.DIAMOND) == 0
                    && results.getAsJsonObject("placement").get("consumed").getAsInt() == 0
                    && results.getAsJsonObject("dropped").get("generated").getAsInt() == 3,
                    "Creative supplies span multiple native transfer stacks and require no carried materials");
            actor.getInventory().clearContent();
            manager.destroy(owner, entry.id);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", batch = "creative", timeoutTicks = 200)
    public static void travelUsesGrantedFlightOutsideCreativeAndAvoidsSolidWalls(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, new Vec3(42.5, 2, 48.5));
        var actor = manager.actor(entry.id);
        for (int z = 44; z <= 52; z++) for (int y = 2; y <= 5; y++) helper.setBlock(new BlockPos(46, y, z), Blocks.STONE);
        actor.getAbilities().mayfly = true;
        Vec3 destination = helper.absoluteVec(new Vec3(50.5, 5, 48.5));
        var job = submit(manager, owner, entry, """
                {"steps":[{"op":"travel","destination":%s,"saveAs":"travel"}]}
                """.formatted(JobJson.vector(destination)));
        Vec3[] previous = {actor.position()};
        int[] ticksSinceArrival = {0};
        helper.onEachTick(() -> {
            if (ticksSinceArrival[0] > 0) {
                if (++ticksSinceArrival[0] < 3) return;
                helper.assertTrue(!actor.getAbilities().flying, "Revoking the ability releases hover");
                manager.destroy(owner, entry.id);
                helper.succeed();
                return;
            }
            helper.assertTrue(job.state() != JobState.FAILED, job.view().toString());
            helper.assertTrue(previous[0].distanceTo(actor.position()) < 1 && helper.getLevel().noCollision(actor, actor.getBoundingBox()),
                    "Flight moves physically and stays outside obstructions");
            previous[0] = actor.position();
            if (job.state() != JobState.SUCCEEDED) return;
            var navigation = job.result().getAsJsonObject("results").getAsJsonObject("travel").getAsJsonObject("navigation");
            helper.assertTrue(!actor.isCreative() && actor.getAbilities().flying && actor.position().distanceTo(destination) < 0.8
                    && navigation.get("mode").getAsString().equals("flying") && navigation.get("replans").getAsInt() > 0,
                    "Normal travel uses permitted three-dimensional flight around the wall to a midair destination");
            helper.assertBlockPresent(Blocks.STONE, new BlockPos(46, 5, 48));
            ticksSinceArrival[0] = 1;
            actor.getAbilities().mayfly = false;
        });
    }

    private static AvatarEntry spawn(GameTestHelper helper, AvatarManager manager, UUID owner, Vec3 relative) {
        return manager.spawn(owner, helper.getLevel(), helper.absoluteVec(relative), "QC" + UUID.randomUUID().toString().substring(0, 8));
    }

    private static GameJob submit(AvatarManager manager, UUID owner, AvatarEntry entry, String program) {
        return manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), UUID.randomUUID(),
                JsonParser.parseString(program).getAsJsonObject(), "replace");
    }
}
