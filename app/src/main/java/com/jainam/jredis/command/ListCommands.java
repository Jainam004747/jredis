package com.jainam.jredis.command;

import com.jainam.jredis.protocol.Resp;
import com.jainam.jredis.store.Database;
import com.jainam.jredis.store.RList;

import java.util.ArrayList;
import java.util.List;

/** List commands: LPUSH, RPUSH, LPOP, RPOP, LRANGE, LINDEX, ... */
final class ListCommands {
    private ListCommands() { }

    static void register(CommandTable t) {
        t.write("LPUSH", -3, (ctx, a) -> push(ctx.db(), a, true, false));
        t.write("RPUSH", -3, (ctx, a) -> push(ctx.db(), a, false, false));
        t.write("LPUSHX", -3, (ctx, a) -> push(ctx.db(), a, true, true));
        t.write("RPUSHX", -3, (ctx, a) -> push(ctx.db(), a, false, true));
        t.write("LPOP", -2, (ctx, a) -> pop(ctx, a, true));
        t.write("RPOP", -2, (ctx, a) -> pop(ctx, a, false));
        t.read("LLEN", 2, (ctx, a) -> {
            RList list = ctx.db().getList(a.get(1), false);
            return Resp.integer(list == null ? 0 : list.size());
        });
        t.read("LRANGE", 4, (ctx, a) -> {
            RList list = ctx.db().getList(a.get(1), false);
            if (list == null) {
                return Resp.EMPTY_ARRAY;
            }
            int[] r = Args.clampRange(Args.parseLong(a.get(2)), Args.parseLong(a.get(3)), list.size());
            return r == null ? Resp.EMPTY_ARRAY : Resp.bulkArray(list.range(r[0], r[1]));
        });
        t.read("LINDEX", 3, (ctx, a) -> {
            RList list = ctx.db().getList(a.get(1), false);
            if (list == null) {
                return Resp.NIL;
            }
            long i = Args.parseLong(a.get(2));
            if (i < 0) {
                i += list.size();
            }
            return (i < 0 || i >= list.size()) ? Resp.NIL : Resp.bulk(list.get((int) i));
        });
        t.write("LSET", 4, (ctx, a) -> {
            RList list = ctx.db().getList(a.get(1), false);
            if (list == null) {
                throw new CommandException("ERR no such key");
            }
            long i = Args.parseLong(a.get(2));
            if (i < 0) {
                i += list.size();
            }
            if (i < 0 || i >= list.size()) {
                throw new CommandException("ERR index out of range");
            }
            list.set((int) i, a.get(3));
            return Resp.OK;
        });
        t.write("LTRIM", 4, (ctx, a) -> {
            RList list = ctx.db().getList(a.get(1), false);
            if (list != null) {
                int[] r = Args.clampRange(Args.parseLong(a.get(2)), Args.parseLong(a.get(3)), list.size());
                if (r == null) {
                    list.clear();
                } else {
                    list.trim(r[0], r[1]);
                }
                ctx.db().deleteIfEmpty(a.get(1));
            }
            return Resp.OK;
        });
        t.write("LREM", 4, (ctx, a) -> {
            RList list = ctx.db().getList(a.get(1), false);
            if (list == null) {
                return Resp.ZERO;
            }
            int removed = list.remove(a.get(3), Args.parseInt(a.get(2)));
            ctx.db().deleteIfEmpty(a.get(1));
            return Resp.integer(removed);
        });
    }

    private static Resp push(Database db, List<String> a, boolean left, boolean onlyIfExists) {
        RList list = db.getList(a.get(1), !onlyIfExists);
        if (list == null) {
            return Resp.ZERO;
        }
        for (String v : a.subList(2, a.size())) {
            if (left) {
                list.addFirst(v);
            } else {
                list.addLast(v);
            }
        }
        return Resp.integer(list.size());
    }

    /** LPOP key [count] / RPOP key [count] */
    private static Resp pop(CommandContext ctx, List<String> a, boolean left) {
        if (a.size() > 3) {
            throw CommandException.syntax();
        }
        Database db = ctx.db();
        RList list = db.getList(a.get(1), false);
        if (a.size() == 2) {
            if (list == null) {
                ctx.propagateNothing();
                return Resp.NIL;
            }
            String v = left ? list.removeFirst() : list.removeLast();
            db.deleteIfEmpty(a.get(1));
            return Resp.bulk(v);
        }
        long count = Args.parseLong(a.get(2));
        if (count < 0) {
            throw new CommandException("ERR value is out of range, must be positive");
        }
        if (list == null) {
            ctx.propagateNothing();
            return new Resp.Array(null);
        }
        List<String> out = new ArrayList<>();
        for (long i = 0; i < count && !list.isEmpty(); i++) {
            out.add(left ? list.removeFirst() : list.removeLast());
        }
        db.deleteIfEmpty(a.get(1));
        return Resp.bulkArray(out);
    }
}
