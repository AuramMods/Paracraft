package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.*;
import com.aurammods.paracraft.forge.execution.*;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.*;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class CraftingGameTests {
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void internalGridCraftsPlanksSticksAndTablesThroughNativeHooks(GameTestHelper h) {
        var m = ServerEvents.avatars(h.getLevel().getServer()); UUID owner = UUID.randomUUID(); var e = spawn(h, m, owner);
        var actor = m.actor(e.id); actor.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 4));
        var hooks = new Hooks(e.id); MinecraftForge.EVENT_BUS.register(hooks);
        var job = program(m, owner, e, """
            {"steps":[{"op":"craft_item","item":"minecraft:oak_planks","quantity":8,"saveAs":"planks"},
                      {"op":"craft_item","item":"minecraft:stick","quantity":8,"saveAs":"sticks"},
                      {"op":"craft_item","item":"minecraft:crafting_table","quantity":1,"saveAs":"table"}]}
            """);
        h.onEachTick(() -> {
            check(h, job); if (job.state() != JobState.SUCCEEDED) return;
            h.assertTrue(actor.getInventory().countItem(Items.OAK_LOG) == 2 && actor.getInventory().countItem(Items.OAK_PLANKS) == 0
                    && actor.getInventory().countItem(Items.STICK) == 8 && actor.getInventory().countItem(Items.CRAFTING_TABLE) == 1, "Native 2x2 crafting consumes the exact carried materials");
            h.assertTrue(hooks.crafts == 5 && hooks.width == 2 && actor.inventoryMenu.getCraftSlots().isEmpty(), "Five real crafting callbacks used and cleared the internal grid");
            MinecraftForge.EVENT_BUS.unregister(hooks); clean(m, owner, e); pass(h, "craft_internal_grid_composite_and_native_hooks");
        });
    }
    @GameTest(template = "arena", timeoutTicks = 500)
    public static void tableCraftingWalksToTheActualMenuAndDiscoversRecipes(GameTestHelper h) {
        var m = ServerEvents.avatars(h.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var e = m.spawn(owner, h.getLevel(), h.absoluteVec(new Vec3(48.5, 2, 48.5)), name()); var actor = m.actor(e.id);
        actor.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3)); actor.getInventory().setItem(1, new ItemStack(Items.STICK, 2));
        h.setBlock(new BlockPos(64, 2, 48), Blocks.CRAFTING_TABLE); Vec3 start = actor.position();
        var job = program(m, owner, e, """
            {"steps":[{"op":"search_recipes","item":"minecraft:wooden_pickaxe","saveAs":"recipes"},
              {"try":[{"op":"craft_item","item":"minecraft:wooden_pickaxe","quantity":1}],"catch":[{"op":"report_failure","saveAs":"needs_table"}]},
              {"op":"craft_item","item":"minecraft:wooden_pickaxe","quantity":1,"table":%s,"saveAs":"pickaxe"}]}
            """.formatted(JobJson.position(h.absolutePos(new BlockPos(64, 2, 48)))));
        h.onEachTick(() -> {
            check(h, job); if (job.state() != JobState.SUCCEEDED) return;
            var results = job.result().getAsJsonObject("results");
            h.assertTrue(results.has("needs_table") && results.getAsJsonObject("recipes").getAsJsonArray("recipes").asList().stream()
                    .anyMatch(r -> !r.getAsJsonObject().get("fits2x2").getAsBoolean() && r.getAsJsonObject().get("craftableNow3x3").getAsBoolean()), "Recipe discovery reports real grid/material requirements");
            h.assertTrue(actor.position().distanceTo(start) > 10 && actor.getInventory().countItem(Items.WOODEN_PICKAXE) == 1
                    && actor.getInventory().countItem(Items.OAK_PLANKS) == 0 && actor.getInventory().countItem(Items.STICK) == 0, "The avatar walks to and crafts at the actual table");
            h.assertTrue(results.getAsJsonObject("pickaxe").get("gridWidth").getAsInt() == 3, "The successful pickup used a real 3x3 menu");
            clean(m, owner, e); pass(h, "craft_table_navigation_recipe_discovery_and_grid_rejection");
        });
    }
    @GameTest(template = "empty")
    public static void cakeReturnsThreeNativeBuckets(GameTestHelper h) {
        var m = ServerEvents.avatars(h.getLevel().getServer()); UUID owner = UUID.randomUUID(); var e = spawn(h, m, owner); var actor = m.actor(e.id);
        for (int i = 0; i < 3; i++) actor.getInventory().setItem(i, new ItemStack(Items.MILK_BUCKET));
        actor.getInventory().setItem(3, new ItemStack(Items.SUGAR, 2)); actor.getInventory().setItem(4, new ItemStack(Items.EGG)); actor.getInventory().setItem(5, new ItemStack(Items.WHEAT, 3));
        h.setBlock(new BlockPos(3, 2, 2), Blocks.CRAFTING_TABLE);
        var job = new CraftJob(UUID.randomUUID(), actor, json("{\"item\":\"minecraft:cake\",\"quantity\":1,\"table\":" + JobJson.position(h.absolutePos(new BlockPos(3, 2, 2))) + "}")); job.tick();
        h.assertTrue(job.state() == JobState.SUCCEEDED && actor.getInventory().countItem(Items.CAKE) == 1 && actor.getInventory().countItem(Items.BUCKET) == 3
                && actor.getInventory().countItem(Items.MILK_BUCKET) == 0 && actor.containerMenu == actor.inventoryMenu, "Native ResultSlot consumption returns all three buckets and closes the table: " + job.view());
        clean(m, owner, e); pass(h, "craft_native_cake_bucket_remainders");
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void tagAlternativesAndForgeNbtIngredientsUseActualStacks(GameTestHelper h) {
        var m = ServerEvents.avatars(h.getLevel().getServer()); UUID owner = UUID.randomUUID(); var e = spawn(h, m, owner); var actor = m.actor(e.id);
        actor.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS)); actor.getInventory().setItem(1, new ItemStack(Items.BIRCH_PLANKS));
        actor.getInventory().setItem(2, new ItemStack(Items.AMETHYST_SHARD, 4)); var marked = new ItemStack(Items.AMETHYST_SHARD); marked.getOrCreateTag().putInt("qa_marker", 1); actor.getInventory().setItem(3, marked);
        var job = program(m, owner, e, """
            {"steps":[{"op":"craft_item","item":"minecraft:paper","quantity":4,"recipe":"paracraft:qa_overlap"},
                      {"op":"craft_item","item":"minecraft:quartz","quantity":1,"recipe":"paracraft:qa_nbt"}]}
            """);
        h.onEachTick(() -> {
            check(h, job); if (job.state() != JobState.SUCCEEDED) return;
            h.assertTrue(actor.getInventory().countItem(Items.PAPER) == 4 && actor.getInventory().countItem(Items.OAK_PLANKS) == 0
                    && actor.getInventory().countItem(Items.BIRCH_PLANKS) == 0, "Overlapping tag/exact ingredients are assigned without a greedy false failure");
            h.assertTrue(actor.getInventory().countItem(Items.QUARTZ) == 1 && actor.getInventory().countItem(Items.AMETHYST_SHARD) == 4
                    && actor.getInventory().items.stream().filter(s -> s.is(Items.AMETHYST_SHARD)).noneMatch(ItemStack::hasTag), "Forge NBT ingredients consume only the actual matching stack");
            clean(m, owner, e); pass(h, "craft_overlap_matching_tags_and_forge_nbt_ingredients");
        });
    }
    @GameTest(template = "empty")
    public static void fullInventoryAndMissingMaterialsPreserveActualPartialProgress(GameTestHelper h) {
        var m = ServerEvents.avatars(h.getLevel().getServer()); UUID owner = UUID.randomUUID(); var e = spawn(h, m, owner); var actor = m.actor(e.id);
        for (int i = 0; i < 36; i++) actor.getInventory().setItem(i, new ItemStack(Items.COBBLESTONE, 64));
        actor.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 64));
        var full = new CraftJob(UUID.randomUUID(), actor, json("{\"item\":\"minecraft:oak_planks\",\"quantity\":4}")); full.tick();
        h.assertTrue(full.state() == JobState.FAILED && actor.getInventory().countItem(Items.OAK_LOG) == 64
                && actor.getInventory().countItem(Items.OAK_PLANKS) == 0 && actor.inventoryMenu.getCraftSlots().isEmpty(), "A refused native output transfer returns inputs without crafting: " + full.view());
        actor.getInventory().clearContent(); actor.getInventory().setItem(0, new ItemStack(Items.OAK_LOG));
        var partial = new CraftJob(UUID.randomUUID(), actor, json("{\"item\":\"minecraft:oak_planks\",\"quantity\":12}")); partial.tick(); partial.tick();
        h.assertTrue(partial.state() == JobState.FAILED && partial.result().get("crafted").getAsInt() == 4
                && actor.getInventory().countItem(Items.OAK_PLANKS) == 4 && actor.getInventory().countItem(Items.OAK_LOG) == 0, "Missing later ingredients preserve the already-crafted batch: " + partial.view());
        clean(m, owner, e); pass(h, "craft_full_inventory_and_actual_partial_failure");
    }
    @GameTest(template = "empty")
    public static void craftingTableHooksAndMissingTablesPreventConsumption(GameTestHelper h) {
        var m = ServerEvents.avatars(h.getLevel().getServer()); UUID owner = UUID.randomUUID(); var e = spawn(h, m, owner); var actor = m.actor(e.id);
        actor.getInventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3)); actor.getInventory().setItem(1, new ItemStack(Items.STICK, 2));
        h.setBlock(new BlockPos(3, 2, 2), Blocks.CRAFTING_TABLE); var denied = new Hooks(e.id); denied.deny = true; MinecraftForge.EVENT_BUS.register(denied);
        var spec = json("{\"item\":\"minecraft:wooden_pickaxe\",\"quantity\":1,\"table\":" + JobJson.position(h.absolutePos(new BlockPos(3, 2, 2))) + "}");
        var protectedJob = new CraftJob(UUID.randomUUID(), actor, spec);
        try { protectedJob.tick(); } finally { MinecraftForge.EVENT_BUS.unregister(denied); }
        h.assertTrue(protectedJob.state() == JobState.FAILED && denied.clicks == 1 && denied.crafts == 0, "Actual table interaction hooks can refuse crafting");
        h.setBlock(new BlockPos(3, 2, 2), Blocks.AIR); var missing = new CraftJob(UUID.randomUUID(), actor, spec); missing.tick();
        h.assertTrue(missing.state() == JobState.FAILED && actor.getInventory().countItem(Items.OAK_PLANKS) == 3
                && actor.getInventory().countItem(Items.STICK) == 2 && actor.getInventory().countItem(Items.WOODEN_PICKAXE) == 0, "Missing/denied tables never grant a free 3x3 grid");
        clean(m, owner, e); pass(h, "craft_table_native_protection_and_removed_block");
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void craftingCheckpointsResumeWithoutRepeatingCompletedBatches(GameTestHelper h) {
        var m = ServerEvents.avatars(h.getLevel().getServer()); UUID owner = UUID.randomUUID(); var e = spawn(h, m, owner); m.actor(e.id).getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 4));
        GameJob[] job = {program(m, owner, e, "{\"steps\":[{\"op\":\"craft_item\",\"item\":\"minecraft:oak_planks\",\"quantity\":8,\"saveAs\":\"crafted\"}]}")}; boolean[] restored = {false}, cancelling = {false};
        h.onEachTick(() -> {
            check(h, job[0]);
            if (!restored[0] && crafted(job[0]) == 4) {
                UUID request = job[0].id; m.disconnectOwner(owner); var decoded = AvatarEntry.load(e.save()); e.physical = decoded.physical; e.execution = decoded.execution; e.control = decoded.control;
                h.assertTrue(m.restoreOwner(owner).isEmpty(), "Native body and pending crafting checkpoint restore");
                job[0] = m.jobs().get(e.id); m.jobs().resumeRequest(owner, e.id, e.authority.session(), request); restored[0] = true;
            }
            if (restored[0] && !cancelling[0] && job[0].state() == JobState.SUCCEEDED) {
                h.assertTrue(m.actor(e.id).getInventory().countItem(Items.OAK_PLANKS) == 8 && m.actor(e.id).getInventory().countItem(Items.OAK_LOG) == 2, "Restored crafting does not replay its first batch");
                job[0] = program(m, owner, e, "{\"steps\":[{\"op\":\"craft_item\",\"item\":\"minecraft:oak_planks\",\"quantity\":8}]}"); cancelling[0] = true;
            }
            if (cancelling[0] && crafted(job[0]) == 4) {
                job[0].cancel("QA cancelled crafting"); job[0].tick();
                h.assertTrue(job[0].state() == JobState.CANCELLED && m.actor(e.id).getInventory().countItem(Items.OAK_PLANKS) == 12
                        && m.actor(e.id).getInventory().countItem(Items.OAK_LOG) == 1 && m.actor(e.id).inventoryMenu.getCraftSlots().isEmpty(), "Cancellation retains the completed batch and stops the next");
                clean(m, owner, e); pass(h, "craft_checkpoint_resume_and_between_batch_cancellation");
            }
        });
    }
    @GameTest(template = "empty")
    public static void explicitGridSupportsDynamicNativeRepairRecipes(GameTestHelper h) {
        var m = ServerEvents.avatars(h.getLevel().getServer()); UUID owner = UUID.randomUUID(); var e = spawn(h, m, owner); var actor = m.actor(e.id);
        for (int i = 0; i < 2; i++) { var tool = new ItemStack(Items.STONE_PICKAXE); tool.setDamageValue(100); actor.getInventory().setItem(i, tool); }
        var job = new CraftJob(UUID.randomUUID(), actor, json("{\"item\":\"minecraft:stone_pickaxe\",\"quantity\":1,\"grid\":[0,1,-1,-1]}")); job.tick();
        var repaired = actor.getInventory().items.stream().filter(s -> s.is(Items.STONE_PICKAXE)).findFirst().orElse(ItemStack.EMPTY);
        h.assertTrue(job.state() == JobState.SUCCEEDED && actor.getInventory().countItem(Items.STONE_PICKAXE) == 1 && repaired.getDamageValue() < 100
                && JobJson.stacks(job.result().getAsJsonArray("acquiredStacks")).get(0).getDamageValue() == repaired.getDamageValue(), "The actual dynamic recipe creates and records the repaired tool: " + job.view());
        clean(m, owner, e); pass(h, "craft_explicit_grid_dynamic_tool_repair");
    }
    @GameTest(template = "empty")
    public static void limitedCraftingUsesActualRecipeUnlocks(GameTestHelper h) {
        var m = ServerEvents.avatars(h.getLevel().getServer()); UUID owner = UUID.randomUUID(); var e = spawn(h, m, owner); var actor = m.actor(e.id);
        actor.getInventory().setItem(0, new ItemStack(Items.OAK_LOG, 2)); var rule = h.getLevel().getGameRules().getRule(GameRules.RULE_LIMITED_CRAFTING); boolean previous = rule.get();
        try {
            rule.set(true, actor.server); var spec = json("{\"item\":\"minecraft:oak_planks\",\"quantity\":1}");
            var locked = new CraftJob(UUID.randomUUID(), actor, spec); locked.tick();
            h.assertTrue(locked.state() == JobState.FAILED && actor.getInventory().countItem(Items.OAK_LOG) == 2, "The real menu refuses an unknown recipe under limited crafting");
            actor.awardRecipes(actor.level().getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).stream().filter(r -> r.getResultItem(actor.level().registryAccess()).is(Items.OAK_PLANKS)).<net.minecraft.world.item.crafting.Recipe<?>>map(r -> r).toList());
            var unlocked = new CraftJob(UUID.randomUUID(), actor, spec); unlocked.tick();
            h.assertTrue(unlocked.state() == JobState.SUCCEEDED && unlocked.result().get("crafted").getAsInt() == 4
                    && actor.getInventory().countItem(Items.OAK_LOG) == 1, "Unlocked native crafting rounds up the actual recipe batch");
        } finally { rule.set(previous, actor.server); }
        clean(m, owner, e); pass(h, "craft_native_recipe_unlock_and_batch_rounding");
    }
    @GameTest(template = "arena", timeoutTicks = 250)
    public static void craftingLedgerDeliversNewItemsAndUsesActualPostHookMetadata(GameTestHelper h) {
        var m = ServerEvents.avatars(h.getLevel().getServer()); UUID recipientOwner = UUID.randomUUID();
        var recipient = m.spawn(recipientOwner, h.getLevel(), h.absoluteVec(new Vec3(52.5, 2, 48.5)), name()); UUID owner = recipient.id;
        var e = m.spawn(owner, h.getLevel(), h.absoluteVec(new Vec3(48.5, 2, 48.5)), name()); var actor = m.actor(e.id);
        var previous = new ItemStack(Items.STICK, 3); previous.getOrCreateTag().putBoolean("qa_previous", true);
        actor.getInventory().setItem(0, previous); actor.getInventory().setItem(1, new ItemStack(Items.OAK_PLANKS, 4));
        var hooks = new Hooks(e.id); hooks.mark = true; MinecraftForge.EVENT_BUS.register(hooks);
        var job = program(m, owner, e, """
            {"steps":[{"op":"craft_item","item":"minecraft:stick","quantity":8,"saveAs":"made"},
             {"op":"deliver_items","itemsFrom":"made","recipient":{"playerUuid":"%s"}}]}
            """.formatted(owner));
        h.onEachTick(() -> {
            check(h, job); if (job.state() != JobState.SUCCEEDED) return;
            var receiver = m.actor(recipient.id);
            int delivered = receiver.getInventory().countItem(Items.STICK) + h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, receiver.getBoundingBox().inflate(3)).stream()
                    .filter(item -> item.getItem().is(Items.STICK)).mapToInt(item -> item.getItem().getCount()).sum();
            h.assertTrue(delivered == 8 && actor.getInventory().countItem(Items.STICK) == 3
                    && actor.getInventory().items.stream().filter(s -> s.is(Items.STICK)).allMatch(s -> s.hasTag() && s.getTag().getBoolean("qa_previous")), "Crafted-item delivery preserves old sticks and records actual inventory metadata after native hooks: " + job.view());
            MinecraftForge.EVENT_BUS.unregister(hooks); clean(m, owner, e); clean(m, recipientOwner, recipient); pass(h, "craft_output_ledger_native_metadata_and_delivery");
        });
    }
    public static final class Hooks {
        final UUID avatar; int crafts, width, clicks; boolean deny, mark;
        Hooks(UUID avatar) { this.avatar = avatar; }
        @SubscribeEvent public void crafted(PlayerEvent.ItemCraftedEvent event) { if (event.getEntity().getUUID().equals(avatar)) { crafts++; width = ((net.minecraft.world.inventory.CraftingContainer)event.getInventory()).getWidth(); if (mark) event.getCrafting().getOrCreateTag().putBoolean("qa_hook", true); } }
        @SubscribeEvent public void click(PlayerInteractEvent.RightClickBlock event) { if (event.getEntity().getUUID().equals(avatar)) { clicks++; if (deny) event.setCanceled(true); } }
    }
    private static long crafted(GameJob job) { var value = job.result(); return value.has("pending") && value.getAsJsonObject("pending").getAsJsonObject("result").has("crafted") ? value.getAsJsonObject("pending").getAsJsonObject("result").get("crafted").getAsLong() : -1; }
    private static AvatarEntry spawn(GameTestHelper h, AvatarManager m, UUID owner) { var e = m.spawn(owner, h.getLevel(), h.absoluteVec(new Vec3(2.5, 2, 2.5)), name()); m.actor(e.id).setNoGravity(true); return e; }
    private static String name() { return "QA" + UUID.randomUUID().toString().substring(0, 8); }
    private static JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static GameJob program(AvatarManager m, UUID owner, AvatarEntry e, String text) { return m.jobs().submitProgram(owner, e.id, e.authority.session(), UUID.randomUUID(), json(text), "replace"); }
    private static void check(GameTestHelper h, GameJob job) { h.assertTrue(job.state() != JobState.FAILED && job.state() != JobState.CANCELLED, "Crafting program failed: " + job.view()); }
    private static void clean(AvatarManager m, UUID owner, AvatarEntry e) { m.actor(e.id).getInventory().clearContent(); m.destroy(owner, e.id); }
    private static void pass(GameTestHelper h, String event) { Paracraft.LOG.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\"}", event); h.succeed(); }
}
