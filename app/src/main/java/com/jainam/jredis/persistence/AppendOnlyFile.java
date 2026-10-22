package com.jainam.jredis.persistence;

import com.jainam.jredis.protocol.Resp;
import com.jainam.jredis.protocol.RespParser;
import com.jainam.jredis.store.Database;
import com.jainam.jredis.store.RList;
import com.jainam.jredis.store.SkipList;
import com.jainam.jredis.store.ZSet;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * The append-only file (AOF): a log of every write command, in the same RESP format clients
 * use. After a crash or restart, replaying the log rebuilds the exact same data.
 *
 * <p><b>Write path.</b> Commands are first collected in an in-memory buffer. The server calls
 * {@link #flush()} after each batch of commands and <em>before</em> sending the replies, so a
 * client never gets "OK" for a write that has not reached the operating system. Whether it has
 * also reached the physical disk depends on the fsync policy:
 * <ul>
 *   <li>{@code always}: fsync after every flush. Safest, slowest.</li>
 *   <li>{@code everysec}: fsync once per second from the timer. At most ~1 s of writes can be
 *       lost if the machine loses power. This is the default, as in Redis.</li>
 *   <li>{@code no}: let the operating system decide. Fastest, least safe.</li>
 * </ul>
 *
 * <p><b>Rewrite (compaction).</b> The log only grows: 1000 INCRs on one key are 1000 entries.
 * {@link #rewrite} writes a new, minimal log from the current data (one SET per key, ...) to a
 * temporary file, then atomically renames it over the old one, so a crash during the rewrite
 * leaves the old log intact.
 */
public final class AppendOnlyFile implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(AppendOnlyFile.class.getName());

    public enum Fsync { ALWAYS, EVERYSEC, NO }

    private final Path path;
    private final Fsync policy;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private FileChannel channel;
    private boolean dirtySinceSync;

    public AppendOnlyFile(Path path, Fsync policy) throws IOException {
        this.path = path;
        this.policy = policy;
        this.channel = open(path);
    }

    private static FileChannel open(Path path) throws IOException {
        return FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
    }

    /** Queues one command. Called by the command layer after a write succeeds. */
    public void append(List<String> command) {
        buffer.writeBytes(Resp.encodeCommand(command));
    }

    /** Writes queued commands to the file (and fsyncs if the policy is ALWAYS). */
    public void flush() throws IOException {
        if (buffer.size() == 0) {
            return;
        }
        ByteBuffer bytes = ByteBuffer.wrap(buffer.toByteArray());
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
        buffer.reset();
        dirtySinceSync = true;
        if (policy == Fsync.ALWAYS) {
            sync();
        }
    }

    /** Called once per second by the server timer. */
    public void everySecond() throws IOException {
        if (policy == Fsync.EVERYSEC) {
            sync();
        }
    }

    private void sync() throws IOException {
        if (dirtySinceSync) {
            channel.force(false);
            dirtySinceSync = false;
        }
    }

    public long size() throws IOException {
        return channel.size();
    }

    @Override
    public void close() throws IOException {
        flush();
        sync();
        channel.close();
    }

    /**
     * Replays a log file, handing each command to {@code executor}. If the file ends in the
     * middle of a command (the server died while writing it), that incomplete tail is cut off,
     * as Redis does with {@code aof-load-truncated yes}. Corruption anywhere else is an error.
     *
     * @return number of commands replayed
     */
    public static long replay(Path path, Consumer<List<String>> executor) throws IOException {
        if (!Files.exists(path)) {
            return 0;
        }
        RespParser parser = new RespParser();
        long commands = 0;
        long consumed = 0;
        byte[] chunk = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(path)) {
            int n;
            while ((n = in.read(chunk)) > 0) {
                parser.feed(chunk, 0, n);
                consumed += n;
                List<String> cmd;
                while ((cmd = parser.next()) != null) {
                    executor.accept(cmd);
                    commands++;
                }
            }
        }
        if (parser.buffered() > 0) {
            long goodLength = consumed - parser.buffered();
            LOG.warning("AOF ends with an incomplete command; truncating " + parser.buffered()
                    + " bytes (file " + path + ")");
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE)) {
                ch.truncate(goodLength);
            }
        }
        return commands;
    }

    /** Replaces the log with the smallest set of commands that rebuilds {@code db}. */
    public void rewrite(Database db) throws IOException {
        flush();
        Path tmp = path.resolveSibling(path.getFileName() + ".rewrite.tmp");
        try (FileChannel out = FileChannel.open(tmp, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteArrayOutputStream chunk = new ByteArrayOutputStream();
            for (List<String> cmd : commandsFor(db)) {
                chunk.writeBytes(Resp.encodeCommand(cmd));
                if (chunk.size() > 64 * 1024) {
                    writeAll(out, chunk);
                }
            }
            writeAll(out, chunk);
            out.force(true);
        }
        channel.close();
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        channel = open(path);
    }

    private static void writeAll(FileChannel out, ByteArrayOutputStream chunk) throws IOException {
        ByteBuffer b = ByteBuffer.wrap(chunk.toByteArray());
        while (b.hasRemaining()) {
            out.write(b);
        }
        chunk.reset();
    }

    /** One or two commands per key that recreate its value and TTL. */
    @SuppressWarnings("unchecked")
    static List<List<String>> commandsFor(Database db) {
        List<List<String>> out = new ArrayList<>();
        for (String key : db.keys()) {
            Object v = db.get(key);
            List<String> cmd = new ArrayList<>();
            switch (v) {
                case String s -> cmd.addAll(List.of("SET", key, s));
                case RList l -> {
                    cmd.addAll(List.of("RPUSH", key));
                    cmd.addAll(l.range(0, l.size() - 1));
                }
                case HashMap<?, ?> h -> {
                    cmd.addAll(List.of("HSET", key));
                    for (Map.Entry<String, String> e : ((HashMap<String, String>) h).entrySet()) {
                        cmd.add(e.getKey());
                        cmd.add(e.getValue());
                    }
                }
                case HashSet<?> s -> {
                    cmd.addAll(List.of("SADD", key));
                    cmd.addAll((HashSet<String>) s);
                }
                case ZSet z -> {
                    cmd.addAll(List.of("ZADD", key));
                    for (SkipList.Node n : z.list().all()) {
                        cmd.add(Double.toString(n.score()));
                        cmd.add(n.member());
                    }
                }
                default -> throw new IllegalStateException("unknown type for key " + key);
            }
            out.add(cmd);
            long deadline = db.deadline(key);
            if (deadline > 0) {
                out.add(List.of("PEXPIREAT", key, Long.toString(deadline)));
            }
        }
        return out;
    }
}
