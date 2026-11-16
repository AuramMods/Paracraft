package com.aurammods.paracraft.forge.execution;

import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.UUID;

/** Incremental full-height observation of loaded blocks in a frozen nine-chunk scope. */
public final class BlockSearchJob extends GameJob {
    private final JsonObject specification;
    private final Block block;
    private final TagKey<Block> tag;
    private final int limit;
    private ChunkPos center;
    private int chunkIndex,sectionIndex,offset;
    private long total,observedAt;
    private JsonArray matches=new JsonArray(),covered=new JsonArray(),missing=new JsonArray();
    public BlockSearchJob(UUID id,CompanionPlayer actor,JsonObject specification){
        super(id,"search_blocks",actor);this.specification=specification.deepCopy();center=actor.chunkPosition();
        String selector=specification.get("block").getAsString();boolean tagged=selector.startsWith("#");ResourceLocation key=ResourceLocation.tryParse(tagged?selector.substring(1):selector);
        if(key==null || (!tagged && !ForgeRegistries.BLOCKS.containsKey(key)))throw new IllegalArgumentException("Unknown block selector: "+selector);
        block=tagged?null:ForgeRegistries.BLOCKS.getValue(key);tag=tagged?TagKey.create(Registries.BLOCK,key):null;
        limit=specification.has("limit")?specification.get("limit").getAsInt():256;observedAt=actor.level().getGameTime();refresh();
    }
    private boolean matches(BlockState state){return tag!=null?state.is(tag):state.is(block);}
    @Override protected void advance(){
        phase="searching_blocks";observedAt=actor.level().getGameTime();int budget=4096;
        while(chunkIndex<9 && budget>0){
            ChunkPos next=new ChunkPos(center.x+chunkIndex/3-1,center.z+chunkIndex%3-1);var chunk=actor.serverLevel().getChunkSource().getChunkNow(next.x,next.z);
            if(chunk==null){missing.add(pair(next));nextChunk();budget--;continue;}
            var sections=chunk.getSections();
            if(sectionIndex>=sections.length){covered.add(pair(next));nextChunk();budget--;continue;}
            var section=sections[sectionIndex];
            if(offset==0 && !section.maybeHas(this::matches)){sectionIndex++;budget--;continue;}
            while(offset<4096 && budget>0){
                budget--;
                int x=offset&15,z=(offset>>4)&15,y=offset>>8;offset++;
                if(!matches(section.getBlockState(x,y,z)))continue;total++;
                if(matches.size()<limit){var value=new JsonObject();value.add("position",JobJson.position(new BlockPos(next.getMinBlockX()+x,actor.serverLevel().getMinBuildHeight()+sectionIndex*16+y,next.getMinBlockZ()+z)));value.addProperty("block",ForgeRegistries.BLOCKS.getKey(section.getBlockState(x,y,z).getBlock()).toString());value.addProperty("observedAt",observedAt);matches.add(value);}
            }
            if(offset>=4096){offset=0;sectionIndex++;}
        }
        refresh();if(chunkIndex>=9)succeed(result);
    }
    private static JsonArray pair(ChunkPos chunk){var pair=new JsonArray();pair.add(chunk.x);pair.add(chunk.z);return pair;}
    private void nextChunk(){chunkIndex++;sectionIndex=0;offset=0;}
    private void refresh(){result.addProperty("dimension",actor.serverLevel().dimension().location().toString());result.addProperty("selector",specification.get("block").getAsString());result.addProperty("centerChunkX",center.x);result.addProperty("centerChunkZ",center.z);result.addProperty("complete",chunkIndex>=9 && missing.isEmpty());result.addProperty("observedAt",observedAt);result.addProperty("total",total);result.addProperty("truncated",total>matches.size());result.add("blocks",matches.deepCopy());result.add("coveredChunks",covered.deepCopy());result.add("missingChunks",missing.deepCopy());}
    @Override protected JsonObject writeData(){var data=new JsonObject();data.add("specification",specification.deepCopy());data.add("center",pair(center));data.addProperty("chunkIndex",chunkIndex);data.addProperty("sectionIndex",sectionIndex);data.addProperty("offset",offset);data.addProperty("total",total);data.addProperty("observedAt",observedAt);data.add("blocks",matches.deepCopy());data.add("coveredChunks",covered.deepCopy());data.add("missingChunks",missing.deepCopy());return data;}
    @Override protected void readData(JsonObject data){var c=data.getAsJsonArray("center");center=new ChunkPos(c.get(0).getAsInt(),c.get(1).getAsInt());chunkIndex=data.get("chunkIndex").getAsInt();sectionIndex=data.get("sectionIndex").getAsInt();offset=data.get("offset").getAsInt();total=data.get("total").getAsLong();observedAt=data.get("observedAt").getAsLong();matches=data.getAsJsonArray("blocks").deepCopy();covered=data.getAsJsonArray("coveredChunks").deepCopy();missing=data.getAsJsonArray("missingChunks").deepCopy();refresh();}
}
