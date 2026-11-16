package com.aurammods.paracraft.forge.inventory;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fixed nine-chunk scope; inspect a bounded number of block entities each tick. */
public final class InventorySearch {
    public record Match(BlockPos position, long count, boolean countCertain, long observedAt) { }
    private final ServerLevel level;
    private final ChunkPos center;
    private final ItemSelector selector;
    private final ArrayDeque<ChunkPos> chunks = new ArrayDeque<>();
    private final ArrayDeque<BlockPos> blocks = new ArrayDeque<>();
    private final List<ChunkPos> covered = new ArrayList<>(), missing = new ArrayList<>();
    private final List<Match> matches = new ArrayList<>();
    private final Set<BlockPos> seen = new HashSet<>();
    private long observedAt;

    public InventorySearch(ServerLevel level, ChunkPos center, ItemSelector selector) {
        this.level = level;
        this.center = center;
        this.selector = selector;
        observedAt = level.getGameTime();
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) chunks.add(new ChunkPos(center.x + x, center.z + z));
    }
    public boolean done() { return chunks.isEmpty() && blocks.isEmpty(); }
    public List<Match> matches() { return List.copyOf(matches); }
    public void tick() {
        if (done()) return;
        observedAt = level.getGameTime();
        if (blocks.isEmpty() && !chunks.isEmpty()) {
            ChunkPos next = chunks.remove();
            var chunk = level.getChunkSource().getChunkNow(next.x, next.z);
            if (chunk == null) missing.add(next);
            else { covered.add(next); blocks.addAll(chunk.getBlockEntities().keySet().stream().sorted().toList()); }
        }
        for (int budget = 0; budget < 8 && !blocks.isEmpty(); budget++) {
            BlockPos position = blocks.remove();
            var target = InventoryAccess.resolve(level, position);
            if (target == null || !seen.add(target.identity())) continue;
            long count = InventoryAccess.count(target, selector);
            if (count > 0) matches.add(new Match(position, count, InventoryAccess.countCertain(target), level.getGameTime()));
        }
    }
    public JsonObject result() {
        JsonObject result = new JsonObject();
        result.addProperty("dimension", level.dimension().location().toString());
        result.addProperty("centerChunkX", center.x); result.addProperty("centerChunkZ", center.z);
        result.addProperty("selector", selector.expression());
        result.addProperty("complete", done() && missing.isEmpty());
        result.addProperty("total", matches.stream().mapToLong(Match::count).sum());
        result.addProperty("countCertain", matches.stream().allMatch(Match::countCertain));
        result.addProperty("observedAt", observedAt);
        JsonArray entries = new JsonArray();
        for (Match match : matches) {
            JsonObject entry = new JsonObject();
            entry.addProperty("x", match.position().getX()); entry.addProperty("y", match.position().getY());
            entry.addProperty("z", match.position().getZ()); entry.addProperty("count", match.count());
            entry.addProperty("countCertain", match.countCertain()); entry.addProperty("observedAt", match.observedAt());
            entries.add(entry);
        }
        result.add("containers", entries); result.add("coveredChunks", encode(covered)); result.add("missingChunks", encode(missing));
        return result;
    }
    private static JsonArray encode(List<ChunkPos> positions) {
        JsonArray array = new JsonArray();
        for (ChunkPos pos : positions) { JsonArray pair = new JsonArray(); pair.add(pos.x); pair.add(pos.z); array.add(pair); }
        return array;
    }
}
