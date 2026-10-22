package com.jainam.jredis.server;

import com.jainam.jredis.persistence.AppendOnlyFile.Fsync;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Server settings, parsed from command-line flags.
 *
 * <pre>
 *   --port 6380               TCP port (0 = pick a free one; used by tests)
 *   --mode eventloop|threads  single-threaded NIO event loop (default) or thread per client
 *   --dir .                   where the AOF and snapshot files live
 *   --appendonly yes|no       log every write to the append-only file (default yes)
 *   --appendfsync everysec|always|no
 *   --dbfilename dump.jrdb    snapshot file name
 *   --save-on-shutdown yes|no write a snapshot when the server stops (default yes)
 * </pre>
 */
public record Config(int port, Mode mode, Path dir, boolean appendOnly, Fsync appendFsync,
                     String dbFilename, String aofFilename, boolean saveOnShutdown) {

    public enum Mode { EVENTLOOP, THREADS }

    public static Config defaults() {
        return new Config(6380, Mode.EVENTLOOP, Path.of("."), true, Fsync.EVERYSEC,
                "dump.jrdb", "appendonly.aof", true);
    }

    public static Config parse(String[] args) {
        Config c = defaults();
        for (int i = 0; i < args.length; i++) {
            String flag = args[i];
            if (!flag.startsWith("--") || i + 1 >= args.length) {
                throw new IllegalArgumentException("expected --flag value, got: " + flag);
            }
            String v = args[++i];
            c = switch (flag) {
                case "--port" -> c.withPort(parsePort(v));
                case "--mode" -> new Config(c.port, Mode.valueOf(v.toUpperCase(Locale.ROOT)), c.dir, c.appendOnly,
                        c.appendFsync, c.dbFilename, c.aofFilename, c.saveOnShutdown);
                case "--dir" -> new Config(c.port, c.mode, Path.of(v), c.appendOnly, c.appendFsync,
                        c.dbFilename, c.aofFilename, c.saveOnShutdown);
                case "--appendonly" -> new Config(c.port, c.mode, c.dir, yesNo(v), c.appendFsync,
                        c.dbFilename, c.aofFilename, c.saveOnShutdown);
                case "--appendfsync" -> new Config(c.port, c.mode, c.dir, c.appendOnly,
                        Fsync.valueOf(v.toUpperCase(Locale.ROOT)), c.dbFilename, c.aofFilename, c.saveOnShutdown);
                case "--dbfilename" -> new Config(c.port, c.mode, c.dir, c.appendOnly, c.appendFsync,
                        v, c.aofFilename, c.saveOnShutdown);
                case "--save-on-shutdown" -> new Config(c.port, c.mode, c.dir, c.appendOnly, c.appendFsync,
                        c.dbFilename, c.aofFilename, yesNo(v));
                default -> throw new IllegalArgumentException("unknown flag: " + flag);
            };
        }
        return c;
    }

    public Config withPort(int newPort) {
        return new Config(newPort, mode, dir, appendOnly, appendFsync, dbFilename, aofFilename, saveOnShutdown);
    }

    public Path aofPath() {
        return dir.resolve(aofFilename);
    }

    public Path snapshotPath() {
        return dir.resolve(dbFilename);
    }

    private static int parsePort(String v) {
        try {
            int p = Integer.parseInt(v);
            if (p < 0 || p > 65535) {
                throw new IllegalArgumentException("port must be 0-65535: " + v);
            }
            return p;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("port must be a number: " + v, e);
        }
    }

    private static boolean yesNo(String v) {
        return switch (v.toLowerCase(Locale.ROOT)) {
            case "yes", "true" -> true;
            case "no", "false" -> false;
            default -> throw new IllegalArgumentException("expected yes or no, got: " + v);
        };
    }
}
