package com.jainam.jredis.server;

import com.jainam.jredis.protocol.ProtocolException;
import com.jainam.jredis.protocol.Resp;
import com.jainam.jredis.protocol.RespParser;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * The simple design: one thread per connected client, all sharing the engine through one lock.
 *
 * <p>Each thread blocks on {@code read()} for its own client, so a slow client only blocks its
 * own thread (the problem the M1 echo server had). The cost: every client costs a thread
 * (memory for its stack, and context switches), and since the engine is not thread-safe, a
 * single lock serialises all commands anyway. With thousands of mostly idle clients this wastes
 * resources, which is what the {@link EventLoopServer} fixes.
 */
public final class ThreadedServer implements Server {
    private static final Logger LOG = Logger.getLogger(ThreadedServer.class.getName());

    private final Engine engine;
    private final Object lock = new Object();
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private ServerSocket serverSocket;
    private Thread acceptThread;
    private Thread cronThread;
    private volatile boolean running;

    public ThreadedServer(Engine engine) {
        this.engine = engine;
    }

    @Override
    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(engine.config().port()));
        running = true;
        acceptThread = Thread.ofPlatform().name("jredis-accept").start(this::acceptLoop);
        cronThread = Thread.ofPlatform().name("jredis-cron").daemon().start(this::cronLoop);
        LOG.info("jredis (threads mode) listening on port " + port());
    }

    @Override
    public int port() {
        return serverSocket.getLocalPort();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                clients.add(client);
                synchronized (lock) {
                    engine.clientConnected();
                }
                Thread.ofPlatform().name("jredis-client-" + client.getPort()).daemon()
                        .start(() -> serve(client));
            } catch (IOException e) {
                if (running) {
                    LOG.warning("accept failed: " + e.getMessage());
                }
            }
        }
    }

    private void serve(Socket client) {
        RespParser parser = new RespParser();
        byte[] buf = new byte[16 * 1024];
        try (client; InputStream in = client.getInputStream();
             OutputStream out = new BufferedOutputStream(client.getOutputStream(), 16 * 1024)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                parser.feed(buf, 0, n);
                List<String> cmd;
                while ((cmd = nextCommand(parser, out)) != null) {
                    if (cmd.get(0).equalsIgnoreCase("QUIT")) {
                        out.write(Resp.OK.toBytes());
                        out.flush();
                        return;
                    }
                    Resp reply;
                    synchronized (lock) {
                        reply = engine.execute(cmd);
                        engine.afterBatch(); // AOF write happens before the reply is sent
                    }
                    out.write(reply.toBytes());
                }
                out.flush();
            }
        } catch (ProtocolException e) {
            // already reported to the client by nextCommand; the socket is now closed
        } catch (SocketException e) {
            // client vanished or server is closing: normal
        } catch (IOException e) {
            LOG.fine("client I/O error: " + e.getMessage());
        } finally {
            clients.remove(client);
            synchronized (lock) {
                engine.clientDisconnected();
            }
        }
    }

    /**
     * Returns the next parsed command, or null if more bytes are needed. On a protocol error the
     * client is told why before the exception closes the connection (the socket must still be
     * open at that point, which is why this is not done in the outer catch block).
     */
    private static List<String> nextCommand(RespParser parser, OutputStream out) throws IOException {
        try {
            return parser.next();
        } catch (ProtocolException e) {
            out.write(Resp.error("ERR Protocol error: " + e.getMessage()).toBytes());
            out.flush();
            throw e;
        }
    }

    private void cronLoop() {
        while (running) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (lock) {
                engine.cron();
            }
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        serverSocket.close();
        for (Socket s : clients) {
            s.close();
        }
        cronThread.interrupt();
        try {
            acceptThread.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        synchronized (lock) {
            engine.close();
        }
    }
}
