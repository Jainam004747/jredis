package com.jainam.jredis.client;

import com.jainam.jredis.protocol.Resp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A small blocking client that speaks RESP: it sends a command and reads the reply.
 *
 * <p>Used by the web console's HTTP bridge, the benchmark tool and the integration tests. It
 * works against jredis and against a real Redis server, which is a handy way to check that
 * jredis behaves the same.
 */
public final class RespClient implements AutoCloseable {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public RespClient(String host, int port) throws IOException {
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 3000);
        socket.setTcpNoDelay(true);
        in = new BufferedInputStream(socket.getInputStream());
        out = new BufferedOutputStream(socket.getOutputStream());
    }

    /** Sends one command and waits for its reply. */
    public Resp call(String... args) throws IOException {
        return call(Arrays.asList(args));
    }

    public Resp call(List<String> args) throws IOException {
        send(args);
        out.flush();
        return readReply();
    }

    /**
     * Pipelining: sends many commands in one go, then reads all replies. Saves a network
     * round trip per command, which is how redis-benchmark -P reaches high throughput.
     */
    public List<Resp> pipeline(List<List<String>> commands) throws IOException {
        for (List<String> c : commands) {
            send(c);
        }
        out.flush();
        List<Resp> replies = new ArrayList<>(commands.size());
        for (int i = 0; i < commands.size(); i++) {
            replies.add(readReply());
        }
        return replies;
    }

    private void send(List<String> args) throws IOException {
        out.write(Resp.encodeCommand(args));
    }

    /** Reads one reply of any type from the stream. */
    public Resp readReply() throws IOException {
        int type = in.read();
        if (type == -1) {
            throw new EOFException("server closed the connection");
        }
        String line = readLine();
        return switch (type) {
            case '+' -> Resp.simple(line);
            case '-' -> Resp.error(line);
            case ':' -> Resp.integer(Long.parseLong(line));
            case '$' -> {
                int len = Integer.parseInt(line);
                if (len < 0) {
                    yield Resp.NIL;
                }
                byte[] data = in.readNBytes(len);
                if (data.length != len) {
                    throw new EOFException("truncated bulk reply");
                }
                in.readNBytes(2); // trailing \r\n
                yield Resp.bulk(new String(data, StandardCharsets.ISO_8859_1));
            }
            case '*' -> {
                int count = Integer.parseInt(line);
                if (count < 0) {
                    yield new Resp.Array(null);
                }
                List<Resp> items = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    items.add(readReply());
                }
                yield Resp.array(items);
            }
            default -> throw new IOException("unexpected reply type '" + (char) type + "'");
        };
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                in.read(); // '\n'
                return line.toString(StandardCharsets.ISO_8859_1);
            }
            line.write(b);
        }
        throw new EOFException("server closed the connection");
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    /** Renders a reply the way redis-cli does, for humans. */
    public static String format(Resp r) {
        return format(r, "");
    }

    private static String format(Resp r, String indent) {
        return switch (r) {
            case Resp.Simple s -> s.value();
            case Resp.Error e -> "(error) " + e.message();
            case Resp.Int i -> "(integer) " + i.value();
            case Resp.Bulk b -> b.value() == null ? "(nil)" : "\"" + b.value() + "\"";
            case Resp.Array a -> {
                if (a.items() == null) {
                    yield "(nil)";
                }
                if (a.items().isEmpty()) {
                    yield "(empty array)";
                }
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < a.items().size(); i++) {
                    if (i > 0) {
                        sb.append('\n').append(indent);
                    }
                    String prefix = (i + 1) + ") ";
                    sb.append(prefix).append(format(a.items().get(i), indent + " ".repeat(prefix.length())));
                }
                yield sb.toString();
            }
        };
    }
}
