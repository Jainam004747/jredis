package com.jainam.jredis.protocol;

/** Thrown when a client sends bytes that can never form a valid request. The connection is then closed. */
public final class ProtocolException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
