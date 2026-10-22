package com.jainam.jredis.command;

/** A user-facing command error. The message becomes the RESP error, e.g. "ERR syntax error". */
public final class CommandException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public CommandException(String message) {
        super(message);
    }

    public static CommandException syntax() {
        return new CommandException("ERR syntax error");
    }

    public static CommandException notInteger() {
        return new CommandException("ERR value is not an integer or out of range");
    }

    public static CommandException notFloat() {
        return new CommandException("ERR value is not a valid float");
    }
}
