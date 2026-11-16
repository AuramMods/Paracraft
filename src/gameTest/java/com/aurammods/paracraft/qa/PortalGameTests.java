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
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class PortalGameTests {
    @GameTest(batch="portals",template="empty",timeoutTicks=160)
    public static void avatarPhysicallyApproachesSelectedEndPortal(GameTestHelper h){
        floor(h);var m=manager(h);UUID owner=UUID.randomUUID();BlockPos portal=h.absolutePos(new BlockPos(6,2,5));h.getLevel().setBlockAndUpdate(portal,Blocks.END_PORTAL.defaultBlockState());var entry=spawn(h,m,owner,h.absolutePos(new BlockPos(2,2,5)));var original=m.actor(entry.id);Vec3 start=original.position();
        var work=submit(m,owner,entry,"{\"steps\":["+portalStep(portal,"minecraft:the_end",120)+"]}");double[] walked={0};Vec3[] previous={start};
        h.onEachTick(()->{
            if(original.serverLevel()==h.getLevel()){walked[0]+=original.position().distanceTo(previous[0]);previous[0]=original.position();}
            check(h,work);if(work.state()!=JobState.SUCCEEDED)return;
            h.assertTrue(walked[0]>2.5 && original.serverLevel().dimension()==Level.END,"Avatar physically walked into the selected portal before native transition");clear(m,owner,entry);pass("physical_approach_selected_end_portal");h.succeed();
        });
    }
    @GameTest(batch="portals",template="empty",timeoutTicks=160)
    public static void blockSearchKeepsNineChunkScopeCountsFreshnessAndCheckpoint(GameTestHelper h){
        floor(h);var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,h.absolutePos(new BlockPos(3,2,3)));var actor=m.actor(entry.id);var center=actor.chunkPosition();
        BlockPos high=new BlockPos(center.getMinBlockX()+3,250,center.getMinBlockZ()+3),diagonal=high.offset(16,0,16),outside=high.offset(32,0,16);
        for(var pos:java.util.List.of(high,diagonal,outside))h.getLevel().setBlockAndUpdate(pos,Blocks.GILDED_BLACKSTONE.defaultBlockState());
        GameJob[] work={submit(m,owner,entry,"{\"steps\":[{\"op\":\"search_blocks\",\"block\":\"minecraft:gilded_blackstone\",\"limit\":1,\"saveAs\":\"found\"}]}")};boolean[] restored={false},finished={false};
        h.onEachTick(()->{
            if(finished[0])return;check(h,work[0]);
            if(!restored[0] && work[0].state()==JobState.RUNNING && work[0].view().toString().contains("searching_blocks")){
                restored[0]=true;UUID id=work[0].id;m.disconnectOwner(owner);restore(h,m,owner,entry);work[0]=m.jobs().request(entry.id,id);m.jobs().resumeRequest(owner,entry.id,entry.authority.session(),id);
            }
            if(work[0].state()!=JobState.SUCCEEDED)return;finished[0]=true;var found=work[0].result().getAsJsonObject("results").getAsJsonObject("found");
            h.assertTrue(restored[0] && found.get("total").getAsLong()==2 && found.get("truncated").getAsBoolean() && found.getAsJsonArray("blocks").size()==1,"Actual high/diagonal blocks counted once across checkpoint; tenth chunk excluded and result cap explicit");
            h.assertTrue(found.get("complete").getAsBoolean() && found.getAsJsonArray("coveredChunks").size()==9 && found.getAsJsonArray("missingChunks").isEmpty(),"All nine actual loaded chunks are covered");long observed=found.get("observedAt").getAsLong();
            h.runAfterDelay(8,()->{h.assertTrue(work[0].result().getAsJsonObject("results").getAsJsonObject("found").get("observedAt").getAsLong()==observed,"Completed block observation freshness stays frozen");for(var pos:java.util.List.of(high,diagonal,outside))h.getLevel().setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());clear(m,owner,entry);pass("block_search_nine_chunks_checkpoint_count_freshness");h.succeed();});
        });
    }
    @GameTest(batch="portals",template="empty",timeoutTicks=260)
    public static void explicitNetherPortalUsesNativeDelayAndContinuesInDestination(GameTestHelper h){
        floor(h);var m=manager(h);UUID owner=UUID.randomUUID();BlockPos source=h.absolutePos(new BlockPos(5,2,5));portal(h.getLevel(),source);
        var entry=spawn(h,m,owner,source);var actor=m.actor(entry.id);actor.getInventory().setItem(0,new ItemStack(Items.DIAMOND,5));
        GameJob[] work={null};long observed=h.getLevel().getGameTime();long[] started={0};
        h.onEachTick(()->{
            if(work[0]==null){
            if(h.getLevel().getGameTime()-observed<20)return;
            h.assertTrue(actor.serverLevel()==h.getLevel() && !actor.canChangeDimensions(),"Standing in a portal has no implicit travel permission");
            work[0]=submit(m,owner,entry,"{\"steps\":["+portalStep(source,"minecraft:the_nether",220)+",{\"op\":\"execute_command\",\"command\":\"qa_identity\",\"saveAs\":\"identity\"}]}");
            started[0]=h.getLevel().getGameTime();return;
            }
                check(h,work[0]);if(work[0].state()!=JobState.SUCCEEDED)return;
                h.assertTrue(h.getLevel().getGameTime()-started[0]>=80,"Survival player waited native portal ticks");
                h.assertTrue(actor.serverLevel().dimension()==Level.NETHER && !actor.isChangingDimension(),"Native world transition completed in actual destination");
                h.assertTrue(actor.serverLevel().getEntity(entry.id)==actor && h.getLevel().getEntity(entry.id)==null && actor.server.getPlayerList().getPlayer(entry.id)==actor,"Native world and global roster retain the same body");
                h.assertTrue(actor.getInventory().countItem(Items.DIAMOND)==5 && !actor.canChangeDimensions(),"Items are conserved and arrival revokes automatic portal permission");
                h.assertTrue(work[0].result().getAsJsonObject("results").getAsJsonObject("identity").get("dimension").getAsString().equals("minecraft:the_nether"),"Dependent command ran in destination");
                clear(m,owner,entry);pass("explicit_native_nether_portal_and_followup");h.succeed();
        });
    }
    @GameTest(batch="portals",template="empty",timeoutTicks=310)
    public static void pauseAndBodyRestoreRemovePortalPermissionUntilExplicitResume(GameTestHelper h){
        floor(h);var m=manager(h);UUID owner=UUID.randomUUID();BlockPos source=h.absolutePos(new BlockPos(5,2,5));portal(h.getLevel(),source);var entry=spawn(h,m,owner,source);
        GameJob[] current={submit(m,owner,entry,"{\"steps\":["+portalStep(source,"minecraft:the_nether",240)+"]}")};boolean[] saved={false},resumed={false};
        h.onEachTick(()->{
            check(h,current[0]);
            if(!saved[0] && current[0].view().toString().contains("inside_portal")){
                saved[0]=true;UUID id=current[0].id;m.disconnectOwner(owner);restore(h,m,owner,entry);current[0]=m.jobs().request(entry.id,id);
                h.assertTrue(current[0].state()==JobState.PAUSED && !m.actor(entry.id).canChangeDimensions(),"Restored body has no live portal grant");
                h.runAfterDelay(95,()->{
                    h.assertTrue(m.actor(entry.id).serverLevel()==h.getLevel() && current[0].state()==JobState.PAUSED,"No native transition occurs during paused portal contact");
                    m.jobs().resumeRequest(owner,entry.id,entry.authority.session(),id);resumed[0]=true;
                });
            }
            if(current[0].state()!=JobState.SUCCEEDED)return;
            h.assertTrue(saved[0] && resumed[0] && m.actor(entry.id).serverLevel().dimension()==Level.NETHER,"Explicit resume completed the saved portal operation");clear(m,owner,entry);pass("native_portal_pause_rebind_resume");h.succeed();
        });
    }
    @GameTest(batch="portals",template="empty",timeoutTicks=180)
    public static void travelHookRefusalIsActuallyAttemptedThenTimesOutIntoRecovery(GameTestHelper h){
        floor(h);var m=manager(h);UUID owner=UUID.randomUUID();BlockPos source=h.absolutePos(new BlockPos(5,2,5));portal(h.getLevel(),source);var entry=spawn(h,m,owner,source);int[] attempts={0};
        class Deny{@SubscribeEvent public void travel(EntityTravelToDimensionEvent e){if(e.getEntity().getUUID().equals(entry.id)){attempts[0]++;e.setCanceled(true);}}}
        var deny=new Deny();MinecraftForge.EVENT_BUS.register(deny);
        var work=submit(m,owner,entry,"{\"steps\":[{\"try\":["+portalStep(source,"minecraft:the_nether",110)+"],\"catch\":[{\"op\":\"report_failure\",\"saveAs\":\"denial\"}]},{\"op\":\"execute_command\",\"command\":\"qa_identity\",\"saveAs\":\"after\"}]}");
        h.onEachTick(()->{
            check(h,work);if(work.state()!=JobState.SUCCEEDED)return;MinecraftForge.EVENT_BUS.unregister(deny);
            h.assertTrue(attempts[0]>0 && m.actor(entry.id).serverLevel()==h.getLevel(),"Native travel was attempted and refused by the real Forge hook");
            h.assertTrue(work.result().getAsJsonObject("results").getAsJsonObject("denial").toString().contains("before timeout") && !m.actor(entry.id).canChangeDimensions(),"Actual failed outcome reaches catch and revokes entry");
            clear(m,owner,entry);pass("native_portal_refusal_timeout_recovery");h.succeed();
        });
    }
    @GameTest(batch="portals",template="empty",timeoutTicks=150)
    public static void endEntryAndCreditsReturnPreserveLifeInventoryAndPendingProgram(GameTestHelper h){
        floor(h);var m=manager(h);UUID owner=UUID.randomUUID();BlockPos source=h.absolutePos(new BlockPos(5,2,5));h.getLevel().setBlockAndUpdate(source,Blocks.END_PORTAL.defaultBlockState());
        var entry=spawn(h,m,owner,source);var original=m.actor(entry.id);BlockPos spawn=h.absolutePos(new BlockPos(9,2,9));original.setRespawnPosition(Level.OVERWORLD,spawn,0,true,false);
        ItemStack diamonds=new ItemStack(Items.DIAMOND,7);diamonds.getOrCreateTag().putString("qa_portal","keep");original.getInventory().setItem(0,diamonds);original.getInventory().setItem(38,new ItemStack(Items.IRON_CHESTPLATE));original.giveExperienceLevels(3);
        var into=submit(m,owner,entry,"{\"steps\":["+portalStep(source,"minecraft:the_end",100)+"]}");boolean[] returning={false};UUID[] returnId={null};int[] clones={0},returns={0};
        class Observe{@SubscribeEvent public void clone(PlayerEvent.Clone e){if(e.getEntity().getUUID().equals(entry.id) && !e.isWasDeath())clones[0]++;}@SubscribeEvent public void respawn(PlayerEvent.PlayerRespawnEvent e){if(e.getEntity().getUUID().equals(entry.id) && e.isEndConquered())returns[0]++;}}
        var observer=new Observe();MinecraftForge.EVENT_BUS.register(observer);long life=entry.control.has("lifeRevision")?entry.control.get("lifeRevision").getAsLong():0;
        h.onEachTick(()->{
            if(!returning[0]){
                check(h,into);if(into.state()!=JobState.SUCCEEDED)return;
                h.assertTrue(original.serverLevel().dimension()==Level.END && original.getInventory().countItem(Items.DIAMOND)==7,"Native End entry retained the actual body/items");
                original.setHealth(11);original.getFoodData().setFoodLevel(8);BlockPos exit=original.blockPosition();original.serverLevel().setBlockAndUpdate(exit,Blocks.END_PORTAL.defaultBlockState());
                var back=submit(m,owner,entry,"{\"steps\":["+portalStep(exit,"minecraft:overworld",100)+",{\"op\":\"execute_command\",\"command\":\"qa_identity\",\"saveAs\":\"after\"}]}");returnId[0]=back.id;returning[0]=true;return;
            }
            var back=m.jobs().request(entry.id,returnId[0]);check(h,back);if(back.state()!=JobState.SUCCEEDED)return;var fresh=m.actor(entry.id);MinecraftForge.EVENT_BUS.unregister(observer);
            h.assertTrue(fresh!=original && original.retired() && fresh.serverLevel()==h.getLevel() && fresh.position().distanceTo(Vec3.atBottomCenterOf(spawn))<2,"Native credits return uses the saved spawn and retires the old body");
            h.assertTrue(fresh.getInventory().countItem(Items.DIAMOND)==7 && fresh.getInventory().getItem(0).hasTag() && fresh.getInventory().getItem(38).is(Items.IRON_CHESTPLATE) && fresh.experienceLevel==3,"End return preserves items, metadata, equipment and experience");
            h.assertTrue(fresh.getHealth()==11 && fresh.getFoodData().getFoodLevel()==8 && !fresh.wonGame && !fresh.isChangingDimension(),"End return preserves survival state and completes local acknowledgment");
            h.assertTrue(clones[0]==1 && returns[0]==1 && (entry.control.has("lifeRevision")?entry.control.get("lifeRevision").getAsLong():0)==life,"Return emits native nondeath clone/respawn without a new life");
            h.assertTrue(back.result().getAsJsonObject("results").has("after") && fresh.server.getPlayerList().getPlayer(entry.id)==fresh,"Pending program and native roster rebind to returned body");
            clear(m,owner,entry);pass("native_end_entry_and_credits_return");h.succeed();
        });
    }
    @GameTest(batch="portals",template="empty",timeoutTicks=150)
    public static void cancelledPortalCannotFireLaterOrRunCatch(GameTestHelper h){
        floor(h);var m=manager(h);UUID owner=UUID.randomUUID();BlockPos source=h.absolutePos(new BlockPos(5,2,5));portal(h.getLevel(),source);var entry=spawn(h,m,owner,source);
        var work=submit(m,owner,entry,"{\"steps\":[{\"try\":["+portalStep(source,"minecraft:the_nether",130)+"],\"catch\":[{\"op\":\"execute_command\",\"command\":\"qa_identity\",\"saveAs\":\"catch\"}]}]}");boolean[] cancelled={false};
        h.onEachTick(()->{
            if(cancelled[0] || !work.view().toString().contains("inside_portal"))return;cancelled[0]=true;m.jobs().cancel(entry.id,"owner cancellation");
            h.runAfterDelay(100,()->{h.assertTrue(work.state()==JobState.CANCELLED && !work.result().getAsJsonObject("results").has("catch") && m.actor(entry.id).serverLevel()==h.getLevel() && !m.actor(entry.id).canChangeDimensions(),"Cancellation bypasses catch and removes delayed portal permission");clear(m,owner,entry);pass("native_portal_cancellation_no_late_transition");h.succeed();});
        });
    }
    private static String portalStep(BlockPos pos,String dimension,int ticks){return "{\"op\":\"use_portal\",\"position\":"+JobJson.position(pos)+",\"dimension\":\""+dimension+"\",\"timeoutTicks\":"+ticks+",\"saveAs\":\"portal\"}";}
    private static GameJob submit(AvatarManager m,UUID owner,AvatarEntry entry,String json){return m.jobs().submitProgram(owner,entry.id,entry.authority.session(),UUID.randomUUID(),JsonParser.parseString(json).getAsJsonObject(),"replace");}
    private static AvatarManager manager(GameTestHelper h){return ServerEvents.avatars(h.getLevel().getServer());}
    private static AvatarEntry spawn(GameTestHelper h,AvatarManager m,UUID owner,BlockPos position){return m.spawn(owner,h.getLevel(),Vec3.atBottomCenterOf(position),"QP"+UUID.randomUUID().toString().substring(0,8));}
    private static void restore(GameTestHelper h,AvatarManager m,UUID owner,AvatarEntry entry){h.assertTrue(m.restoreOwner(owner).isEmpty(),"Saved body restored");m.entry(entry.id).authority.connect();}
    private static void clear(AvatarManager m,UUID owner,AvatarEntry entry){var actor=m.actor(entry.id);if(actor!=null)actor.getInventory().clearContent();m.destroy(owner,entry.id);}
    private static void check(GameTestHelper h,GameJob work){h.assertTrue(work!=null && work.state()!=JobState.FAILED && work.state()!=JobState.CANCELLED,"Native portal work failed: "+(work==null?"missing":work.view()));}
    private static void floor(GameTestHelper h){for(int x=0;x<14;x++)for(int z=0;z<14;z++)h.getLevel().setBlockAndUpdate(h.absolutePos(new BlockPos(x,1,z)),Blocks.STONE.defaultBlockState());}
    private static void portal(ServerLevel level,BlockPos bottom){
        for(int x=-1;x<=2;x++)for(int y=-1;y<=3;y++)level.setBlockAndUpdate(bottom.offset(x,y,0),(x==-1 || x==2 || y==-1 || y==3)?Blocks.OBSIDIAN.defaultBlockState():Blocks.AIR.defaultBlockState());
        for(int x=0;x<2;x++)for(int y=0;y<3;y++)level.setBlockAndUpdate(bottom.offset(x,y,0),Blocks.NETHER_PORTAL.defaultBlockState().setValue(NetherPortalBlock.AXIS,Direction.Axis.X));
    }
    private static void pass(String event){Paracraft.LOG.info("QA_EVT {}","{\"event\":\""+event+"\",\"status\":\"pass\"}");}
}
