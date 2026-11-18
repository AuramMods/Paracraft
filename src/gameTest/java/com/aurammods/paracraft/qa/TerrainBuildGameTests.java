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
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class TerrainBuildGameTests {
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
