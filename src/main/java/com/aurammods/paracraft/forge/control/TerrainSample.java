package com.aurammods.paracraft.forge.control;

import com.aurammods.paracraft.core.program.BlockTargets;
import com.aurammods.paracraft.core.program.LayerBlueprint;
import com.aurammods.paracraft.forge.avatar.CompanionPlayer;
import com.aurammods.paracraft.forge.execution.JobJson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read a fixed cube without loading chunks or changing the avatar's current work. */
final class TerrainSample {
    private static final char OTHER = 'Z', UNAVAILABLE = 'Y';
    private static final String MATERIALS = LayerBlueprint.SYMBOLS.replace("Y", "").replace("Z", "");
    private TerrainSample() { }

    static JsonObject capture(CompanionPlayer actor, JsonObject arguments) {
        var coordinates = BlockTargets.position(arguments.get("center"));
        var center = new BlockPos(coordinates.x(), coordinates.y(), coordinates.z());
        int size = size(arguments);
        BlockPos origin;
        try {
            int x = Math.subtractExact(center.getX(), size / 2), y = Math.subtractExact(center.getY(), size / 2);
            int z = Math.subtractExact(center.getZ(), size / 2);
            Math.addExact(x, size - 1);
            Math.addExact(y, size - 1);
            Math.addExact(z, size - 1);
            origin = new BlockPos(x, y, z);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Terrain cube exceeds the block coordinate range");
        }
        Set<Block> filter = filter(arguments);
        var level = actor.serverLevel();
        var current = actor.chunkPosition();
        int minX = origin.getX() >> 4, minZ = origin.getZ() >> 4;
        int maxX = (origin.getX() + size - 1) >> 4, maxZ = (origin.getZ() + size - 1) >> 4;
        LevelChunk[][] chunks = new LevelChunk[maxX - minX + 1][maxZ - minZ + 1];
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            if (Math.abs(x - current.x) <= 1 && Math.abs(z - current.z) <= 1)
                chunks[x - minX][z - minZ] = level.getChunkSource().getChunkNow(x, z);
        }

        var symbols = new LinkedHashMap<Block, Character>();
        var groupedLayers = new LinkedHashMap<List<String>, JsonArray>();
        var pos = new BlockPos.MutableBlockPos();
        int filtered = 0, overflow = 0, unavailable = 0;
        for (int y = 0; y < size; y++) {
            var rows = new ArrayList<String>(size);
            for (int z = 0; z < size; z++) {
                var row = new StringBuilder(size);
                for (int x = 0; x < size; x++) {
                    pos.set(origin.getX() + x, origin.getY() + y, origin.getZ() + z);
                    var chunk = chunks[(pos.getX() >> 4) - minX][(pos.getZ() >> 4) - minZ];
                    if (chunk == null || level.isOutsideBuildHeight(pos) || !level.getWorldBorder().isWithinBounds(pos)) {
                        unavailable++;
                        row.append(UNAVAILABLE);
                        continue;
                    }
                    Block block = chunk.getBlockState(pos).getBlock();
                    if (!filter.isEmpty() && !filter.contains(block)) { filtered++; row.append(OTHER); continue; }
                    var symbol = symbols.get(block);
                    if (symbol == null && symbols.size() < MATERIALS.length()) {
                        symbol = MATERIALS.charAt(symbols.size());
                        symbols.put(block, symbol);
                    }
                    if (symbol == null) { overflow++; row.append(OTHER); }
                    else row.append(symbol.charValue());
                }
                rows.add(row.toString());
            }
            groupedLayers.computeIfAbsent(rows, ignored -> new JsonArray()).add(y);
        }

        var palette = new JsonArray();
        symbols.forEach((block, symbol) -> palette.add(entry(symbol, ForgeRegistries.BLOCKS.getKey(block).toString())));
        if (filtered > 0 || overflow > 0) palette.add(entry(OTHER, LayerBlueprint.OTHER));
        if (unavailable > 0) palette.add(entry(UNAVAILABLE, LayerBlueprint.UNAVAILABLE));
        var layers = new JsonArray();
        for (Map.Entry<List<String>, JsonArray> group : groupedLayers.entrySet()) {
            var layer = new JsonObject();
            layer.add("y", group.getValue());
            var rows = new JsonArray();
            group.getKey().forEach(rows::add);
            layer.add("rows", rows);
            layers.add(layer);
        }
        var result = new JsonObject();
        result.addProperty("dimension", level.dimension().location().toString());
        result.addProperty("observedAt", level.getGameTime());
        result.add("center", JobJson.position(center));
        result.add("origin", JobJson.position(origin));
        result.addProperty("size", size);
        result.addProperty("complete", unavailable == 0 && overflow == 0);
        result.addProperty("filteredCells", filtered);
        result.addProperty("overflowCells", overflow);
        result.addProperty("unavailableCells", unavailable);
        result.add("palette", palette);
        result.add("layers", layers);
        return result;
    }

    private static JsonObject entry(char symbol, String block) {
        var entry = new JsonObject();
        entry.addProperty("symbol", symbol);
        entry.addProperty("block", block);
        return entry;
    }

    private static int size(JsonObject args) {
        var value = args.get("size");
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            var number = value.getAsBigDecimal();
            if (number.compareTo(java.math.BigDecimal.valueOf(8)) == 0) return 8;
            if (number.compareTo(java.math.BigDecimal.valueOf(16)) == 0) return 16;
        }
        throw new IllegalArgumentException("Terrain size must be 8 or 16");
    }

    private static Set<Block> filter(JsonObject args) {
        var blocks = new HashSet<Block>();
        if (!args.has("filter") || args.get("filter").isJsonNull()) return blocks;
        if (!args.get("filter").isJsonArray()) throw new IllegalArgumentException("Terrain filter must be a list of block IDs");
        for (var item : args.getAsJsonArray("filter")) {
            var key = item.isJsonPrimitive() && item.getAsJsonPrimitive().isString() ? ResourceLocation.tryParse(item.getAsString()) : null;
            if (key == null || !ForgeRegistries.BLOCKS.containsKey(key)) throw new IllegalArgumentException("Unknown terrain filter block " + item);
            blocks.add(ForgeRegistries.BLOCKS.getValue(key));
        }
        return blocks;
    }
}
