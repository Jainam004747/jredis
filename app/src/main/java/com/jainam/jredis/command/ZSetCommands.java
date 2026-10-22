package com.jainam.jredis.command;

import com.jainam.jredis.protocol.Resp;
import com.jainam.jredis.store.SkipList;
import com.jainam.jredis.store.SkipList.ScoreRange;
import com.jainam.jredis.store.ZSet;

import java.util.ArrayList;
import java.util.List;

/** Sorted-set commands: ZADD, ZRANGE, ZRANK, ZSCORE, ZINCRBY, ZRANGEBYSCORE, ... */
final class ZSetCommands {
    private ZSetCommands() { }

    static void register(CommandTable t) {
        t.write("ZADD", -4, ZSetCommands::zadd);
        t.write("ZINCRBY", 4, (ctx, a) -> {
            ZSet z = ctx.db().getZSet(a.get(1), true);
            Double cur = z.score(a.get(3));
            double next = (cur == null ? 0 : cur) + Args.parseDouble(a.get(2));
            if (Double.isNaN(next)) {
                throw new CommandException("ERR resulting score is not a number (NaN)");
            }
            z.add(a.get(3), next);
            String formatted = Args.formatDouble(next);
            ctx.propagate(List.of("ZADD", a.get(1), formatted, a.get(3)));
            return Resp.bulk(formatted);
        });
        t.write("ZREM", -3, (ctx, a) -> {
            ZSet z = ctx.db().getZSet(a.get(1), false);
            if (z == null) {
                return Resp.ZERO;
            }
            long n = 0;
            for (String m : a.subList(2, a.size())) {
                if (z.remove(m)) {
                    n++;
                }
            }
            ctx.db().deleteIfEmpty(a.get(1));
            return Resp.integer(n);
        });
        t.read("ZSCORE", 3, (ctx, a) -> {
            ZSet z = ctx.db().getZSet(a.get(1), false);
            Double s = z == null ? null : z.score(a.get(2));
            return Resp.bulk(s == null ? null : Args.formatDouble(s));
        });
        t.read("ZCARD", 2, (ctx, a) -> {
            ZSet z = ctx.db().getZSet(a.get(1), false);
            return Resp.integer(z == null ? 0 : z.size());
        });
        t.read("ZRANK", 3, (ctx, a) -> rank(ctx, a, false));
        t.read("ZREVRANK", 3, (ctx, a) -> rank(ctx, a, true));
        t.read("ZRANGE", -4, (ctx, a) -> range(ctx, a, false));
        t.read("ZREVRANGE", -4, (ctx, a) -> range(ctx, a, true));
        t.read("ZRANGEBYSCORE", -4, ZSetCommands::rangeByScore);
        t.read("ZCOUNT", 4, (ctx, a) -> {
            ZSet z = ctx.db().getZSet(a.get(1), false);
            ScoreRange r = Args.parseRange(a.get(2), a.get(3));
            return Resp.integer(z == null ? 0 : z.list().rangeByScore(r).size());
        });
    }

    /** ZADD key [NX|XX] [CH] score member [score member ...] */
    private static Resp zadd(CommandContext ctx, List<String> a) {
        boolean nx = false;
        boolean xx = false;
        boolean ch = false;
        int i = 2;
        for (; i < a.size(); i++) {
            String opt = a.get(i);
            if (Args.is(opt, "NX")) {
                nx = true;
            } else if (Args.is(opt, "XX")) {
                xx = true;
            } else if (Args.is(opt, "CH")) {
                ch = true;
            } else {
                break;
            }
        }
        int pairs = a.size() - i;
        if (pairs == 0 || pairs % 2 != 0 || (nx && xx)) {
            throw CommandException.syntax();
        }
        double[] scores = new double[pairs / 2];
        for (int k = 0; k < scores.length; k++) {
            scores[k] = Args.parseDouble(a.get(i + 2 * k)); // validate all before changing anything
        }
        ZSet z = ctx.db().getZSet(a.get(1), !xx);
        if (z == null) {
            ctx.propagateNothing();
            return Resp.ZERO;
        }
        long added = 0;
        long changed = 0;
        for (int k = 0; k < scores.length; k++) {
            String member = a.get(i + 2 * k + 1);
            Double old = z.score(member);
            if ((nx && old != null) || (xx && old == null)) {
                continue;
            }
            if (z.add(member, scores[k])) {
                added++;
            } else if (old != null && old != scores[k]) {
                changed++;
            }
        }
        ctx.db().deleteIfEmpty(a.get(1));
        return Resp.integer(ch ? added + changed : added);
    }

    private static Resp rank(CommandContext ctx, List<String> a, boolean reverse) {
        ZSet z = ctx.db().getZSet(a.get(1), false);
        int r = z == null ? -1 : z.rank(a.get(2), reverse);
        return r < 0 ? Resp.NIL : Resp.integer(r);
    }

    /** ZRANGE key start stop [WITHSCORES] / ZREVRANGE ... */
    private static Resp range(CommandContext ctx, List<String> a, boolean reverse) {
        boolean withScores = false;
        for (int i = 4; i < a.size(); i++) {
            if (Args.is(a.get(i), "WITHSCORES")) {
                withScores = true;
            } else if (Args.is(a.get(i), "REV") && !reverse) {
                reverse = true;
            } else {
                throw CommandException.syntax();
            }
        }
        ZSet z = ctx.db().getZSet(a.get(1), false);
        if (z == null) {
            return Resp.EMPTY_ARRAY;
        }
        int[] r = Args.clampRange(Args.parseLong(a.get(2)), Args.parseLong(a.get(3)), z.size());
        if (r == null) {
            return Resp.EMPTY_ARRAY;
        }
        return reply(z.list().rangeByRank(r[0], r[1], reverse), withScores);
    }

    /** ZRANGEBYSCORE key min max [WITHSCORES] [LIMIT offset count] */
    private static Resp rangeByScore(CommandContext ctx, List<String> a) {
        ScoreRange range = Args.parseRange(a.get(2), a.get(3));
        boolean withScores = false;
        long offset = 0;
        long count = -1;
        for (int i = 4; i < a.size(); i++) {
            if (Args.is(a.get(i), "WITHSCORES")) {
                withScores = true;
            } else if (Args.is(a.get(i), "LIMIT") && i + 2 < a.size()) {
                offset = Args.parseLong(a.get(++i));
                count = Args.parseLong(a.get(++i));
            } else {
                throw CommandException.syntax();
            }
        }
        ZSet z = ctx.db().getZSet(a.get(1), false);
        if (z == null || offset < 0) {
            return Resp.EMPTY_ARRAY;
        }
        List<SkipList.Node> nodes = z.list().rangeByScore(range);
        int from = (int) Math.min(offset, nodes.size());
        int to = count < 0 ? nodes.size() : (int) Math.min(nodes.size(), from + count);
        return reply(nodes.subList(from, to), withScores);
    }

    private static Resp reply(List<SkipList.Node> nodes, boolean withScores) {
        List<String> out = new ArrayList<>(nodes.size() * (withScores ? 2 : 1));
        for (SkipList.Node n : nodes) {
            out.add(n.member());
            if (withScores) {
                out.add(Args.formatDouble(n.score()));
            }
        }
        return Resp.bulkArray(out);
    }
}
