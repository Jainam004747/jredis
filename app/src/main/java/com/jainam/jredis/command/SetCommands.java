package com.jainam.jredis.command;

import com.jainam.jredis.protocol.Resp;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Set commands: SADD, SREM, SMEMBERS, SISMEMBER, SINTER, SUNION, SDIFF, ... */
final class SetCommands {
    private SetCommands() { }

    static void register(CommandTable t) {
        t.write("SADD", -3, (ctx, a) -> {
            HashSet<String> s = ctx.db().getSet(a.get(1), true);
            long n = 0;
            for (String m : a.subList(2, a.size())) {
                if (s.add(m)) {
                    n++;
                }
            }
            return Resp.integer(n);
        });
        t.write("SREM", -3, (ctx, a) -> {
            HashSet<String> s = ctx.db().getSet(a.get(1), false);
            if (s == null) {
                return Resp.ZERO;
            }
            long n = 0;
            for (String m : a.subList(2, a.size())) {
                if (s.remove(m)) {
                    n++;
                }
            }
            ctx.db().deleteIfEmpty(a.get(1));
            return Resp.integer(n);
        });
        t.read("SMEMBERS", 2, (ctx, a) -> {
            HashSet<String> s = ctx.db().getSet(a.get(1), false);
            return Resp.bulkArray(s == null ? List.of() : new TreeSet<>(s));
        });
        t.read("SISMEMBER", 3, (ctx, a) -> {
            HashSet<String> s = ctx.db().getSet(a.get(1), false);
            return Resp.bool(s != null && s.contains(a.get(2)));
        });
        t.read("SCARD", 2, (ctx, a) -> {
            HashSet<String> s = ctx.db().getSet(a.get(1), false);
            return Resp.integer(s == null ? 0 : s.size());
        });
        t.read("SINTER", -2, (ctx, a) -> {
            Set<String> result = null;
            for (String key : a.subList(1, a.size())) {
                HashSet<String> s = ctx.db().getSet(key, false);
                if (s == null) {
                    return Resp.EMPTY_ARRAY; // intersection with an empty set is empty
                }
                if (result == null) {
                    result = new TreeSet<>(s);
                } else {
                    result.retainAll(s);
                }
            }
            return Resp.bulkArray(result);
        });
        t.read("SUNION", -2, (ctx, a) -> {
            Set<String> result = new TreeSet<>();
            for (String key : a.subList(1, a.size())) {
                HashSet<String> s = ctx.db().getSet(key, false);
                if (s != null) {
                    result.addAll(s);
                }
            }
            return Resp.bulkArray(result);
        });
        t.read("SDIFF", -2, (ctx, a) -> {
            HashSet<String> first = ctx.db().getSet(a.get(1), false);
            Set<String> result = first == null ? new TreeSet<>() : new TreeSet<>(first);
            for (String key : a.subList(2, a.size())) {
                HashSet<String> s = ctx.db().getSet(key, false);
                if (s != null) {
                    result.removeAll(s);
                }
            }
            return Resp.bulkArray(result);
        });
    }
}
