package com.aurammods.paracraft.core.agent;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Local memory only. No credential access or auth transport is implemented here. */
public final class AgentStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path root;
    public AgentStore(Path root) { this.root = root.toAbsolutePath(); }
    public Path path(AgentBinding binding) {
        return root.resolve(binding.world().toString()).resolve(binding.owner().toString()).resolve(binding.avatar() + ".json");
    }
    public Optional<AgentState> load(AgentBinding binding) throws IOException {
        Path file = path(binding);
        String content;
        try { content = Files.readString(file); }
        catch (java.nio.file.NoSuchFileException absent) { return Optional.empty(); }
        try {
            JsonObject record = JsonParser.parseString(content).getAsJsonObject();
            if (record.get("schema").getAsInt() != 1
                    || !binding.world().toString().equals(record.get("world").getAsString())
                    || !binding.owner().toString().equals(record.get("owner").getAsString())
                    || !binding.avatar().toString().equals(record.get("avatar").getAsString()))
                throw new IllegalArgumentException("Memory binding or schema does not match");
            return Optional.of(AgentState.load(record.getAsJsonObject("state")));
        } catch (RuntimeException invalid) {
            throw new IOException("Cannot read companion memory " + file, invalid);
        }
    }
    public void save(AgentBinding binding, AgentState state) throws IOException {
        Path destination = path(binding); Files.createDirectories(destination.getParent());
        JsonObject record = new JsonObject(); record.addProperty("schema", 1);
        record.addProperty("world", binding.world().toString()); record.addProperty("owner", binding.owner().toString());
        record.addProperty("avatar", binding.avatar().toString()); record.add("state", state.save());
        byte[] encoded = JSON.toJson(record).getBytes(StandardCharsets.UTF_8);
        Path temp = Files.createTempFile(destination.getParent(), ".memory-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
