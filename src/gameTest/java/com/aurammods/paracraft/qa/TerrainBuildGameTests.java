package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.core.program.LayerBlueprint;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.AvatarEntry;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.aurammods.paracraft.forge.execution.GameJob;
import com.aurammods.paracraft.forge.execution.JobJson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class TerrainBuildGameTests {
    @GameTest(template = "arena", batch = "layer_build", timeoutTicks = 420)
    public static void explicitPlacementsFinishASurvivalShelterAroundExistingWork(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        var entry = manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(48.5, 2, 44.5)), name());
        var actor = manager.actor(entry.id);
        actor.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 64));
        var origin = new BlockPos(46, 2, 46);
        helper.setBlock(origin, Blocks.COBBLESTONE);
        var program = json("""
                {"steps":[{"op":"place_blocks","saveAs":"build","placements":[]}]}
                """);
        var placements = program.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonArray("placements");
        for (int y = 0; y <= 2; y++) for (int z = 0; z < 5; z++) for (int x = 0; x < 5; x++) {
            boolean wall = x == 0 || x == 4 || z == 0 || z == 4;
            boolean doorway = x == 2 && z == 0;
            if (y < 2 && (!wall || doorway)) continue;
            var placement = new JsonObject();
            placement.add("position", JobJson.position(helper.absolutePos(origin.offset(x, y, z))));
            placement.addProperty("item", "minecraft:cobblestone");
            placements.add(placement);
        }
        var job = submit(manager, owner, entry, program);
        helper.onEachTick(() -> {
            helper.assertTrue(job.state() != JobState.FAILED, "Survival shelter failed: " + job.view());
            if (job.state() != JobState.SUCCEEDED) return;
            var result = job.result().getAsJsonObject("results").getAsJsonObject("build");
            helper.assertTrue(result.get("alreadyPresent").getAsInt() == 1 && result.get("placed").getAsInt() == 54,
                    "An already-placed block still allows the rest of the ordered program to run: " + result);
            helper.assertTrue(actor.getInventory().countItem(Items.COBBLESTONE) == 10, "Only actual placements consume materials");
            helper.assertBlockPresent(Blocks.COBBLESTONE, origin.offset(2, 2, 2));
            helper.assertBlockPresent(Blocks.AIR, origin.offset(2, 1, 0));
            manager.destroy(owner, entry.id);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", batch = "layer_build", timeoutTicks = 280)
    public static void survivalMiningReachesTheTopOfATrunkFromTheGround(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        var entry = manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(48.5, 2, 45.5)), name());
        var actor = manager.actor(entry.id);
        actor.getInventory().setItem(0, new ItemStack(Items.DIAMOND_AXE));
        var trunk = new BlockPos(48, 2, 47);
        for (int y = 0; y < 5; y++) helper.setBlock(trunk.above(y), Blocks.OAK_LOG);
        for (int y = 2; y < 5; y++) for (int z = -1; z <= 1; z++) for (int x = -1; x <= 1; x++) {
            if (x != 0 || z != 0) helper.setBlock(trunk.offset(x, y, z),
                    Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        }
        var program = json("""
                {"steps":[{"op":"mine_blocks","saveAs":"logs","blocks":[]}]}
                """);
        var blocks = program.getAsJsonArray("steps").get(0).getAsJsonObject().getAsJsonArray("blocks");
        for (int y = 0; y < 5; y++) blocks.add(JobJson.position(helper.absolutePos(trunk.above(y))));
        var job = submit(manager, owner, entry, program);
        helper.onEachTick(() -> {
            helper.assertTrue(job.state() != JobState.FAILED, "Tree mining failed: " + job.view());
            if (job.state() != JobState.SUCCEEDED) return;
            var result = job.result().getAsJsonObject("results").getAsJsonObject("logs");
            helper.assertTrue(result.get("broken").getAsInt() == 5 && actor.getInventory().countItem(Items.OAK_LOG) == 5,
                    "Every reachable log was mined and its drop picked up: " + result);
            helper.assertBlockPresent(Blocks.AIR, trunk.above(4));
            helper.assertBlockPresent(Blocks.OAK_LEAVES, trunk.offset(0, 3, -1));
            manager.destroy(owner, entry.id);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", batch = "layer_build", timeoutTicks = 200)
    public static void placementBatchesReportMissedCoordinatesAndKeepWorking(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        var entry = manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(48.5, 2, 45.5)), name());
        var actor = manager.actor(entry.id);
        actor.getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 5));
        var origin = new BlockPos(47, 3, 47);
        var occupied = origin.offset(0, -1, 2);
        var reachable = occupied.east(2);
        helper.setBlock(origin.offset(2, -1, 0), Blocks.GOLD_BLOCK);
        helper.setBlock(occupied, Blocks.GOLD_BLOCK);
        var job = submit(manager, owner, entry, json("""
                {"wakeOnSuccess":false,"steps":[{"try":[{"op":"build_layers","origin":%s,
                   "palette":[{"symbol":"x","block":"minecraft:cobblestone"},{"symbol":"a","block":"minecraft:air"}],
                   "layers":[{"y":[0],"rows":["xax"]}]}],"catch":[{"op":"report_failure","saveAs":"blueprint"}]},
                   {"op":"place_blocks","placements":[{"position":%s,"item":"minecraft:cobblestone"},
                                                       {"position":%s,"item":"minecraft:cobblestone"}]}]}
                """.formatted(JobJson.position(helper.absolutePos(origin)), JobJson.position(helper.absolutePos(occupied)),
                JobJson.position(helper.absolutePos(reachable)))));
        helper.onEachTick(() -> {
            if (!job.state().terminal()) return;
            var machine = job.result();
            helper.assertTrue(job.state() == JobState.FAILED, "Partial placement wakes as a failure: " + machine);
            var blueprint = machine.getAsJsonObject("results").getAsJsonObject("blueprint").getAsJsonObject("partial");
            var explicit = machine.getAsJsonObject("error").getAsJsonObject("partial");
            helper.assertTrue(blueprint.get("placed").getAsInt() == 1 && blueprint.get("unreachableCount").getAsInt() == 1
                    && JobJson.position(blueprint.getAsJsonArray("unreachablePositions").get(0)).equals(helper.absolutePos(origin)),
                    "Layered placement records the unsupported cell and places the next one");
            helper.assertTrue(explicit.get("placed").getAsInt() == 1 && explicit.get("unreachableCount").getAsInt() == 1
                    && JobJson.position(explicit.getAsJsonArray("unreachablePositions").get(0)).equals(helper.absolutePos(occupied)),
                    "Explicit placements record an occupied cell and place the next one");
            helper.assertBlockPresent(Blocks.COBBLESTONE, origin.east(2));
            helper.assertBlockPresent(Blocks.GOLD_BLOCK, occupied);
            helper.assertBlockPresent(Blocks.COBBLESTONE, reachable);
            helper.assertTrue(actor.getInventory().countItem(Items.COBBLESTONE) == 3, "Missed placements consumed no items");
            manager.destroy(owner, entry.id);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", batch = "layer_build", timeoutTicks = 140)
    public static void integralTravelTargetsTheCenterOfTheRequestedBlock(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        var entry = manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(48.5, 2, 45.5)), name());
        var actor = manager.actor(entry.id);
        var destination = helper.absolutePos(new BlockPos(54, 2, 48));
        var job = submit(manager, owner, entry, json("""
                {"steps":[{"op":"travel","destination":%s,"saveAs":"arrival"}]}
                """.formatted(JobJson.position(destination))));
        helper.onEachTick(() -> {
            helper.assertTrue(job.state() != JobState.FAILED, "Integral travel failed: " + job.view());
            if (job.state() != JobState.SUCCEEDED) return;
            var arrival = job.result().getAsJsonObject("results").getAsJsonObject("arrival");
            var center = Vec3.atBottomCenterOf(destination);
            helper.assertTrue(JobJson.vector(arrival.get("target")).equals(center) && actor.position().distanceTo(center) <= 0.7,
                    "Navigation and the result agree on the center of the target block");
            manager.destroy(owner, entry.id);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", batch = "layer_build", timeoutTicks = 280)
    public static void sharedLayerRowsBuildAShellAndPreserveAirCells(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        var entry = manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(48.5, 2, 45.5)), name());
        var actor = manager.actor(entry.id);
        actor.setGameMode(GameType.CREATIVE);
        BlockPos origin = new BlockPos(47, 2, 48);
        helper.setBlock(origin.offset(1, 1, 0), Blocks.GOLD_BLOCK);
        var blueprint = json("""
                {"steps":[{"op":"build_layers","origin":%s,"saveAs":"build",
                  "palette":[{"symbol":"x","block":"minecraft:stone"},{"symbol":"a","block":"minecraft:air"}],
                  "layers":[{"y":[0,2],"rows":["xxx","xxx"]},{"y":["1-1"],"rows":["xax","xax"]}]}]}
                """.formatted(JobJson.position(helper.absolutePos(origin))));
        GameJob[] job = {submit(manager, owner, entry, blueprint)};
        boolean[] retry = {false};
        helper.onEachTick(() -> {
            helper.assertTrue(job[0].state() != JobState.FAILED, "Layer build failed: " + job[0].view());
            if (job[0].state() != JobState.SUCCEEDED) return;
            var result = job[0].result().getAsJsonObject("results").getAsJsonObject("build");
            if (!retry[0]) {
                for (int y : new int[] {0, 2}) for (int z = 0; z < 2; z++) for (int x = 0; x < 3; x++)
                    helper.assertBlockPresent(Blocks.STONE, origin.offset(x, y, z));
                helper.assertBlockPresent(Blocks.GOLD_BLOCK, origin.offset(1, 1, 0));
                helper.assertBlockPresent(Blocks.AIR, origin.offset(1, 1, 1));
                helper.assertTrue(result.get("placed").getAsInt() == 16 && result.get("skipped").getAsInt() == 2
                        && result.get("consumed").getAsInt() == 0, "Native Creative placement matches the requested cells");
                retry[0] = true;
                job[0] = submit(manager, owner, entry, blueprint);
                return;
            }
            helper.assertTrue(result.get("alreadyPresent").getAsInt() == 16 && result.get("placed").getAsInt() == 0,
                    "A repeated blueprint recognizes matching blocks");
            manager.destroy(owner, entry.id);
            helper.succeed();
        });
    }

    @GameTest(template = "arena", batch = "layer_build", timeoutTicks = 100)
    public static void terrainSamplingReturnsBothSizesAndMarksMaskedCoverage(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer());
        UUID owner = UUID.randomUUID();
        var entry = manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(45.5, 2, 48.5)), name());
        var actor = manager.actor(entry.id);
        var work = submit(manager, owner, entry, json("{\"steps\":[{\"op\":\"wait_ticks\",\"ticks\":80}]}"));
        Vec3 before = actor.position();
        var center = new BlockPos(48, 8, 48);
        helper.setBlock(center.offset(1, -2, -1), Blocks.STONE);
        helper.setBlock(center.offset(2, -2, -1), Blocks.DIRT);
        helper.setBlock(center.offset(3, -2, -1), Blocks.OAK_PLANKS);
        var absolute = helper.absolutePos(center);
        var compact = sample(manager, owner, entry, absolute, 8, "[]");
        var filtered = sample(manager, owner, entry, absolute, 16, "[\"minecraft:stone\",\"minecraft:grass_block\",\"minecraft:dirt\"]");
        var remote = sample(manager, owner, entry, absolute.offset(160, 0, 0), 8, "[]");
        var small = new LayerBlueprint(compact);
        var large = new LayerBlueprint(filtered);
        helper.assertTrue(small.size() == 512 && large.size() == 4096 && compact.get("complete").getAsBoolean(), "Both sample sizes are complete cubes");
        helper.assertTrue(small.cell(0).position().x() == absolute.getX() - 4 && large.cell(0).position().z() == absolute.getZ() - 8,
                "Even-sized coordinates anchor at center minus half the size");
        helper.assertTrue(small.cell(index(8, 3, -2, -1)).block().equals("minecraft:oak_planks")
                && large.cell(index(16, 1, -2, -1)).block().equals("minecraft:stone")
                && large.cell(index(16, 2, -2, -1)).block().equals("minecraft:dirt")
                && large.cell(index(16, 3, -2, -1)).block().equals(LayerBlueprint.OTHER),
                "The same palette and layer parser round-trips actual world coordinates and filter masks");
        helper.assertTrue(filtered.getAsJsonArray("palette").toString().contains("\"symbol\":\"Z\"")
                && remote.get("unavailableCells").getAsInt() == 512 && !remote.get("complete").getAsBoolean()
                && remote.getAsJsonArray("palette").toString().contains("\"symbol\":\"Y\""),
                "Filtered cells and beyond-scope cells use distinct markers");
        helper.assertTrue(manager.jobs().get(entry.id) == work && work.state() == JobState.RUNNING && actor.position().equals(before),
                "Sampling retains ongoing work and does not move the avatar");
        manager.destroy(owner, entry.id);
        helper.succeed();
    }

    private static JsonObject sample(AvatarManager manager, UUID owner, AvatarEntry entry, BlockPos center, int size, String filter) {
        var args = json("""
                {"center":%s,"size":%d,"filter":%s}
                """.formatted(JobJson.position(center), size, filter));
        var reply = manager.control().request(owner, entry.id, entry.authority.session(), UUID.randomUUID(), "sample_terrain", args, 0).join();
        if (!reply.get("ok").getAsBoolean()) throw new AssertionError(reply);
        return reply.getAsJsonObject("value").getAsJsonObject("observation");
    }

    private static int index(int size, int x, int y, int z) {
        int half = size / 2;
        return (y + half) * size * size + (z + half) * size + x + half;
    }

    private static GameJob submit(AvatarManager manager, UUID owner, AvatarEntry entry, JsonObject program) {
        return manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), UUID.randomUUID(), program, "replace");
    }
    private static JsonObject json(String source) { return JsonParser.parseString(source).getAsJsonObject(); }
    private static String name() { return "QL" + UUID.randomUUID().toString().substring(0, 8); }
}
