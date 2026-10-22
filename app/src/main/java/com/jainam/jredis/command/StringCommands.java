package com.jainam.jredis.command;

import com.jainam.jredis.protocol.Resp;
import com.jainam.jredis.store.Database;

import java.util.ArrayList;
import java.util.List;

/** String commands: SET, GET, INCR, APPEND, MSET, ... */
final class StringCommands {
    private StringCommands() { }

    static void register(CommandTable t) {
        t.write("SET", -3, StringCommands::set);
        t.read("GET", 2, (ctx, a) -> Resp.bulk(ctx.db().getString(a.get(1))));
        t.write("GETDEL", 2, (ctx, a) -> {
            String v = ctx.db().getString(a.get(1));
            if (v == null) {
                ctx.propagateNothing();
            } else {
                ctx.db().delete(a.get(1));
                ctx.propagate(List.of("DEL", a.get(1)));
            }
            return Resp.bulk(v);
        });
        t.write("GETSET", 3, (ctx, a) -> {
            String old = ctx.db().getString(a.get(1));
            ctx.db().put(a.get(1), a.get(2));
            return Resp.bulk(old);
        });
        t.write("SETNX", 3, (ctx, a) -> {
            if (ctx.db().exists(a.get(1))) {
                ctx.propagateNothing();
                return Resp.ZERO;
            }
            ctx.db().put(a.get(1), a.get(2));
            return Resp.ONE;
        });
        t.write("SETEX", 4, (ctx, a) -> setWithTtl(ctx, a, 1000));
        t.write("PSETEX", 4, (ctx, a) -> setWithTtl(ctx, a, 1));
        t.read("MGET", -2, (ctx, a) -> {
            List<String> out = new ArrayList<>();
            for (String key : a.subList(1, a.size())) {
                Object v = ctx.db().get(key);
                out.add(v instanceof String s ? s : null); // wrong types read as nil in MGET
            }
            return Resp.bulkArray(out);
        });
        t.write("MSET", -3, (ctx, a) -> {
            if (a.size() % 2 == 0) {
                throw new CommandException("ERR wrong number of arguments for 'mset' command");
            }
            for (int i = 1; i < a.size(); i += 2) {
                ctx.db().put(a.get(i), a.get(i + 1));
            }
            return Resp.OK;
        });
        t.write("INCR", 2, (ctx, a) -> incrBy(ctx.db(), a.get(1), 1));
        t.write("DECR", 2, (ctx, a) -> incrBy(ctx.db(), a.get(1), -1));
        t.write("INCRBY", 3, (ctx, a) -> incrBy(ctx.db(), a.get(1), Args.parseLong(a.get(2))));
        t.write("DECRBY", 3, (ctx, a) -> {
            long by = Args.parseLong(a.get(2));
            if (by == Long.MIN_VALUE) {
                throw new CommandException("ERR decrement would overflow");
            }
            return incrBy(ctx.db(), a.get(1), -by);
        });
        t.write("INCRBYFLOAT", 3, (ctx, a) -> {
            Database db = ctx.db();
            String cur = db.getString(a.get(1));
            double base = cur == null ? 0 : parseFloatValue(cur);
            double result = base + Args.parseDouble(a.get(2));
            if (Double.isInfinite(result) || Double.isNaN(result)) {
                throw new CommandException("ERR increment would produce NaN or Infinity");
            }
            String formatted = Args.formatDouble(result);
            db.putKeepTtl(a.get(1), formatted);
            // Log the final value, so replay does not depend on floating-point rounding.
            ctx.propagate(List.of("SET", a.get(1), formatted, "KEEPTTL"));
            return Resp.bulk(formatted);
        });
        t.write("APPEND", 3, (ctx, a) -> {
            Database db = ctx.db();
            String cur = db.getString(a.get(1));
            String next = (cur == null ? "" : cur) + a.get(2);
            db.putKeepTtl(a.get(1), next);
            return Resp.integer(next.length());
        });
        t.read("STRLEN", 2, (ctx, a) -> {
            String v = ctx.db().getString(a.get(1));
            return Resp.integer(v == null ? 0 : v.length());
        });
        t.read("GETRANGE", 4, (ctx, a) -> {
            String v = ctx.db().getString(a.get(1));
            if (v == null) {
                return Resp.bulk("");
            }
            int[] r = Args.clampRange(Args.parseLong(a.get(2)), Args.parseLong(a.get(3)), v.length());
            return Resp.bulk(r == null ? "" : v.substring(r[0], r[1] + 1));
        });
    }

