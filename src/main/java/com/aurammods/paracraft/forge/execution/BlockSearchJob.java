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
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import java.util.Comparator;
import java.util.PriorityQueue;
import java.util.UUID;

/** Incremental loaded nine-chunk observation, retaining the nearest N matches in bounded memory. */
public final class BlockSearchJob extends GameJob {
    private record Match(BlockPos position,String block,long observedAt,double distanceSquared) { }
    private static final Comparator<Match> NEAREST=Comparator.comparingDouble(Match::distanceSquared)
            .thenComparingInt(m->m.position.getX()).thenComparingInt(m->m.position.getY()).thenComparingInt(m->m.position.getZ());
    private final JsonObject specification;
    private final Block block;
    private final TagKey<Block> tag;
    private final int limit;
    private final Double radius;
    private final PriorityQueue<Match> nearest=new PriorityQueue<>(NEAREST.reversed());
    private ChunkPos center;
    private Vec3 origin;
    private int chunkIndex,sectionIndex,offset;
    private long total,observedAt;
    private JsonArray covered=new JsonArray(),missing=new JsonArray(),excluded=new JsonArray();
    public BlockSearchJob(UUID id,CompanionPlayer actor,JsonObject specification){
        super(id,"search_blocks",actor);this.specification=specification.deepCopy();center=actor.chunkPosition();origin=actor.position();
        String selector=specification.get("block").getAsString();boolean tagged=selector.startsWith("#");ResourceLocation key=ResourceLocation.tryParse(tagged?selector.substring(1):selector);
        if(key==null || (!tagged && !ForgeRegistries.BLOCKS.containsKey(key)))throw new IllegalArgumentException("Unknown block selector: "+selector);
        block=tagged?null:ForgeRegistries.BLOCKS.getValue(key);tag=tagged?TagKey.create(Registries.BLOCK,key):null;
        limit=specification.has("limit")?specification.get("limit").getAsInt():256;
        radius=specification.has("radius")?specification.get("radius").getAsDouble():null;
        observedAt=actor.level().getGameTime();refresh();
    }
    private boolean matches(BlockState state){return tag!=null?state.is(tag):state.is(block);}
    @Override protected void advance(){
        phase="searching_blocks";observedAt=actor.level().getGameTime();int budget=4096;
        while(chunkIndex<9 && budget>0){
            ChunkPos next=new ChunkPos(center.x+chunkIndex/3-1,center.z+chunkIndex%3-1);
            if(!intersects(next,actor.serverLevel().getMinBuildHeight(),actor.serverLevel().getMaxBuildHeight())){excluded.add(pair(next));nextChunk();budget--;continue;}
            var chunk=actor.serverLevel().getChunkSource().getChunkNow(next.x,next.z);
            if(chunk==null){missing.add(pair(next));nextChunk();budget--;continue;}
            var sections=chunk.getSections();
            if(sectionIndex>=sections.length){covered.add(pair(next));nextChunk();budget--;continue;}
            var section=sections[sectionIndex];int baseY=actor.serverLevel().getMinBuildHeight()+sectionIndex*16;
            if(offset==0 && (!intersects(next,baseY,baseY+16) || !section.maybeHas(this::matches))){sectionIndex++;budget--;continue;}
            while(offset<4096 && budget>0){
                budget--;int x=offset&15,z=(offset>>4)&15,y=offset>>8;offset++;
                var state=section.getBlockState(x,y,z);if(!matches(state))continue;
                BlockPos position=new BlockPos(next.getMinBlockX()+x,baseY+y,next.getMinBlockZ()+z);
                double distance=Vec3.atCenterOf(position).distanceToSqr(origin);
                if(radius!=null && distance>radius*radius)continue;
                total++;
                var worst=nearest.peek();
                if(nearest.size()==limit && distance>worst.distanceSquared)continue;
                var match=new Match(position,ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString(),observedAt,distance);
                if(nearest.size()<limit)nearest.add(match);
                else if(NEAREST.compare(match,worst)<0){nearest.remove();nearest.add(match);}
            }
            if(offset>=4096){offset=0;sectionIndex++;}
        }
        refresh();if(chunkIndex>=9)succeed(result);
    }
    private boolean intersects(ChunkPos chunk,int minY,int maxY){
        if(radius==null)return true;
        double dx=axis(origin.x,chunk.getMinBlockX()+0.5,chunk.getMaxBlockX()+0.5),dy=axis(origin.y,minY+0.5,maxY-0.5),dz=axis(origin.z,chunk.getMinBlockZ()+0.5,chunk.getMaxBlockZ()+0.5);
        return dx*dx+dy*dy+dz*dz<=radius*radius;
    }
    private static double axis(double value,double min,double max){return Math.max(0,Math.max(min-value,value-max));}
    private boolean radiusCovered(){
        if(radius==null)return true;
        double x=Math.floor(origin.x)+0.5,z=Math.floor(origin.z)+0.5;
        double y=Math.max(actor.serverLevel().getMinBuildHeight()+0.5,Math.min(actor.serverLevel().getMaxBuildHeight()-0.5,Math.floor(origin.y)+0.5));
        double nearestOutside=Math.min(Math.min(new Vec3(Math.min(x,center.getMinBlockX()-16-0.5),y,z).distanceToSqr(origin),new Vec3(Math.max(x,center.getMaxBlockX()+16+1.5),y,z).distanceToSqr(origin)),
                Math.min(new Vec3(x,y,Math.min(z,center.getMinBlockZ()-16-0.5)).distanceToSqr(origin),new Vec3(x,y,Math.max(z,center.getMaxBlockZ()+16+1.5)).distanceToSqr(origin)));
        return nearestOutside>radius*radius;
    }
    private JsonArray rows(){
        var rows=new JsonArray();nearest.stream().sorted(NEAREST).forEach(match->{
            var value=new JsonObject();value.add("position",JobJson.position(match.position));value.addProperty("block",match.block);
            value.addProperty("observedAt",match.observedAt);value.addProperty("distance",Math.sqrt(match.distanceSquared));rows.add(value);
        });return rows;
    }
    private static JsonArray pair(ChunkPos chunk){var pair=new JsonArray();pair.add(chunk.x);pair.add(chunk.z);return pair;}
    private void nextChunk(){chunkIndex++;sectionIndex=0;offset=0;}
    private void refresh(){
        result.addProperty("dimension",actor.serverLevel().dimension().location().toString());result.addProperty("selector",specification.get("block").getAsString());
        result.addProperty("scope","neighbor_chunks");result.addProperty("chunkRadius",1);result.addProperty("centerChunkX",center.x);result.addProperty("centerChunkZ",center.z);
        result.add("origin",JobJson.vector(origin));result.addProperty("order","nearest");result.addProperty("limit",limit);
        if(radius!=null){result.addProperty("radius",radius);result.addProperty("radiusFullyCovered",radiusCovered());}
        result.addProperty("scanComplete",chunkIndex>=9);result.addProperty("complete",chunkIndex>=9 && missing.isEmpty() && radiusCovered());
        result.addProperty("observedAt",observedAt);result.addProperty("total",total);result.addProperty("truncated",total>nearest.size());result.add("blocks",rows());
        result.add("coveredChunks",covered.deepCopy());result.add("missingChunks",missing.deepCopy());result.add("excludedChunks",excluded.deepCopy());
    }
    @Override protected JsonObject writeData(){
        var data=new JsonObject();data.add("specification",specification.deepCopy());data.add("center",pair(center));data.add("origin",JobJson.vector(origin));
        data.addProperty("chunkIndex",chunkIndex);data.addProperty("sectionIndex",sectionIndex);data.addProperty("offset",offset);data.addProperty("total",total);data.addProperty("observedAt",observedAt);
        data.add("blocks",rows());data.add("coveredChunks",covered.deepCopy());data.add("missingChunks",missing.deepCopy());data.add("excludedChunks",excluded.deepCopy());return data;
    }
    @Override protected void readData(JsonObject data){
        var c=data.getAsJsonArray("center");center=new ChunkPos(c.get(0).getAsInt(),c.get(1).getAsInt());
        // Old checkpoints retained scan-order prefixes, which cannot prove nearest results.
        // Restart that read-only observation rather than continue with an incomplete top-N set.
        if(!data.has("origin")){refresh();return;}
        origin=JobJson.vector(data.get("origin"));chunkIndex=data.get("chunkIndex").getAsInt();sectionIndex=data.get("sectionIndex").getAsInt();offset=data.get("offset").getAsInt();
        total=data.get("total").getAsLong();observedAt=data.get("observedAt").getAsLong();nearest.clear();
        for(var entry:data.getAsJsonArray("blocks")){var row=entry.getAsJsonObject();var pos=JobJson.position(row.get("position"));nearest.add(new Match(pos,row.get("block").getAsString(),row.get("observedAt").getAsLong(),Vec3.atCenterOf(pos).distanceToSqr(origin)));}
        covered=data.getAsJsonArray("coveredChunks").deepCopy();missing=data.getAsJsonArray("missingChunks").deepCopy();
        excluded=data.has("excludedChunks")?data.getAsJsonArray("excludedChunks").deepCopy():new JsonArray();refresh();
    }
}
