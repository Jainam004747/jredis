package com.jainam.jredis.command;

import com.jainam.jredis.store.Database;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Everything a command needs while it runs: the database, a way to reach server-level features
 * (SAVE, INFO, ...), and the "propagation" channel to the append-only file.
 *
 * <p><b>Propagation.</b> After a write command succeeds, it is appended to the AOF so it can be
 * replayed after a restart. Most commands are logged exactly as the client sent them. A few must
 * be rewritten first: {@code EXPIRE key 10} means "10 seconds from now", and replaying it
 * tomorrow would give the key a fresh 10 seconds. Those commands call {@link #propagate} with an
 * absolute form such as {@code PEXPIREAT key 1767225600000} instead.
 */
public final class CommandContext {
    private final Database db;
    private final ServerHooks server;
    private final Consumer<List<String>> sink;
    private final List<List<String>> pending = new ArrayList<>();
    private boolean custom;

    public CommandContext(Database db, ServerHooks server, Consumer<List<String>> sink) {
        this.db = db;
        this.server = server;
        this.sink = sink;
    }

    public Database db() {
        return db;
    }

    public ServerHooks server() {
        return server;
    }

    /** Records a command to log instead of (or in addition to) the original. */
    public void propagate(List<String> command) {
        custom = true;
        pending.add(command);
    }

    /** Marks this run as "nothing to log", e.g. SET ... NX when the key already existed. */
    public void propagateNothing() {
        custom = true;
    }

    void beginCommand() {
        pending.clear();
        custom = false;
    }

    void commitPropagation(List<String> original) {
        if (sink == null) {
            return;
        }
        if (!custom) {
            sink.accept(original);
        } else {
            pending.forEach(sink);
        }
        pending.clear();
    }
}