    /**
     * SET key value [NX | XX] [GET] [EX s | PX ms | EXAT s | PXAT ms | KEEPTTL]
     */
    private static Resp set(CommandContext ctx, List<String> a) {
        Database db = ctx.db();
        String key = a.get(1);
        String value = a.get(2);
        boolean nx = false;
        boolean xx = false;
        boolean get = false;
        boolean keepTtl = false;
        long deadline = -1;
        for (int i = 3; i < a.size(); i++) {
            String opt = a.get(i);
            boolean hasNext = i + 1 < a.size();
            if (Args.is(opt, "NX")) {
                nx = true;
            } else if (Args.is(opt, "XX")) {
                xx = true;
            } else if (Args.is(opt, "GET")) {
                get = true;
            } else if (Args.is(opt, "KEEPTTL")) {
                keepTtl = true;
            } else if (hasNext && deadline < 0 && (Args.is(opt, "EX") || Args.is(opt, "PX")
                    || Args.is(opt, "EXAT") || Args.is(opt, "PXAT"))) {
                long n = Args.parseLong(a.get(++i));
                if (n <= 0) {
                    throw new CommandException("ERR invalid expire time in 'set' command");
                }
                String o = opt.toUpperCase();
                try {
                    deadline = switch (o) {
                        case "EX" -> Math.addExact(db.now(), Math.multiplyExact(n, 1000L));
                        case "PX" -> Math.addExact(db.now(), n);
                        case "EXAT" -> Math.multiplyExact(n, 1000L);
                        default -> n;
                    };
                } catch (ArithmeticException e) {
                    throw new CommandException("ERR invalid expire time in 'set' command");
                }
            } else {
                throw CommandException.syntax();
            }
        }
        if ((nx && xx) || (keepTtl && deadline >= 0)) {
            throw CommandException.syntax();
        }
        String old = null;
        if (get) {
            old = db.getString(key); // throws WRONGTYPE if the key is not a string
        }
        boolean exists = db.exists(key);
        if ((nx && exists) || (xx && !exists)) {
            ctx.propagateNothing();
            return get ? Resp.bulk(old) : Resp.NIL;
        }
        if (keepTtl) {
            db.putKeepTtl(key, value);
        } else {
            db.put(key, value);
        }
        if (deadline >= 0) {
            db.expireAt(key, deadline);
            ctx.propagate(List.of("SET", key, value));
            ctx.propagate(List.of("PEXPIREAT", key, Long.toString(deadline)));
        }
        return get ? Resp.bulk(old) : Resp.OK;
    }

    private static Resp setWithTtl(CommandContext ctx, List<String> a, long unitMillis) {
        long amount = Args.parseLong(a.get(2));
        if (amount <= 0) {
            throw new CommandException("ERR invalid expire time in '" + a.get(0).toLowerCase() + "' command");
        }
        long deadline = ctx.db().now() + amount * unitMillis;
        ctx.db().put(a.get(1), a.get(3));
        ctx.db().expireAt(a.get(1), deadline);
        ctx.propagate(List.of("SET", a.get(1), a.get(3)));
        ctx.propagate(List.of("PEXPIREAT", a.get(1), Long.toString(deadline)));
        return Resp.OK;
    }

    private static Resp incrBy(Database db, String key, long by) {
        String cur = db.getString(key);
        long base = 0;
        if (cur != null) {
            try {
                base = Long.parseLong(cur);
            } catch (NumberFormatException e) {
                throw CommandException.notInteger();
            }
        }
        long result;
        try {
            result = Math.addExact(base, by);
        } catch (ArithmeticException e) {
            throw new CommandException("ERR increment or decrement would overflow");
        }
        db.putKeepTtl(key, Long.toString(result)); // INCR keeps the key's TTL
        return Resp.integer(result);
    }

    private static double parseFloatValue(String s) {
        try {
            return Args.parseDouble(s);
        } catch (CommandException e) {
            throw new CommandException("ERR value is not a valid float");
        }
    }
}
