package com.jainam.jredis.store;

/** Thrown when a command is used on a key holding a different type, e.g. LPUSH on a string. */
public final class WrongTypeException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public WrongTypeException() {
        super("WRONGTYPE Operation against a key holding the wrong kind of value");
    }
}
