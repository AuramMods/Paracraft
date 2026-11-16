package com.aurammods.paracraft.core.protocol;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WireCodecTest {
    @Test void largeUnicodeSnapshotsRoundTripOutOfOrderAndDuplicatePartsAreHarmless() {
        JsonObject original = new JsonObject(); var text = new StringBuilder("☃ 日本語 ");
        for (int i = 0; i < 5000; i++) text.append(UUID.randomUUID()); original.addProperty("inventoryNbt", text.toString());
        var frames = new ArrayList<>(WireCodec.encode(original)); assertTrue(frames.size() > 1);
        assertTrue(frames.stream().allMatch(f -> f.bytes().length <= 24_000));
        var assembler = new WireCodec.Assembler(); Collections.reverse(frames);
        assertTrue(assembler.accept(frames.get(0), 0).isEmpty()); assertTrue(assembler.accept(frames.get(0), 0).isEmpty());
        JsonObject actual = null;
        for (int i = 1; i < frames.size(); i++) { var reply = assembler.accept(frames.get(i), 0); if (reply.isPresent()) actual = reply.get(); }
        assertEquals(original, actual); assertEquals(0, assembler.bufferedBytes()); assertEquals(0, assembler.pendingMessages());
    }
    @Test void conflictingFragmentsFailAndAbandonedAssembliesExpire() {
        var assembler = new WireCodec.Assembler(); UUID id = UUID.randomUUID();
        assembler.accept(new WireCodec.Frame(id, 0, 2, new byte[] {1}), 0);
        assertThrows(IllegalArgumentException.class, () -> assembler.accept(new WireCodec.Frame(id, 0, 2, new byte[] {2}), 0));
        assertEquals(0, assembler.bufferedBytes()); assertEquals(0, assembler.pendingMessages());
        assembler.accept(new WireCodec.Frame(id, 0, 2, new byte[] {1}), 0); assembler.expire(30_000_000_000L);
        assertEquals(0, assembler.pendingMessages());
        assertThrows(IllegalArgumentException.class, () -> new WireCodec.Frame(id, -1, 2, new byte[] {1}));
        assertThrows(IllegalArgumentException.class, () -> new WireCodec.Frame(id, 0, 1, new byte[24_001]));
    }
    @Test void actualDecompressionLimitRejectsACompressedOversizedMessage() throws Exception {
        byte[] raw = ("{\"data\":\"" + "a".repeat(WireCodec.MAX_BYTES) + "\"}").getBytes(StandardCharsets.UTF_8);
        var packed = new ByteArrayOutputStream(); try (var gzip = new GZIPOutputStream(packed)) { gzip.write(raw); }
        assertTrue(packed.size() < WireCodec.CHUNK_BYTES);
        var assembler = new WireCodec.Assembler();
        var failure = assertThrows(IllegalArgumentException.class, () -> assembler.accept(new WireCodec.Frame(UUID.randomUUID(), 0, 1, packed.toByteArray()), 0));
        assertTrue(failure.getMessage().contains("decompressed")); assertEquals(0, assembler.bufferedBytes());
    }
}
