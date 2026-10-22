package com.jainam.jredis.console;

import com.jainam.jredis.client.RespClient;
import com.jainam.jredis.protocol.ProtocolException;
import com.jainam.jredis.protocol.Resp;
import com.jainam.jredis.protocol.RespParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * The web console's backend: a small HTTP server that turns browser requests into RESP
 * commands for jredis.
 *
 * <p><b>Why a bridge?</b> Browsers can only speak HTTP (and WebSockets); a web page cannot open
 * a raw TCP connection to port 6380 the way redis-cli or nc can. So the page calls this server
 * with HTTP + JSON, and this server talks RESP over TCP to jredis:
 *
 * <pre>
 *   browser ──HTTP/JSON──▶ ConsoleServer (:8080) ──RESP/TCP──▶ jredis (:6380)
 * </pre>
 *
 * <p>The console binds to 127.0.0.1 by default and has no login, so only people on this machine
 * can use it. Exposing it on a network would need authentication first.
 *
 * <p>Text encoding: jredis stores raw bytes; the browser works in Unicode. Values typed in the
 * console are sent to jredis as UTF-8 bytes and decoded as UTF-8 on the way back.
 */
public final class ConsoleServer {
    private static final Logger LOG = Logger.getLogger(ConsoleServer.class.getName());
    private static final int MAX_KEYS = 500;

    private final String targetHost;
    private final int targetPort;
    private RespClient client;
    private HttpServer http;

