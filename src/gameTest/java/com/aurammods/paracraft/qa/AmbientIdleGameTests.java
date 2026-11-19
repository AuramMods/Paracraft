package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.core.agent.*;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.*;
import com.aurammods.paracraft.forge.execution.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class AmbientIdleGameTests {
    @GameTest(batch="ambient",template="arena",timeoutTicks=1000)
    public static void defaultIdleWalksWithinItsFixedCenterAndGlancesWithoutInference(GameTestHelper h) {
        var m=manager(h);UUID root=UUID.randomUUID();var owner=spawn(h,m,root,45.5,48.5,false);var e=spawn(h,m,owner.id,48.5,48.5,true);var actor=m.actor(e.id);var person=m.actor(owner.id);
        h.assertTrue(m.behavior(owner.id,e.id).getAsJsonObject("behavior").get("ambientIdle").getAsBoolean(),"Production ambient default is enabled");
        var center=actor.position();var clock=new Clock();var loop=loop(m,e,clock);double[] distance={0};Vec3[] previous={actor.position()};long[] calls={-1};int[] ticks={0},glanceTicks={0};float[] head={actor.getYHeadRot()};boolean[] turned={false};
        h.onEachTick(()->{
            ticks[0]++;person.setDeltaMovement(person.getX()<center.x?0.035:-0.035,person.getDeltaMovement().y,0);
            clock.advance();loop.accept(m.control().snapshot(owner.id,e.id));loop.tick();loop.tick();
            if(ticks[0]==5)calls[0]=loop.calls();if(ticks[0]>5)h.assertTrue(loop.calls()==calls[0],"Ambient activity does not trigger decisions");
            distance[0]+=actor.position().distanceTo(previous[0]);previous[0]=actor.position();var state=ambient(m,e);
            h.assertTrue(actor.position().distanceTo(center)<=16.01 && !actor.isSprinting(),"Every idle movement stays in radius and walks");
            h.assertTrue(JobJson.vector(state.get("center")).distanceTo(center)<0.001,"Random walks never drift the saved center");
            if(state.has("state") && state.get("state").getAsString().equals("owner_glance")){glanceTicks[0]++;turned[0]|=Math.abs(net.minecraft.util.Mth.wrapDegrees(actor.getYHeadRot()-head[0]))>0.1;}head[0]=actor.getYHeadRot();
            if(ticks[0]<650)return;
            h.assertTrue(number(state,"walks")>=2 && number(state,"ownerGlances")>=1 && number(state,"randomLooks")>=2 && distance[0]>4,"Actual pacing and intermittent owner/random looks ran: "+state);
            h.assertTrue(turned[0] && glanceTicks[0]>0 && glanceTicks[0]<ticks[0]/2,"Owner glances actually turn the head briefly, not a permanent stare");
            m.pause(owner.id,e.id,true);h.assertTrue(ambient(m,e).get("state").getAsString().equals("paused") && m.statuses().label(e.id).equals("Paused"),"Owner pause stops the routine and reports its actual state");
            loop.close();clear(m,e);clear(m,owner);pass("ambient_default_fixed_radius_looks_walk_no_inference");h.succeed();
        });
    }
    @GameTest(batch="ambient",template="arena",timeoutTicks=900)
    public static void agentToggleStopsMovingPersistsAndCanResume(GameTestHelper h) {
        var m=manager(h);var e=spawn(h,m,UUID.randomUUID(),48.5,48.5,true);var clock=new Clock();var loop=loop(m,e,clock);int[] stage={0},since={0};Vec3[] stopped={null};float[] yaw={0};
        h.onEachTick(()->{
            clock.advance();loop.accept(m.control().snapshot(e.authority.owner(),e.id));loop.tick();loop.tick();var actor=m.actor(e.id);
            if(stage[0]==0 && m.survivalMode(e.id).equals("idle_wandering")){m.control().chat(e.authority.owner(),e.name+", stop moving");stage[0]=1;}
            if(stage[0]==1 && !m.behavior(e.authority.owner(),e.id).getAsJsonObject("behavior").get("ambientIdle").getAsBoolean()){
                h.assertTrue(loop.planningCalls()>0,"Toggle passed through Light escalation and the real server tool");stopped[0]=actor.position();yaw[0]=actor.getYRot();stage[0]=2;since[0]=0;
            }
            if(stage[0]==2 && ++since[0]>=70){
                h.assertTrue(actor.position().distanceTo(stopped[0])<0.05 && Math.abs(actor.getYRot()-yaw[0])<0.01,"Disabling stops pacing and idle looking immediately");
                m.disconnectOwner(e.authority.owner());m.restoreOwner(e.authority.owner());
                h.assertTrue(!m.behavior(e.authority.owner(),e.id).getAsJsonObject("behavior").get("ambientIdle").getAsBoolean(),"Disabled setting survives reconnect");
                m.control().chat(e.authority.owner(),e.name+", resume idling");stage[0]=3;
            }
            if(stage[0]==3 && m.behavior(e.authority.owner(),e.id).getAsJsonObject("behavior").get("ambientIdle").getAsBoolean() && actor.position().distanceTo(stopped[0])>1){
                loop.close();clear(m,e);pass("ambient_agent_toggle_reconnect_resume");h.succeed();
            }
        });
    }
    @GameTest(batch="ambient",template="arena",timeoutTicks=650)
    public static void restocksOnlyNearbySafeFoodAndEatsForNativeRecovery(GameTestHelper h) {
        var m=manager(h);var e=spawn(h,m,UUID.randomUUID(),48.5,48.5,true);var actor=m.actor(e.id);
        actor.getFoodData().setFoodLevel(8);actor.getFoodData().setSaturation(0);actor.setHealth(18);
        BlockPos near=new BlockPos(57,2,48),toxic=new BlockPos(50,2,48),outside=new BlockPos(66,2,48);
        chest(h,near,Items.COOKED_BEEF,8);chest(h,toxic,Items.ROTTEN_FLESH,8);chest(h,outside,Items.COOKED_BEEF,8);
        var clock=new Clock();var loop=loop(m,e,clock);long[] calls={-1};int[] ticks={0};Vec3 center=actor.position();
        h.onEachTick(()->{
            ticks[0]++;clock.advance();loop.accept(m.control().snapshot(e.authority.owner(),e.id));loop.tick();loop.tick();if(ticks[0]==5)calls[0]=loop.calls();
            if(ticks[0]>5)h.assertTrue(loop.calls()==calls[0],"Restocking and eating do not create inference wake events");
            h.assertTrue(!actor.isSprinting() && actor.position().distanceTo(center)<=16.01,"Food approach remains a bounded walk");
            if(actor.getFoodData().getFoodLevel()<18 || actor.getHealth()<=18)return;
            h.assertTrue(count(h,near)==4 && count(h,toxic)==8 && count(h,outside)==8,"Only four safe nearby foods were gathered; toxic and outside-radius food is untouched");
            h.assertTrue(number(ambient(m,e),"foodAcquired")==4,"Actual transferred food is tracked without spawning items");
            loop.close();clear(m,e);pass("ambient_local_food_native_eating_health_no_inference");h.succeed();
        });
    }
    @GameTest(batch="ambient",template="arena",timeoutTicks=900)
    public static void completedWorkAndExplicitTeleportsReanchorButReconnectDoesNotDrift(GameTestHelper h) {
        var m=manager(h);var e=spawn(h,m,UUID.randomUUID(),32.5,48.5,true);var actor=m.actor(e.id);Vec3 initial=actor.position();
        var work=m.jobs().submitProgram(e.authority.owner(),e.id,e.authority.session(),UUID.randomUUID(),json("{\"steps\":[{\"op\":\"travel\",\"destination\":"+JobJson.vector(h.absoluteVec(new Vec3(72.5,2,48.5)))+"}]}"),"replace");
        int[] stage={0};Vec3[] afterWork={null},teleported={null};
        h.onEachTick(()->{
            h.assertTrue(work.state()!=JobState.FAILED,"Explicit work remains unrestricted by the idle boundary");
            if(stage[0]==0 && work.state()==JobState.SUCCEEDED){afterWork[0]=actor.position();stage[0]=1;return;}
            if(stage[0]==1){h.assertTrue(JobJson.vector(ambient(m,e).get("center")).distanceTo(afterWork[0])<0.1 && actor.position().distanceTo(initial)>30,"Completed work establishes its new resting spot");
                teleported[0]=h.absoluteVec(new Vec3(40.5,2,48.5));actor.teleportTo(h.getLevel(),teleported[0].x,teleported[0].y,teleported[0].z,0,0);stage[0]=2;return;}
            if(stage[0]==2){h.assertTrue(JobJson.vector(ambient(m,e).get("center")).distanceTo(teleported[0])<0.01,"Explicit teleport establishes a new center");
                m.disconnectOwner(e.authority.owner());m.restoreOwner(e.authority.owner());h.assertTrue(JobJson.vector(ambient(m,e).get("center")).distanceTo(teleported[0])<0.01,"Reconnect preserves the center rather than following each wander");
                clear(m,e);pass("ambient_work_teleport_anchor_reconnect");h.succeed();}
        });
    }
    @GameTest(batch="ambient",template="arena",timeoutTicks=850)
    public static void proactiveRestockingHonorsProtectionAndTriesAnotherNearbyChest(GameTestHelper h) {
        var m=manager(h);var e=spawn(h,m,UUID.randomUUID(),48.5,48.5,true);var actor=m.actor(e.id);
        BlockPos denied=new BlockPos(52,2,48),allowed=new BlockPos(59,2,48);chest(h,denied,Items.BREAD,8);chest(h,allowed,Items.BREAD,8);int[] refusals={0};
        class Protection {@net.minecraftforge.eventbus.api.SubscribeEvent public void open(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event){
            if(event.getEntity()==actor && event.getPos().equals(h.absolutePos(denied))){refusals[0]++;event.setCanceled(true);}
        }}
        var protection=new Protection();net.minecraftforge.common.MinecraftForge.EVENT_BUS.register(protection);
        h.onEachTick(()->{
            if(actor.getInventory().countItem(Items.BREAD)<4)return;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.unregister(protection);
            h.assertTrue(refusals[0]>=1 && refusals[0]<=2 && count(h,denied)==8 && count(h,allowed)==4,"Native protection was honored, retries backed off, and another reachable food source supplied exactly four items");
            h.assertTrue(actor.getFoodData().getFoodLevel()==20 && !actor.isSprinting(),"An empty inventory restocks before hunger drops, without unnecessary eating or sprinting");
            clear(m,e);pass("ambient_proactive_restock_native_protection_fallback");h.succeed();
        });
    }
    private static long oldTime;private static boolean oldDaylight;
    @BeforeBatch(batch="ambient_night") public static void night(net.minecraft.server.level.ServerLevel level){oldTime=level.getDayTime();oldDaylight=level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,level.getServer());level.setDayTime(18000);}
    @AfterBatch(batch="ambient_night") public static void restoreDay(net.minecraft.server.level.ServerLevel level){level.setDayTime(oldTime);level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(oldDaylight,level.getServer());}
    @GameTest(batch="ambient_night",template="arena",timeoutTicks=1200)
    public static void sleepsInANearbyBedAndWakesForDayAndNewWork(GameTestHelper h) {
        var m=manager(h);var owner=spawn(h,m,UUID.randomUUID(),30.5,48.5,false);var e=spawn(h,m,owner.id,48.5,48.5,true);var actor=m.actor(e.id);
        actor.getInventory().setItem(13, new ItemStack(Items.TORCH, 8));
        BlockPos near=new BlockPos(58,2,48),outside=new BlockPos(67,2,48);bed(h,near);bed(h,outside);int[] stage={0};long[] dawn={0};GameJob[] work={null};
        h.onEachTick(()->{
            h.assertTrue(!actor.isSprinting(),"Nighttime bed approach walks");
            if(stage[0]==0 && actor.isSleeping()){
                h.assertTrue(actor.getSleepingPos().orElseThrow().equals(h.absolutePos(near)) && m.statuses().label(e.id).equals("Sleeping"),"Actual sleep uses the nearby bed and public server status");
                h.assertTrue(actor.getOffhandItem().isEmpty() && actor.getInventory().getItem(13).getCount() == 8,
                        "A nearby bed takes priority over holding a light");
                h.getLevel().setDayTime(6000);dawn[0]=h.getLevel().getGameTime();stage[0]=1;return;
            }
            if(stage[0]==1 && h.getLevel().getGameTime()-dawn[0]>5){h.assertTrue(!actor.isSleeping(),"Dawn releases native sleep");h.getLevel().setDayTime(18000);stage[0]=2;return;}
            if(stage[0]==2 && actor.isSleeping()){
                work[0]=m.jobs().submitProgram(owner.id,e.id,e.authority.session(),UUID.randomUUID(),json("{\"steps\":[{\"op\":\"wait_ticks\",\"ticks\":20}]}"),"replace");
                h.assertTrue(!actor.isSleeping() && !h.getLevel().getBlockState(h.absolutePos(near)).getValue(BedBlock.OCCUPIED),"New work immediately wakes and releases the bed");stage[0]=3;
            }
            if(stage[0]==3 && work[0].state()==JobState.SUCCEEDED){h.assertTrue(!h.getLevel().getBlockState(h.absolutePos(outside)).getValue(BedBlock.OCCUPIED),"Outside-radius bed was not used");clear(m,e);clear(m,owner);pass("ambient_night_native_sleep_dawn_work_wake");h.succeed();}
        });
    }
    @BeforeBatch(batch = "ambient_lights")
    public static void startLightTestAtNight(net.minecraft.server.level.ServerLevel level) { night(level); }
    @AfterBatch(batch = "ambient_lights")
    public static void restoreTimeAfterLightTest(net.minecraft.server.level.ServerLevel level) { restoreDay(level); }

    @GameTest(batch = "ambient_lights", template = "arena", timeoutTicks = 250)
    public static void idleNightUsesACarriedLightAndRestoresTheOffhandAtWorkOrDawn(GameTestHelper h) {
        var manager = manager(h);
        UUID owner = UUID.randomUUID();
        var entry = spawn(h, manager, owner, 48.5, 48.5, true);
        var actor = manager.actor(entry.id);
        var light = new ItemStack(Items.LANTERN, 6);
        var original = new ItemStack(Items.SHIELD);
        original.getOrCreateTag().putString("qa_marker", "mine");
        actor.setItemSlot(EquipmentSlot.OFFHAND, original);
        actor.getInventory().setItem(14, light);
        GameJob[] work = {null};
        boolean[] dawn = {false};
        h.onEachTick(() -> {
            if (work[0] == null) {
                if (!actor.getOffhandItem().is(Items.LANTERN)) return;
                h.assertTrue(ambient(manager, entry).get("offhandLight").getAsString().equals("minecraft:lantern")
                        && actor.getOffhandItem().getCount() == 6 && ItemStack.isSameItemSameTags(actor.getInventory().getItem(14), original),
                        "The idle avatar holds the original carried lantern stack and preserves its former offhand item");
                work[0] = manager.jobs().submitProgram(owner, entry.id, entry.authority.session(), UUID.randomUUID(),
                        json("{\"steps\":[{\"op\":\"wait_ticks\",\"ticks\":25}]}"), "replace");
            }
            if (work[0].state() == JobState.RUNNING) {
                h.assertTrue(ItemStack.isSameItemSameTags(actor.getOffhandItem(), original)
                        && actor.getInventory().getItem(14).getCount() == 6, "Starting work restores the prior offhand");
                return;
            }
            if (!dawn[0]) {
                if (!actor.getOffhandItem().is(Items.LANTERN)) return;
                dawn[0] = true;
                h.getLevel().setDayTime(6000);
                return;
            }
            if (!h.getLevel().isDay() || actor.getOffhandItem().is(Items.LANTERN)) return;
            h.assertTrue(ItemStack.isSameItemSameTags(actor.getOffhandItem(), original) && actor.getInventory().getItem(14).getCount() == 6
                    && !ambient(manager, entry).has("offhandLight"), "Dawn stows the light without dropping or consuming it");
            clear(manager, entry);
            h.succeed();
        });
    }
    @BeforeBatch(batch="ambient_blocked") public static void terrainOn(net.minecraft.server.level.ServerLevel level){Paracraft.NAVIGATION_TERRAIN_CHANGES.set(true);}
    @AfterBatch(batch="ambient_blocked") public static void terrainOff(net.minecraft.server.level.ServerLevel level){Paracraft.NAVIGATION_TERRAIN_CHANGES.set(false);}
    @GameTest(batch="ambient_blocked",template="arena",timeoutTicks=350)
    public static void blockedIdleFoodNeverBreaksBuildsEvenWithTerrainRecoveryEnabled(GameTestHelper h) {
        var m=manager(h);var e=spawn(h,m,UUID.randomUUID(),48.5,48.5,true);var actor=m.actor(e.id);actor.getFoodData().setFoodLevel(10);
        actor.getInventory().setItem(0,new ItemStack(Items.IRON_AXE));actor.getInventory().setItem(1,new ItemStack(Items.COBBLESTONE,8));
        for(int x=47;x<=49;x++)for(int z=47;z<=49;z++)for(int y=2;y<=4;y++)if(x!=48 || z!=48 || y==4)h.setBlock(new BlockPos(x,y,z),Blocks.OAK_PLANKS);
        BlockPos chest=new BlockPos(54,2,48);chest(h,chest,Items.COOKED_BEEF,8);Vec3 initial=actor.position();
        h.runAfterDelay(280,()->{
            h.assertTrue(count(h,chest)==8 && actor.position().distanceTo(initial)<0.2 && actor.getInventory().getItem(0).getDamageValue()==0 && actor.getInventory().countItem(Items.COBBLESTONE)==8,"Blocked idle route consumed no blocks, tools or food and did not escape");
            for(int y=2;y<=3;y++)h.assertTrue(h.getLevel().getBlockState(h.absolutePos(new BlockPos(49,y,48))).is(Blocks.OAK_PLANKS),"The built wall remains intact despite terrain recovery opt-in");
            h.assertTrue(ambient(m,e).has("lastError"),"Actual navigation failure is available in the snapshot without invoking AI");clear(m,e);pass("ambient_blocked_preserves_build_with_terrain_enabled");h.succeed();
        });
    }
    private static AvatarManager manager(GameTestHelper h){return ServerEvents.avatars(h.getLevel().getServer());}
    private static AvatarEntry spawn(GameTestHelper h,AvatarManager m,UUID owner,double x,double z,boolean ambient){
        var e=m.spawn(owner,h.getLevel(),h.absoluteVec(new Vec3(x,2,z)),"QI"+UUID.randomUUID().toString().substring(0,8));
        if(ambient)m.actor(e.id).getTags().add("qa_ambient");
        // Isolate ambient bedtime from the separately covered one-time spawn-point initializer.
        e.control.getAsJsonObject("survival").getAsJsonObject("initialBed").addProperty("state","already_set");return e;
    }
    private static JsonObject ambient(AvatarManager m,AvatarEntry e){return m.behavior(e.authority.owner(),e.id).getAsJsonObject("ambient");}
    private static long number(JsonObject value,String key){return value.has(key)?value.get(key).getAsLong():0;}
    private static void clear(AvatarManager m,AvatarEntry e){m.actor(e.id).getInventory().clearContent();m.destroy(e.authority.owner(),e.id);}
    private static JsonObject json(String text){return JsonParser.parseString(text).getAsJsonObject();}
    private static void chest(GameTestHelper h,BlockPos pos,Item item,int count){h.setBlock(pos,Blocks.CHEST);((ChestBlockEntity)h.getLevel().getBlockEntity(h.absolutePos(pos))).setItem(0,new ItemStack(item,count));}
    private static int count(GameTestHelper h,BlockPos pos){return ((ChestBlockEntity)h.getLevel().getBlockEntity(h.absolutePos(pos))).getItem(0).getCount();}
    private static void bed(GameTestHelper h,BlockPos head){var state=Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING,Direction.EAST);h.setBlock(head.relative(Direction.WEST),state.setValue(BedBlock.PART,BedPart.FOOT));h.setBlock(head,state.setValue(BedBlock.PART,BedPart.HEAD));}
    private static HarnessLoop loop(AvatarManager m,AvatarEntry e,Clock clock){return new HarnessLoop(new AgentBinding(m.worldId(),e.authority.owner(),e.id),new AgentState(),new SimulatedProvider(),clock,(id,tool,args,trigger)->m.control().request(e.authority.owner(),e.id,e.authority.session(),id,tool,args,trigger),()->{});}
    private static void pass(String event){Paracraft.LOG.info("QA_EVT {\"event\":\"{}\",\"status\":\"pass\"}",event);}
    private static final class Clock implements AgentClock {long ticks;public long nanoTime(){return ticks*1_000_000_000L;}public long epochMillis(){return 1_800_000_000_000L+ticks*1000;}void advance(){ticks++;}}
}
