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
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.SleepingTimeCheckEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class SurvivalGameTests {
    @GameTest(template = "empty", timeoutTicks = 120)
    public static void nativeDeathDropsOnceCancelsParkedWorkAndRespawnsAtBed(GameTestHelper helper) {
        floor(helper); bed(helper, 4, 2, 3); var manager = manager(helper); UUID owner = UUID.randomUUID();
        var entry = spawn(helper, manager, owner); var actor = manager.actor(entry.id);
        actor.setRespawnPosition(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(4,2,3)), 0, false, false);
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 7); diamonds.getOrCreateTag().putString("qa_death", entry.id.toString());
        actor.getInventory().setItem(0, diamonds); actor.containerMenu.setCarried(diamonds.copyWithCount(3));
        actor.setItemSlot(EquipmentSlot.FEET, new ItemStack(Items.IRON_BOOTS));
        ItemStack cursed = new ItemStack(Items.DIAMOND_SWORD); cursed.enchant(Enchantments.VANISHING_CURSE, 1); actor.getInventory().setItem(2, cursed);
        actor.experienceLevel = 5; actor.totalExperience = 60;
        var first = submit(manager, owner, entry, waitProgram(200), "replace");
        var current = submit(manager, owner, entry, "{\"steps\":[{\"try\":[{\"op\":\"wait_ticks\",\"ticks\":200}],\"catch\":[{\"op\":\"chat\",\"text\":\"must not run\"}]}]}", "interrupt");
        var queued = submit(manager, owner, entry, waitProgram(200), "append"); UUID lease = entry.authority.session();
        int[] clones = {0}, respawns = {0}; boolean[] cloneEmpty = {false};
        class Hooks {
            @SubscribeEvent public void clone(PlayerEvent.Clone e) { if (e.getEntity().getUUID().equals(entry.id) && e.isWasDeath()) { clones[0]++; cloneEmpty[0] = e.getEntity().getInventory().isEmpty() && e.getEntity().totalExperience == 0; } }
            @SubscribeEvent public void respawn(PlayerEvent.PlayerRespawnEvent e) { if (e.getEntity().getUUID().equals(entry.id) && !e.isEndConquered()) respawns[0]++; }
        }
        var hooks = new Hooks(); MinecraftForge.EVENT_BUS.register(hooks);
        boolean keep = helper.getLevel().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY);
        helper.assertTrue(!keep, "Disposable QA world uses normal death drops");
        helper.assertTrue(actor.hurt(actor.damageSources().genericKill(), 10000), "Native lethal damage accepted");
        helper.assertTrue(actor.deathCommitted(), "Native death reached its completion point");
        // Native scattered death drops travel away from the body during the animation.
        var box = actor.getBoundingBox().inflate(32);
        helper.assertTrue(drops(helper, box, entry.id) == 10, "Inventory and menu cursor became exactly ten real tagged diamond drops");
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(ItemEntity.class, box).stream().anyMatch(e -> e.getItem().is(Items.IRON_BOOTS))
                && helper.getLevel().getEntitiesOfClass(ItemEntity.class, box).stream().noneMatch(e -> e.getItem().is(Items.DIAMOND_SWORD)), "Native equipment drops and vanishing curse applied");
        helper.assertTrue(helper.getLevel().getEntitiesOfClass(net.minecraft.world.entity.ExperienceOrb.class, actor.getBoundingBox().inflate(3)).stream().mapToInt(orb -> {
            // Vanilla can merge equal-value orbs during award; Value is per orb in its Count.
            var tag = new net.minecraft.nbt.CompoundTag(); orb.addAdditionalSaveData(tag);
            return orb.getValue() * tag.getInt("Count");
        }).sum() >= 35, "Native death emitted experience into the world");
        actor.die(actor.damageSources().genericKill());
        helper.assertTrue(drops(helper, box, entry.id) == 10, "Repeated death notification does not repeat loot");
        helper.runAfterDelay(25, () -> {
            var fresh = manager.actor(entry.id);
            helper.assertTrue(fresh != actor && fresh.isAlive() && fresh.getHealth() == fresh.getMaxHealth(), "Actual death ticks produced a fresh healthy body");
            helper.assertTrue(fresh.getUUID().equals(entry.id) && fresh.getGameProfile().getName().equals(entry.name), "Identity survives respawn");
            helper.assertTrue(fresh.getRespawnPosition().equals(helper.absolutePos(new BlockPos(4,2,3)))
                    && fresh.position().distanceTo(Vec3.atCenterOf(fresh.getRespawnPosition())) < 4, "Native bed resolver supplies respawn position");
            helper.assertTrue(cloneEmpty[0] && fresh.getInventory().isEmpty() && drops(helper, box, entry.id) == 10, "Fresh clone did not resurrect dropped inventory or XP; actual drops remain in the world");
            helper.assertTrue(first.state() == JobState.CANCELLED && current.state() == JobState.CANCELLED && queued.state() == JobState.CANCELLED,
                    "Current, parked and queued programs all stop outside catch recovery");
            helper.assertTrue(!entry.authority.controls(owner, lease) && entry.authority.session() != null, "Respawn rotates control authority");
            helper.assertTrue(clones[0] == 1 && respawns[0] == 1, "Normal Forge clone and respawn hooks each fire once");
            helper.assertTrue(manager.control().snapshot(owner, entry.id).getAsJsonObject("lifecycle").get("usedSpawnBlock").getAsBoolean(), "Snapshot reports actual spawn choice");
            MinecraftForge.EVENT_BUS.unregister(hooks); clear(manager, owner, entry); pass("native_death_drop_cancel_bed_respawn"); helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void keepInventoryAndBrokenBedUseNativeRespawnRules(GameTestHelper helper) {
        floor(helper); bed(helper,4,2,3); var manager = manager(helper); UUID owner = UUID.randomUUID(); var entry = spawn(helper, manager, owner); var actor = manager.actor(entry.id);
        actor.setRespawnPosition(helper.getLevel().dimension(), helper.absolutePos(new BlockPos(4,2,3)), 0, false, false);
        helper.setBlock(new BlockPos(4,2,3), Blocks.AIR); helper.setBlock(new BlockPos(4,2,4), Blocks.AIR);
        actor.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 6)); actor.experienceLevel = 4; actor.totalExperience = 48;
        actor.getFoodData().setFoodLevel(5);
        var rule = helper.getLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY); boolean before = rule.get(); rule.set(true, helper.getLevel().getServer());
        try {
            actor.hurt(actor.damageSources().genericKill(), 10000); manager.tickLifecycle(); actor.deathTime = 20; manager.tickLifecycle();
            var fresh = manager.actor(entry.id);
            helper.assertTrue(fresh != actor && fresh.getInventory().countItem(Items.DIAMOND) == 6 && fresh.experienceLevel == 4 && fresh.totalExperience == 48,
                    "Native keep-inventory respawn retains inventory and experience");
            helper.assertTrue(fresh.getFoodData().getFoodLevel() == 20 && fresh.getHealth() == fresh.getMaxHealth(), "Death still resets hunger and health");
            helper.assertTrue(fresh.getRespawnPosition() == null && entry.control.getAsJsonObject("lifecycle").get("invalidSpawnBlock").getAsBoolean(), "Broken bed uses normal overworld fallback and reports invalid spawn");
        } finally { rule.set(before, helper.getLevel().getServer()); }
        clear(manager, owner, entry); pass("keep_inventory_and_broken_bed_fallback"); helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void disconnectWhileDeadDoesNotDropAgainOnRestoration(GameTestHelper helper) {
        floor(helper); var manager = manager(helper); UUID owner = UUID.randomUUID(); var entry = spawn(helper, manager, owner); var actor = manager.actor(entry.id);
        ItemStack diamonds = new ItemStack(Items.DIAMOND, 8); diamonds.getOrCreateTag().putString("qa_death", entry.id.toString()); actor.getInventory().setItem(0, diamonds);
        var box = actor.getBoundingBox().inflate(32); actor.hurt(actor.damageSources().genericKill(), 10000);
        manager.disconnectOwner(owner); var decoded = AvatarEntry.load(entry.save()); entry.physical = decoded.physical; entry.execution = decoded.execution; entry.control = decoded.control;
        helper.assertTrue(entry.physical.getBoolean("ParacraftDeathCommitted") && manager.restoreOwner(owner).isEmpty(), "Committed death state survives saved-body reconstruction");
        var dead = manager.actor(entry.id); helper.assertTrue(dead.deathCommitted() && entry.authority.session() == null, "Dead saved body cannot acquire gameplay control");
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(manager.actor(entry.id) != dead && manager.actor(entry.id).isAlive() && manager.actor(entry.id).getInventory().isEmpty(), "Restored dead body respawns without retaining lost items");
            helper.assertTrue(drops(helper, box, entry.id) == 8, "Reconnect and respawn did not repeat death drops");
            clear(manager, owner, entry); pass("dead_disconnect_restore_no_duplicate_loot"); helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void cancelledNativeDeathDoesNotTriggerCompanionRespawn(GameTestHelper helper) {
        floor(helper); var manager = manager(helper); UUID owner = UUID.randomUUID(); var entry = spawn(helper, manager, owner); var actor = manager.actor(entry.id); UUID lease = entry.authority.session();
        actor.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 5));
        class Rescue { @SubscribeEvent public void death(LivingDeathEvent event) { if (event.getEntity() == actor) { event.setCanceled(true); actor.setHealth(4); } } }
        var rescue = new Rescue(); MinecraftForge.EVENT_BUS.register(rescue);
        try {
            actor.hurt(actor.damageSources().genericKill(), 10000); manager.tickLifecycle();
            helper.assertTrue(manager.actor(entry.id) == actor && actor.isAlive() && !actor.deathCommitted(), "Forge-cancelled death does not commit or replace the body");
            helper.assertTrue(actor.getInventory().countItem(Items.DIAMOND) == 5 && entry.authority.controls(owner, lease), "Rescue preserves belongings and control");
        } finally { MinecraftForge.EVENT_BUS.unregister(rescue); }
        clear(manager, owner, entry); pass("cancelled_death_preserves_native_rescue"); helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 150)
    public static void bedProgramSleepsBrieflyAndRespectsOccupiedBeds(GameTestHelper helper) {
        floor(helper); bed(helper,4,2,3); var manager = manager(helper); UUID owner = UUID.randomUUID(); var entry = spawn(helper, manager, owner); var actor = manager.actor(entry.id);
        var head = helper.absolutePos(new BlockPos(4,2,3));
        class Night { @SubscribeEvent public void time(SleepingTimeCheckEvent event) { if (event.getEntity() == actor) event.setResult(Event.Result.ALLOW); } }
        var night = new Night(); MinecraftForge.EVENT_BUS.register(night);
        String operation = "{\"steps\":[{\"op\":\"set_spawn\",\"bed\":" + JobJson.position(head) + ",\"saveAs\":\"bed\"}]}";
        var job = submit(manager, owner, entry, operation, "replace"); boolean[] slept = {false};
        helper.onEachTick(() -> {
            if (actor.isSleeping()) slept[0] = true;
            helper.assertTrue(job.state() != JobState.FAILED && job.state() != JobState.CANCELLED, "Bed operation: " + job.view());
            if (job.state() != JobState.SUCCEEDED) return;
            helper.assertTrue(slept[0] && !actor.isSleeping() && !helper.getLevel().getBlockState(head).getValue(BedBlock.OCCUPIED), "Native sleep actually began and cleared bed occupancy after brief rest");
            helper.assertTrue(actor.getRespawnPosition().equals(head) && job.result().getAsJsonObject("results").getAsJsonObject("bed").get("sleepEntered").getAsBoolean(), "Normal bed interaction established spawn");
            MinecraftForge.EVENT_BUS.unregister(night);
            helper.getLevel().setBlockAndUpdate(head, helper.getLevel().getBlockState(head).setValue(BedBlock.OCCUPIED,true));
            var denied = submit(manager, owner, entry, operation, "replace"); denied.tick();
            helper.assertTrue(denied.state() == JobState.FAILED && denied.view().get("error").getAsString().contains("occupied"), "Occupied bed fails without ejecting its occupant");
            helper.getLevel().setBlockAndUpdate(head, helper.getLevel().getBlockState(head).setValue(BedBlock.OCCUPIED,false));
            clear(manager, owner, entry); pass("native_bed_brief_sleep_and_occupied_refusal"); helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void initialBedSearchSetsDaytimeSpawnWithoutClaimingSleep(GameTestHelper helper) {
        floor(helper); bed(helper,4,2,3); var manager = manager(helper); UUID owner = UUID.randomUUID(); var entry = spawn(helper, manager, owner); var actor = manager.actor(entry.id);
        class Day { @SubscribeEvent public void time(SleepingTimeCheckEvent event) { if (event.getEntity() == actor) event.setResult(Event.Result.DENY); } }
        var day = new Day(); MinecraftForge.EVENT_BUS.register(day);
        helper.onEachTick(() -> {
            var initial = entry.control.getAsJsonObject("survival").getAsJsonObject("initialBed");
            if (!initial.get("state").getAsString().equals("succeeded")) return;
            helper.assertTrue(actor.getRespawnPosition().equals(helper.absolutePos(new BlockPos(4,2,3))) && !actor.isSleeping(), "Initial server routine establishes bed spawn during daytime");
            helper.assertTrue(!initial.getAsJsonObject("result").get("sleepEntered").getAsBoolean()
                    && initial.getAsJsonObject("result").get("sleepProblem").getAsString().equals("NOT_POSSIBLE_NOW"), "Spawn success is distinguished from game-refused sleep");
            manager.disconnectOwner(owner); manager.restoreOwner(owner);
            helper.assertTrue(manager.actor(entry.id).getRespawnPosition().equals(helper.absolutePos(new BlockPos(4,2,3))), "Bed spawn survives disconnect restoration");
            MinecraftForge.EVENT_BUS.unregister(day); clear(manager, owner, entry); pass("initial_bed_day_spawn_and_persistence"); helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void eatingUsesNativeFoodAndRemainderThenResumesWork(GameTestHelper helper) {
        floor(helper); var manager = manager(helper); UUID owner = UUID.randomUUID(); var entry = spawn(helper, manager, owner); var actor = manager.actor(entry.id);
        actor.getInventory().setItem(0, new ItemStack(Items.IRON_SWORD)); actor.getInventory().setItem(12, new ItemStack(Items.MUSHROOM_STEW));
        actor.getFoodData().setFoodLevel(10); actor.getFoodData().setSaturation(0);
        var job = submit(manager, owner, entry, waitProgram(10), "replace"); int[] finished = {0}; boolean[] held = {false};
        class Food { @SubscribeEvent public void finish(LivingEntityUseItemEvent.Finish event) { if (event.getEntity() == actor && event.getItem().is(Items.MUSHROOM_STEW)) finished[0]++; } }
        var food = new Food(); MinecraftForge.EVENT_BUS.register(food);
        helper.onEachTick(() -> {
            if (actor.isUsingItem()) { held[0] = true; helper.assertTrue(job.state() == JobState.PAUSED, "Native food use holds gameplay at a safe boundary"); }
            helper.assertTrue(job.state() != JobState.FAILED && job.state() != JobState.CANCELLED, "Food interrupted job remains recoverable");
            if (job.state() != JobState.SUCCEEDED) return;
            helper.assertTrue(held[0] && finished[0] == 1 && actor.getFoodData().getFoodLevel() == 16, "Actual use duration and native finish event supplied food");
            helper.assertTrue(actor.getInventory().countItem(Items.BOWL) == 1 && actor.getInventory().countItem(Items.MUSHROOM_STEW) == 0
                    && actor.getInventory().countItem(Items.IRON_SWORD) == 1, "Native food remainder and prior tool are conserved");
            helper.assertTrue(!manager.reflexBusy(entry.id) && manager.control().snapshot(owner, entry.id).getAsJsonObject("survival").get("mode").getAsString().equals("idle"), "Reflex releases work and reports completion without inference");
            MinecraftForge.EVENT_BUS.unregister(food); clear(manager, owner, entry); pass("native_eating_remainder_and_work_resume"); helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void ownerPauseDuringEatingPreventsAutomaticJobResumption(GameTestHelper helper) {
        floor(helper); var manager = manager(helper); UUID owner = UUID.randomUUID(); var entry = spawn(helper, manager, owner); var actor = manager.actor(entry.id);
        actor.getInventory().setItem(0, new ItemStack(Items.COOKED_BEEF)); actor.getFoodData().setFoodLevel(10); actor.getFoodData().setSaturation(0);
        var job = submit(manager, owner, entry, waitProgram(5), "replace"); boolean[] paused = {false}, checked = {false};
        helper.onEachTick(() -> {
            if (!paused[0] && actor.isUsingItem()) { manager.pause(owner, entry.id, true); paused[0] = true; }
            if (!paused[0] || actor.isUsingItem() || checked[0]) return;
            checked[0] = true; helper.assertTrue(actor.getFoodData().getFoodLevel() == 18, "Survival eating finishes while owner has paused work");
            helper.runAfterDelay(8, () -> {
                helper.assertTrue(job.state() == JobState.PAUSED && entry.paused, "Reflex completion cannot override explicit owner pause");
                manager.pause(owner, entry.id, false);
                helper.runAfterDelay(12, () -> {
                    helper.assertTrue(job.state() == JobState.SUCCEEDED, "Owner resume releases remaining work"); clear(manager, owner, entry); pass("owner_pause_survives_eating_reflex"); helper.succeed();
                });
            });
        });
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void deniedFoodUseDoesNotConsumeOrLeaveWorkHeld(GameTestHelper helper) {
        floor(helper); var manager = manager(helper); UUID owner = UUID.randomUUID(); var entry = spawn(helper, manager, owner); var actor = manager.actor(entry.id);
        actor.getInventory().setItem(0, new ItemStack(Items.COOKED_BEEF)); actor.getFoodData().setFoodLevel(10); actor.getFoodData().setSaturation(0); int[] attempts = {0};
        class Deny { @SubscribeEvent public void use(LivingEntityUseItemEvent.Start e) { if (e.getEntity() == actor) { attempts[0]++; e.setCanceled(true); } } }
        var deny = new Deny(); MinecraftForge.EVENT_BUS.register(deny); var job = submit(manager, owner, entry, waitProgram(10), "replace");
        helper.onEachTick(() -> {
            if (job.state() != JobState.SUCCEEDED) return;
            helper.assertTrue(attempts[0] == 1 && !actor.isUsingItem() && !manager.reflexBusy(entry.id), "Cancelled native food use releases the gameplay hold and does not retry every tick");
            helper.assertTrue(actor.getFoodData().getFoodLevel() == 10 && actor.getInventory().countItem(Items.COOKED_BEEF) == 1, "Refused item use grants no nutrition and consumes no food");
            MinecraftForge.EVENT_BUS.unregister(deny); clear(manager, owner, entry); pass("food_hook_refusal_releases_work"); helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 180)
    public static void disconnectDuringFoodUsePreservesItemsAndPausedProgram(GameTestHelper helper) {
        floor(helper); var manager = manager(helper); UUID owner = UUID.randomUUID(); var entry = spawn(helper, manager, owner); var first = manager.actor(entry.id);
        first.getInventory().setItem(12, new ItemStack(Items.MUSHROOM_STEW)); first.getFoodData().setFoodLevel(10); first.getFoodData().setSaturation(0);
        var work = submit(manager, owner, entry, waitProgram(10), "replace"); boolean[] disconnected = {false};
        helper.onEachTick(() -> {
            if (!disconnected[0] && first.isUsingItem()) {
                disconnected[0] = true; manager.disconnectOwner(owner);
                helper.assertTrue(!first.isUsingItem() && manager.restoreOwner(owner).isEmpty(), "Disconnect stops native food use before saving the body");
                helper.assertTrue(manager.actor(entry.id).getInventory().countItem(Items.MUSHROOM_STEW) == 1 && manager.actor(entry.id).getFoodData().getFoodLevel() == 10, "Incomplete use restores unconsumed food and actual hunger");
            }
            if (!disconnected[0]) return; var fresh = manager.actor(entry.id);
            if (fresh.getFoodData().getFoodLevel() != 16) return;
            helper.assertTrue(fresh.getInventory().countItem(Items.MUSHROOM_STEW) == 0 && fresh.getInventory().countItem(Items.BOWL) == 1, "Restored native eating consumes once and returns one bowl");
            helper.assertTrue(manager.jobs().request(entry.id, work.id).state() == JobState.PAUSED, "Finishing survival behavior cannot auto-resume a program paused by disconnect");
            clear(manager, owner, entry); pass("disconnect_during_native_food_use"); helper.succeed();
        });
    }

    @GameTest(template = "empty")
    public static void bedSpawnRespectsForgeSpawnCancellation(GameTestHelper helper) {
        floor(helper); bed(helper,4,2,3); var manager = manager(helper); UUID owner = UUID.randomUUID(); var entry = spawn(helper, manager, owner); var actor = manager.actor(entry.id);
        class Deny {
            @SubscribeEvent public void spawn(net.minecraftforge.event.entity.player.PlayerSetSpawnEvent e) { if (e.getEntity() == actor) e.setCanceled(true); }
            @SubscribeEvent public void day(SleepingTimeCheckEvent e) { if (e.getEntity() == actor) e.setResult(Event.Result.DENY); }
        }
        var deny = new Deny(); MinecraftForge.EVENT_BUS.register(deny);
        try {
            var job = submit(manager, owner, entry, "{\"steps\":[{\"op\":\"set_spawn\",\"bed\":" + JobJson.position(helper.absolutePos(new BlockPos(4,2,3))) + "}]}", "replace"); job.tick();
            helper.assertTrue(job.state() == JobState.FAILED && actor.getRespawnPosition() == null && !actor.isSleeping(), "A denied native spawn change is reported without bypassing Forge");
        } finally { MinecraftForge.EVENT_BUS.unregister(deny); }
        clear(manager, owner, entry); pass("bed_spawn_hook_refusal"); helper.succeed();
    }

    @GameTest(template = "empty")
    public static void spectatorDoesNotRunSurvivalActionsOrSubmitGameplay(GameTestHelper helper) {
        floor(helper); bed(helper,4,2,3); var manager = manager(helper); UUID owner = UUID.randomUUID(); var entry = spawn(helper, manager, owner); var actor = manager.actor(entry.id);
        actor.setGameMode(net.minecraft.world.level.GameType.SPECTATOR); actor.getFoodData().setFoodLevel(10); actor.getInventory().setItem(0,new ItemStack(Items.COOKED_BEEF));
        manager.tickLifecycle();
        helper.assertTrue(!actor.isUsingItem() && !actor.isSleeping() && actor.getRespawnPosition() == null && !manager.reflexBusy(entry.id), "Spectator state runs no food or initial-bed actions");
        try { submit(manager,owner,entry,waitProgram(1),"replace"); throw new AssertionError("Spectator gameplay submission should fail"); }
        catch (IllegalStateException expected) { }
        var bedJob = new com.aurammods.paracraft.forge.execution.BedSpawnJob(UUID.randomUUID(), actor, helper.absolutePos(new BlockPos(4,2,3))); bedJob.tick();
        helper.assertTrue(bedJob.state() == JobState.FAILED && actor.getRespawnPosition() == null, "Direct bed action also respects native spectator semantics");
        clear(manager,owner,entry); pass("spectator_suppresses_survival_gameplay"); helper.succeed();
    }

    private static AvatarManager manager(GameTestHelper helper) { return ServerEvents.avatars(helper.getLevel().getServer()); }
    private static void floor(GameTestHelper helper) { for (int x=0;x<8;x++) for(int z=0;z<8;z++) helper.setBlock(new BlockPos(x,1,z),Blocks.STONE); }
    private static void bed(GameTestHelper helper, int x,int y,int z) {
        var state = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        helper.setBlock(new BlockPos(x,y,z+1), state.setValue(BedBlock.PART, BedPart.FOOT)); helper.setBlock(new BlockPos(x,y,z), state.setValue(BedBlock.PART,BedPart.HEAD));
    }
    private static AvatarEntry spawn(GameTestHelper helper, AvatarManager manager, UUID owner) { return manager.spawn(owner, helper.getLevel(), helper.absoluteVec(new Vec3(2.5,2,3.5)), "QS"+UUID.randomUUID().toString().substring(0,8)); }
    private static GameJob submit(AvatarManager manager, UUID owner, AvatarEntry entry, String program, String mode) { return manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), UUID.randomUUID(), JsonParser.parseString(program).getAsJsonObject(), mode); }
    private static String waitProgram(int ticks) { return "{\"steps\":[{\"op\":\"wait_ticks\",\"ticks\":"+ticks+"}]}"; }
    private static int drops(GameTestHelper helper, net.minecraft.world.phys.AABB box, UUID id) { return helper.getLevel().getEntitiesOfClass(ItemEntity.class, box).stream().filter(e -> e.getItem().is(Items.DIAMOND) && e.getItem().hasTag() && id.toString().equals(e.getItem().getTag().getString("qa_death"))).mapToInt(e -> e.getItem().getCount()).sum(); }
    private static void clear(AvatarManager manager, UUID owner, AvatarEntry entry) { manager.actor(entry.id).getInventory().clearContent(); manager.destroy(owner, entry.id); }
    private static void pass(String event) { Paracraft.LOG.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\"}", event); }
}
