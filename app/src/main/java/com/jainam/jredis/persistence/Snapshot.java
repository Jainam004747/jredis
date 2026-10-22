package com.jainam.jredis.persistence;

import com.jainam.jredis.store.Database;
import com.jainam.jredis.store.RList;
import com.jainam.jredis.store.SkipList;
import com.jainam.jredis.store.ZSet;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.CheckedInputStream;
import java.util.zip.CheckedOutputStream;

/**
 * A point-in-time copy of the whole database in a compact binary file (jredis's version of
 * Redis's RDB file). Snapshots load much faster than replaying a long AOF.
 *
 * <pre>
 * File layout (all numbers big-endian):
 *   "JRDB"                      magic bytes: is this our file at all?
 *   int     version             lets a future format change stay readable
 *   repeated entries:
 *     byte  type                1=string 2=list 3=hash 4=set 5=zset
 *     long  expireAt            absolute ms, or -1 for no TTL
 *     str   key                 str = int length + bytes
 *     ...   value               depends on type (counts followed by elements)
 *   byte    0xFF                end marker
 *   long    CRC32               checksum of every byte before it: detects corruption
 * </pre>
 *
 * <p>Saving writes to a temporary file, fsyncs it, then atomically renames it over the old
 * snapshot. A crash at any moment therefore leaves either the complete old snapshot or the
 * complete new one, never a half-written file.
 */
public final class Snapshot {
    private static final byte[] MAGIC = {'J', 'R', 'D', 'B'};
    private static final int VERSION = 1;
    private static final int STRING = 1;
    private static final int LIST = 2;
    private static final int HASH = 3;
    private static final int SET = 4;
    private static final int ZSET = 5;
    private static final int END = 0xFF;

    private Snapshot() { }

    @SuppressWarnings("unchecked")
    public static void save(Database db, Path path) throws IOException {
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        CRC32 crc = new CRC32();
        try (OutputStream file = Files.newOutputStream(tmp)) {
            DataOutputStream out = new DataOutputStream(
                    new CheckedOutputStream(new BufferedOutputStream(file), crc));
            out.write(MAGIC);
            out.writeInt(VERSION);
            for (String key : db.keys()) {
                Object v = db.get(key);
                switch (v) {
                    case String s -> {
                        header(out, STRING, db.deadline(key), key);
                        writeStr(out, s);
                    }
                    case RList l -> {
                        header(out, LIST, db.deadline(key), key);
                        out.writeInt(l.size());
                        for (int i = 0; i < l.size(); i++) {
                            writeStr(out, l.get(i));
                        }
                    }
                    case HashMap<?, ?> h -> {
                        header(out, HASH, db.deadline(key), key);
                        out.writeInt(h.size());
                        for (Map.Entry<String, String> e : ((HashMap<String, String>) h).entrySet()) {
                            writeStr(out, e.getKey());
                            writeStr(out, e.getValue());
                        }
                    }
                    case HashSet<?> s -> {
                        header(out, SET, db.deadline(key), key);
                        out.writeInt(s.size());
                        for (String m : (HashSet<String>) s) {
                            writeStr(out, m);
                        }
                    }
                    case ZSet z -> {
                        header(out, ZSET, db.deadline(key), key);
                        out.writeInt(z.size());
                        for (SkipList.Node n : z.list().all()) {
                            writeStr(out, n.member());
                            out.writeDouble(n.score());
                        }
                    }
                    default -> throw new IllegalStateException("unknown type for key " + key);
                }
            }
            out.writeByte(END);
            out.flush();
            // The checksum itself is written outside the checked stream.
            new DataOutputStream(file).writeLong(crc.getValue());
            file.flush();
        }
        try (var ch = java.nio.channels.FileChannel.open(tmp, java.nio.file.StandardOpenOption.WRITE)) {
            ch.force(true);
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Loads a snapshot into an empty database. Returns the number of keys loaded. */
    public static int load(Path path, Database db) throws IOException {
        CRC32 crc = new CRC32();
        int keys = 0;
        try (InputStream file = new BufferedInputStream(Files.newInputStream(path))) {
            DataInputStream in = new DataInputStream(new CheckedInputStream(file, crc));
            byte[] magic = in.readNBytes(4);
            if (!java.util.Arrays.equals(magic, MAGIC)) {
                throw new IOException("not a jredis snapshot: " + path);
            }
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("unsupported snapshot version " + version);
            }
            long now = db.now();
            while (true) {
                int type = in.readUnsignedByte();
                if (type == END) {
                    break;
                }
                long expireAt = in.readLong();
                String key = readStr(in);
                Object value = switch (type) {
                    case STRING -> readStr(in);
                    case LIST -> {
                        RList l = new RList();
                        for (int i = in.readInt(); i > 0; i--) {
                            l.addLast(readStr(in));
                        }
                        yield l;
                    }
                    case HASH -> {
                        HashMap<String, String> h = new HashMap<>();
                        for (int i = in.readInt(); i > 0; i--) {
                            h.put(readStr(in), readStr(in));
                        }
                        yield h;
                    }
                    case SET -> {
                        HashSet<String> s = new HashSet<>();
                        for (int i = in.readInt(); i > 0; i--) {
                            s.add(readStr(in));
                        }
                        yield s;
                    }
                    case ZSET -> {
                        ZSet z = new ZSet();
                        for (int i = in.readInt(); i > 0; i--) {
                            String member = readStr(in);
                            z.add(member, in.readDouble());
                        }
                        yield z;
                    }
                    default -> throw new IOException("corrupt snapshot: unknown type " + type);
                };
                if (expireAt > 0 && expireAt <= now) {
                    continue; // expired while the server was down: skip it
                }
                db.put(key, value);
                if (expireAt > 0) {
                    db.expireAt(key, expireAt);
                }
                keys++;
            }
            long expected = crc.getValue();
            long stored = new DataInputStream(file).readLong();
            if (stored != expected) {
                throw new IOException("snapshot checksum mismatch: file is corrupt");
            }
        } catch (EOFException e) {
            throw new IOException("snapshot is truncated: " + path, e);
        }
        return keys;
    }

    private static void header(DataOutputStream out, int type, long expireAt, String key) throws IOException {
        out.writeByte(type);
        out.writeLong(expireAt);
        writeStr(out, key);
    }

    private static void writeStr(DataOutputStream out, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.ISO_8859_1);
        out.writeInt(b.length);
        out.write(b);
    }

    private static String readStr(DataInputStream in) throws IOException {
        int len = in.readInt();
        if (len < 0 || len > 512 * 1024 * 1024) {
            throw new IOException("corrupt snapshot: bad string length " + len);
        }
        return new String(in.readNBytes(len), StandardCharsets.ISO_8859_1);
    }
}
