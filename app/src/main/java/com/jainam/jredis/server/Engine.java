package com.jainam.jredis.server;

import com.jainam.jredis.command.CommandContext;
import com.jainam.jredis.command.CommandTable;
import com.jainam.jredis.command.ServerHooks;
import com.jainam.jredis.persistence.AppendOnlyFile;
import com.jainam.jredis.persistence.Snapshot;
import com.jainam.jredis.protocol.Resp;
import com.jainam.jredis.store.Database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * The heart of the server, independent of networking: it owns the database, runs commands,
 * writes the append-only file, and does periodic housekeeping.
 *
 * <p>Both network front ends (the NIO event loop and the thread-per-client server) drive the
 * same Engine. That separation is why switching between them is a single flag, and why the
 * engine can be tested without opening a socket.
 *
 * <p>The engine is <b>not thread-safe</b>. The event loop only calls it from one thread; the
 * threaded server holds a lock around every call.
 */
public final class Engine implements ServerHooks, AutoCloseable {
    private static final Logger LOG = Logger.getLogger(Engine.class.getName());

    private final Config config;
    private final Database db;
    private final CommandTable commands = CommandTable.standard();
    private final CommandContext ctx;
    private final AppendOnlyFile aof;
    private final long startMillis = System.currentTimeMillis();
    private long lastSaveSeconds;
    private long commandsProcessed;
    private long connectionsReceived;
    private int connectedClients;
    private long lastSecondTick;

    public Engine(Config config) throws IOException {
        this(config, new Database());
    }

    public Engine(Config config, Database db) throws IOException {
        this.config = config;
        this.db = db;
        Files.createDirectories(config.dir());
        loadData();
        if (config.appendOnly()) {
            this.aof = new AppendOnlyFile(config.aofPath(), config.appendFsync());
            this.ctx = new CommandContext(db, this, aof::append);
        } else {
            this.aof = null;
            this.ctx = new CommandContext(db, this, null);
        }
    }

    /**
     * Restores data at startup. The AOF is preferred when enabled because it is the most
     * complete record; otherwise the last snapshot is used.
     */
    private void loadData() throws IOException {
        long t0 = System.nanoTime();
        if (config.appendOnly() && Files.exists(config.aofPath())) {
            CommandContext replayCtx = new CommandContext(db, ServerHooks.NONE, null);
            long n = AppendOnlyFile.replay(config.aofPath(), cmd -> {
                Resp r = commands.execute(replayCtx, cmd);
                if (r instanceof Resp.Error e) {
                    LOG.warning("AOF replay: " + cmd.get(0) + " failed: " + e.message());
                }
            });
            LOG.info(String.format("Loaded %d commands from %s in %d ms (%d keys)",
                    n, config.aofPath(), (System.nanoTime() - t0) / 1_000_000, db.size()));
        } else if (Files.exists(config.snapshotPath())) {
            int keys = Snapshot.load(config.snapshotPath(), db);
            lastSaveSeconds = Files.getLastModifiedTime(config.snapshotPath()).toMillis() / 1000;
            LOG.info(String.format("Loaded %d keys from %s in %d ms",
                    keys, config.snapshotPath(), (System.nanoTime() - t0) / 1_000_000));
        }
    }

    /** Runs one command. */
    public Resp execute(List<String> args) {
        commandsProcessed++;
        return commands.execute(ctx, args);
    }

    /**
     * Called after a batch of commands and before their replies are sent: makes sure every
     * write in the batch has been handed to the operating system first.
     */
    public void afterBatch() {
        if (aof != null) {
            try {
                aof.flush();
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write the append-only file", e);
            }
        }
    }

    /** Periodic housekeeping, called about 10 times per second. */
    public void cron() {
        db.activeExpireCycle();
        long now = System.currentTimeMillis();
        if (now - lastSecondTick >= 1000) {
            lastSecondTick = now;
            if (aof != null) {
                try {
                    aof.everySecond();
                } catch (IOException e) {
                    LOG.severe("AOF fsync failed: " + e.getMessage());
                }
            }
        }
    }

