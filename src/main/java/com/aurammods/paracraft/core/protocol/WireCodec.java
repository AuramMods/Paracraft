package com.aurammods.paracraft.core.protocol;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Versioned JSON carried in fragments below Minecraft 1.20.1's serverbound payload limit. */
public final class WireCodec {
    public static final int VERSION = 1, CHUNK_BYTES = 24_000, MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_PARTS = (MAX_BYTES + CHUNK_BYTES - 1) / CHUNK_BYTES;
    private WireCodec() { }
    public record Frame(UUID id, int index, int count, byte[] bytes) {
        public Frame {
            if (id == null || count < 1 || count > MAX_PARTS || index < 0 || index >= count || bytes.length == 0 || bytes.length > CHUNK_BYTES)
                throw new IllegalArgumentException("Invalid Paracraft fragment");
            bytes = bytes.clone();
        }
        @Override public byte[] bytes() { return bytes.clone(); }
    }
    public static List<Frame> encode(JsonObject message) {
        byte[] raw = message.toString().getBytes(StandardCharsets.UTF_8);
        if (raw.length > MAX_BYTES) throw new IllegalArgumentException("Paracraft message exceeds 16 MiB of JSON");
        try {
            var output = new ByteArrayOutputStream();
            try (var gzip = new GZIPOutputStream(output)) { gzip.write(raw); }
            byte[] packed = output.toByteArray();
            if (packed.length > MAX_BYTES) throw new IllegalArgumentException("Paracraft compressed message exceeds 16 MiB");
            int count = (packed.length + CHUNK_BYTES - 1) / CHUNK_BYTES; UUID id = UUID.randomUUID(); List<Frame> frames = new ArrayList<>();
            for (int i = 0; i < count; i++) frames.add(new Frame(id, i, count, Arrays.copyOfRange(packed, i * CHUNK_BYTES, Math.min((i + 1) * CHUNK_BYTES, packed.length))));
            return frames;
        } catch (IOException impossible) { throw new IllegalStateException("Cannot encode Paracraft JSON", impossible); }
    }
    /** One assembler per connection, ticked on its game thread. Buffered transport data is bounded. */
    public static final class Assembler {
        private static final long EXPIRY_NANOS = 30_000_000_000L;
        private static final class Parts {
            final byte[][] bytes; final long created; int received, size;
            Parts(int count, long created) { bytes = new byte[count][]; this.created = created; }
        }
        private final Map<UUID, Parts> pending = new HashMap<>();
        private int buffered;
        public Optional<JsonObject> accept(Frame frame, long now) {
            expire(now);
            Parts parts = pending.get(frame.id);
            if (parts == null) {
                if (pending.size() >= 16) throw new IllegalArgumentException("Too many unfinished Paracraft messages");
                parts = new Parts(frame.count, now); pending.put(frame.id, parts);
            }
            if (parts.bytes.length != frame.count) { remove(frame.id); throw new IllegalArgumentException("Fragment count changed"); }
            byte[] previous = parts.bytes[frame.index];
            if (previous != null) {
                if (!Arrays.equals(previous, frame.bytes)) { remove(frame.id); throw new IllegalArgumentException("Fragment contents changed"); }
                return Optional.empty();
            }
            if (parts.size + frame.bytes.length > MAX_BYTES || buffered + frame.bytes.length > 2 * MAX_BYTES) {
                remove(frame.id); throw new IllegalArgumentException("Paracraft transport buffer exceeded");
            }
            parts.bytes[frame.index] = frame.bytes.clone(); parts.received++; parts.size += frame.bytes.length; buffered += frame.bytes.length;
            if (parts.received != parts.bytes.length) return Optional.empty();
            remove(frame.id);
            try {
                var packed = new ByteArrayOutputStream(parts.size); for (byte[] bytes : parts.bytes) packed.write(bytes);
                var raw = new ByteArrayOutputStream();
                try (var gzip = new GZIPInputStream(new ByteArrayInputStream(packed.toByteArray()))) {
                    byte[] block = new byte[8192]; int read;
                    while ((read = gzip.read(block)) != -1) {
                        if (raw.size() + read > MAX_BYTES) throw new IllegalArgumentException("Paracraft decompressed JSON exceeds 16 MiB");
                        raw.write(block, 0, read);
                    }
                }
                return Optional.of(JsonParser.parseString(raw.toString(StandardCharsets.UTF_8)).getAsJsonObject());
            } catch (IOException failure) { throw new IllegalArgumentException("Invalid compressed Paracraft message", failure); }
        }
        public void expire(long now) {
            for (UUID id : List.copyOf(pending.keySet())) if (now - pending.get(id).created >= EXPIRY_NANOS) remove(id);
        }
        private void remove(UUID id) { Parts p = pending.remove(id); if (p != null) buffered -= p.size; }
        public int bufferedBytes() { return buffered; }
        public int pendingMessages() { return pending.size(); }
        public void clear() { pending.clear(); buffered = 0; }
    }
}
