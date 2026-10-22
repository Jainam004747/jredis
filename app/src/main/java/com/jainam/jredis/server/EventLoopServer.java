package com.jainam.jredis.server;

import com.jainam.jredis.protocol.ProtocolException;
import com.jainam.jredis.protocol.Resp;
import com.jainam.jredis.protocol.RespParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

/**
 * The Redis design: ONE thread serves every client, using non-blocking I/O.
 *
 * <p>Instead of a thread waiting on each socket, a {@link Selector} watches all sockets at once
 * and reports which ones are ready ("client 7 sent data", "client 3 can accept more output").
 * The loop handles each ready socket briefly and moves on, so it never waits on any one client.
 *
 * <pre>
 *   loop forever:
 *     select()            wait up to 100 ms for any socket to become ready
 *     for each ready socket:
 *       accept / read bytes → parse complete commands → execute → queue replies
 *     engine.afterBatch()   write the batch's commands to the AOF
 *     send queued replies   (only now, so no client sees OK before its write is logged)
 *     every 100 ms: engine.cron()  (active expiry, AOF fsync)
 * </pre>
 *
 * <p>Because only this thread touches the engine, the database needs no locks at all. The
 * trade-off: one slow command (KEYS on a million keys) delays every client, so commands must
 * stay fast. That is exactly the trade-off real Redis makes.
 */
public final class EventLoopServer implements Server {
    private static final Logger LOG = Logger.getLogger(EventLoopServer.class.getName());
    private static final int READ_BUFFER = 16 * 1024;

    private final Engine engine;
    private final ByteBuffer readBuffer = ByteBuffer.allocateDirect(READ_BUFFER);
    private final List<Connection> pendingWrites = new ArrayList<>();
    private Selector selector;
    private ServerSocketChannel serverChannel;
    private Thread loopThread;
    private volatile boolean running;
    private long lastCron;

    /** Per-client state, attached to the client's SelectionKey. */
    private static final class Connection {
        final SocketChannel channel;
        final RespParser parser = new RespParser();
        final ByteArrayOutputStream replies = new ByteArrayOutputStream();
        ByteBuffer writing; // bytes handed to the socket but not fully sent yet
        boolean closeAfterWrite;
        boolean queued; // already in pendingWrites for this loop iteration

        Connection(SocketChannel channel) {
            this.channel = channel;
        }

        boolean hasOutput() {
            return replies.size() > 0 || (writing != null && writing.hasRemaining());
        }
    }

    public EventLoopServer(Engine engine) {
        this.engine = engine;
    }

    @Override
    public void start() throws IOException {
        selector = Selector.open();
        serverChannel = ServerSocketChannel.open();
        serverChannel.setOption(StandardSocketOptions.SO_REUSEADDR, true);
        serverChannel.bind(new InetSocketAddress(engine.config().port()));
        serverChannel.configureBlocking(false);
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        running = true;
        loopThread = Thread.ofPlatform().name("jredis-eventloop").start(this::loop);
        LOG.info("jredis (event loop mode) listening on port " + port());
    }

    @Override
    public int port() {
        return serverChannel.socket().getLocalPort();
    }

    private void loop() {
        while (running) {
            try {
                selector.select(100);
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        accept();
                    } else {
                        Connection c = (Connection) key.attachment();
                        if (key.isReadable()) {
                            read(key, c);
                        }
                        if (key.isValid() && key.isWritable()) {
                            queueWrite(c); // written below, after the AOF flush
                        }
                    }
                }
                engine.afterBatch();
                flushPendingWrites();
                long now = System.currentTimeMillis();
                if (now - lastCron >= 100) {
                    lastCron = now;
                    engine.cron();
                }
            } catch (IOException e) {
                if (running) {
                    LOG.severe("event loop error: " + e);
                }
            }
        }
    }

    private void accept() throws IOException {
        SocketChannel ch;
        while ((ch = serverChannel.accept()) != null) {
            ch.configureBlocking(false);
            ch.setOption(StandardSocketOptions.TCP_NODELAY, true);
            ch.register(selector, SelectionKey.OP_READ, new Connection(ch));
            engine.clientConnected();
        }
    }

    private void read(SelectionKey key, Connection c) {
        try {
            int n;
            byte[] chunk = new byte[READ_BUFFER];
            // Read everything currently available (bounded so one client cannot hog the loop).
            for (int rounds = 0; rounds < 16; rounds++) {
                readBuffer.clear();
                n = c.channel.read(readBuffer);
                if (n == -1) {
                    close(key, c);
                    return;
                }
                if (n == 0) {
                    break;
                }
                readBuffer.flip();
                readBuffer.get(chunk, 0, n);
                c.parser.feed(chunk, 0, n);
            }
            List<String> cmd;
            while ((cmd = c.parser.next()) != null) {
                if (cmd.get(0).equalsIgnoreCase("QUIT")) {
                    c.replies.writeBytes(Resp.OK.toBytes());
                    c.closeAfterWrite = true;
                    key.interestOps(0); // stop reading; just finish writing
                    break;
                }
                engine.execute(cmd).writeTo(c.replies);
            }
        } catch (ProtocolException e) {
            Resp.error("ERR Protocol error: " + e.getMessage()).writeTo(c.replies);
            c.closeAfterWrite = true;
            key.interestOps(0);
        } catch (IOException e) {
            close(key, c); // connection reset by peer, etc.
            return;
        }
        if (c.hasOutput()) {
            queueWrite(c);
        }
    }

    private void queueWrite(Connection c) {
        if (!c.queued) {
            c.queued = true;
            pendingWrites.add(c);
        }
    }

    /** Sends replies queued during this loop iteration (after the AOF was flushed). */
    private void flushPendingWrites() {
        for (Connection c : pendingWrites) {
            c.queued = false;
            SelectionKey key = c.channel.keyFor(selector);
            if (key != null && key.isValid()) {
                write(key, c);
            }
        }
        pendingWrites.clear();
    }

    private void write(SelectionKey key, Connection c) {
        try {
            if (c.writing == null || !c.writing.hasRemaining()) {
                if (c.replies.size() == 0) {
                    c.writing = null;
                } else {
                    c.writing = ByteBuffer.wrap(c.replies.toByteArray());
                    c.replies.reset();
                }
            }
            if (c.writing != null) {
                c.channel.write(c.writing);
            }
            if (c.hasOutput()) {
                // The socket's send buffer is full (slow client): ask to be told when it drains.
                key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
            } else if (c.closeAfterWrite) {
                close(key, c);
            } else {
                key.interestOps(SelectionKey.OP_READ);
            }
        } catch (IOException e) {
            close(key, c);
        }
    }

    private void close(SelectionKey key, Connection c) {
        key.cancel();
        try {
            c.channel.close();
        } catch (IOException ignored) {
            // already closing
        }
        engine.clientDisconnected();
    }

    @Override
    public void close() throws IOException {
        running = false;
        selector.wakeup();
        try {
            loopThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (SelectionKey key : selector.keys()) {
            key.channel().close();
        }
        selector.close();
        engine.close();
    }
}
