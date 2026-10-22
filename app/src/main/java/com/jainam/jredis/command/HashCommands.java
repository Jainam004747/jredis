package com.jainam.jredis.command;

import com.jainam.jredis.protocol.Resp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Hash commands: HSET, HGET, HGETALL, HDEL, HINCRBY, ... A hash is a map inside a key. */
final class HashCommands {
    private HashCommands() { }

    static void register(CommandTable t) {
        t.write("HSET", -4, HashCommands::hset);
        t.write("HMSET", -4, (ctx, a) -> {
            hset(ctx, a);
            return Resp.OK;
        });
        t.write("HSETNX", 4, (ctx, a) -> {
            HashMap<String, String> h = ctx.db().getHash(a.get(1), true);
            if (h.containsKey(a.get(2))) {
                ctx.propagateNothing();
                return Resp.ZERO;
            }
            h.put(a.get(2), a.get(3));
            return Resp.ONE;
        });
        t.read("HGET", 3, (ctx, a) -> {
            HashMap<String, String> h = ctx.db().getHash(a.get(1), false);
            return Resp.bulk(h == null ? null : h.get(a.get(2)));
        });
        t.read("HMGET", -3, (ctx, a) -> {
            HashMap<String, String> h = ctx.db().getHash(a.get(1), false);
            List<String> out = new ArrayList<>();
            for (String f : a.subList(2, a.size())) {
                out.add(h == null ? null : h.get(f));
            }
            return Resp.bulkArray(out);
        });
        t.write("HDEL", -3, (ctx, a) -> {
            HashMap<String, String> h = ctx.db().getHash(a.get(1), false);
            if (h == null) {
                return Resp.ZERO;
            }
            long n = 0;
            for (String f : a.subList(2, a.size())) {
                if (h.remove(f) != null) {
                    n++;
                }
            }
            ctx.db().deleteIfEmpty(a.get(1));
            return Resp.integer(n);
        });
        t.read("HGETALL", 2, (ctx, a) -> {
            HashMap<String, String> h = ctx.db().getHash(a.get(1), false);
            List<String> out = new ArrayList<>();
            if (h != null) {
                for (Map.Entry<String, String> e : new TreeMap<>(h).entrySet()) {
                    out.add(e.getKey());
                    out.add(e.getValue());
                }
            }
            return Resp.bulkArray(out);
        });
        t.read("HKEYS", 2, (ctx, a) -> {
            HashMap<String, String> h = ctx.db().getHash(a.get(1), false);
            return Resp.bulkArray(h == null ? List.of() : new TreeMap<>(h).keySet());
        });
        t.read("HVALS", 2, (ctx, a) -> {
            HashMap<String, String> h = ctx.db().getHash(a.get(1), false);
            return Resp.bulkArray(h == null ? List.of() : new TreeMap<>(h).values());
        });
        t.read("HLEN", 2, (ctx, a) -> {
            HashMap<String, String> h = ctx.db().getHash(a.get(1), false);
            return Resp.integer(h == null ? 0 : h.size());
        });
        t.read("HEXISTS", 3, (ctx, a) -> {
            HashMap<String, String> h = ctx.db().getHash(a.get(1), false);
            return Resp.bool(h != null && h.containsKey(a.get(2)));
        });
        t.write("HINCRBY", 4, (ctx, a) -> {
            HashMap<String, String> h = ctx.db().getHash(a.get(1), true);
            String cur = h.get(a.get(2));
            long base;
            try {
                base = cur == null ? 0 : Long.parseLong(cur);
            } catch (NumberFormatException e) {
                throw new CommandException("ERR hash value is not an integer");
            }
            long result;
            try {
                result = Math.addExact(base, Args.parseLong(a.get(3)));
            } catch (ArithmeticException e) {
                throw new CommandException("ERR increment or decrement would overflow");
            }
            h.put(a.get(2), Long.toString(result));
            return Resp.integer(result);
        });
    }

    private static Resp hset(CommandContext ctx, List<String> a) {
        if (a.size() % 2 != 0) {
            throw new CommandException("ERR wrong number of arguments for '" + a.get(0).toLowerCase() + "' command");
        }
        HashMap<String, String> h = ctx.db().getHash(a.get(1), true);
        long added = 0;
        for (int i = 2; i < a.size(); i += 2) {
            if (h.put(a.get(i), a.get(i + 1)) == null) {
                added++;
            }
        }
        return Resp.integer(added);
    }
}
