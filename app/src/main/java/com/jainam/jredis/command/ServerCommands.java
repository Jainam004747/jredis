package com.jainam.jredis.command;

import com.jainam.jredis.protocol.Resp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Connection and server commands: PING, ECHO, INFO, SAVE, CONFIG, ...
 *
 * <p>Some exist mainly so that real Redis tools work against jredis: redis-cli sends
 * {@code COMMAND DOCS} when it starts, and redis-benchmark sends {@code CONFIG GET}.
 */
final class ServerCommands {
    private ServerCommands() { }

    static void register(CommandTable t) {
        t.read("PING", -1, (ctx, a) -> {
            if (a.size() > 2) {
                throw new CommandException("ERR wrong number of arguments for 'ping' command");
            }
            return a.size() == 2 ? Resp.bulk(a.get(1)) : Resp.PONG;
        });
        t.read("ECHO", 2, (ctx, a) -> Resp.bulk(a.get(1)));
        t.read("TIME", 1, (ctx, a) -> {
            long micros = ctx.db().now() * 1000;
            return Resp.bulkArray(List.of(Long.toString(micros / 1_000_000), Long.toString(micros % 1_000_000)));
        });
        t.read("SELECT", 2, (ctx, a) -> {
            if (!a.get(1).equals("0")) {
                throw new CommandException("ERR DB index is out of range (jredis has a single database: 0)");
            }
            return Resp.OK;
        });
        t.read("COMMAND", -1, (ctx, a) -> {
            if (a.size() >= 2 && Args.is(a.get(1), "COUNT")) {
                return Resp.integer(t.all().size());
            }
            return Resp.EMPTY_ARRAY; // command docs are not implemented; clients cope with an empty list
        });
        t.read("CLIENT", -2, (ctx, a) -> Resp.OK); // CLIENT SETNAME / SETINFO: accepted and ignored
        t.read("INFO", -1, (ctx, a) -> Resp.bulk(ctx.server().info(a.size() > 1 ? a.get(1) : "all")));
        t.read("SAVE", 1, (ctx, a) -> result(ctx.server().save(), Resp.OK));
        t.read("BGSAVE", -1, (ctx, a) -> result(ctx.server().save(), Resp.simple("Background saving started")));
        t.read("BGREWRITEAOF", 1, (ctx, a) ->
                result(ctx.server().rewriteAof(), Resp.simple("Background append only file rewriting started")));
        t.read("LASTSAVE", 1, (ctx, a) -> Resp.integer(ctx.server().lastSave()));
        t.read("CONFIG", -2, (ctx, a) -> {
            String sub = a.get(1).toUpperCase(Locale.ROOT);
            if (sub.equals("GET") && a.size() >= 3) {
                List<String> out = new ArrayList<>();
                for (String name : a.subList(2, a.size())) {
                    String v = ctx.server().config(name.toLowerCase(Locale.ROOT));
                    if (v != null) {
                        out.add(name.toLowerCase(Locale.ROOT));
                        out.add(v);
                    }
                }
                return Resp.bulkArray(out);
            }
            if (sub.equals("RESETSTAT")) {
                return Resp.OK;
            }
            throw new CommandException("ERR CONFIG " + sub + " is not supported by jredis");
        });
    }

    private static Resp result(String error, Resp ok) {
        return error == null ? ok : Resp.error(error);
    }
}
