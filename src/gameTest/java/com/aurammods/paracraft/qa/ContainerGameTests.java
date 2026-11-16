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
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class ContainerGameTests {
    @GameTest(batch="containers",template="arena",timeoutTicks=350)
    public static void openTakeAndDepositPreserveActualCountsAndStackMetadata(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();BlockPos source=h.absolutePos(new BlockPos(5,2,5)),destination=h.absolutePos(new BlockPos(35,2,5));chest(h,source).setItem(0,tagged(12));chest(h,destination);
        var entry=spawn(h,m,owner);var actor=m.actor(entry.id);actor.getInventory().setItem(0,new ItemStack(Items.DIAMOND,3));
        var program=JsonParser.parseString("{\"steps\":[{\"op\":\"open_inventory\",\"position\":"+JobJson.position(source)+",\"saveAs\":\"opened\"},{\"op\":\"take_items\",\"position\":"+JobJson.position(source)+",\"item\":\"minecraft:diamond\",\"quantity\":7,\"saveAs\":\"taken\"},{\"op\":\"deposit_items\",\"destination\":"+JobJson.position(destination)+",\"item\":\"minecraft:diamond\",\"quantity\":7,\"saveAs\":\"deposited\"}]}").getAsJsonObject();
        var work=m.jobs().submitProgram(owner,entry.id,entry.authority.session(),UUID.randomUUID(),program,"replace");double[] walked={0};Vec3[] last={actor.position()};
        h.onEachTick(()->{walked[0]+=actor.position().distanceTo(last[0]);last[0]=actor.position();check(h,work);if(work.state()!=JobState.SUCCEEDED)return;
            var results=work.result().getAsJsonObject("results");h.assertTrue(results.getAsJsonObject("opened").get("complete").getAsBoolean() && results.getAsJsonObject("opened").getAsJsonArray("views").toString().contains("qa_container"),"Native opening returned actual metadata-bearing slots");
            h.assertTrue(((ChestBlockEntity)h.getLevel().getBlockEntity(source)).getItem(0).getCount()==5 && actor.getInventory().countItem(Items.DIAMOND)==3 && walked[0]>25,"Real body walked and source/avatar counts conserve items");
            var dest=(ChestBlockEntity)h.getLevel().getBlockEntity(destination);int tagged=0,plain=0;for(int slot=0;slot<dest.getContainerSize();slot++){var stack=dest.getItem(slot);if(stack.is(Items.DIAMOND)){if(stack.hasTag())tagged+=stack.getCount();else plain+=stack.getCount();}}
            h.assertTrue(tagged+plain==7 && tagged==4 && plain==3,"Selector deposit uses actual carried slot order and preserves each stack's metadata");
            h.assertTrue(m.jobs().submitProgram(owner,entry.id,entry.authority.session(),work.id,program,"replace")==work && ((ChestBlockEntity)h.getLevel().getBlockEntity(source)).getItem(0).getCount()==5,"Retry acknowledges the existing fixed-source transfer");clear(m,owner,entry);pass("fixed_container_open_take_deposit_metadata_dedup");h.succeed();});
    }
    @GameTest(batch="containers",template="arena",timeoutTicks=250)
    public static void takeCheckpointDoesNotRepeatEarlierBatches(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();BlockPos source=h.absolutePos(new BlockPos(5,2,5));var chest=chest(h,source);chest.setItem(0,tagged(64));chest.setItem(1,tagged(64));chest.setItem(2,tagged(2));var entry=spawn(h,m,owner);
        GameJob[] work={submit(m,owner,entry,"{\"steps\":[{\"op\":\"take_items\",\"position\":"+JobJson.position(source)+",\"item\":\"minecraft:diamond\",\"quantity\":130,\"saveAs\":\"taken\"}]}")};boolean[] restored={false};
        h.onEachTick(()->{check(h,work[0]);if(!restored[0] && m.actor(entry.id).getInventory().countItem(Items.DIAMOND)==64){UUID id=work[0].id;m.disconnectOwner(owner);h.assertTrue(m.restoreOwner(owner).isEmpty(),"Body restored");work[0]=m.jobs().request(entry.id,id);m.jobs().resumeRequest(owner,entry.id,entry.authority.session(),id);restored[0]=true;}
            if(work[0].state()!=JobState.SUCCEEDED)return;h.assertTrue(restored[0] && chest.isEmpty() && m.actor(entry.id).getInventory().countItem(Items.DIAMOND)==130 && work[0].result().getAsJsonObject("results").getAsJsonObject("taken").get("acquired").getAsLong()==130,"Restored fixed-source count and ledger agree with actual inventory");clear(m,owner,entry);pass("fixed_container_take_checkpoint_no_repeat");h.succeed();});
    }
    @GameTest(batch="containers",template="arena",timeoutTicks=300)
    public static void nonContainerSidedCapabilitySupportsQueriesTransfersAndPersistence(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();BlockPos source=h.absolutePos(new BlockPos(5,2,5)),destination=h.absolutePos(new BlockPos(25,2,5));h.getLevel().setBlockAndUpdate(source,Blocks.SKELETON_SKULL.defaultBlockState());h.getLevel().setBlockAndUpdate(destination,Blocks.SKELETON_SKULL.defaultBlockState());
        var machine=CapabilityFixture.machine(h.getLevel().getBlockEntity(source));h.assertTrue(machine!=null && !(h.getLevel().getBlockEntity(source) instanceof net.minecraft.world.Container),"Fixture is an actual non-Container block entity with attached Forge item capability");machine.storage.setStackInSlot(0,tagged(4));machine.storage.setStackInSlot(1,tagged(6));var nativeData=h.getLevel().getBlockEntity(source).saveWithFullMetadata();
        h.getLevel().setBlockAndUpdate(source,Blocks.AIR.defaultBlockState());h.getLevel().setBlockAndUpdate(source,Blocks.SKELETON_SKULL.defaultBlockState());h.getLevel().getBlockEntity(source).load(nativeData);var restored=CapabilityFixture.machine(h.getLevel().getBlockEntity(source));h.assertTrue(restored.storage.getStackInSlot(1).getCount()==6,"Attached item capability restored through native block NBT");
        var entry=spawn(h,m,owner);var work=submit(m,owner,entry,"{\"steps\":[{\"op\":\"open_inventory\",\"position\":"+JobJson.position(source)+",\"saveAs\":\"machine\"},{\"op\":\"search_containers\",\"item\":\"minecraft:diamond\",\"saveAs\":\"found\"},{\"op\":\"take_items\",\"position\":"+JobJson.position(source)+",\"item\":\"minecraft:diamond\",\"quantity\":6,\"saveAs\":\"taken\"},{\"op\":\"deposit_items\",\"destination\":"+JobJson.position(destination)+",\"item\":\"minecraft:diamond\",\"quantity\":6}]}");
        long started=h.getLevel().getGameTime();
        h.onEachTick(()->{check(h,work);if(work.state()!=JobState.SUCCEEDED)return;
            var target=CapabilityFixture.machine(h.getLevel().getBlockEntity(destination));h.assertTrue(restored.storage.getStackInSlot(0).getCount()==4 && restored.storage.getStackInSlot(1).isEmpty() && target.storage.getStackInSlot(0).getCount()==6 && target.storage.getStackInSlot(1).isEmpty(),"Only north output was extracted and only top input accepted insertion");
            var results=work.result().getAsJsonObject("results");var entries=results.getAsJsonObject("found").getAsJsonArray("containers");boolean counted=false;for(var element:entries){var value=element.getAsJsonObject();if(value.get("x").getAsInt()==source.getX() && value.get("y").getAsInt()==source.getY() && value.get("z").getAsInt()==source.getZ())counted=value.get("count").getAsLong()==10 && !value.get("countCertain").getAsBoolean();}
            h.assertTrue(!results.getAsJsonObject("machine").get("countCertain").getAsBoolean() && results.getAsJsonObject("machine").getAsJsonArray("views").size()==2 && counted,"Overlapping face views are reported with conservative count uncertainty, without double-counting");clear(m,owner,entry);pass("attached_sided_machine_capability_native_transfer_nbt");h.succeed();});
        h.runAfterDelay(280,()->{if(work.state()!=JobState.SUCCEEDED)h.fail("Actual custom capability work stalled after "+(h.getLevel().getGameTime()-started)+" ticks: "+work.view()+" survival="+entry.control+" body="+(m.actor(entry.id)==null?"absent":m.actor(entry.id).position()));});
    }
    private static AvatarManager manager(GameTestHelper h){return ServerEvents.avatars(h.getLevel().getServer());}
    private static AvatarEntry spawn(GameTestHelper h,AvatarManager m,UUID owner){return m.spawn(owner,h.getLevel(),h.absoluteVec(new Vec3(3.5,2,5.5)),"QI"+UUID.randomUUID().toString().substring(0,8));}
    private static GameJob submit(AvatarManager m,UUID owner,AvatarEntry entry,String json){return m.jobs().submitProgram(owner,entry.id,entry.authority.session(),UUID.randomUUID(),JsonParser.parseString(json).getAsJsonObject(),"replace");}
    private static ChestBlockEntity chest(GameTestHelper h,BlockPos pos){h.getLevel().setBlockAndUpdate(pos,Blocks.CHEST.defaultBlockState());return (ChestBlockEntity)h.getLevel().getBlockEntity(pos);}
    private static ItemStack tagged(int count){var item=new ItemStack(Items.DIAMOND,count);item.getOrCreateTag().putString("qa_container","retained");return item;}
    private static void clear(AvatarManager m,UUID owner,AvatarEntry entry){m.actor(entry.id).getInventory().clearContent();m.destroy(owner,entry.id);}
    private static void check(GameTestHelper h,GameJob work){h.assertTrue(work.state()!=JobState.FAILED && work.state()!=JobState.CANCELLED,"Container work failed: "+work.view());}
    private static void pass(String event){Paracraft.LOG.info("QA_EVT {}","{\"event\":\""+event+"\",\"status\":\"pass\"}");}
}
