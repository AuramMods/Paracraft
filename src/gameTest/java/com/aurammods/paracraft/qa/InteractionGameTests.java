package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.*;
import com.aurammods.paracraft.forge.execution.*;
import com.google.gson.*;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.gametest.*;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class InteractionGameTests {
    @GameTest(batch="interactions",template="arena",timeoutTicks=300)
    public static void blockClicksWalkRestoreOnceAndObserveNativeMenus(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner);BlockPos lever=pos(h,25,2,5),chest=pos(h,29,2,5);lever(h,lever);h.getLevel().setBlockAndUpdate(chest,Blocks.CHEST.defaultBlockState());
        var program=json("{\"steps\":[{\"op\":\"interact_block\",\"position\":"+JobJson.position(lever)+",\"saveAs\":\"lever\"},{\"op\":\"interact_block\",\"position\":"+JobJson.position(chest)+",\"saveAs\":\"chest\"}]}");GameJob[] work={submit(m,owner,entry,program)};boolean[] restored={false};Vec3[] previous={m.actor(entry.id).position()};double[] distance={0};int[] clicks={0};
        class Count{@SubscribeEvent public void click(PlayerInteractEvent.RightClickBlock e){if(e.getEntity().getUUID().equals(entry.id) && e.getPos().equals(lever))clicks[0]++;}}var count=new Count();MinecraftForge.EVENT_BUS.register(count);
        h.onEachTick(()->{
            var actor=m.actor(entry.id);distance[0]+=actor.position().distanceTo(previous[0]);previous[0]=actor.position();check(h,work[0]);
            if(!restored[0] && work[0].view().toString().contains("approaching block interaction")){UUID id=work[0].id;m.disconnectOwner(owner);h.assertTrue(m.restoreOwner(owner).isEmpty(),"Body restored while approaching right click");work[0]=m.jobs().request(entry.id,id);m.jobs().resumeRequest(owner,entry.id,entry.authority.session(),id);restored[0]=true;return;}
            if(work[0].state()!=JobState.SUCCEEDED)return;MinecraftForge.EVENT_BUS.unregister(count);var results=work[0].result().getAsJsonObject("results");
            h.assertTrue(restored[0] && distance[0]>20 && clicks[0]==1 && h.getLevel().getBlockState(lever).getValue(LeverBlock.POWERED),"Real body walks and restores before one native lever activation");h.assertTrue(results.getAsJsonObject("chest").get("menuOpened").getAsBoolean() && results.getAsJsonObject("chest").get("menuSlots").getAsInt()>36 && actor.containerMenu==actor.inventoryMenu,"Native chest menu is observed and then closed");
            h.assertTrue(m.jobs().submitProgram(owner,entry.id,entry.authority.session(),work[0].id,program,"replace")==work[0] && clicks[0]==1,"Retry acknowledges a recorded right click rather than toggling twice");clear(m,owner,entry);pass("native_block_click_navigation_checkpoint_menu_dedup");h.succeed();
        });
    }
    @GameTest(batch="interactions",template="arena",timeoutTicks=80)
    public static void offhandSneakClickUsesNativeItemAndRestoresStance(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner);var actor=m.actor(entry.id);BlockPos crop=pos(h,5,2,5);h.getLevel().setBlockAndUpdate(crop.below(),Blocks.FARMLAND.defaultBlockState());h.getLevel().setBlockAndUpdate(crop,Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE,6));
        actor.getInventory().setItem(13, new ItemStack(Items.BONE_MEAL, 2));
        actor.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.SHIELD));
        var stick=new ItemStack(Items.STICK);stick.getOrCreateTag().putString("interaction_marker","keep");actor.setItemInHand(InteractionHand.MAIN_HAND,stick);
        var work=submit(m,owner,entry,json("{\"steps\":[{\"op\":\"interact_block\",\"position\":"+JobJson.position(crop)+",\"item\":\"minecraft:bone_meal\",\"hand\":\"offhand\",\"sneak\":true,\"hit\":[0.5,0.5,0.5],\"saveAs\":\"grown\"}]}"));
        h.onEachTick(()->{check(h,work);if(work.state()!=JobState.SUCCEEDED)return;h.assertTrue(h.getLevel().getBlockState(crop).getValue(CropBlock.AGE)==7 && actor.getOffhandItem().getCount()==1 && actor.getOffhandItem().is(Items.BONE_MEAL) && actor.getInventory().getItem(13).is(Items.SHIELD),"The item selector moves backpack bone meal to the offhand and native use consumes one");h.assertTrue(!actor.isShiftKeyDown() && ItemStack.isSameItemSameTags(stick,actor.getMainHandItem()),"Sneak modifier is restored and the other hand's metadata is conserved");clear(m,owner,entry);pass("native_offhand_sneak_block_item_use");h.succeed();});
    }
    @GameTest(batch="interactions",template="arena",timeoutTicks=180)
    public static void fluidBlockClickFallsThroughToNativeBucketUse(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner);var actor=m.actor(entry.id);BlockPos water=pos(h,5,1,5);h.getLevel().setBlockAndUpdate(water.below(),Blocks.STONE.defaultBlockState());h.getLevel().setBlockAndUpdate(water,Blocks.WATER.defaultBlockState());actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.BUCKET));
        var work=submit(m,owner,entry,json("{\"steps\":[{\"op\":\"interact_block\",\"position\":"+JobJson.position(water)+",\"saveAs\":\"filled\"}]}"));h.onEachTick(()->{check(h,work);if(work.state()!=JobState.SUCCEEDED)return;h.assertTrue(actor.getMainHandItem().is(Items.WATER_BUCKET) && h.getLevel().getBlockState(water).isAir() && work.result().getAsJsonObject("results").getAsJsonObject("filled").get("blockOutcome").getAsString().equals("PASS"),"Actual source-fluid hit, block PASS and native bucket item use collect one world source");clear(m,owner,entry);pass("native_block_pass_bucket_fluid_use");h.succeed();});
    }
    @GameTest(batch="interactions",template="arena",timeoutTicks=180)
    public static void entityClickWalksAndMilksThroughSpecificAndOrdinaryHooks(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner);var actor=m.actor(entry.id);actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.BUCKET));var cow=h.spawn(EntityType.COW,new BlockPos(12,2,5));cow.setNoAi(true);int[] specific={0},ordinary={0};Vec3 start=actor.position();
        class Count{@SubscribeEvent public void specific(PlayerInteractEvent.EntityInteractSpecific e){if(e.getEntity()==actor)specific[0]++;}@SubscribeEvent public void ordinary(PlayerInteractEvent.EntityInteract e){if(e.getEntity()==actor)ordinary[0]++;}}var count=new Count();MinecraftForge.EVENT_BUS.register(count);
        var work=submit(m,owner,entry,json("{\"steps\":[{\"op\":\"interact_entity\",\"target\":\""+cow.getUUID()+"\",\"saveAs\":\"milked\"}]}"));h.onEachTick(()->{check(h,work);if(work.state()!=JobState.SUCCEEDED)return;MinecraftForge.EVENT_BUS.unregister(count);h.assertTrue(actor.position().distanceTo(start)>5 && actor.getMainHandItem().is(Items.MILK_BUCKET) && specific[0]==1 && ordinary[0]==1,"Native approach, interact-at fallback, Forge events and survival bucket remainder ran once");cow.discard();clear(m,owner,entry);pass("native_entity_milking_navigation_hooks");h.succeed();});
    }
    @GameTest(batch="interactions",template="arena",timeoutTicks=90)
    public static void entityHitPointEquipsArmorStandThroughInteractAt(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner);var actor=m.actor(entry.id);actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.DIAMOND_HELMET));var stand=h.spawn(EntityType.ARMOR_STAND,new BlockPos(5,2,5));stand.setNoGravity(true);
        var work=submit(m,owner,entry,json("{\"steps\":[{\"op\":\"interact_entity\",\"target\":\""+stand.getUUID()+"\",\"hit\":[0,1.7,0],\"saveAs\":\"equipped\"}]}"));h.onEachTick(()->{check(h,work);if(work.state()!=JobState.SUCCEEDED)return;h.assertTrue(stand.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET) && actor.getMainHandItem().isEmpty(),"Native precise head interaction transfers one actual helmet to the armor stand");stand.discard();clear(m,owner,entry);pass("native_entity_precise_armor_stand_interact_at");h.succeed();});
    }
    @GameTest(batch="interactions",template="arena",timeoutTicks=50)
    public static void nativeHookCancellationCannotFallThroughOrConsumeHeldItems(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner);var actor=m.actor(entry.id);BlockPos lever=pos(h,5,2,5);lever(h,lever);var cow=h.spawn(EntityType.COW,new BlockPos(5,2,6));cow.setNoAi(true);
        class Deny{boolean specific=true;int blocks,specificCalls,ordinary,air;@SubscribeEvent public void block(PlayerInteractEvent.RightClickBlock e){if(e.getEntity()==actor){blocks++;e.setCanceled(true);e.setCancellationResult(InteractionResult.PASS);}}@SubscribeEvent public void at(PlayerInteractEvent.EntityInteractSpecific e){if(e.getEntity()==actor){specificCalls++;if(specific){e.setCanceled(true);e.setCancellationResult(InteractionResult.PASS);}}}@SubscribeEvent public void entity(PlayerInteractEvent.EntityInteract e){if(e.getEntity()==actor){ordinary++;e.setCanceled(true);e.setCancellationResult(InteractionResult.FAIL);}}@SubscribeEvent public void item(PlayerInteractEvent.RightClickItem e){if(e.getEntity()==actor){air++;e.setCanceled(true);e.setCancellationResult(InteractionResult.FAIL);}}}var deny=new Deny();MinecraftForge.EVENT_BUS.register(deny);
        try{
            actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.SNOWBALL,3));var block=direct(actor,"{\"op\":\"interact_block\",\"position\":"+JobJson.position(lever)+"}");block.tick();h.assertTrue(block.state()==JobState.FAILED && deny.blocks==1 && deny.air==0 && actor.getMainHandItem().getCount()==3 && !h.getLevel().getBlockState(lever).getValue(LeverBlock.POWERED),"Cancelled PASS block event does not fall through to item use");
            actor.moveTo(actor.getX(),actor.getY(),actor.getZ()+1,0,0);actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.BUCKET));String entity="{\"op\":\"interact_entity\",\"target\":\""+cow.getUUID()+"\"}";var at=direct(actor,entity);at.tick();h.assertTrue(at.state()==JobState.FAILED && deny.specificCalls==1 && deny.ordinary==0 && actor.getMainHandItem().is(Items.BUCKET),"Cancelled specific PASS never bypasses the hook via ordinary entity fallback: "+at.view());
            deny.specific=false;var ordinary=direct(actor,entity);ordinary.tick();h.assertTrue(ordinary.state()==JobState.FAILED && deny.ordinary==1 && actor.getMainHandItem().is(Items.BUCKET),"Native ordinary entity cancellation prevents milking");
            actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.SNOWBALL,3));var item=direct(actor,"{\"op\":\"use_item\"}");item.tick();h.assertTrue(item.state()==JobState.FAILED && deny.air==1 && actor.getMainHandItem().getCount()==3,"Native air-use hook prevents projectile item consumption");
        }finally{MinecraftForge.EVENT_BUS.unregister(deny);}cow.discard();clear(m,owner,entry);pass("native_interaction_hook_cancellation_no_fallback");h.succeed();
    }
    @GameTest(batch="interactions",template="arena",timeoutTicks=140)
    public static void heldDrinkCompletesNativelyAndAimedItemUseConsumesOneProjectile(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner);var actor=m.actor(entry.id);actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.MILK_BUCKET));actor.getInventory().setItem(10,new ItemStack(Items.SNOWBALL,3));Vec3 aim=actor.position().add(0,20,20);
        var work=submit(m,owner,entry,json("{\"steps\":[{\"op\":\"use_item\",\"holdTicks\":40,\"saveAs\":\"drank\"},{\"op\":\"equip\",\"item\":\"minecraft:snowball\",\"slot\":\"mainhand\"},{\"op\":\"use_item\",\"lookAt\":"+JobJson.vector(aim)+",\"saveAs\":\"thrown\"}]}"));
        h.onEachTick(()->{check(h,work);if(work.state()!=JobState.SUCCEEDED)return;var result=work.result().getAsJsonObject("results");h.assertTrue(actor.getInventory().countItem(Items.BUCKET)==1 && actor.getInventory().countItem(Items.MILK_BUCKET)==0 && actor.getInventory().countItem(Items.SNOWBALL)==2 && !actor.isUsingItem(),"Native drink completes, bucket remainder survives and one snowball is consumed");h.assertTrue(result.getAsJsonObject("drank").get("heldTicks").getAsInt()>=30 && h.getLevel().getEntitiesOfClass(Snowball.class,actor.getBoundingBox().inflate(15)).stream().anyMatch(s->s.getOwner()==actor),"Actual native item-use duration and owned world projectile are observed");clear(m,owner,entry);pass("native_held_drink_and_aimed_air_use");h.succeed();});
    }
    @GameTest(batch="interactions",template="arena",timeoutTicks=120)
    public static void heldBowReleasesNativelyAtRequestedDuration(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner);var actor=m.actor(entry.id);actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.BOW));actor.getInventory().setItem(10,new ItemStack(Items.ARROW,2));
        var work=submit(m,owner,entry,json("{\"steps\":[{\"op\":\"use_item\",\"lookAt\":"+JobJson.vector(actor.position().add(0,20,20))+",\"holdTicks\":20,\"saveAs\":\"shot\"}]}"));
        h.onEachTick(()->{check(h,work);if(work.state()!=JobState.SUCCEEDED)return;var shot=work.result().getAsJsonObject("results").getAsJsonObject("shot");h.assertTrue(shot.get("heldTicks").getAsInt()==20 && shot.get("released").getAsBoolean() && !actor.isUsingItem() && actor.getInventory().countItem(Items.ARROW)==1 && actor.getMainHandItem().getDamageValue()==1,"Requested hold time releases native bow use, consuming one arrow and normal durability");h.assertTrue(h.getLevel().getEntitiesOfClass(AbstractArrow.class,actor.getBoundingBox().inflate(15)).stream().anyMatch(a->a.getOwner()==actor),"Native release creates an owned world projectile");clear(m,owner,entry);pass("native_held_bow_duration_release");h.succeed();});
    }
    @GameTest(batch="interactions",template="arena",timeoutTicks=120)
    public static void pausedHeldBowAbortsWithoutReleaseAndRestoresWithoutReclicking(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner);m.actor(entry.id).setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.BOW));m.actor(entry.id).getInventory().setItem(10,new ItemStack(Items.ARROW,2));GameJob[] work={submit(m,owner,entry,json("{\"steps\":[{\"try\":[{\"op\":\"use_item\",\"holdTicks\":40,\"saveAs\":\"gesture\"}],\"catch\":[{\"op\":\"report_failure\",\"saveAs\":\"interrupted\"}]}]}"))};boolean[] restored={false};
        h.onEachTick(()->{
            check(h,work[0]);if(!restored[0] && work[0].view().toString().contains("holding_item_use")){
                UUID id=work[0].id;m.jobs().pause(entry.id);h.assertTrue(!m.actor(entry.id).isUsingItem() && m.actor(entry.id).getInventory().countItem(Items.ARROW)==2,"Pause aborts drawing without firing or consuming an arrow");m.disconnectOwner(owner);h.assertTrue(m.restoreOwner(owner).isEmpty(),"Paused bow body restored");work[0]=m.jobs().request(entry.id,id);m.jobs().resumeRequest(owner,entry.id,entry.authority.session(),id);restored[0]=true;return;
            }
            if(work[0].state()!=JobState.SUCCEEDED)return;var actor=m.actor(entry.id);h.assertTrue(restored[0] && work[0].result().getAsJsonObject("results").getAsJsonObject("interrupted").toString().contains("click was not repeated") && actor.getInventory().countItem(Items.ARROW)==2 && !actor.isUsingItem(),"Restoration reports interrupted partial use to catch without repeating its click");h.assertTrue(h.getLevel().getEntitiesOfClass(AbstractArrow.class,actor.getBoundingBox().inflate(20)).stream().noneMatch(a->a.getOwner()==actor),"No delayed projectile is fired after pause or restoration");clear(m,owner,entry);pass("native_held_use_pause_restore_no_release_replay");h.succeed();
        });
    }
    @GameTest(batch="interactions",template="arena",timeoutTicks=50)
    public static void obstructedTargetsAndInvalidInteractionArgumentsCannotMutateWorld(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner);var actor=m.actor(entry.id);BlockPos lever=pos(h,5,2,5);lever(h,lever);for(int y=2;y<6;y++)for(int z=3;z<8;z++)h.getLevel().setBlockAndUpdate(pos(h,4,y,z),Blocks.STONE.defaultBlockState());
        var block=direct(actor,"{\"op\":\"interact_block\",\"position\":"+JobJson.position(lever)+"}");block.tick();h.assertTrue(block.state()==JobState.FAILED && !h.getLevel().getBlockState(lever).getValue(LeverBlock.POWERED),"Block behind a solid wall cannot be right clicked");var cow=h.spawn(EntityType.COW,new BlockPos(5,2,5));cow.setNoAi(true);actor.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.BUCKET));var entity=direct(actor,"{\"op\":\"interact_entity\",\"target\":\""+cow.getUUID()+"\"}");entity.tick();h.assertTrue(entity.state()==JobState.FAILED && actor.getMainHandItem().is(Items.BUCKET),"Entity behind a solid wall cannot be milked");
        for(String invalid:new String[]{"{\"op\":\"use_item\",\"hand\":\"head\"}","{\"op\":\"use_item\",\"holdTicks\":-1}","{\"op\":\"use_item\",\"lookAt\":[0,1]}","{\"op\":\"interact_block\",\"position\":[0,2,0],\"hit\":[2,0,0]}","{\"op\":\"interact_entity\",\"target\":\"bad\"}","{\"op\":\"use_item\",\"position\":[0,0,0]}"}){boolean rejected=false;try{OperationSyntax.validate(json(invalid));}catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,"Invalid raw interaction syntax is rejected: "+invalid);}
        cow.discard();clear(m,owner,entry);pass("native_interaction_obstruction_and_syntax");h.succeed();
    }
    private static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    private static AvatarManager manager(GameTestHelper h){return ServerEvents.avatars(h.getLevel().getServer());}
    private static BlockPos pos(GameTestHelper h,int x,int y,int z){return h.absolutePos(new BlockPos(x,y,z));}
    private static void lever(GameTestHelper h,BlockPos position){h.getLevel().setBlockAndUpdate(position,Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE,AttachFace.FLOOR));}
    private static AvatarEntry spawn(GameTestHelper h,AvatarManager m,UUID owner){return m.spawn(owner,h.getLevel(),Vec3.atBottomCenterOf(pos(h,3,2,5)),"QX"+UUID.randomUUID().toString().substring(0,8));}
    private static GameJob submit(AvatarManager m,UUID owner,AvatarEntry entry,JsonObject program){return m.jobs().submitProgram(owner,entry.id,entry.authority.session(),UUID.randomUUID(),program,"replace");}
    private static InteractionJob direct(CompanionPlayer actor,String value){return new InteractionJob(UUID.randomUUID(),actor,json(value));}
    private static void clear(AvatarManager m,UUID owner,AvatarEntry entry){m.actor(entry.id).getInventory().clearContent();m.destroy(owner,entry.id);}
    private static void check(GameTestHelper h,GameJob work){h.assertTrue(work.state()!=JobState.FAILED && work.state()!=JobState.CANCELLED,"Interaction failed: "+work.view());}
    private static void pass(String event){Paracraft.LOG.info("QA_EVT {}","{\"event\":\""+event+"\",\"status\":\"pass\"}");}
}
