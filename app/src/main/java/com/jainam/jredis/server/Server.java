package com.jainam.jredis.server;

import java.io.IOException;

/** A network front end that accepts clients and feeds their commands to an {@link Engine}. */
public interface Server extends AutoCloseable {

    /** Binds the port and starts serving in the background. Returns once it is listening. */
    void start() throws IOException;

    /** The port actually bound (useful when the configured port was 0). */
    int port();

    /** Stops accepting clients, closes connections, and waits for the server thread to end. */
    @Override
    void close() throws IOException;

    static Server create(Engine engine) {
        return switch (engine.config().mode()) {
            case EVENTLOOP -> new EventLoopServer(engine);
            case THREADS -> new ThreadedServer(engine);
        };
    }
}
