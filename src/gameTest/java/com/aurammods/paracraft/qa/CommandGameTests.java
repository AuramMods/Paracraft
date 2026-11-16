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
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class CommandGameTests {
    @GameTest(batch="commands",template="empty",timeoutTicks=160)
    public static void nativeRosterDiscoveryOutputAndPermissionsUseTheAvatarIdentity(GameTestHelper h){
        floor(h);var m=manager(h);UUID ownerOwner=UUID.randomUUID();var owner=spawn(h,m,ownerOwner,2.5,2.5);var entry=spawn(h,m,owner.id,4.5,4.5);var actor=m.actor(entry.id);var list=h.getLevel().getServer().getPlayerList();
        list.getOps().add(new ServerOpListEntry(m.actor(owner.id).getGameProfile(),2,false));
        h.assertTrue(list.getPlayer(entry.id)==actor && list.getPlayerByName(entry.name)==actor && list.getPlayers().contains(actor),"Native UUID, name and online roster resolve the actual body");
        var work=submit(m,owner.id,entry,"{\"steps\":[{\"op\":\"discover_commands\",\"prefix\":\"qa_\",\"saveAs\":\"commands\"},{\"op\":\"execute_command\",\"command\":\"/qa_identity\",\"saveAs\":\"identity\"},{\"op\":\"execute_command\",\"command\":\"qa_zero\",\"saveAs\":\"zero\"},{\"try\":[{\"op\":\"execute_command\",\"command\":\"qa_secret\"}],\"catch\":[{\"op\":\"report_failure\",\"saveAs\":\"denied\"}]}]}","replace");
        h.onEachTick(()->{
            running(h,work);if(work.state()!=JobState.SUCCEEDED)return;var results=work.result().getAsJsonObject("results");
            String forms=results.getAsJsonObject("commands").getAsJsonArray("forms").toString();h.assertTrue(forms.contains("qa_identity") && !forms.contains("qa_secret"),"Discovery uses current native permissions");
            var identity=results.getAsJsonObject("identity");h.assertTrue(identity.get("actorUuid").getAsString().equals(entry.id.toString()) && !actor.createCommandSourceStack().hasPermission(2),"Owner op permissions were not inherited");
            h.assertTrue(identity.getAsJsonArray("messages").toString().contains(entry.id.toString()) && identity.getAsJsonArray("messages").toString().contains("direct-player-reply"),"Both native source output and direct player messages are captured");
            h.assertTrue(results.getAsJsonObject("zero").get("nativeResult").getAsInt()==0 && results.getAsJsonObject("zero").get("callbackSuccess").getAsBoolean(),"A native successful zero result is not mislabeled as a failed dispatch");
            h.assertTrue(results.getAsJsonObject("denied").toString().contains("qa_secret"),"Native permission failure reaches normal catch context");
            list.getOps().remove(m.actor(owner.id).getGameProfile());clear(m,owner.id,entry);clear(m,ownerOwner,owner);pass("native_roster_command_identity_permissions_output");h.succeed();
        });
    }
    @GameTest(batch="commands",template="empty")
    public static void rejectedWorldSpawnDoesNotLeakNativeRosterEntries(GameTestHelper h){
        var m=manager(h);var list=h.getLevel().getServer().getPlayerList();int before=list.getPlayers().size();String name=name();
        class Reject{@SubscribeEvent public void join(EntityJoinLevelEvent e){if(e.getEntity() instanceof CompanionPlayer p && p.getGameProfile().getName().equals(name))e.setCanceled(true);}}
        var reject=new Reject();MinecraftForge.EVENT_BUS.register(reject);
        try{m.spawn(UUID.randomUUID(),h.getLevel(),h.absoluteVec(new Vec3(2.5,2,2.5)),name);throw new AssertionError("World refusal must reach the caller");}catch(IllegalStateException expected){}finally{MinecraftForge.EVENT_BUS.unregister(reject);}
        h.assertTrue(list.getPlayers().size()==before && list.getPlayerByName(name)==null,"Rejected spawn rolls back actual online lookup");pass("native_roster_spawn_rollback");h.succeed();
    }
    @GameTest(batch="commands",template="empty",timeoutTicks=180)
    public static void delayedTeleportIsObservedAfterAcceptanceAndReconnectWithoutResubmission(GameTestHelper h){
        floor(h);var m=manager(h);UUID recipientOwner=UUID.randomUUID();var recipient=spawn(h,m,recipientOwner,5.5,5.5);var entry=spawn(h,m,recipient.id,2.5,2.5);var old=m.actor(entry.id);old.getInventory().setItem(0,new ItemStack(Items.DIAMOND,9));
        var work=submit(m,recipient.id,entry,request(recipient.name,recipient.id,180),"replace");boolean[] restored={false};GameJob[] current={work};
        h.onEachTick(()->{
            running(h,current[0]);
            if(!restored[0] && CommandFixtures.calls(entry.id)==1){
                h.assertTrue(current[0].state()==JobState.RUNNING && old.distanceTo(m.actor(recipient.id))>3,"Request acknowledgment has not completed or moved the actor");
                UUID id=current[0].id;m.disconnectOwner(recipient.id);restore(h,m,recipient.id,entry);current[0]=m.jobs().get(entry.id);m.jobs().resumeRequest(recipient.id,entry.id,entry.authority.session(),id);restored[0]=true;
                old.teleportTo(h.getLevel(),old.getX()+20,old.getY(),old.getZ(),0,0);h.assertTrue(h.getLevel().getEntity(entry.id)==m.actor(entry.id),"A delayed callback cannot revive the retired disconnected body");
                h.runAfterDelay(8,()->{
                    h.assertTrue(current[0].state()==JobState.RUNNING && CommandFixtures.calls(entry.id)==1,"Restored command waits instead of submitting a second request");
                    var output=new java.util.ArrayList<String>();var stop=m.actor(recipient.id).observeMessages(message->output.add(message.getString()));
                    int accepted=h.getLevel().getServer().getCommands().performPrefixedCommand(m.actor(recipient.id).createCommandSourceStack(),"qa_tpaccept "+entry.id);stop.run();
                    h.assertTrue(accepted==1,"Recipient resolves the restored body by native UUID lookup; output="+output+" lookup="+h.getLevel().getServer().getPlayerList().getPlayer(entry.id));
                });
            }
            if(current[0].state()!=JobState.SUCCEEDED)return;
            var result=current[0].result().getAsJsonObject("results").getAsJsonObject("command");
            h.assertTrue(restored[0] && result.get("teleportObserved").getAsBoolean() && m.actor(entry.id).distanceTo(m.actor(recipient.id))<0.1,"Real native relocation completes the waiting operation");
            h.assertTrue(CommandFixtures.calls(entry.id)==1 && m.actor(entry.id).getInventory().countItem(Items.DIAMOND)==9,"Reconnect preserves command identity and carried items");
            clear(m,recipient.id,entry);clear(m,recipientOwner,recipient);pass("native_delayed_teleport_reconnect_no_reissue");h.succeed();
        });
    }
    @GameTest(batch="commands",template="empty",timeoutTicks=100)
    public static void acceptedTeleportWithoutAnOutcomeTimesOutAndDoesNotRunDependentActions(GameTestHelper h){
        floor(h);var m=manager(h);UUID otherOwner=UUID.randomUUID();var recipient=spawn(h,m,otherOwner,5.5,5.5);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,2.5,2.5);
        String program=request(recipient.name,recipient.id,20);var parsed=json(program);var later=json("{\"op\":\"execute_command\",\"command\":\"qa_identity\",\"saveAs\":\"must_not_run\"}");parsed.getAsJsonArray("steps").add(later);
        var work=m.jobs().submitProgram(owner,entry.id,entry.authority.session(),UUID.randomUUID(),parsed,"replace");
        h.onEachTick(()->{
            if(work.state()!=JobState.FAILED)return;
            h.assertTrue(work.view().get("error").getAsString().contains("not observed") && !work.result().getAsJsonObject("results").has("must_not_run"),"Acceptance is not reported as completed teleport, and dependent work stops");
            h.assertTrue(CommandFixtures.calls(entry.id)==1 && m.actor(entry.id).teleportSerial()==0,"No invented teleport or repeat command");clear(m,owner,entry);clear(m,otherOwner,recipient);pass("native_teleport_timeout_stops_dependents");h.succeed();
        });
    }
    @GameTest(batch="commands",template="empty",timeoutTicks=200)
    public static void crossDimensionCommandContinuesProgramInTheActualDestinationWorld(GameTestHelper h){
        floor(h);var m=manager(h);UUID otherOwner=UUID.randomUUID();var recipient=spawn(h,m,otherOwner,5.5,5.5);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,2.5,2.5);var actor=m.actor(entry.id);
        ServerLevel nether=h.getLevel().getServer().getLevel(Level.NETHER);BlockPos base=new BlockPos(800,100,800);area(nether,base);m.actor(recipient.id).teleportTo(nether,base.getX()+0.5,base.getY(),base.getZ()+0.5,0,0);
        ItemStack stone=new ItemStack(Items.COBBLESTONE,4);stone.getOrCreateTag().putString("qa_transfer","retained");actor.getInventory().setItem(0,stone);
        var parsed=json(request(recipient.name,recipient.id,150));parsed.getAsJsonArray("steps").add(json("{\"op\":\"place_blocks\",\"placements\":[{\"position\":"+JobJson.position(base.east())+",\"item\":\"minecraft:cobblestone\"}],\"saveAs\":\"placed\"}"));
        var work=m.jobs().submitProgram(owner,entry.id,entry.authority.session(),UUID.randomUUID(),parsed,"replace");boolean[] accepted={false};
        h.onEachTick(()->{
            running(h,work);if(!accepted[0] && CommandFixtures.calls(entry.id)==1){accepted[0]=true;h.getLevel().getServer().getCommands().performPrefixedCommand(m.actor(recipient.id).createCommandSourceStack(),"qa_tpaccept "+entry.name);}
            if(work.state()!=JobState.SUCCEEDED)return;
            h.assertTrue(actor.serverLevel()==nether && h.getLevel().getEntity(entry.id)==null && nether.getEntity(entry.id)==actor && !actor.isChangingDimension(),"Native world membership and transition acknowledgment match the destination");
            h.assertTrue(nether.getBlockState(base.east()).is(Blocks.COBBLESTONE) && actor.getInventory().countItem(Items.COBBLESTONE)==3 && actor.getInventory().getItem(0).hasTag(),"Dependent placement uses the destination game mode and physical inventory");
            m.disconnectOwner(owner);restore(h,m,owner,entry);h.assertTrue(m.actor(entry.id).serverLevel()==nether && m.actor(entry.id).getInventory().countItem(Items.COBBLESTONE)==3,"Dimension and inventory survive native roster removal/restoration");
            clear(m,owner,entry);clear(m,otherOwner,recipient);pass("native_command_dimension_transfer_and_following_action");h.succeed();
        });
    }
    @GameTest(batch="commands",template="empty",timeoutTicks=160)
    public static void homeCommandsRetainDistinctIdentityAcrossBodyRestoration(GameTestHelper h){
        floor(h);var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,2.5,2.5);var actor=m.actor(entry.id);Vec3 home=actor.position();
        var set=submit(m,owner,entry,"{\"steps\":[{\"op\":\"execute_command\",\"command\":\"qa_sethome\"}]}","replace");GameJob[] returned={null};boolean[] reset={false};
        h.onEachTick(()->{
            running(h,set);if(set.state()!=JobState.SUCCEEDED)return;
            if(!reset[0]){reset[0]=true;actor.teleportTo(h.getLevel(),home.x+3,home.y,home.z+3,0,0);m.disconnectOwner(owner);restore(h,m,owner,entry);returned[0]=submit(m,owner,entry,"{\"steps\":[{\"op\":\"execute_command\",\"command\":\"qa_home\",\"await\":\"teleport\",\"saveAs\":\"home\"}]}","replace");}
            running(h,returned[0]);if(returned[0].state()!=JobState.SUCCEEDED)return;
            h.assertTrue(m.actor(entry.id).position().distanceTo(home)<0.1 && m.actor(entry.id).getUUID().equals(entry.id),"Normal command state belongs to the persistent avatar UUID across bodies");clear(m,owner,entry);pass("native_home_identity_survives_reconnect");h.succeed();
        });
    }
    @GameTest(batch="commands",template="empty",timeoutTicks=160)
    public static void foreignDimensionWorkStaysParkedUntilTheAvatarReturns(GameTestHelper h){
        floor(h);var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,2.5,2.5);var actor=m.actor(entry.id);Vec3 start=actor.position();
        var work=submit(m,owner,entry,"{\"steps\":[{\"op\":\"wait_ticks\",\"ticks\":20}]}","replace");m.pause(owner,entry.id,true);var nether=h.getLevel().getServer().getLevel(Level.NETHER);BlockPos base=new BlockPos(820,100,800);area(nether,base);actor.teleportTo(nether,820.5,100,800.5,0,0);m.disconnectOwner(owner);restore(h,m,owner,entry);
        var parked=m.jobs().request(entry.id,work.id);h.assertTrue(parked.state()==JobState.PAUSED && parked.view().has("blockedReason"),"World-specific checkpoint is retained instead of failed or rebound in the wrong dimension");
        m.pause(owner,entry.id,false);
        h.assertTrue(m.jobs().resumeRequest(owner,entry.id,entry.authority.session(),work.id).state()==JobState.PAUSED,"Resume acknowledges the actual blocked state without executing old coordinates in the wrong dimension");
        m.actor(entry.id).teleportTo(h.getLevel(),start.x,start.y,start.z,0,0);var resumed=m.jobs().resumeRequest(owner,entry.id,entry.authority.session(),work.id);
        h.onEachTick(()->{running(h,resumed);if(resumed.state()!=JobState.SUCCEEDED)return;h.assertTrue(resumed.id.equals(work.id),"Returning rebinds and resumes the same request");clear(m,owner,entry);pass("foreign_dimension_checkpoint_parks_and_rebinds");h.succeed();});
    }
    @GameTest(batch="commands",template="empty",timeoutTicks=120)
    public static void nativeDisconnectEscapesCatchAndOwnerCanRestoreTheSavedAvatar(GameTestHelper h){
        floor(h);var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,2.5,2.5);var actor=m.actor(entry.id);actor.getInventory().setItem(0,new ItemStack(Items.DIAMOND,5));
        actor.server.getPlayerList().getOps().add(new ServerOpListEntry(actor.getGameProfile(),2,false));
        var work=submit(m,owner,entry,"{\"steps\":[{\"try\":[{\"op\":\"execute_command\",\"command\":\"qa_disconnect_self\"}],\"catch\":[{\"op\":\"execute_command\",\"command\":\"qa_identity\",\"saveAs\":\"must_not_run\"}]}]}","replace");boolean[] done={false};
        h.onEachTick(()->{
            if(done[0] || m.actor(entry.id)!=null)return;done[0]=true;
            h.assertTrue(work.state()==JobState.CANCELLED && !work.result().getAsJsonObject("results").has("must_not_run"),"Native listener disconnect bypasses catches");
            h.assertTrue(actor.server.getPlayerList().getPlayer(entry.id)==null && entry.paused,"Disconnected avatar is saved and removed from native lookup");
            actor.server.getPlayerList().getOps().remove(actor.getGameProfile());m.pause(owner,entry.id,false);
            h.assertTrue(m.actor(entry.id)!=actor && m.actor(entry.id).getInventory().countItem(Items.DIAMOND)==5 && !m.actor(entry.id).hasDisconnected(),"Owner resume restores saved state without reusing the retired connection");clear(m,owner,entry);pass("native_disconnect_cancels_and_owner_restore");h.succeed();
        });
    }
    @GameTest(batch="commands",template="empty",timeoutTicks=180)
    public static void routedSimulatedCommandUsesNativeExecutionAndCompletesItsTask(GameTestHelper h){
        floor(h);var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,2.5,2.5);var memory=new com.aurammods.paracraft.core.agent.AgentState();
        class Clock implements com.aurammods.paracraft.core.agent.AgentClock{long ticks;public long nanoTime(){return ticks*1_000_000_000L;}public long epochMillis(){return 1_800_000_000_000L+ticks*1000;}}
        var clock=new Clock();var loop=new com.aurammods.paracraft.core.agent.HarnessLoop(new com.aurammods.paracraft.core.agent.AgentBinding(m.worldId(),owner,entry.id),memory,new com.aurammods.paracraft.core.agent.SimulatedProvider(),clock,
                (id,tool,args,trigger)->m.control().request(owner,entry.id,entry.authority.session(),id,tool,args,trigger),()->{});boolean[] done={false};
        m.control().chat(owner,entry.name+", execute command qa_identity");
        h.onEachTick(()->{
            if(done[0])return;clock.ticks++;loop.accept(m.control().snapshot(owner,entry.id));loop.tick();loop.tick();h.assertTrue(loop.lastError().isEmpty(),"Routed command harness: "+loop.lastError());
            if(memory.tasks().isEmpty() || memory.hasWork() || !loop.status().equals("Idle"))return;done[0]=true;
            var work=m.jobs().get(entry.id);h.assertTrue(work.state()==JobState.SUCCEEDED && loop.planningCalls()>=2 && work.result().toString().contains(entry.id.toString()),"Simulated planning submitted an actual native command and completed from its result");
            loop.close();clear(m,owner,entry);pass("simulated_routed_native_command_task");h.succeed();
        });
    }

    @GameTest(batch="commands",template="empty")
    public static void nativeLogoutMutationIsCapturedBeforeRestoreAndDestroyDropsTheRemainder(GameTestHelper h)throws Exception{
        floor(h);var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner,2.5,2.5);var actor=m.actor(entry.id);var stack=new ItemStack(Items.DIAMOND,5);stack.getOrCreateTag().putString("logout_marker",entry.id.toString());actor.getInventory().setItem(0,stack);
        Vec3 moved=actor.position().add(2,0,2);
        class Logout{@SubscribeEvent public void leaving(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent e){if(e.getEntity()==actor){var taken=actor.getInventory().removeItem(0,1);actor.drop(taken,false);actor.teleportTo(h.getLevel(),moved.x,moved.y,moved.z,0,0);}}}
        var logout=new Logout();MinecraftForge.EVENT_BUS.register(logout);
        try{m.disconnectOwner(owner);}finally{MinecraftForge.EVENT_BUS.unregister(logout);}
        restore(h,m,owner,entry);var fresh=m.actor(entry.id);
        h.assertTrue(fresh.getInventory().countItem(Items.DIAMOND)==4 && fresh.position().distanceTo(moved)<0.001,"Post-logout native inventory and teleport are the state restored by Paracraft");
        m.destroy(owner,entry.id);
        int total=h.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,fresh.getBoundingBox().inflate(8)).stream().filter(item->item.getItem().is(Items.DIAMOND) && item.getItem().hasTag() && entry.id.toString().equals(item.getItem().getTag().getString("logout_marker"))).mapToInt(item->item.getItem().getCount()).sum();
        h.assertTrue(total==5 && h.getLevel().getServer().getPlayerList().getPlayer(entry.id)==null,"Logout drop plus explicit destruction conserve exactly five tagged items and remove native presence");
        var path=h.getLevel().getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).resolve(entry.id+".dat");
        try(var input=java.nio.file.Files.newInputStream(path)){var tag=net.minecraft.nbt.NbtIo.readCompressed(input);h.assertTrue(tag.getList("Inventory",10).isEmpty(),"Native player-data mirror cannot retain a second carried inventory after destruction");}
        pass("native_logout_mutation_and_destroy_conservation");h.succeed();
    }

    private static AvatarManager manager(GameTestHelper h){return ServerEvents.avatars(h.getLevel().getServer());}
    private static void floor(GameTestHelper h){for(int x=0;x<8;x++)for(int z=0;z<8;z++)h.setBlock(new BlockPos(x,1,z),Blocks.STONE);}
    private static void area(ServerLevel level,BlockPos base){for(BlockPos p:BlockPos.betweenClosed(base.offset(-2,-1,-2),base.offset(3,4,3)))level.setBlockAndUpdate(p,p.getY()==base.getY()-1?Blocks.STONE.defaultBlockState():Blocks.AIR.defaultBlockState());}
    private static String name(){return "QC"+UUID.randomUUID().toString().substring(0,8);}
    private static AvatarEntry spawn(GameTestHelper h,AvatarManager m,UUID owner,double x,double z){return m.spawn(owner,h.getLevel(),h.absoluteVec(new Vec3(x,2,z)),name());}
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private static GameJob submit(AvatarManager m,UUID owner,AvatarEntry e,String text,String mode){return m.jobs().submitProgram(owner,e.id,e.authority.session(),UUID.randomUUID(),json(text),mode);}
    private static String request(String name,UUID recipient,int timeout){return "{\"steps\":[{\"op\":\"execute_command\",\"command\":\"qa_tpa "+name+"\",\"await\":\"teleport\",\"playerUuid\":\""+recipient+"\",\"timeoutTicks\":"+timeout+",\"saveAs\":\"command\"}]}";}
    private static void restore(GameTestHelper h,AvatarManager m,UUID owner,AvatarEntry entry){var decoded=AvatarEntry.load(entry.save());entry.physical=decoded.physical;entry.execution=decoded.execution;entry.control=decoded.control;h.assertTrue(m.restoreOwner(owner).isEmpty(),"Saved avatar restored");}
    private static void running(GameTestHelper h,GameJob job){h.assertTrue(job.state()!=JobState.FAILED && job.state()!=JobState.CANCELLED,"Unexpected command work: "+job.view());}
    private static void clear(AvatarManager m,UUID owner,AvatarEntry e){m.actor(e.id).getInventory().clearContent();m.destroy(owner,e.id);}
    private static void pass(String e){Paracraft.LOG.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\"}",e);}
}
