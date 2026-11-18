package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.AvatarEntry;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.execution.GameJob;
import com.aurammods.paracraft.forge.execution.JobJson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class GameplayGameTests {
    @GameTest(template = "arena", timeoutTicks = 900)
    public static void miningUsesEffectiveToolsAndRestoresBetweenRealBlocksWithoutReplay(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 48.5, 48.5); var recipient = spawn(helper, manager, owner, 62.5, 48.5);
        var actor = manager.actor(entry.id); actor.getInventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        actor.getInventory().setItem(11, new ItemStack(Items.DIAMOND_PICKAXE));
        ItemStack previous = new ItemStack(Items.COBBLESTONE, 3); previous.getOrCreateTag().putString("qa_marker", "previous");
        actor.getInventory().setItem(12, previous);
        for (int x = 52; x <= 54; x++) helper.setBlock(new BlockPos(x, 2, 48), Blocks.STONE);
        String program = """
            {"steps":[{"op":"mine_blocks","cuboid":{"from":%s,"to":%s},"saveAs":"dug"},
                      {"op":"deliver_items","itemsFrom":"dug","recipient":"%s"}]}
            """.formatted(position(helper, 52,2,48), position(helper,54,2,48), recipient.id);
        GameJob[] current = {submit(manager, owner, entry, program)}; boolean[] restored = {false};
        helper.onEachTick(() -> {
            requireRunning(helper, current[0]);
            if (!restored[0] && current[0].result().has("pending")) {
                JsonObject partial = current[0].result().getAsJsonObject("pending").getAsJsonObject("result");
                if (partial.has("broken") && partial.get("broken").getAsInt() == 1 && partial.get("acquired").getAsInt() == 1) {
                    UUID id = current[0].id; restore(manager, owner, entry); current[0] = manager.jobs().get(entry.id);
                    manager.jobs().resumeRequest(owner, entry.id, entry.authority.session(), id); restored[0] = true;
                }
            }
            if (current[0].state() != JobState.SUCCEEDED) return;
            var body = manager.actor(entry.id); var receiver = manager.actor(recipient.id);
            helper.assertTrue(restored[0] && current[0].result().getAsJsonObject("results").getAsJsonObject("dug").get("broken").getAsInt() == 3, "Three actual blocks, including a resumed checkpoint, were mined");
            helper.assertTrue(damage(body, Items.DIAMOND_PICKAXE) == 3 && damage(body, Items.WOODEN_PICKAXE) == 0, "Fast effective tool was selected and wore normally");
            helper.assertTrue(body.getInventory().countItem(Items.COBBLESTONE) == 3 && body.getInventory().items.stream().anyMatch(s -> s.hasTag() && "previous".equals(s.getTag().getString("qa_marker"))),
                    "Pre-existing metadata-bearing belongings were not delivered");
            int delivered = receiver.getInventory().countItem(Items.COBBLESTONE) + helper.getLevel().getEntitiesOfClass(ItemEntity.class, receiver.getBoundingBox().inflate(3)).stream()
                    .filter(e -> e.getItem().is(Items.COBBLESTONE) && assigned(e, recipient.id)).mapToInt(e -> e.getItem().getCount()).sum();
            helper.assertTrue(delivered == 3, "The native pickup ledger delivers exactly three new drops");
            for (int x = 52; x <= 54; x++) helper.assertBlockPresent(Blocks.AIR, new BlockPos(x,2,48));
            clean(manager, owner, entry); clean(manager, owner, recipient); pass("mine_tools_checkpoint_and_drop_ledger"); helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 250)
    public static void miningPauseAndCancellationDoNotLeaveDelayedDestruction(GameTestHelper helper) {
        floor(helper); var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); var actor = manager.actor(entry.id);
        actor.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE)); helper.setBlock(new BlockPos(3,2,2), Blocks.OBSIDIAN);
        int[] starts = {0}, aborts = {0};
        class Clicks { @SubscribeEvent public void observe(net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event) {
            if (!event.getEntity().getUUID().equals(entry.id)) return;
            if (event.getAction() == net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.START) starts[0]++;
            if (event.getAction() == net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock.Action.ABORT) aborts[0]++;
        } }
        var clicks = new Clicks(); MinecraftForge.EVENT_BUS.register(clicks);
        var job = submit(manager, owner, entry, "{\"steps\":[{\"op\":\"mine_blocks\",\"block\":" + position(helper,3,2,2) + "}]}");
        boolean[] paused = {false};
        helper.onEachTick(() -> {
            if (paused[0] || !job.result().has("pending")) return; var partial = job.result().getAsJsonObject("pending").getAsJsonObject("result");
            if (!partial.has("breakProgress") || partial.get("breakProgress").getAsFloat() <= 0) return;
            paused[0] = true; manager.pause(owner, entry.id, true);
            helper.runAfterDelay(30, () -> {
                helper.assertBlockPresent(Blocks.OBSIDIAN, new BlockPos(3,2,2)); helper.assertTrue(job.state() == JobState.PAUSED, "Pause stops native break progress");
                manager.pause(owner, entry.id, false);
                helper.runAfterDelay(10, () -> {
                    manager.jobs().cancel(entry.id, "Owner cancellation");
                    helper.runAfterDelay(30, () -> {
                        helper.assertBlockPresent(Blocks.OBSIDIAN, new BlockPos(3,2,2));
                        helper.assertTrue(job.state() == JobState.CANCELLED && damage(actor, Items.DIAMOND_PICKAXE) == 0, "No delayed break or durability loss survives cancellation");
                        helper.assertTrue(starts[0] == 2 && aborts[0] == 2, "Pause/resume/cancel publish the normal Forge start/abort interaction lifecycle");
                        MinecraftForge.EVENT_BUS.unregister(clicks);
                        clean(manager, owner, entry); pass("mine_pause_cancel_no_delayed_break"); helper.succeed();
                    });
                });
            });
        });
    }
    @GameTest(template = "empty", timeoutTicks = 250)
    public static void protectedMiningStopsRemainingTargetsAndExposesRealPartialResults(GameTestHelper helper) {
        floor(helper); var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); var actor = manager.actor(entry.id); actor.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
        for (int x = 3; x <= 5; x++) helper.setBlock(new BlockPos(x,2,2), Blocks.STONE);
        BlockPos denied = helper.absolutePos(new BlockPos(4,2,2));
        class Protection { @SubscribeEvent public void deny(BlockEvent.BreakEvent event) { if (event.getPlayer().getUUID().equals(entry.id) && event.getPos().equals(denied)) event.setCanceled(true); } }
        var protection = new Protection(); MinecraftForge.EVENT_BUS.register(protection);
        var job = submit(manager, owner, entry, """
            {"steps":[{"try":[{"op":"mine_blocks","blocks":[%s,%s,%s],"saveAs":"partial"}],"catch":[{"op":"report_failure","saveAs":"caught"}]}]}
            """.formatted(position(helper,3,2,2), position(helper,4,2,2), position(helper,5,2,2)));
        helper.onEachTick(() -> {
            requireRunning(helper, job); if (job.state() != JobState.SUCCEEDED) return;
            var results = job.result().getAsJsonObject("results");
            helper.assertTrue(results.getAsJsonObject("partial").get("broken").getAsInt() == 1 && results.getAsJsonObject("caught").getAsJsonObject("partial").get("acquired").getAsInt() == 1, "Catch receives actual mined/picked partial state");
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(3,2,2)); helper.assertBlockPresent(Blocks.STONE, new BlockPos(4,2,2)); helper.assertBlockPresent(Blocks.STONE, new BlockPos(5,2,2));
            helper.assertTrue(damage(actor, Items.DIAMOND_PICKAXE) == 1 && actor.getInventory().countItem(Items.COBBLESTONE) == 1, "Denied target changes neither tool nor contents");
            MinecraftForge.EVENT_BUS.unregister(protection); clean(manager, owner, entry); pass("mine_protection_partial_recovery"); helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void fullInventoryLeavesNativeMiningDropsInTheWorld(GameTestHelper helper) {
        floor(helper); var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); var actor = manager.actor(entry.id);
        for (int i = 0; i < 36; i++) actor.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
        actor.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE)); helper.setBlock(new BlockPos(3,2,2), Blocks.DIAMOND_ORE);
        BlockPos ore=helper.absolutePos(new BlockPos(3,2,2)); java.util.Set<UUID> generated=new java.util.HashSet<>();
        class Drops { @SubscribeEvent public void join(net.minecraftforge.event.entity.EntityJoinLevelEvent e) {
            if(e.getLevel()==helper.getLevel() && e.getEntity() instanceof ItemEntity item && item.blockPosition().equals(ore) && item.getItem().is(Items.DIAMOND)) generated.add(item.getUUID());
        } }
        var sourceDrops=new Drops(); MinecraftForge.EVENT_BUS.register(sourceDrops);
        var job = submit(manager, owner, entry, "{\"steps\":[{\"op\":\"mine_blocks\",\"block\":" + position(helper,3,2,2) + ",\"saveAs\":\"mined\"}]}");
        int[] diagnostic = {0};
        helper.onEachTick(() -> {
            if (++diagnostic[0] == 150 || job.state() == JobState.SUCCEEDED) Paracraft.LOG.info("Full mining pickup diagnostic: {} position={} drops={}", job.view(), actor.position(),
                    helper.getLevel().getEntitiesOfClass(ItemEntity.class, actor.getBoundingBox().inflate(8)).stream().map(e -> e.position() + " " + e.getItem()).toList());
            helper.assertTrue(job.state() != JobState.SUCCEEDED, "Full inventory must report pickup failure: " + job.view());
            if (job.state() != JobState.FAILED) return;
            var pending = job.result().getAsJsonObject("error").getAsJsonObject("partial");
            helper.assertTrue(pending.get("broken").getAsInt() == 1 && actor.getInventory().countItem(Items.DIAMOND) == 0, "Actual pickup failure retains mined partial state");
            int drops=generated.stream().map(helper.getLevel()::getEntity).filter(e->e instanceof ItemEntity).map(e->(ItemEntity)e).filter(e->e.isAlive() && e.getItem().is(Items.DIAMOND)).mapToInt(e->e.getItem().getCount()).sum();
            helper.assertTrue(generated.size()==1 && drops==1, "The exact ore-generated diamond remains a real world item: generated="+generated+" remaining="+drops);
            MinecraftForge.EVENT_BUS.unregister(sourceDrops);
            clean(manager, owner, entry); pass("mine_full_inventory_preserves_drop"); helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 150)
    public static void toolTierAndLeftClickDenialPreserveTheUnminedBlock(GameTestHelper helper) {
        floor(helper); var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); var actor = manager.actor(entry.id);
        actor.getInventory().setItem(0, new ItemStack(Items.STONE_PICKAXE)); helper.setBlock(new BlockPos(3,2,2), Blocks.DIAMOND_ORE);
        String program = "{\"steps\":[{\"op\":\"mine_blocks\",\"block\":" + position(helper,3,2,2) + "}]}";
        GameJob[] job = {submit(manager, owner, entry, program)}; int[] stage = {0}, calls = {0}; BlockPos target = helper.absolutePos(new BlockPos(3,2,2));
        class Protection { @SubscribeEvent public void deny(net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event) {
            if (event.getEntity().getUUID().equals(entry.id) && event.getPos().equals(target)) { calls[0]++; event.setCanceled(true); }
        } }
        var protection = new Protection();
        helper.onEachTick(() -> {
            if (job[0].state() != JobState.FAILED) return;
            helper.assertBlockPresent(Blocks.DIAMOND_ORE, new BlockPos(3,2,2));
            if (stage[0] == 0) {
                helper.assertTrue(damage(actor, Items.STONE_PICKAXE) == 0 && job[0].view().get("error").getAsString().contains("No carried tool"), "Wrong-tier tools do not destroy valuable drops");
                actor.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE)); MinecraftForge.EVENT_BUS.register(protection);
                job[0] = submit(manager, owner, entry, program); stage[0] = 1;
            } else {
                helper.assertTrue(calls[0] == 1 && damage(actor, Items.IRON_PICKAXE) == 0, "The actual canceled left-click hook stops mining without wear");
                MinecraftForge.EVENT_BUS.unregister(protection); clean(manager, owner, entry); pass("mine_tool_tier_and_left_click_denial"); helper.succeed();
            }
        });
    }
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nativeCombatCanUseBareHandsWhenNoWeaponIsCarried(GameTestHelper helper) {
        floor(helper); var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); var actor = manager.actor(entry.id);
        var cow = EntityType.COW.create(helper.getLevel()); cow.setNoAi(true); cow.setHealth(2);
        cow.moveTo(helper.absoluteVec(new Vec3(4.0,2,2.5))); helper.getLevel().addFreshEntity(cow);
        var job = submit(manager, owner, entry, "{\"steps\":[{\"op\":\"attack_entity\",\"target\":\"" + cow.getUUID() + "\",\"ticks\":80,\"saveAs\":\"combat\"}]}");
        helper.onEachTick(() -> {
            requireRunning(helper, job); if (job.state() != JobState.SUCCEEDED) return;
            var combat = job.result().getAsJsonObject("results").getAsJsonObject("combat");
            helper.assertTrue(!cow.isAlive() && combat.get("actorAttackDamage").getAsDouble() == 1 && combat.get("hits").getAsInt() == 2,
                    "Two native bare-hand hits defeat the known two-health target");
            cow.discard(); clean(manager, owner, entry); pass("native_unarmed_combat"); helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 150)
    public static void deathInsideABreakHookBypassesRecoveryAndSubsequentInstructions(GameTestHelper helper) {
        floor(helper); var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); var actor = manager.actor(entry.id);
        actor.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE)); actor.getInventory().setItem(1, new ItemStack(Items.COBBLESTONE, 2));
        helper.setBlock(new BlockPos(3,2,2), Blocks.STONE);
        class DeathHook { @SubscribeEvent public void die(BlockEvent.BreakEvent event) { if (event.getPlayer().getUUID().equals(entry.id)) actor.setHealth(0); } }
        var hook = new DeathHook(); MinecraftForge.EVENT_BUS.register(hook);
        var job = submit(manager, owner, entry, """
            {"steps":[{"try":[{"op":"mine_blocks","block":%s}],"catch":[{"op":"place_blocks","placements":[{"position":%s,"item":"minecraft:cobblestone"}]}]},
                      {"op":"place_blocks","placements":[{"position":%s,"item":"minecraft:cobblestone"}]}]}
            """.formatted(position(helper,3,2,2), position(helper,1,2,3), position(helper,3,2,3)));
        helper.onEachTick(() -> {
            helper.assertTrue(job.state() != JobState.FAILED && job.state() != JobState.SUCCEEDED, "A death control interruption cannot become ordinary failure/success: " + job.view());
            if (job.state() != JobState.CANCELLED) return;
            helper.assertBlockPresent(Blocks.AIR, new BlockPos(1,2,3)); helper.assertBlockPresent(Blocks.AIR, new BlockPos(3,2,3));
            MinecraftForge.EVENT_BUS.unregister(hook); clean(manager, owner, entry); pass("native_hook_death_bypasses_recovery"); helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 150)
    public static void placementRestoresItsCursorAndRespectsTheNativeCanceledPlaceEvent(GameTestHelper helper) {
        floor(helper); var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); manager.actor(entry.id).getInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 3));
        BlockPos denied = helper.absolutePos(new BlockPos(1,2,3)); int[] deniedCalls = {0};
        class Protection { @SubscribeEvent public void deny(BlockEvent.EntityPlaceEvent event) { if (event.getEntity() != null && event.getEntity().getUUID().equals(entry.id) && event.getPos().equals(denied)) { deniedCalls[0]++; event.setCanceled(true); } } }
        var protection = new Protection(); MinecraftForge.EVENT_BUS.register(protection);
        GameJob[] job = {submit(manager, owner, entry, """
            {"steps":[{"try":[{"op":"place_blocks","placements":[{"position":%s,"item":"minecraft:cobblestone"},{"position":%s,"item":"minecraft:cobblestone"},{"position":%s,"item":"minecraft:cobblestone"}],"saveAs":"partial"}],
                       "catch":[{"op":"report_failure","saveAs":"caught"}]}]}
            """.formatted(position(helper,3,2,3), position(helper,1,2,3), position(helper,2,2,5)))}; boolean[] rebound = {false};
        helper.onEachTick(() -> {
            requireRunning(helper, job[0]);
            if (!rebound[0] && job[0].result().has("pending") && job[0].result().getAsJsonObject("pending").getAsJsonObject("result").has("placed")) {
                UUID id = job[0].id; restore(manager, owner, entry); job[0] = manager.jobs().get(entry.id); manager.jobs().resumeRequest(owner, entry.id, entry.authority.session(), id); rebound[0] = true;
            }
            if (job[0].state() != JobState.SUCCEEDED) return;
            helper.assertTrue(rebound[0] && deniedCalls[0] == 1 && job[0].result().getAsJsonObject("results").getAsJsonObject("partial").get("placed").getAsInt() == 1,
                    "Resume skips completed placement and catch preserves actual partial result; rebound=" + rebound[0] + " denied=" + deniedCalls[0] + " result=" + job[0].view());
            helper.assertBlockPresent(Blocks.COBBLESTONE, new BlockPos(3,2,3)); helper.assertBlockPresent(Blocks.AIR, new BlockPos(1,2,3)); helper.assertBlockPresent(Blocks.AIR, new BlockPos(2,2,5));
            helper.assertTrue(manager.actor(entry.id).getInventory().countItem(Items.COBBLESTONE) == 2, "Canceled native placement restores its input item");
            MinecraftForge.EVENT_BUS.unregister(protection); clean(manager, owner, entry); pass("place_checkpoint_and_native_protection"); helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 250)
    public static void farmingHarvestsMatureCropsCollectsAndReplantsWithRealSeeds(GameTestHelper helper) {
        floor(helper); var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); var actor = manager.actor(entry.id);
        actor.getInventory().setItem(10, new ItemStack(Items.WHEAT_SEEDS, 5));
        for (int x = 3; x <= 4; x++) helper.setBlock(new BlockPos(x,1,2), Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
        helper.setBlock(new BlockPos(3,2,2), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7)); helper.setBlock(new BlockPos(4,2,2), Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 0));
        int[] pickedSeeds = {0}; Runnable stop = actor.observePickups(stack -> { if (stack.is(Items.WHEAT_SEEDS)) pickedSeeds[0] += stack.getCount(); });
        var job = submit(manager, owner, entry, """
            {"steps":[{"op":"farm","cuboid":{"from":%s,"to":%s},"plant":"minecraft:wheat_seeds","saveAs":"farm"}]}
            """.formatted(position(helper,3,2,2), position(helper,4,2,2)));
        helper.onEachTick(() -> {
            requireRunning(helper, job); if (job.state() != JobState.SUCCEEDED) return;
            var result = job.result().getAsJsonObject("results").getAsJsonObject("farm");
            helper.assertTrue(result.get("broken").getAsInt() == 1 && result.get("replanted").getAsInt() == 1 && result.get("skipped").getAsInt() == 1, "Only a mature crop was harvested and replanted");
            helper.assertBlockPresent(Blocks.WHEAT, new BlockPos(3,2,2));
            helper.assertTrue(helper.getBlockState(new BlockPos(3,2,2)).getValue(CropBlock.AGE) < 7 && actor.getInventory().countItem(Items.WHEAT) == 1, "Crop resets through actual seed placement and wheat is carried");
            helper.assertTrue(actor.getInventory().countItem(Items.WHEAT_SEEDS) == 5 + pickedSeeds[0] - 1, "Exactly one actual seed is consumed after native pickups");
            stop.run(); clean(manager, owner, entry); pass("farm_native_harvest_pickup_replant"); helper.succeed();
        });
    }
    @GameTest(template = "arena", timeoutTicks = 700)
    public static void followingTracksAMovingEntityAndNativeCombatUsesItsWeapon(GameTestHelper helper) {
        var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 40.5, 48.5); var actor = manager.actor(entry.id);
        actor.getInventory().setItem(0, new ItemStack(Items.WOODEN_SWORD)); actor.getInventory().setItem(10, new ItemStack(Items.DIAMOND_SWORD));
        var cow = EntityType.COW.create(helper.getLevel()); cow.setNoAi(true); cow.moveTo(helper.absoluteVec(new Vec3(60.5,2,48.5))); helper.getLevel().addFreshEntity(cow);
        var follow = submit(manager, owner, entry, "{\"steps\":[{\"op\":\"follow\",\"target\":\"" + cow.getUUID() + "\",\"distance\":2,\"ticks\":160}]}");
        GameJob[] combat = {null}; Vec3 start = actor.position();
        helper.runAfterDelay(60, () -> cow.moveTo(helper.absoluteVec(new Vec3(64.5,2,51.5))));
        helper.onEachTick(() -> {
            requireRunning(helper, follow);
            if (follow.state() == JobState.SUCCEEDED && combat[0] == null) {
                helper.assertTrue(actor.position().distanceTo(start) > 15 && actor.distanceTo(cow) < 2.8, "Follow reacts to the actual moved target");
                helper.assertTrue(cow.tickCount > 60 && helper.getLevel().isPositionEntityTicking(cow.blockPosition()),
                        "The moved target receives native entity ticks beyond the GameTest template's forced chunks");
                combat[0] = submit(manager, owner, entry, "{\"steps\":[{\"op\":\"attack_entity\",\"target\":\"" + cow.getUUID() + "\",\"ticks\":400,\"saveAs\":\"combat\"}]}");
            }
            if (combat[0] == null) return; requireRunning(helper, combat[0]); if (combat[0].state() != JobState.SUCCEEDED) return;
            int hits = combat[0].result().getAsJsonObject("results").getAsJsonObject("combat").get("hits").getAsInt();
            helper.assertTrue(!cow.isAlive() && hits > 0 && damage(actor, Items.DIAMOND_SWORD) == hits && damage(actor, Items.WOODEN_SWORD) == 0,
                    "Normal attacks defeat the cow with matching native hit/durability counts; " + combat[0].view());
            cow.discard(); clean(manager, owner, entry); pass("follow_moving_entity_and_native_melee"); helper.succeed();
        });
    }
    @GameTest(template = "empty", timeoutTicks = 350)
    public static void equipmentUsesNativeSlotsBindingRulesAndAttackProtection(GameTestHelper helper) {
        floor(helper); var manager = ServerEvents.avatars(helper.getLevel().getServer()); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner, 2.5, 2.5); var actor = manager.actor(entry.id);
        ItemStack old = new ItemStack(Items.LEATHER_HELMET); old.getOrCreateTag().putString("qa_marker", "old"); actor.setItemSlot(EquipmentSlot.HEAD, old);
        actor.getInventory().setItem(10, new ItemStack(Items.IRON_HELMET)); actor.getInventory().setItem(11, new ItemStack(Items.SHIELD));
        actor.getInventory().setItem(12, new ItemStack(Items.DIAMOND_BOOTS)); actor.getInventory().setItem(13, new ItemStack(Items.DIAMOND_SWORD));
        var setup = submit(manager, owner, entry, """
            {"steps":[{"op":"equip","item":"minecraft:iron_helmet","slot":"head"},{"op":"equip","item":"minecraft:shield","slot":"offhand"},
                      {"try":[{"op":"equip","item":"minecraft:diamond_boots","slot":"chest"}],"catch":[{"op":"report_failure","saveAs":"invalid"}]}]}
            """);
        GameJob[] deniedCombat = {null}; Object[] listener = {null};
        helper.onEachTick(() -> {
            requireRunning(helper, setup); if (setup.state() != JobState.SUCCEEDED) return;
            if (deniedCombat[0] == null) {
                helper.assertTrue(actor.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET) && actor.getOffhandItem().is(Items.SHIELD)
                        && actor.getInventory().countItem(Items.DIAMOND_BOOTS) == 1 && actor.getInventory().items.stream().anyMatch(s -> s.hasTag() && "old".equals(s.getTag().getString("qa_marker"))),
                        "Native equipment exchanges preserve replaced armor and reject an invalid slot");
                var already = new com.aurammods.paracraft.forge.execution.EquipJob(UUID.randomUUID(), actor, "minecraft:iron_helmet", "head"); already.tick();
                helper.assertTrue(already.state() == JobState.SUCCEEDED && already.result().get("alreadyEquipped").getAsBoolean(), "Already-equipped gear needs no duplicate carried copy");
                actor.getItemBySlot(EquipmentSlot.HEAD).enchant(Enchantments.BINDING_CURSE, 1);
                var binding = new com.aurammods.paracraft.forge.execution.EquipJob(UUID.randomUUID(), actor, "minecraft:leather_helmet", "head"); binding.tick();
                helper.assertTrue(binding.state() == JobState.FAILED && actor.getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET), "Binding curse prevents removal normally");
                var cow = EntityType.COW.create(helper.getLevel()); cow.setNoAi(true); cow.moveTo(helper.absoluteVec(new Vec3(4.5,2,2.5))); helper.getLevel().addFreshEntity(cow);
                class Protection { @SubscribeEvent public void deny(AttackEntityEvent event) { if (event.getEntity().getUUID().equals(entry.id)) event.setCanceled(true); } }
                listener[0] = new Protection(); MinecraftForge.EVENT_BUS.register(listener[0]);
                deniedCombat[0] = submit(manager, owner, entry, "{\"steps\":[{\"op\":\"attack_entity\",\"target\":\"" + cow.getUUID() + "\",\"ticks\":300}]}");
                helper.runAfterDelay(250, () -> {
                    helper.assertTrue(deniedCombat[0].state() == JobState.FAILED && cow.getHealth() == cow.getMaxHealth() && damage(actor, Items.DIAMOND_SWORD) == 0,
                            "Native canceled attack hooks prevent damage and weapon wear");
                    MinecraftForge.EVENT_BUS.unregister(listener[0]); cow.discard(); clean(manager, owner, entry); pass("equip_native_slots_binding_and_attack_denial"); helper.succeed();
                });
            }
        });
    }
    private static void floor(GameTestHelper helper) { for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++) helper.setBlock(new BlockPos(x,1,z), Blocks.STONE); }
    private static AvatarEntry spawn(GameTestHelper helper, AvatarManager manager, UUID owner, double x, double z) { return manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(x,2,z)), "QA" + UUID.randomUUID().toString().substring(0,8)); }
    private static GameJob submit(AvatarManager manager, UUID owner, AvatarEntry entry, String specification) { return manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), UUID.randomUUID(), JsonParser.parseString(specification).getAsJsonObject(), "replace"); }
    private static String position(GameTestHelper helper, int x, int y, int z) { return JobJson.position(helper.absolutePos(new BlockPos(x,y,z))).toString(); }
    private static void requireRunning(GameTestHelper helper, GameJob job) { helper.assertTrue(job.state() != JobState.FAILED && job.state() != JobState.CANCELLED, "Unexpected job result: " + job.view()); }
    private static void clean(AvatarManager manager, UUID owner, AvatarEntry entry) { if (manager.actor(entry.id) != null) manager.actor(entry.id).getInventory().clearContent(); manager.destroy(owner, entry.id); }
    private static int damage(CompanionPlayer actor, net.minecraft.world.item.Item item) { return actor.getInventory().items.stream().filter(stack -> stack.is(item)).mapToInt(ItemStack::getDamageValue).sum(); }
    private static boolean assigned(ItemEntity item, UUID recipient) { CompoundTag tag = item.saveWithoutId(new CompoundTag()); return tag.hasUUID("Owner") && recipient.equals(tag.getUUID("Owner")); }
    private static void restore(AvatarManager manager, UUID owner, AvatarEntry entry) {
        manager.disconnectOwner(owner); AvatarEntry decoded = AvatarEntry.load(entry.save());
        entry.physical = decoded.physical; entry.execution = decoded.execution; entry.control = decoded.control;
        if (!manager.restoreOwner(owner).isEmpty()) throw new AssertionError("Body restoration failed");
    }
    private static void pass(String event) { Paracraft.LOG.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\"}", event); }
}