    public void clientConnected() {
        connectionsReceived++;
        connectedClients++;
    }

    public void clientDisconnected() {
        connectedClients--;
    }

    public Database db() {
        return db;
    }

    public Config config() {
        return config;
    }

    @Override
    public void close() throws IOException {
        if (aof != null) {
            aof.close();
        }
        if (config.saveOnShutdown()) {
            Snapshot.save(db, config.snapshotPath());
        }
    }

    // ---- ServerHooks: features that commands such as SAVE and INFO call back into ----

    @Override
    public String save() {
        try {
            Snapshot.save(db, config.snapshotPath());
            lastSaveSeconds = System.currentTimeMillis() / 1000;
            return null;
        } catch (IOException e) {
            return "ERR snapshot failed: " + e.getMessage();
        }
    }

    @Override
    public String rewriteAof() {
        if (aof == null) {
            return "ERR append-only file is disabled (start with --appendonly yes)";
        }
        try {
            aof.rewrite(db);
            return null;
        } catch (IOException e) {
            return "ERR AOF rewrite failed: " + e.getMessage();
        }
    }

    @Override
    public long lastSave() {
        return lastSaveSeconds;
    }

    @Override
    public String config(String name) {
        return switch (name) {
            case "port" -> Integer.toString(config.port());
            case "appendonly" -> config.appendOnly() ? "yes" : "no";
            case "appendfsync" -> config.appendFsync().name().toLowerCase(Locale.ROOT);
            case "dir" -> config.dir().toAbsolutePath().toString();
            case "dbfilename" -> config.dbFilename();
            case "save" -> "";
            case "databases" -> "1";
            default -> null;
        };
    }

    @Override
    public String info(String section) {
        Runtime rt = Runtime.getRuntime();
        long aofSize = 0;
        try {
            aofSize = aof == null ? 0 : aof.size();
        } catch (IOException ignored) {
            // INFO should never fail because of a stat
        }
        StringBuilder sb = new StringBuilder();
        sb.append("# Server\r\n")
          .append("jredis_version:1.0.0\r\n")
          .append("mode:").append(config.mode().name().toLowerCase(Locale.ROOT)).append("\r\n")
          .append("tcp_port:").append(config.port()).append("\r\n")
          .append("uptime_in_seconds:").append((System.currentTimeMillis() - startMillis) / 1000).append("\r\n")
          .append("java_version:").append(System.getProperty("java.version")).append("\r\n")
          .append("process_id:").append(ManagementFactory.getRuntimeMXBean().getPid()).append("\r\n")
          .append("\r\n# Clients\r\n")
          .append("connected_clients:").append(connectedClients).append("\r\n")
          .append("\r\n# Memory\r\n")
          .append("used_memory:").append(rt.totalMemory() - rt.freeMemory()).append("\r\n")
          .append("max_memory:").append(rt.maxMemory()).append("\r\n")
          .append("\r\n# Persistence\r\n")
          .append("aof_enabled:").append(aof != null ? 1 : 0).append("\r\n")
          .append("aof_current_size:").append(aofSize).append("\r\n")
          .append("rdb_last_save_time:").append(lastSaveSeconds).append("\r\n")
          .append("\r\n# Stats\r\n")
          .append("total_connections_received:").append(connectionsReceived).append("\r\n")
          .append("total_commands_processed:").append(commandsProcessed).append("\r\n")
          .append("expired_keys:").append(db.expiredKeys()).append("\r\n")
          .append("\r\n# Keyspace\r\n");
        if (db.size() > 0) {
            sb.append("db0:keys=").append(db.size()).append(",expires=").append(db.keysWithTtl()).append("\r\n");
        }
        if (section == null || section.equalsIgnoreCase("all") || section.equalsIgnoreCase("default")) {
            return sb.toString();
        }
        // Return just the requested section, e.g. INFO keyspace
        String wanted = "# " + section.toLowerCase(Locale.ROOT);
        for (String block : sb.toString().split("\r\n\r\n")) {
            if (block.toLowerCase(Locale.ROOT).startsWith(wanted)) {
                return block + "\r\n";
            }
        }
        return "";
    }
}
