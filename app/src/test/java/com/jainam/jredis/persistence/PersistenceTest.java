package com.jainam.jredis.persistence;

import com.jainam.jredis.command.CommandContext;
import com.jainam.jredis.command.CommandTable;
import com.jainam.jredis.command.ServerHooks;
import com.jainam.jredis.protocol.Resp;
import com.jainam.jredis.store.Database;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersistenceTest {
    @TempDir
    Path dir;

    private final CommandTable table = CommandTable.standard();

    private Database populated() {
        Database db = new Database(System::currentTimeMillis, new Random(1));
        CommandContext ctx = new CommandContext(db, ServerHooks.NONE, null);
        run(ctx, "SET", "drink", "double double");
        run(ctx, "SET", "binary", "\u0000ÿ\r\n");
        run(ctx, "RPUSH", "cart", "coffee", "donut", "bagel");
        run(ctx, "HSET", "user:42", "name", "Jainam", "city", "Sudbury");
        run(ctx, "SADD", "tags", "a", "b", "c");
        run(ctx, "ZADD", "lb", "100", "alice", "2.5", "bob", "-inf", "carol");
        run(ctx, "SET", "temp", "x", "EX", "1000");
        return db;
    }

    private void run(CommandContext ctx, String... args) {
        Resp r = table.execute(ctx, Arrays.asList(args));
        assertTrue(!(r instanceof Resp.Error), "command failed: " + r);
    }

    /** Dumps a database as the AOF rewrite sees it: a stable way to compare two databases. */
    private static String fingerprint(Database db) {
        List<List<String>> cmds = AppendOnlyFile.commandsFor(db);
        return cmds.stream().map(c -> {
            List<String> sorted = new java.util.ArrayList<>(c);
            java.util.Collections.sort(sorted);
            return String.join(" ", sorted);
        }).sorted().toList().toString();
    }

    @Test
    void snapshotRoundTripPreservesEveryType() throws IOException {
        Database original = populated();
        Path file = dir.resolve("dump.jrdb");
        Snapshot.save(original, file);
        Database loaded = new Database();
        assertEquals(7, Snapshot.load(file, loaded));
        assertEquals(fingerprint(original), fingerprint(loaded));
        assertEquals(original.deadline("temp"), loaded.deadline("temp"));
    }

    @Test
    void corruptedSnapshotIsDetected() throws IOException {
        Path file = dir.resolve("dump.jrdb");
        Snapshot.save(populated(), file);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length / 2] ^= 0x01; // flip one bit in the middle
        Files.write(file, bytes);
        assertThrows(IOException.class, () -> Snapshot.load(file, new Database()));
    }

    @Test
    void aofReplayRebuildsTheDatabase() throws IOException {
        Path file = dir.resolve("appendonly.aof");
        Database db = new Database();
        try (AppendOnlyFile aof = new AppendOnlyFile(file, AppendOnlyFile.Fsync.ALWAYS)) {
            CommandContext ctx = new CommandContext(db, ServerHooks.NONE, aof::append);
            run(ctx, "SET", "a", "1");
            run(ctx, "INCR", "a");
            run(ctx, "RPUSH", "l", "x", "y");
            run(ctx, "LPOP", "l");
            run(ctx, "EXPIRE", "a", "500");
            aof.flush();
        }
        Database replayed = new Database();
        CommandContext ctx = new CommandContext(replayed, ServerHooks.NONE, null);
        long n = AppendOnlyFile.replay(file, cmd -> table.execute(ctx, cmd));
        assertEquals(5, n);
        assertEquals("2", replayed.getString("a"));
        assertEquals(db.deadline("a"), replayed.deadline("a"));
        assertEquals("y", replayed.getList("l", false).get(0));
    }

    @Test
    void truncatedAofTailIsCutOffAndTheRestLoads() throws IOException {
        Path file = dir.resolve("appendonly.aof");
        try (AppendOnlyFile aof = new AppendOnlyFile(file, AppendOnlyFile.Fsync.ALWAYS)) {
            aof.append(List.of("SET", "k1", "v1"));
            aof.append(List.of("SET", "k2", "v2"));
            aof.flush();
        }
        long good = Files.size(file);
        // Simulate a crash in the middle of writing a third command.
        Files.write(file, "*3\r\n$3\r\nSET\r\n$2\r\nk3".getBytes(), StandardOpenOption.APPEND);
        Database db = new Database();
        CommandContext ctx = new CommandContext(db, ServerHooks.NONE, null);
        assertEquals(2, AppendOnlyFile.replay(file, cmd -> table.execute(ctx, cmd)));
        assertEquals(2, db.size());
        assertEquals(good, Files.size(file), "the broken tail was removed");
    }

    @Test
    void rewriteShrinksTheLogButKeepsTheData() throws IOException {
        Path file = dir.resolve("appendonly.aof");
        Database db = new Database();
        try (AppendOnlyFile aof = new AppendOnlyFile(file, AppendOnlyFile.Fsync.NO)) {
            CommandContext ctx = new CommandContext(db, ServerHooks.NONE, aof::append);
            for (int i = 0; i < 1000; i++) {
                run(ctx, "INCR", "counter");
            }
            aof.flush();
            long before = aof.size();
            aof.rewrite(db);
            assertTrue(aof.size() < before / 100, "rewritten log should be tiny");
            run(ctx, "INCR", "counter"); // writes after a rewrite go to the new file
            aof.flush();
        }
        Database replayed = new Database();
        CommandContext ctx = new CommandContext(replayed, ServerHooks.NONE, null);
        AppendOnlyFile.replay(file, cmd -> table.execute(ctx, cmd));
        assertEquals("1001", replayed.getString("counter"));
        try (FileChannel ch = FileChannel.open(file)) {
            assertTrue(ch.size() > 0);
        }
    }
}
