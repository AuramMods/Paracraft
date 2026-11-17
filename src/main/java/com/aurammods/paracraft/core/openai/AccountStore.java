package com.aurammods.paracraft.core.openai;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** This directory is client-owned, separate from worlds, packets and cognitive snapshots. */
final class AccountStore {
    interface Transaction<T>{T run(JsonObject state)throws Exception;}
    private static final ConcurrentHashMap<Path,ReentrantLock> JVM_LOCKS=new ConcurrentHashMap<>();
    final Path directory;
    private final Path file,lock;
    AccountStore(Path directory)throws IOException{
        this.directory=directory.toAbsolutePath().normalize();Files.createDirectories(this.directory);protect(this.directory,true);file=this.directory.resolve("accounts.json");lock=this.directory.resolve("session.lock");
    }
    <T>T transact(Transaction<T> transaction)throws Exception{
        var mutex=JVM_LOCKS.computeIfAbsent(directory,k->new ReentrantLock());mutex.lock();
        try(var channel=FileChannel.open(lock,StandardOpenOption.CREATE,StandardOpenOption.WRITE)){
            protect(lock,false);try(var held=channel.lock()){
                JsonObject state;
                if(Files.exists(file)){if(Files.isSymbolicLink(file))throw new IOException("Credential file must not be a symbolic link");protect(file,false);try{state=JsonParser.parseString(Files.readString(file)).getAsJsonObject();if(state.get("schema").getAsInt()!=1)throw new IOException("Unsupported Paracraft account schema");}catch(RuntimeException malformed){throw new IOException("Cannot read the private Paracraft account record; preserve it for recovery without sharing its contents");}}
                else{state=new JsonObject();state.addProperty("schema",1);state.addProperty("hostId","urn:uuid:"+UUID.randomUUID());state.addProperty("mode","simulation");state.add("accounts",new JsonObject());}
                String before=Files.exists(file)?state.toString():"";try{return transaction.run(state);}finally{if(!before.equals(state.toString()))write(state);}
            }
        }finally{mutex.unlock();}
    }
    private void write(JsonObject state)throws IOException{
        Path temporary=Files.createTempFile(directory,".accounts-",".tmp");
        try{protect(temporary,false);try(var channel=FileChannel.open(temporary,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)){ByteBuffer bytes=java.nio.charset.StandardCharsets.UTF_8.encode(state.toString());while(bytes.hasRemaining())channel.write(bytes);channel.force(true);}
            try{Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException fallback){Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temporary);}
    }
    static void protect(Path path,boolean directory)throws IOException{
        try{Files.setPosixFilePermissions(path,directory?PosixFilePermissions.fromString("rwx------"):PosixFilePermissions.fromString("rw-------"));}
        catch(UnsupportedOperationException noPosix){var view=Files.getFileAttributeView(path,AclFileAttributeView.class);if(view==null)throw new IOException("This filesystem cannot protect the local account record");var owner=Files.getOwner(path);var entry=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner).setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();view.setAcl(java.util.List.of(entry));}
    }
}
