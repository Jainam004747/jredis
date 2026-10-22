package com.jainam.jredis.command;

import com.jainam.jredis.protocol.Resp;
import com.jainam.jredis.store.WrongTypeException;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Looks up commands by name, checks their argument count, runs them, and turns failures into
 * RESP error replies.
 *
 * <p>Each command is registered once with its <em>arity</em>, using the Redis convention:
 * a positive number is the exact argument count including the command name ({@code GET key} = 2),
 * a negative number is a minimum ({@code DEL key [key ...]} = -2).
 */
public final class CommandTable {

    /** What a command does. {@code args.get(0)} is the command name. */
    @FunctionalInterface
    public interface Handler {
        Resp execute(CommandContext ctx, List<String> args);
    }

    /**
     * @param write true if the command can change data; such commands are logged to the
     *              append-only file when they succeed
     */
    public record Spec(String name, int arity, boolean write, Handler handler) { }

    private final Map<String, Spec> specs = new HashMap<>();

    /** A table with every command jredis supports. */
    public static CommandTable standard() {
        CommandTable t = new CommandTable();
        KeyCommands.register(t);
        StringCommands.register(t);
        ListCommands.register(t);
        HashCommands.register(t);
        SetCommands.register(t);
        ZSetCommands.register(t);
        ServerCommands.register(t);
        return t;
    }

    public void read(String name, int arity, Handler handler) {
        specs.put(name, new Spec(name, arity, false, handler));
    }

    public void write(String name, int arity, Handler handler) {
        specs.put(name, new Spec(name, arity, true, handler));
    }

    public Spec lookup(String name) {
        return specs.get(name.toUpperCase(Locale.ROOT));
    }

    public Collection<Spec> all() {
        return specs.values();
    }

    /** Runs one command and always returns a reply; never throws for bad input. */
    public Resp execute(CommandContext ctx, List<String> args) {
        if (args.isEmpty()) {
            return Resp.error("ERR empty command");
        }
        Spec spec = lookup(args.get(0));
        if (spec == null) {
            return Resp.error("ERR unknown command '" + args.get(0) + "'");
        }
        int n = args.size();
        if ((spec.arity() > 0 && n != spec.arity()) || (spec.arity() < 0 && n < -spec.arity())) {
            return Resp.error("ERR wrong number of arguments for '" + spec.name().toLowerCase(Locale.ROOT) + "' command");
        }
        ctx.beginCommand();
        try {
            Resp reply = spec.handler().execute(ctx, args);
            if (spec.write() && !(reply instanceof Resp.Error)) {
                ctx.commitPropagation(args);
            }
            return reply;
        } catch (CommandException e) {
            return Resp.error(e.getMessage());
        } catch (WrongTypeException e) {
            return Resp.error(e.getMessage());
        }
    }
}
