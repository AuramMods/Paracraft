package com.aurammods.paracraft.core.program;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** A palette and shared layer rows, with positions resolved only as the build advances. */
public final class LayerBlueprint {
    public static final String SYMBOLS = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    public static final int MAX_CELLS = 65_536;
    public static final String OTHER = "other", UNAVAILABLE = "unavailable";
    private static final Pattern RANGE = Pattern.compile("([0-9]+)-([0-9]+)");
    private record Layer(int y, List<String> rows) { }
    public record Cell(BlockTargets.Position position, String block, int layer) { }

    private final BlockTargets.Position origin;
    private final Map<Character, String> palette;
    private final List<Layer> layers;
    private final int width, depth, area;

    public LayerBlueprint(JsonObject specification) {
        origin = BlockTargets.position(specification.get("origin"));
        var colors = new LinkedHashMap<Character, String>();
        for (var entry : array(specification, "palette")) {
            var value = object(entry);
            String symbol = text(value.get("symbol"));
            if (symbol.length() != 1 || SYMBOLS.indexOf(symbol.charAt(0)) < 0)
                throw new IllegalArgumentException("Palette symbols must be single a-z, 0-9 or A-Z characters");
            if (colors.putIfAbsent(symbol.charAt(0), text(value.get("block"))) != null)
                throw new IllegalArgumentException("Duplicate palette symbol " + symbol);
        }
        palette = Map.copyOf(colors);

        var offsets = new TreeMap<Integer, List<String>>();
        int rowWidth = 0, rowCount = 0;
        for (var entry : array(specification, "layers")) {
            var value = object(entry);
            var rows = array(value, "rows").asList().stream().map(LayerBlueprint::text).toList();
            if (rowCount == 0) { rowCount = rows.size(); rowWidth = rows.get(0).length(); }
            if (rows.size() != rowCount) throw new IllegalArgumentException("All layers must have the same row count");
            for (String row : rows) {
                if (row.length() != rowWidth) throw new IllegalArgumentException("All rows must have the same width");
                for (int i = 0; i < row.length(); i++) if (!palette.containsKey(row.charAt(i)))
                    throw new IllegalArgumentException("Undefined palette symbol " + row.charAt(i));
            }
            for (var level : array(value, "y")) {
                int first, last;
                if (level.isJsonPrimitive() && level.getAsJsonPrimitive().isNumber()) first = last = offset(level.getAsString());
                else {
                    var range = RANGE.matcher(text(level));
                    if (!range.matches()) throw new IllegalArgumentException("Layer y needs an integer or inclusive range such as 1-3");
                    first = offset(range.group(1));
                    last = offset(range.group(2));
                }
                if (last < first) throw new IllegalArgumentException("Layer ranges must ascend");
                long total = offsets.size() + (long) last - first + 1;
                if (total > MAX_CELLS / ((long) rowCount * rowWidth))
                    throw new IllegalArgumentException("A layer build can contain at most " + MAX_CELLS + " cells");
                for (long y = first; y <= last; y++) if (offsets.putIfAbsent((int)y, rows) != null)
                    throw new IllegalArgumentException("Layer " + y + " is defined more than once");
            }
        }
        width = rowWidth;
        depth = rowCount;
        area = width * depth;
        try {
            Math.addExact(origin.x(), width - 1);
            Math.addExact(origin.y(), offsets.lastKey());
            Math.addExact(origin.z(), depth - 1);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Layer build coordinates exceed the block coordinate range");
        }
        var ordered = new ArrayList<Layer>(offsets.size());
        offsets.forEach((y, rows) -> ordered.add(new Layer(y, rows)));
        layers = List.copyOf(ordered);
    }

    public int size() { return area * layers.size(); }
    public Iterable<String> blocks() { return palette.values(); }

    public Cell cell(int index) {
        var layer = layers.get(index / area);
        int x = index % width, z = index / width % depth;
        return new Cell(new BlockTargets.Position(origin.x() + x, origin.y() + layer.y, origin.z() + z),
                palette.get(layer.rows.get(z).charAt(x)), layer.y);
    }

    private static JsonArray array(JsonObject object, String field) {
        var value = object.get(field);
        if (value == null || !value.isJsonArray() || value.getAsJsonArray().isEmpty())
            throw new IllegalArgumentException(field + " must be a nonempty array");
        return value.getAsJsonArray();
    }

    private static JsonObject object(JsonElement value) {
        if (!value.isJsonObject()) throw new IllegalArgumentException("Layer and palette entries must be objects");
        return value.getAsJsonObject();
    }

    private static String text(JsonElement value) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString() || value.getAsString().isBlank())
            throw new IllegalArgumentException("Layer rows, range strings and palette entries must be nonempty text");
        return value.getAsString();
    }

    private static int offset(String text) {
        try {
            int y = new java.math.BigDecimal(text).intValueExact();
            if (y >= 0) return y;
        } catch (NumberFormatException | ArithmeticException invalid) {
            // Report the same syntax error for an out-of-range or inexact level.
        }
        throw new IllegalArgumentException("Layer y must be a nonnegative 32-bit offset");
    }
}
