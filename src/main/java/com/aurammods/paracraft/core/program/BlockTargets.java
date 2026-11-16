package com.aurammods.paracraft.core.program;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/** Lazy finite targets. Cuboids run top-down without allocating a block-sized list. */
public final class BlockTargets {
    public record Position(int x, int y, int z) { }
    private final JsonObject specification;
    private final List<Position> positions;
    private final int minX, maxY, minZ;
    private final long width, depth, size;
    public BlockTargets(JsonObject specification) {
        this.specification = specification.deepCopy();
        int forms = (specification.has("block") ? 1 : 0) + (specification.has("blocks") ? 1 : 0) + (specification.has("cuboid") ? 1 : 0);
        if (forms != 1) throw new IllegalArgumentException("Supply exactly one of block, blocks, or cuboid");
        if (specification.has("cuboid")) {
            if (!specification.get("cuboid").isJsonObject()) throw new IllegalArgumentException("cuboid must be an object");
            JsonObject box = specification.getAsJsonObject("cuboid"); Position a = position(box.get("from")), b = position(box.get("to"));
            minX = Math.min(a.x, b.x); maxY = Math.max(a.y, b.y); minZ = Math.min(a.z, b.z);
            width = Math.abs((long)a.x - b.x) + 1; depth = Math.abs((long)a.z - b.z) + 1;
            try { size = Math.multiplyExact(Math.multiplyExact(width, depth), Math.abs((long)a.y - b.y) + 1); }
            catch (ArithmeticException overflow) { throw new IllegalArgumentException("Cuboid exceeds the supported target-count range"); }
            positions = null;
        } else {
            minX = maxY = minZ = 0; width = depth = 0;
            if (specification.has("block")) positions = List.of(position(specification.get("block")));
            else {
                if (!specification.get("blocks").isJsonArray()) throw new IllegalArgumentException("blocks must be an array");
                positions = specification.getAsJsonArray("blocks").asList().stream().map(BlockTargets::position).toList();
            }
            size = positions.size();
        }
    }
    public long size() { return size; }
    public Position get(long index) {
        if (index < 0 || index >= size) throw new IndexOutOfBoundsException("Target index " + index);
        if (positions != null) return positions.get(Math.toIntExact(index));
        long layer = width * depth;
        return new Position(Math.toIntExact(minX + index % width), Math.toIntExact(maxY - index / layer), Math.toIntExact(minZ + index / width % depth));
    }
    public JsonObject specification() { return specification.deepCopy(); }
    public static Position position(JsonElement value) {
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().size() != 3) throw new IllegalArgumentException("Block position must be [x,y,z]");
        JsonArray values = value.getAsJsonArray();
        try {
            for (JsonElement coordinate : values) if (!coordinate.isJsonPrimitive() || !coordinate.getAsJsonPrimitive().isNumber()) throw new ArithmeticException();
            return new Position(values.get(0).getAsBigDecimal().intValueExact(), values.get(1).getAsBigDecimal().intValueExact(), values.get(2).getAsBigDecimal().intValueExact());
        } catch (ArithmeticException failure) { throw new IllegalArgumentException("Block coordinates must be exact 32-bit integers"); }
    }
}
