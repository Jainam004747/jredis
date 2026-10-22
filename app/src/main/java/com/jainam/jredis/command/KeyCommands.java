package com.jainam.jredis.command;

import com.jainam.jredis.protocol.Resp;
import com.jainam.jredis.store.Database;

import java.util.ArrayList;
import java.util.List;

/** Commands that work on keys of any type: DEL, EXISTS, TYPE, KEYS, EXPIRE, TTL, RENAME, ... */
final class KeyCommands {
    private KeyCommands() { }

    static void register(CommandTable t) {
        t.write("DEL", -2, KeyCommands::del);
        t.write("UNLINK", -2, KeyCommands::del);
        t.read("EXISTS", -2, (ctx, a) -> {
            long n = 0;
            for (String key : a.subList(1, a.size())) {
                if (ctx.db().exists(key)) {
                    n++;
                }
            }
            return Resp.integer(n);
        });
        t.read("TYPE", 2, (ctx, a) -> Resp.simple(ctx.db().type(a.get(1))));
        t.read("KEYS", 2, (ctx, a) -> {
            List<String> out = new ArrayList<>();
            for (String key : ctx.db().keys()) {
                if (Glob.matches(a.get(1), key)) {
                    out.add(key);
                }
            }
            out.sort(null); // sorted output is friendlier for people and tests
            return Resp.bulkArray(out);
        });
        t.read("DBSIZE", 1, (ctx, a) -> Resp.integer(ctx.db().size()));
        t.write("FLUSHALL", -1, KeyCommands::flush);
        t.write("FLUSHDB", -1, KeyCommands::flush);

        t.write("EXPIRE", 3, (ctx, a) -> expire(ctx, a, 1000, false));
        t.write("PEXPIRE", 3, (ctx, a) -> expire(ctx, a, 1, false));
        t.write("EXPIREAT", 3, (ctx, a) -> expire(ctx, a, 1000, true));
        t.write("PEXPIREAT", 3, (ctx, a) -> expire(ctx, a, 1, true));
        t.read("TTL", 2, (ctx, a) -> ttl(ctx.db(), a.get(1), 1000));
        t.read("PTTL", 2, (ctx, a) -> ttl(ctx.db(), a.get(1), 1));
        t.read("EXPIRETIME", 2, (ctx, a) -> expireTime(ctx.db(), a.get(1), 1000));
        t.read("PEXPIRETIME", 2, (ctx, a) -> expireTime(ctx.db(), a.get(1), 1));
        t.write("PERSIST", 2, (ctx, a) -> {
            boolean changed = ctx.db().persist(a.get(1));
            if (!changed) {
                ctx.propagateNothing();
            }
            return Resp.bool(changed);
        });
        t.write("RENAME", 3, (ctx, a) -> {
            Database db = ctx.db();
            String from = a.get(1);
            String to = a.get(2);
            Object value = db.get(from);
            if (value == null) {
                throw new CommandException("ERR no such key");
            }
            long deadline = db.deadline(from);
            db.delete(from);
            db.put(to, value);
            if (deadline > 0) {
                db.expireAt(to, deadline);
            }
            return Resp.OK;
        });
    }

    private static Resp del(CommandContext ctx, List<String> a) {
        long n = 0;
        for (String key : a.subList(1, a.size())) {
            if (ctx.db().delete(key)) {
                n++;
            }
        }
        return Resp.integer(n);
    }

    private static Resp flush(CommandContext ctx, List<String> a) {
        ctx.db().clear();
        ctx.propagate(List.of("FLUSHALL"));
        return Resp.OK;
    }

    /**
     * EXPIRE family. {@code unitMillis} is 1000 for seconds and 1 for milliseconds;
     * {@code absolute} is true for the *AT variants.
     */
    private static Resp expire(CommandContext ctx, List<String> a, long unitMillis, boolean absolute) {
        Database db = ctx.db();
        String key = a.get(1);
        long amount = Args.parseLong(a.get(2));
        long deadline;
        try {
            deadline = Math.multiplyExact(amount, unitMillis);
            if (!absolute) {
                deadline = Math.addExact(db.now(), deadline);
            }
        } catch (ArithmeticException e) {
            throw new CommandException("ERR invalid expire time in '" + a.get(0).toLowerCase() + "' command");
        }
        if (!db.exists(key)) {
            ctx.propagateNothing();
            return Resp.ZERO;
        }
        db.expireAt(key, deadline);
        // Log an absolute time so replaying the file later gives the same deadline.
        if (db.exists(key)) {
            ctx.propagate(List.of("PEXPIREAT", key, Long.toString(deadline)));
        } else {
            ctx.propagate(List.of("DEL", key));
        }
        return Resp.ONE;
    }

    private static Resp ttl(Database db, String key, long unitMillis) {
        if (!db.exists(key)) {
            return Resp.integer(-2);
        }
        long deadline = db.deadline(key);
        if (deadline < 0) {
            return Resp.integer(-1);
        }
        long remaining = deadline - db.now();
        // Round up, like Redis: a key with 1500 ms left reports TTL 2.
        return Resp.integer((remaining + unitMillis - 1) / unitMillis);
    }

    private static Resp expireTime(Database db, String key, long unitMillis) {
        if (!db.exists(key)) {
            return Resp.integer(-2);
        }
        long deadline = db.deadline(key);
        return Resp.integer(deadline < 0 ? -1 : deadline / unitMillis);
    }
}
