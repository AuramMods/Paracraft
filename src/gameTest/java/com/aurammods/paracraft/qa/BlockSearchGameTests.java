package com.aurammods.paracraft.qa;

import com.aurammods.paracraft.Paracraft;
import com.aurammods.paracraft.core.JobState;
import com.aurammods.paracraft.forge.ServerEvents;
import com.aurammods.paracraft.forge.avatar.AvatarEntry;
import com.aurammods.paracraft.forge.avatar.AvatarManager;
import com.aurammods.paracraft.forge.execution.BlockSearchJob;
import com.aurammods.paracraft.forge.execution.GameJob;
import com.aurammods.paracraft.forge.execution.JobCodec;
import com.aurammods.paracraft.forge.execution.JobJson;
import com.aurammods.paracraft.forge.execution.OperationSyntax;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.util.List;
import java.util.UUID;

@GameTestHolder(Paracraft.ID)
@PrefixGameTestTemplate(false)
public final class BlockSearchGameTests {
    @GameTest(batch="block_queries",template="arena",timeoutTicks=160)
    public static void nearestCountRetainsClosestInsteadOfFirstScanMatches(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner);var actor=m.actor(entry.id);Vec3 origin=actor.position();var center=actor.chunkPosition();
        BlockPos far=new BlockPos(center.getMinBlockX()-15,2,center.getMinBlockZ()-15),high=actor.blockPosition().atY(250),near=actor.blockPosition().offset(2,0,0),next=actor.blockPosition().offset(4,0,0);
        var positions=List.of(far,high,near,next);for(var pos:positions)h.getLevel().setBlockAndUpdate(pos,Blocks.LODESTONE.defaultBlockState());
        var work=submit(m,owner,entry,"{\"op\":\"search_blocks\",\"block\":\"minecraft:lodestone\",\"limit\":2,\"saveAs\":\"found\"}");
        h.onEachTick(()->{
            check(h,work);if(work.state()!=JobState.SUCCEEDED)return;var result=work.result().getAsJsonObject("results").getAsJsonObject("found");var rows=result.getAsJsonArray("blocks");
            h.assertTrue(result.get("total").getAsLong()==4 && result.get("truncated").getAsBoolean() && rows.size()==2,"All full-height matches counted while only the nearest two are retained");
            h.assertTrue(JobJson.position(rows.get(0).getAsJsonObject().get("position")).equals(near) && JobJson.position(rows.get(1).getAsJsonObject().get("position")).equals(next),"Nearer later-scanned blocks replace farther first matches and are sorted nearest first");
            h.assertTrue(Math.abs(rows.get(0).getAsJsonObject().get("distance").getAsDouble()-Vec3.atCenterOf(near).distanceTo(origin))<1e-9,"Reported distance is measured from the fixed avatar origin to block center");
            h.assertTrue(result.get("order").getAsString().equals("nearest") && JobJson.vector(result.get("origin")).equals(origin) && result.get("complete").getAsBoolean() && result.getAsJsonArray("coveredChunks").size()==9,"Origin, order and full nine-chunk coverage are explicit");
            clear(h,m,owner,entry,positions);pass("nearest_block_count_and_distance_order");h.succeed();
        });
    }

    @GameTest(batch="block_queries",template="arena",timeoutTicks=160)
    public static void blockTagRadiusUsesThreeDimensionsAndDeterministicTies(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner);var actor=m.actor(entry.id);BlockPos left=actor.blockPosition().offset(-2,0,0),right=actor.blockPosition().offset(2,0,0),above=actor.blockPosition().offset(0,6,0);
        h.getLevel().setBlockAndUpdate(left,Blocks.OAK_LOG.defaultBlockState());h.getLevel().setBlockAndUpdate(right,Blocks.BIRCH_LOG.defaultBlockState());h.getLevel().setBlockAndUpdate(above,Blocks.OAK_LOG.defaultBlockState());var positions=List.of(left,right,above);
        var work=submit(m,owner,entry,"{\"op\":\"search_blocks\",\"block\":\"#minecraft:logs\",\"limit\":3,\"radius\":3,\"saveAs\":\"found\"}");
        h.onEachTick(()->{
            check(h,work);if(work.state()!=JobState.SUCCEEDED)return;var result=work.result().getAsJsonObject("results").getAsJsonObject("found");var rows=result.getAsJsonArray("blocks");
            h.assertTrue(result.get("total").getAsLong()==2 && rows.size()==2 && !result.get("truncated").getAsBoolean() && result.get("complete").getAsBoolean() && result.get("radiusFullyCovered").getAsBoolean(),"Only log-tag matches inside the three-dimensional radius are counted");
            h.assertTrue(JobJson.position(rows.get(0).getAsJsonObject().get("position")).equals(left) && rows.get(0).getAsJsonObject().get("block").getAsString().equals("minecraft:oak_log") && JobJson.position(rows.get(1).getAsJsonObject().get("position")).equals(right) && rows.get(1).getAsJsonObject().get("block").getAsString().equals("minecraft:birch_log"),"Equal distances use stable coordinate order and return actual block IDs");
            var zero=new BlockSearchJob(UUID.randomUUID(),actor,json("{\"op\":\"search_blocks\",\"block\":\"#minecraft:logs\",\"limit\":1,\"radius\":0}"));for(int i=0;i<10 && zero.state()==JobState.RUNNING;i++)zero.tick();check(h,zero);
            h.assertTrue(zero.state()==JobState.SUCCEEDED && zero.result().get("total").getAsLong()==0 && zero.result().get("complete").getAsBoolean(),"A zero-radius search is valid and completes without unrelated matches");
            for(String bad:List.of("{\"radius\":-1}","{\"radius\":\"3\"}","{\"radius\":null}","{\"limit\":0}","{\"limit\":4097}")){
                var invalid=json(bad);invalid.addProperty("op","search_blocks");invalid.addProperty("block","minecraft:lodestone");boolean rejected=false;try{OperationSyntax.validate(invalid);}catch(IllegalArgumentException expected){rejected=true;}h.assertTrue(rejected,"Invalid raw query arguments are rejected: "+bad);
            }
            clear(h,m,owner,entry,positions);pass("block_tag_radius_and_stable_ties");h.succeed();
        });
    }

    @GameTest(batch="block_queries",template="arena",timeoutTicks=220)
    public static void checkpointPreservesOriginAndLegacyQueriesRestartSafely(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner);var actor=m.actor(entry.id);Vec3 origin=actor.position();var center=actor.chunkPosition();
        BlockPos far=new BlockPos(center.getMinBlockX()-15,2,center.getMinBlockZ()-15),originalNear=actor.blockPosition().offset(1,0,0),movedNear=actor.blockPosition().offset(11,0,0);var positions=List.of(far,originalNear,movedNear);for(var pos:positions)h.getLevel().setBlockAndUpdate(pos,Blocks.LODESTONE.defaultBlockState());
        GameJob[] work={new BlockSearchJob(UUID.randomUUID(),actor,json("{\"op\":\"search_blocks\",\"block\":\"minecraft:lodestone\",\"limit\":1}"))};JsonObject[] legacy={null};int[] stage={0};Vec3 moved=origin.add(12,0,0);
        h.onEachTick(()->{
            work[0].tick();check(h,work[0]);
            if(stage[0]==0 && work[0].result().get("total").getAsLong()>0){
                h.assertTrue(work[0].state()==JobState.RUNNING,"Checkpoint captures a genuinely partial scan with retained matches");work[0].pause();var saved=work[0].checkpoint();legacy[0]=saved.deepCopy();legacy[0].getAsJsonObject("data").remove("origin");actor.moveTo(moved.x,moved.y,moved.z,0,0);work[0]=JobCodec.restore(actor,saved);work[0].resume();stage[0]=1;return;
            }
            if(work[0].state()!=JobState.SUCCEEDED)return;var result=work[0].result();h.assertTrue(result.get("total").getAsLong()==3,"Checkpoint scanning counts each observed match once");
            if(stage[0]==1){
                h.assertTrue(JobJson.vector(result.get("origin")).equals(origin) && JobJson.position(result.getAsJsonArray("blocks").get(0).getAsJsonObject().get("position")).equals(originalNear),"Resumed nearest ranking retains its original origin even after body movement");
                work[0]=JobCodec.restore(actor,legacy[0]);work[0].resume();stage[0]=2;return;
            }
            h.assertTrue(stage[0]==2 && JobJson.vector(result.get("origin")).equals(moved) && JobJson.position(result.getAsJsonArray("blocks").get(0).getAsJsonObject().get("position")).equals(movedNear),"Legacy scan-order checkpoints restart the read-only query and rank against the restored body position");
            clear(h,m,owner,entry,positions);pass("nearest_block_checkpoint_origin_and_legacy_restart");h.succeed();
        });
    }

    @GameTest(batch="block_queries",template="arena",timeoutTicks=40)
    public static void unloadedQueriesReportMissingCoverageWithoutGeneratingChunks(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner);var actor=m.actor(entry.id);Vec3 original=actor.position();actor.moveTo(800000.5,70,800000.5,0,0);var center=actor.chunkPosition();
        // Native player movement itself loads the destination chunk. Record actual availability
        // after that move, then verify that running the query loads none of its absent neighbors.
        var absent=new java.util.ArrayList<net.minecraft.world.level.ChunkPos>();for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)if(h.getLevel().getChunkSource().getChunkNow(center.x+x,center.z+z)==null)absent.add(new net.minecraft.world.level.ChunkPos(center.x+x,center.z+z));
        var work=new BlockSearchJob(UUID.randomUUID(),actor,json("{\"op\":\"search_blocks\",\"block\":\"minecraft:lodestone\",\"limit\":2}"));work.tick();check(h,work);var result=work.result();
        h.assertTrue(work.state()==JobState.SUCCEEDED && result.get("scanComplete").getAsBoolean() && !result.get("complete").getAsBoolean() && result.get("total").getAsLong()==0 && !absent.isEmpty() && result.getAsJsonArray("missingChunks").size()==absent.size() && result.getAsJsonArray("coveredChunks").size()==9-absent.size(),"Unloaded coverage reports the actual missing neighbors: "+work.view());
        for(var chunk:absent)h.assertTrue(h.getLevel().getChunkSource().getChunkNow(chunk.x,chunk.z)==null,"Searching does not load or generate absent chunks");
        actor.moveTo(original.x,original.y,original.z,0,0);clear(h,m,owner,entry,List.of());pass("block_search_unloaded_coverage_no_generation");h.succeed();
    }

    @GameTest(batch="block_queries",template="arena",timeoutTicks=120)
    public static void radiusClippedByNineChunkScopeCannotClaimCompleteCoverage(GameTestHelper h){
        var m=manager(h);UUID owner=UUID.randomUUID();var entry=spawn(h,m,owner);var actor=m.actor(entry.id);var center=actor.chunkPosition();BlockPos near=actor.blockPosition().offset(2,0,0),outside=new BlockPos(center.getMinBlockX()+32,2,actor.blockPosition().getZ());var positions=List.of(near,outside);for(var pos:positions)h.getLevel().setBlockAndUpdate(pos,Blocks.LODESTONE.defaultBlockState());
        var work=submit(m,owner,entry,"{\"op\":\"search_blocks\",\"block\":\"minecraft:lodestone\",\"limit\":2,\"radius\":100,\"saveAs\":\"found\"}");
        h.onEachTick(()->{
            check(h,work);if(work.state()!=JobState.SUCCEEDED)return;var result=work.result().getAsJsonObject("results").getAsJsonObject("found");
            h.assertTrue(result.get("scanComplete").getAsBoolean() && !result.get("complete").getAsBoolean() && !result.get("radiusFullyCovered").getAsBoolean() && result.getAsJsonArray("coveredChunks").size()==9,"A radius beyond the frozen nine chunks is explicitly incomplete even with all nine loaded");
            h.assertTrue(result.get("total").getAsLong()==1 && JobJson.position(result.getAsJsonArray("blocks").get(0).getAsJsonObject().get("position")).equals(near),"Radius does not expand chunk scope or include the tenth chunk");
            clear(h,m,owner,entry,positions);pass("block_search_radius_scope_clipping");h.succeed();
        });
    }
    private static JsonObject json(String value){return JsonParser.parseString(value).getAsJsonObject();}
    private static GameJob submit(AvatarManager m,UUID owner,AvatarEntry entry,String step){return m.jobs().submitProgram(owner,entry.id,entry.authority.session(),UUID.randomUUID(),json("{\"steps\":["+step+"]}"),"replace");}
    private static AvatarManager manager(GameTestHelper h){return ServerEvents.avatars(h.getLevel().getServer());}
    private static AvatarEntry spawn(GameTestHelper h,AvatarManager m,UUID owner){return m.spawn(owner,h.getLevel(),Vec3.atBottomCenterOf(h.absolutePos(new BlockPos(48,2,48))),"QB"+UUID.randomUUID().toString().substring(0,8));}
    private static void clear(GameTestHelper h,AvatarManager m,UUID owner,AvatarEntry entry,List<BlockPos> blocks){for(var pos:blocks)h.getLevel().setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());m.actor(entry.id).getInventory().clearContent();m.destroy(owner,entry.id);}
    private static void check(GameTestHelper h,GameJob work){h.assertTrue(work.state()!=JobState.FAILED && work.state()!=JobState.CANCELLED,"Block search failed: "+work.view());}
    private static void pass(String event){Paracraft.LOG.info("QA_EVT {}","{\"event\":\""+event+"\",\"status\":\"pass\"}");}
}