    public ConsoleServer(String targetHost, int targetPort) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
    }

    public static void main(String[] args) throws IOException {
        String target = "localhost:6380";
        int httpPort = 8080;
        String bind = "127.0.0.1";
        for (int i = 0; i + 1 < args.length; i += 2) {
            switch (args[i]) {
                case "--target" -> target = args[i + 1];
                case "--http" -> httpPort = Integer.parseInt(args[i + 1]);
                case "--bind" -> bind = args[i + 1];
                default -> throw new IllegalArgumentException("unknown flag " + args[i]);
            }
        }
        String[] hp = target.split(":");
        ConsoleServer console = new ConsoleServer(hp[0], Integer.parseInt(hp[1]));
        console.start(bind, httpPort);
        System.out.println("jredis console: open http://localhost:" + console.port() + " (talking to " + target + ")");
    }

    public void start(String bind, int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(bind, port), 0);
        http.createContext("/", this::serveStatic);
        http.createContext("/api/info", ex -> handle(ex, this::info));
        http.createContext("/api/keys", ex -> handle(ex, this::keys));
        http.createContext("/api/key", ex -> handle(ex, this::key));
        http.createContext("/api/expire", ex -> handle(ex, this::expire));
        http.createContext("/api/command", ex -> handle(ex, this::command));
        http.setExecutor(Executors.newFixedThreadPool(4));
        http.start();
    }

    public int port() {
        return http.getAddress().getPort();
    }

    public void stop() {
        http.stop(0);
        synchronized (this) {
            closeClient();
        }
    }

    @FunctionalInterface
    private interface Api {
        Object handle(HttpExchange ex) throws IOException;
    }

    /** Runs an API handler and writes its result as JSON, turning failures into {"error": ...}. */
    private void handle(HttpExchange ex, Api api) throws IOException {
        int status = 200;
        Object body;
        try {
            body = api.handle(ex);
        } catch (IllegalArgumentException | ProtocolException e) {
            status = 400;
            body = Map.of("error", e.getMessage());
        } catch (IOException e) {
            status = 502;
            body = Map.of("error", "cannot reach jredis at " + targetHost + ":" + targetPort + " (" + e.getMessage() + ")");
        }
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---- API handlers ----

    /** GET /api/info: connection status and headline stats. */
    private Object info(HttpExchange ex) throws IOException {
        String text = text(call("INFO"));
        Map<String, String> fields = new HashMap<>();
        for (String line : text.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && !line.startsWith("#")) {
                fields.put(line.substring(0, colon), line.substring(colon + 1));
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("target", targetHost + ":" + targetPort);
        out.put("keys", ((Resp.Int) call("DBSIZE")).value());
        out.put("clients", fields.getOrDefault("connected_clients", "?"));
        out.put("memory", fields.getOrDefault("used_memory", "0"));
        out.put("commands", fields.getOrDefault("total_commands_processed", "0"));
        out.put("uptime", fields.getOrDefault("uptime_in_seconds", "0"));
        out.put("mode", fields.getOrDefault("mode", "redis"));
        out.put("aof", fields.getOrDefault("aof_enabled", "0").equals("1"));
        return out;
    }

    /** GET /api/keys?pattern=user:*  → [{key, type, ttl}] */
    private Object keys(HttpExchange ex) throws IOException {
        String pattern = query(ex).getOrDefault("pattern", "*");
        if (pattern.isBlank()) {
            pattern = "*";
        }
        List<String> names = strings(call("KEYS", toBytes(pattern)));
        boolean truncated = names.size() > MAX_KEYS;
        if (truncated) {
            names = names.subList(0, MAX_KEYS);
        }
        // Pipeline TYPE and PTTL for every key: 2 commands per key, one network round trip.
        List<List<String>> cmds = new ArrayList<>();
        for (String k : names) {
            cmds.add(List.of("TYPE", k));
            cmds.add(List.of("PTTL", k));
        }
        List<Resp> replies = pipeline(cmds);
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", fromBytes(names.get(i)));
            row.put("type", text(replies.get(2 * i)));
            row.put("ttl", ((Resp.Int) replies.get(2 * i + 1)).value());
            out.add(row);
        }
        return Map.of("keys", out, "truncated", truncated);
    }

    /** GET / POST / DELETE /api/key */
    private Object key(HttpExchange ex) throws IOException {
        return switch (ex.getRequestMethod()) {
            case "GET" -> readKey(query(ex).get("name"));
            case "DELETE" -> Map.of("deleted", ((Resp.Int) call("DEL", toBytes(required(query(ex), "name")))).value());
            case "POST" -> writeKey(Json.parseObject(body(ex)));
            default -> throw new IllegalArgumentException("unsupported method " + ex.getRequestMethod());
        };
    }

    private Object readKey(String name) throws IOException {
        if (name == null) {
            throw new IllegalArgumentException("missing ?name=");
        }
        String k = toBytes(name);
        String type = text(call("TYPE", k));
        Object value = switch (type) {
            case "string" -> fromBytes(text(call("GET", k)));
            case "list" -> fromBytesList(strings(call("LRANGE", k, "0", "999")));
            case "set" -> fromBytesList(strings(call("SMEMBERS", k)));
            case "hash" -> pairs(strings(call("HGETALL", k)));
            case "zset" -> pairs(strings(call("ZRANGE", k, "0", "999", "WITHSCORES")));
            default -> null;
        };
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("key", name);
        out.put("type", type);
        out.put("ttl", ((Resp.Int) call("PTTL", k)).value());
        out.put("value", value);
        return out;
    }

    /**
     * Creates or replaces a key from a form. Body: {key, type, value, ttl}. For collections the
     * value has one item per line: list/set "item", hash "field=value", zset "score member".
     */
    private Object writeKey(Map<String, String> req) throws IOException {
        String key = toBytes(required(req, "key"));
        String type = req.getOrDefault("type", "string");
        String value = req.getOrDefault("value", "");
        List<String> cmd = new ArrayList<>();
        switch (type) {
            case "string" -> cmd.addAll(List.of("SET", key, toBytes(value)));
            case "list", "set" -> {
                cmd.addAll(List.of(type.equals("list") ? "RPUSH" : "SADD", key));
                lines(value).forEach(l -> cmd.add(toBytes(l)));
            }
            case "hash" -> {
                cmd.addAll(List.of("HSET", key));
                for (String l : lines(value)) {
                    int eq = l.indexOf('=');
                    if (eq <= 0) {
                        throw new IllegalArgumentException("hash lines must look like field=value: " + l);
                    }
                    cmd.add(toBytes(l.substring(0, eq).trim()));
                    cmd.add(toBytes(l.substring(eq + 1).trim()));
                }
            }
            case "zset" -> {
                cmd.addAll(List.of("ZADD", key));
                for (String l : lines(value)) {
                    String[] parts = l.trim().split("\\s+", 2);
                    if (parts.length != 2) {
                        throw new IllegalArgumentException("sorted-set lines must look like: 100 alice");
                    }
                    cmd.add(parts[0]);
                    cmd.add(toBytes(parts[1]));
                }
            }
            default -> throw new IllegalArgumentException("unknown type " + type);
        }
        if (!type.equals("string") && cmd.size() == 2) {
            throw new IllegalArgumentException("add at least one item");
        }
        List<List<String>> batch = new ArrayList<>();
        batch.add(List.of("DEL", key)); // replace, don't merge
        batch.add(cmd);
        String ttl = req.get("ttl");
        if (ttl != null && !ttl.isBlank() && Long.parseLong(ttl) > 0) {
            batch.add(List.of("EXPIRE", key, ttl));
        }
        for (Resp r : pipeline(batch)) {
            if (r instanceof Resp.Error e) {
                throw new IllegalArgumentException(e.message());
            }
        }
        return readKey(fromBytes(key));
    }

    /** POST /api/expire {key, seconds}: seconds &lt;= 0 removes the TTL. */
    private Object expire(HttpExchange ex) throws IOException {
        Map<String, String> req = Json.parseObject(body(ex));
        String key = toBytes(required(req, "key"));
        long seconds = Long.parseLong(req.getOrDefault("seconds", "0"));
        Resp r = seconds > 0 ? call("EXPIRE", key, Long.toString(seconds)) : call("PERSIST", key);
        return Map.of("ok", r instanceof Resp.Int i && i.value() == 1);
    }

    /** POST /api/command {command: "SET a b"} → {reply: "OK"} as redis-cli would print it. */
    private Object command(HttpExchange ex) throws IOException {
        String line = required(Json.parseObject(body(ex)), "command");
        List<String> args = RespParser.splitInline(line);
        if (args.isEmpty()) {
            throw new IllegalArgumentException("type a command, for example: SET greeting hello");
        }
        String name = args.get(0).toUpperCase(Locale.ROOT);
        if (name.equals("QUIT")) {
            throw new IllegalArgumentException("QUIT would close the console's connection");
        }
        List<String> encoded = new ArrayList<>();
        args.forEach(a -> encoded.add(toBytes(a)));
        Resp reply = call(encoded);
        return Map.of("reply", fromBytes(RespClient.format(reply)), "error", reply instanceof Resp.Error);
    }

    // ---- static files: the single-page app in src/main/resources/console ----

    private void serveStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }
        if (!path.equals("/index.html")) {
            ex.sendResponseHeaders(404, -1);
            ex.close();
            return;
        }
        byte[] bytes;
        try (InputStream in = ConsoleServer.class.getResourceAsStream("/console/index.html")) {
            if (in == null) {
                throw new IOException("console page missing from the build");
            }
            bytes = in.readAllBytes();
        }
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---- connection to jredis: one shared client, reconnected on failure ----

    private Resp call(String... args) throws IOException {
        return call(List.of(args));
    }

    private synchronized Resp call(List<String> args) throws IOException {
        try {
            return connection().call(args);
        } catch (IOException e) {
            closeClient(); // try once more with a fresh connection
            return connection().call(args);
        }
    }

    private synchronized List<Resp> pipeline(List<List<String>> cmds) throws IOException {
        if (cmds.isEmpty()) {
            return List.of();
        }
        try {
            return connection().pipeline(cmds);
        } catch (IOException e) {
            closeClient();
            return connection().pipeline(cmds);
        }
    }

    private RespClient connection() throws IOException {
        if (client == null) {
            client = new RespClient(targetHost, targetPort);
        }
        return client;
    }

    private void closeClient() {
        if (client != null) {
            try {
                client.close();
            } catch (IOException ignored) {
                // reconnecting anyway
            }
            client = null;
        }
    }

    // ---- helpers ----

    /** Unicode text from the browser → the UTF-8 bytes jredis stores (as a byte-per-char string). */
    static String toBytes(String s) {
        return new String(s.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    static String fromBytes(String s) {
        return s == null ? null : new String(s.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    private static List<String> fromBytesList(List<String> in) {
        return in.stream().map(ConsoleServer::fromBytes).toList();
    }

    private static List<List<String>> pairs(List<String> flat) {
        List<List<String>> out = new ArrayList<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            out.add(List.of(fromBytes(flat.get(i)), fromBytes(flat.get(i + 1))));
        }
        return out;
    }

    private static String text(Resp r) throws IOException {
        return switch (r) {
            case Resp.Simple s -> s.value();
            case Resp.Bulk b -> b.value();
            case Resp.Error e -> throw new IllegalArgumentException(e.message());
            default -> throw new IOException("unexpected reply " + r);
        };
    }

    private static List<String> strings(Resp r) {
        if (r instanceof Resp.Error e) {
            throw new IllegalArgumentException(e.message());
        }
        List<String> out = new ArrayList<>();
        for (Resp item : ((Resp.Array) r).items()) {
            out.add(((Resp.Bulk) item).value());
        }
        return out;
    }

    private static List<String> lines(String value) {
        return value.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
    }

    private static String required(Map<String, String> m, String name) {
        String v = m.get(name);
        if (v == null || v.isEmpty()) {
            throw new IllegalArgumentException("missing " + name);
        }
        return v;
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> out = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return out;
        }
        for (String part : q.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static String body(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] b = in.readNBytes(1024 * 1024 + 1);
            if (b.length > 1024 * 1024) {
                throw new IllegalArgumentException("request too large");
            }
            return new String(b, StandardCharsets.UTF_8);
        }
    }

    static {
        LOG.fine("console loaded");
    }
}
