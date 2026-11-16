package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.core.agent.*;
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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class ReflexGameTests {
    @GameTest(template="empty",timeoutTicks=240)
    public static void lowOxygenEscapesRoofedWaterThroughASideOpeningAndResumesWork(GameTestHelper helper) {
        pool(helper,true,true); var manager = manager(helper); UUID owner = UUID.randomUUID(); var entry = spawn(helper,manager,owner,3.5,3.5); var actor = manager.actor(entry.id);
        actor.setAirSupply(90); var work = waitJob(manager,owner,entry,20); boolean[] held = {false}; Vec3 start = actor.position(); Vec3[] last = {start};
        helper.onEachTick(()->{
            helper.assertTrue(actor.position().distanceTo(last[0]) < 0.6,"Oxygen escape uses actual bounded player movement"); last[0]=actor.position();
            if (manager.reflexBusy(entry.id)) { held[0]=true; helper.assertTrue(work.state()==JobState.PAUSED,"Oxygen escape holds ordinary work"); }
            helper.assertTrue(work.state()!=JobState.FAILED && work.state()!=JobState.CANCELLED,"Held operation remains recoverable");
            var survival = manager.control().snapshot(owner,entry.id).getAsJsonObject("survival");
            if (survival.has("lastReflex")) helper.assertTrue(!survival.getAsJsonObject("lastReflex").get("state").getAsString().equals("FAILED"),"Oxygen route failed: "+survival);
            if (work.state()!=JobState.SUCCEEDED) return;
            helper.assertTrue(held[0] && !actor.needsAir() && actor.getAirSupply()>=actor.getMaxAirSupply()/2,"Native breathing and air supply actually recovered before work resumed");
            helper.assertTrue(actor.position().distanceTo(start)>2 && actor.getX()>helper.absolutePos(new BlockPos(5,2,3)).getX(),"Volumetric route moved sideways under the roof before surfacing");
            helper.assertTrue(survival.getAsJsonObject("lastReflex").getAsJsonObject("result").get("breathingRestored").getAsBoolean(),"Snapshot exposes actual oxygen recovery");
            clear(manager,owner,entry); pass("oxygen_roof_side_exit_and_work_resume"); helper.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=120)
    public static void trappedOxygenFailureLeavesWorkPausedAndDoesNotSpamRetries(GameTestHelper helper) {
        pool(helper,true,false); var manager = manager(helper); UUID owner=UUID.randomUUID(); var entry=spawn(helper,manager,owner,3.5,3.5); var actor=manager.actor(entry.id); actor.setAirSupply(90);
        var work=waitJob(manager,owner,entry,10);
        helper.runAfterDelay(35,()->{
            var state=manager.control().snapshot(owner,entry.id).getAsJsonObject("survival");
            helper.assertTrue(state.has("lastReflex") && state.getAsJsonObject("lastReflex").get("state").getAsString().equals("FAILED"),"Sealed pool reports an actual failed local search");
            helper.assertTrue(work.state()==JobState.PAUSED && actor.needsAir() && actor.getAirSupply()<90,"Failure grants no artificial air and leaves dangerous work paused");
            long attempts=entry.control.getAsJsonArray("events").asList().stream().filter(e->e.getAsJsonObject().get("type").getAsString().equals("reflex_started")).count();
            helper.assertTrue(attempts==1 && state.get("oxygenRetryTicks").getAsInt()>0,"Failed search does not restart every server tick");
            clear(manager,owner,entry); pass("oxygen_trapped_failure_and_retry_spacing"); helper.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=80)
    public static void nativeWaterBreathingAvoidsAnUnnecessaryOxygenReflex(GameTestHelper helper) {
        pool(helper,false,false); var manager=manager(helper); UUID owner=UUID.randomUUID(); var entry=spawn(helper,manager,owner,3.5,3.5); var actor=manager.actor(entry.id);
        actor.setAirSupply(90); actor.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING,200)); var work=waitJob(manager,owner,entry,12); Vec3 before=actor.position();
        helper.onEachTick(()->{
            if(work.state()!=JobState.SUCCEEDED)return;
            helper.assertTrue(!actor.needsAir() && !actor.canRefillAir() && actor.getAirSupply()==90,"Forge's native water-breathing outcome is honored without inventing air refill");
            helper.assertTrue(!manager.reflexBusy(entry.id) && actor.position().distanceTo(before)<0.5,"Breathable underwater work does not get an unnecessary escape route");
            clear(manager,owner,entry); pass("oxygen_honors_native_water_breathing"); helper.succeed();
        });
    }
    @GameTest(template="arena",timeoutTicks=220)
    public static void neutralMobReactionUsesNativeMeleeAndResumesInterruptedProgram(GameTestHelper helper) {
        var manager=manager(helper); UUID owner=UUID.randomUUID(); var entry=spawn(helper,manager,owner,48.5,48.5); var actor=manager.actor(entry.id);
        actor.getInventory().setItem(0,new ItemStack(Items.DIAMOND_SWORD)); var cow=EntityType.COW.create(helper.getLevel()); cow.setNoAi(true); cow.setHealth(4); cow.moveTo(helper.absoluteVec(new Vec3(50.5,2,48.5))); helper.getLevel().addFreshEntity(cow);
        GameJob[] work={null}; boolean[] held={false}; float[] hurtHealth={20};
        helper.runAfterDelay(70,()->{work[0]=waitJob(manager,owner,entry,20); helper.assertTrue(actor.hurt(actor.damageSources().mobAttack(cow),2),"Actual mob-sourced damage is accepted after spawn protection"); hurtHealth[0]=actor.getHealth();});
        helper.onEachTick(()->{
            if(work[0]==null)return; if(manager.reflexBusy(entry.id)){held[0]=true; helper.assertTrue(work[0].state()==JobState.PAUSED,"Retaliation holds ordinary work");}
            if(work[0].state()!=JobState.SUCCEEDED)return;
            helper.assertTrue(held[0] && !cow.isAlive() && actor.getInventory().getItem(0).getDamageValue()==1,"Server retaliation selected a weapon and made one actual native damaging hit");
            helper.assertTrue(hurtHealth[0]==18,"Native damage changed health before later natural regeneration"); cow.discard(); clear(manager,owner,entry); pass("neutral_mob_native_retaliation_and_resume"); helper.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=150)
    public static void passiveMobReactionStopsWithoutAttackingUntilExplicitResume(GameTestHelper helper) {
        floor(helper); var manager=manager(helper); UUID owner=UUID.randomUUID(); var entry=spawn(helper,manager,owner,2.5,3.5); var actor=manager.actor(entry.id); manager.configureBehavior(owner,entry.id,json("{\"mobs\":\"passive\"}"));
        actor.getInventory().setItem(0,new ItemStack(Items.DIAMOND_SWORD)); var cow=EntityType.COW.create(helper.getLevel()); cow.setNoAi(true); cow.moveTo(helper.absoluteVec(new Vec3(4.5,2,3.5))); helper.getLevel().addFreshEntity(cow); float health=cow.getHealth();
        helper.runAfterDelay(70,()->{
            var work=waitJob(manager,owner,entry,5); actor.hurt(actor.damageSources().mobAttack(cow),2);
            helper.runAfterDelay(15,()->{
                helper.assertTrue(work.state()==JobState.PAUSED && cow.getHealth()==health && actor.getInventory().getItem(0).getDamageValue()==0,"Passive reaction leaves work paused and does no retaliatory damage");
                manager.jobs().resumeRequest(owner,entry.id,entry.authority.session(),work.id);
                helper.runAfterDelay(10,()->{helper.assertTrue(work.state()==JobState.SUCCEEDED,"Explicit planning/owner resumption remains possible"); cow.discard(); clear(manager,owner,entry); pass("passive_reaction_stops_without_attack"); helper.succeed();});
            });
        });
    }
    @GameTest(template="arena",timeoutTicks=280)
    public static void defaultPlayerReactionFleesWithoutRetaliating(GameTestHelper helper) {
        var manager=manager(helper); UUID owner=UUID.randomUUID(), otherOwner=UUID.randomUUID(); var entry=spawn(helper,manager,owner,48.5,48.5); var attacker=spawn(helper,manager,otherOwner,50.5,48.5);
        var actor=manager.actor(entry.id); var other=manager.actor(attacker.id); actor.getInventory().setItem(0,new ItemStack(Items.DIAMOND_SWORD)); GameJob[] work={null}; Vec3 before=actor.position(); boolean[] fled={false};
        helper.runAfterDelay(70,()->{work[0]=waitJob(manager,owner,entry,15); helper.assertTrue(actor.hurt(actor.damageSources().playerAttack(other),2),"Native player damage obeys the QA server's actual enabled PvP rule");});
        helper.onEachTick(()->{
            if(work[0]==null)return; var state=manager.control().snapshot(owner,entry.id).getAsJsonObject("survival"); if(state.get("mode").getAsString().equals("fleeing"))fled[0]=true;
            if(state.has("lastReflex"))helper.assertTrue(!state.getAsJsonObject("lastReflex").get("state").getAsString().equals("FAILED"),"Flee failed: "+state);
            if(work[0].state()!=JobState.SUCCEEDED)return;
            helper.assertTrue(fled[0] && actor.position().distanceTo(before)>5 && actor.distanceTo(other)>=8,"Actual evasive navigation increases separation from the attacker");
            helper.assertTrue(other.getHealth()==other.getMaxHealth() && actor.getInventory().getItem(0).getDamageValue()==0,"Default player response does not attack");
            clear(manager,owner,entry); clear(manager,otherOwner,attacker); pass("player_default_flee_without_retaliation"); helper.succeed();
        });
    }
    @GameTest(template="arena",timeoutTicks=300)
    public static void aggressionUsesSeparateCategoriesAndExcludesInstructionAuthorities(GameTestHelper helper) {
        var manager=manager(helper); UUID helperOwner=UUID.randomUUID(), strangerOwner=UUID.randomUUID(); var authority=spawn(helper,manager,helperOwner,47.5,48.5); UUID owner=authority.id;
        var entry=spawn(helper,manager,owner,48.5,48.5); var stranger=spawn(helper,manager,strangerOwner,50.5,48.5); var actor=manager.actor(entry.id); var victim=manager.actor(stranger.id); victim.setHealth(4);
        actor.getInventory().setItem(0,new ItemStack(Items.DIAMOND_SWORD)); var husk=EntityType.HUSK.create(helper.getLevel()); husk.setNoAi(true); husk.setHealth(4); husk.moveTo(helper.absoluteVec(new Vec3(52.5,2,48.5))); helper.getLevel().addFreshEntity(husk);
        var cow=EntityType.COW.create(helper.getLevel()); cow.setNoAi(true); cow.moveTo(helper.absoluteVec(new Vec3(48.5,2,50.5))); helper.getLevel().addFreshEntity(cow); float cowHealth=cow.getHealth(); boolean[] switched={false};
        manager.configureBehavior(strangerOwner,stranger.id,json("{\"players\":\"passive\"}")); victim.getFoodData().setFoodLevel(6);
        helper.runAfterDelay(70,()->{victim.setHealth(4); manager.configureBehavior(owner,entry.id,json("{\"players\":\"aggressive\",\"mobs\":\"passive\"}"));});
        helper.runAfterDelay(240,()->Paracraft.LOG.info("Aggression QA diagnostic: switched={} actor={} victim={} victimTicks={} husk={} survival={}",switched[0],actor.position(),victim.getHealth(),victim.tickCount,husk.getHealth(),manager.behavior(owner,entry.id)));
        helper.onEachTick(()->{
            if(!switched[0] && !victim.isAlive()) {helper.assertTrue(husk.getHealth()==4,"Player aggression does not enable mob aggression"); switched[0]=true; manager.configureBehavior(owner,entry.id,json("{\"players\":\"passive\",\"mobs\":\"aggressive\"}"));}
            if(!switched[0] || husk.isAlive())return;
            helper.assertTrue(manager.actor(authority.id).getHealth()==20 && cow.getHealth()==cowHealth,"Proactive selection excludes instruction authorities and nonhostile mobs");
            helper.assertTrue(actor.getInventory().getItem(0).getDamageValue()>=2,"Player and hostile-mob categories used actual native attacks");
            manager.configureBehavior(owner,entry.id,json("{\"mobs\":\"passive\"}")); husk.discard(); cow.discard(); clear(manager,owner,entry); clear(manager,helperOwner,authority); clear(manager,strangerOwner,stranger); pass("aggression_categories_authority_and_hostile_filter"); helper.succeed();
        });
    }
    @GameTest(template="empty",timeoutTicks=180)
    public static void simulatedBehaviorRequestEscalatesAndPersistsAcrossOfflineConfiguration(GameTestHelper helper) {
        floor(helper); var manager=manager(helper); UUID owner=UUID.randomUUID(); var entry=spawn(helper,manager,owner,2.5,3.5); var clock=new Clock(); var memory=new AgentState();
        var loop=new HarnessLoop(new AgentBinding(manager.worldId(),owner,entry.id),memory,new SimulatedProvider(),clock,(id,tool,args,trigger)->manager.control().request(owner,entry.id,entry.authority.session(),id,tool,args,trigger),()->{});
        manager.control().chat(owner,entry.name+", behavior players passive");
        helper.onEachTick(()->{
            clock.advance(); loop.accept(manager.control().snapshot(owner,entry.id)); loop.tick(); loop.tick();
            if(!manager.behavior(owner,entry.id).getAsJsonObject("behavior").get("players").getAsString().equals("passive"))return;
            helper.assertTrue(loop.planningCalls()==1 && loop.lastError().isEmpty(),"Actual server behavior tool was reached through Light escalation");
            try {manager.configureBehavior(UUID.randomUUID(),entry.id,json("{\"mobs\":\"aggressive\"}")); throw new AssertionError("Foreign owner should fail");} catch(IllegalArgumentException expected){}
            loop.close(); manager.disconnectOwner(owner); manager.configureBehavior(owner,entry.id,json("{\"mobs\":\"flee\"}")); var decoded=AvatarEntry.load(entry.save()); entry.physical=decoded.physical; entry.control=decoded.control; entry.execution=decoded.execution; manager.restoreOwner(owner);
            var settings=manager.control().snapshot(owner,entry.id).getAsJsonObject("survival").getAsJsonObject("behavior"); helper.assertTrue(settings.get("players").getAsString().equals("passive") && settings.get("mobs").getAsString().equals("flee"),"Independent categories and offline changes survive saved-body restoration");
            clear(manager,owner,entry); pass("simulated_behavior_tool_authority_and_persistence"); helper.succeed();
        });
    }
    @GameTest(template="arena",timeoutTicks=650)
    public static void idlePatrolConsumesNoInferenceYieldsToWorkAndResumesAfterReconnect(GameTestHelper helper) {
        var manager=manager(helper); UUID owner=UUID.randomUUID(); var entry=spawn(helper,manager,owner,48.5,48.5); var clock=new Clock(); var loop=new HarnessLoop(new AgentBinding(manager.worldId(),owner,entry.id),new AgentState(),new SimulatedProvider(),clock,
                (id,tool,args,trigger)->manager.control().request(owner,entry.id,entry.authority.session(),id,tool,args,trigger),()->{});
        boolean[] started={false}, restored={false}, finished={false}; long[] calls={-1}; int[] previousIndex={0}, arrivals={0}, interruptedIndex={0}; GameJob[] work={null};
        helper.runAfterDelay(40,()->{
            var settings=json("{\"idle\":{\"enabled\":true,\"waitTicks\":2,\"waypoints\":[]}}"); settings.getAsJsonObject("idle").getAsJsonArray("waypoints").add(JobJson.vector(helper.absoluteVec(new Vec3(44.5,2,48.5)))); settings.getAsJsonObject("idle").getAsJsonArray("waypoints").add(JobJson.vector(helper.absoluteVec(new Vec3(52.5,2,48.5))));
            manager.configureBehavior(owner,entry.id,settings); started[0]=true; calls[0]=loop.calls();
        });
        helper.onEachTick(()->{
            if(finished[0])return;
            clock.advance(); loop.accept(manager.control().snapshot(owner,entry.id)); loop.tick(); loop.tick(); if(!started[0])return;
            var idle=manager.behavior(owner,entry.id).getAsJsonObject("idle"); helper.assertTrue(!idle.get("state").getAsString().equals("failed"),"Patrol failed: "+idle);
            int index=idle.get("waypoint").getAsInt(); if(index!=previousIndex[0]){arrivals[0]++; previousIndex[0]=index;}
            if(work[0]==null) {
                helper.assertTrue(loop.calls()==calls[0],"Routine arrivals and waits do not invoke inference");
                if(arrivals[0]<2)return;
                interruptedIndex[0]=index; work[0]=manager.jobs().submitProgram(owner,entry.id,entry.authority.session(),UUID.randomUUID(),json("{\"steps\":[{\"op\":\"travel\",\"destination\":"+JobJson.vector(helper.absoluteVec(new Vec3(60.5,2,48.5)))+"}]}"),"replace"); return;
            }
            if(work[0].state()==JobState.RUNNING) {helper.assertTrue(index==interruptedIndex[0],"Ordinary work holds the idle waypoint cursor"); return;}
            helper.assertTrue(work[0].state()==JobState.SUCCEEDED,"Explicit travel should succeed");
            if(!restored[0]) {
                var position=manager.actor(entry.id).position(); manager.disconnectOwner(owner); manager.restoreOwner(owner); restored[0]=true;
                helper.assertTrue(manager.actor(entry.id).position().distanceTo(position)<0.001,"Reconnect retains the actual patrol/interruption location"); return;
            }
            if(arrivals[0]<3 || finished[0])return; finished[0]=true;
            manager.pause(owner,entry.id,true); Vec3 stopped=manager.actor(entry.id).position();
            helper.runAfterDelay(12,()->{
                helper.assertTrue(manager.actor(entry.id).position().distanceTo(stopped)<0.05,"Owner pause immediately stops routine movement");
                helper.assertTrue(manager.behavior(owner,entry.id).getAsJsonObject("behavior").getAsJsonObject("idle").get("enabled").getAsBoolean(),"Idle settings survived reconnect");
                loop.close(); clear(manager,owner,entry); pass("idle_patrol_no_inference_yield_reconnect_pause"); helper.succeed();
            });
        });
    }
    @GameTest(template="empty",timeoutTicks=330)
    public static void deniedRetaliationReportsNativeFailureAndKeepsOrdinaryWorkPaused(GameTestHelper helper) {
        floor(helper);var manager=manager(helper);UUID owner=UUID.randomUUID();var entry=spawn(helper,manager,owner,2.5,3.5);var actor=manager.actor(entry.id);
        actor.getInventory().setItem(0,new ItemStack(Items.DIAMOND_SWORD));var cow=EntityType.COW.create(helper.getLevel());cow.setNoAi(true);cow.moveTo(helper.absoluteVec(new Vec3(4.5,2,3.5)));helper.getLevel().addFreshEntity(cow);float health=cow.getHealth();
        class Protection { @net.minecraftforge.eventbus.api.SubscribeEvent public void attack(net.minecraftforge.event.entity.player.AttackEntityEvent event){if(event.getEntity()==actor)event.setCanceled(true);} }
        var protection=new Protection();net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(protection);GameJob[] work={null};boolean[] finished={false};
        helper.runAfterDelay(70,()->{work[0]=waitJob(manager,owner,entry,10);actor.hurt(actor.damageSources().mobAttack(cow),2);});
        helper.onEachTick(()->{
            if(finished[0] || work[0]==null)return;var state=manager.behavior(owner,entry.id);if(!state.has("lastReflex") || !state.getAsJsonObject("lastReflex").get("state").getAsString().equals("FAILED"))return;
            finished[0]=true;
            helper.assertTrue(cow.getHealth()==health && actor.getInventory().getItem(0).getDamageValue()==0,"Protected retaliation inflicts no damage or weapon wear");
            helper.runAfterDelay(15,()->{
                helper.assertTrue(work[0].state()==JobState.PAUSED && !manager.reflexBusy(entry.id),"Actual failed defense does not silently resume interrupted work");
                helper.assertTrue(state.getAsJsonObject("lastReflex").get("error").getAsString().contains("Ten native attack attempts"),"Failure preserves the actual native refusal");
                net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(protection);cow.discard();clear(manager,owner,entry);pass("denied_retaliation_preserves_pause_and_native_rules");helper.succeed();
            });
        });
    }

    private static void pool(GameTestHelper helper,boolean roof,boolean opening) {
        floor(helper);
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)for(int y=2;y<=6;y++) {
            if(x==0||x==7||z==0||z==7||roof && y==6 && !(opening && x==6)) helper.setBlock(new BlockPos(x,y,z),Blocks.STONE);
            else if(y<=5)helper.setBlock(new BlockPos(x,y,z),Blocks.WATER);
        }
    }
    private static void floor(GameTestHelper h){for(int x=0;x<8;x++)for(int z=0;z<8;z++)h.setBlock(new BlockPos(x,1,z),Blocks.STONE);}
    private static AvatarManager manager(GameTestHelper h){return ServerEvents.avatars(h.getLevel().getServer());}
    private static AvatarEntry spawn(GameTestHelper h,AvatarManager m,UUID owner,double x,double z){return m.spawn(owner,h.getLevel(),h.absoluteVec(new Vec3(x,2,z)),"QR"+UUID.randomUUID().toString().substring(0,8));}
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private static GameJob waitJob(AvatarManager m,UUID owner,AvatarEntry e,int ticks){return m.jobs().submitProgram(owner,e.id,e.authority.session(),UUID.randomUUID(),json("{\"steps\":[{\"op\":\"wait_ticks\",\"ticks\":"+ticks+"}]}"),"replace");}
    private static void clear(AvatarManager m,UUID owner,AvatarEntry e){m.actor(e.id).getInventory().clearContent();m.destroy(owner,e.id);}
    private static void pass(String e){Paracraft.LOG.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\"}",e);}
    private static final class Clock implements AgentClock {long n; public long nanoTime(){return n*1_000_000;} public long epochMillis(){return 1_800_000_000_000L+n;} void advance(){n+=1000;}}
}
