package com.jainam.jredis.command;

import com.jainam.jredis.protocol.Resp;
import com.jainam.jredis.store.Database;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandTableTest {
    private final AtomicLong time = new AtomicLong(1_700_000_000_000L);
    private final Database db = new Database(time::get, new Random(1));
    private final List<List<String>> logged = new ArrayList<>();
    private final CommandContext ctx = new CommandContext(db, ServerHooks.NONE, logged::add);
    private final CommandTable table = CommandTable.standard();

    private Resp run(String... args) {
        return table.execute(ctx, Arrays.asList(args));
    }

    @Test
    void stringBasics() {
        assertEquals(Resp.OK, run("SET", "drink", "double double"));
        assertEquals(Resp.bulk("double double"), run("GET", "drink"));
        assertEquals(Resp.NIL, run("GET", "nope"));
        assertEquals(Resp.integer(1), run("INCR", "visits"));
        assertEquals(Resp.integer(11), run("INCRBY", "visits", "10"));
        assertEquals(Resp.integer(10), run("DECR", "visits"));
        assertInstanceOf(Resp.Error.class, run("INCR", "drink"));
        assertEquals(Resp.integer(20), run("APPEND", "drink", " please"));
        assertEquals(Resp.bulk("3.5"), run("INCRBYFLOAT", "f", "3.5"));
        assertEquals(Resp.bulkArray(Arrays.asList("10", null)), run("MGET", "visits", "nope"));
    }

    @Test
    void setOptions() {
        assertEquals(Resp.OK, run("SET", "k", "1", "NX"));
        assertEquals(Resp.NIL, run("SET", "k", "2", "NX"));
        assertEquals(Resp.NIL, run("SET", "other", "2", "XX"));
        assertEquals(Resp.bulk("1"), run("SET", "k", "3", "GET"));
        assertEquals(Resp.OK, run("SET", "t", "v", "EX", "10"));
        assertEquals(Resp.integer(10), run("TTL", "t"));
        assertInstanceOf(Resp.Error.class, run("SET", "k", "v", "EX", "0"));
        assertInstanceOf(Resp.Error.class, run("SET", "k", "v", "NX", "XX"));
        assertInstanceOf(Resp.Error.class, run("SET", "k", "v", "BOGUS"));
    }

    @Test
    void expiryCommands() {
        run("SET", "promo", "free donut");
        assertEquals(Resp.integer(-1), run("TTL", "promo"));
        assertEquals(Resp.integer(1), run("EXPIRE", "promo", "60"));
        assertEquals(Resp.integer(60), run("TTL", "promo"));
        time.addAndGet(59_500);
        assertEquals(Resp.integer(1), run("TTL", "promo")); // 500 ms left rounds up to 1
        assertEquals(Resp.integer(500), run("PTTL", "promo"));
        time.addAndGet(500);
        assertEquals(Resp.NIL, run("GET", "promo"));
        assertEquals(Resp.integer(-2), run("TTL", "promo"));
        assertEquals(Resp.integer(0), run("EXPIRE", "promo", "10"));
        run("SET", "p", "x");
        run("EXPIRE", "p", "100");
        assertEquals(Resp.integer(1), run("PERSIST", "p"));
        assertEquals(Resp.integer(-1), run("TTL", "p"));
    }

    @Test
    void relativeExpiryIsLoggedAsAbsoluteTime() {
        run("SET", "k", "v");
        logged.clear();
        run("EXPIRE", "k", "10");
        assertEquals(List.of(List.of("PEXPIREAT", "k", Long.toString(time.get() + 10_000))), logged);
        logged.clear();
        run("SET", "x", "y", "NX");
        run("SET", "x", "z", "NX"); // no change: must not be logged
        run("GET", "x");            // reads are never logged
        assertEquals(List.of(List.of("SET", "x", "y", "NX")), logged);
    }

    @Test
    void lists() {
        assertEquals(Resp.integer(3), run("RPUSH", "cart", "coffee", "donut", "bagel"));
        assertEquals(Resp.integer(4), run("LPUSH", "cart", "tea"));
        assertEquals(Resp.bulkArray(List.of("tea", "coffee", "donut", "bagel")), run("LRANGE", "cart", "0", "-1"));
        assertEquals(Resp.bulkArray(List.of("donut", "bagel")), run("LRANGE", "cart", "-2", "100"));
        assertEquals(Resp.bulk("tea"), run("LPOP", "cart"));
        assertEquals(Resp.bulkArray(List.of("bagel", "donut")), run("RPOP", "cart", "2"));
        assertEquals(Resp.bulk("coffee"), run("LINDEX", "cart", "0"));
        assertEquals(Resp.bulk("coffee"), run("LPOP", "cart"));
        assertEquals(Resp.integer(0), run("EXISTS", "cart")); // empty list deletes the key
        run("SET", "s", "x");
        assertInstanceOf(Resp.Error.class, run("LPUSH", "s", "y"));
    }

    @Test
    void hashesAndSets() {
        assertEquals(Resp.integer(2), run("HSET", "user:42", "name", "Jainam", "city", "Sudbury"));
        assertEquals(Resp.bulk("Sudbury"), run("HGET", "user:42", "city"));
        assertEquals(Resp.bulkArray(List.of("city", "Sudbury", "name", "Jainam")), run("HGETALL", "user:42"));
        assertEquals(Resp.integer(5), run("HINCRBY", "user:42", "visits", "5"));
        assertEquals(Resp.integer(1), run("HDEL", "user:42", "city", "nope"));
        assertEquals(Resp.integer(2), run("HLEN", "user:42"));

        assertEquals(Resp.integer(3), run("SADD", "a", "x", "y", "z"));
        assertEquals(Resp.integer(0), run("SADD", "a", "x"));
        run("SADD", "b", "y", "z", "w");
        assertEquals(Resp.bulkArray(List.of("y", "z")), run("SINTER", "a", "b"));
        assertEquals(Resp.bulkArray(List.of("w", "x", "y", "z")), run("SUNION", "a", "b"));
        assertEquals(Resp.bulkArray(List.of("x")), run("SDIFF", "a", "b"));
        assertEquals(Resp.integer(1), run("SISMEMBER", "a", "x"));
    }

    @Test
    void sortedSetsAsLeaderboard() {
        assertEquals(Resp.integer(3), run("ZADD", "scores", "100", "alice", "250", "bob", "175", "carol"));
        assertEquals(Resp.bulkArray(List.of("alice", "carol", "bob")), run("ZRANGE", "scores", "0", "-1"));
        assertEquals(Resp.bulkArray(List.of("bob", "250", "carol", "175")),
                run("ZREVRANGE", "scores", "0", "1", "WITHSCORES"));
        assertEquals(Resp.integer(0), run("ZRANK", "scores", "alice"));
        assertEquals(Resp.integer(0), run("ZREVRANK", "scores", "bob"));
        assertEquals(Resp.bulk("300"), run("ZINCRBY", "scores", "200", "alice"));
        assertEquals(Resp.integer(0), run("ZREVRANK", "scores", "alice"));
        assertEquals(Resp.bulkArray(List.of("carol", "bob")), run("ZRANGEBYSCORE", "scores", "(100", "250"));
        assertEquals(Resp.integer(2), run("ZCOUNT", "scores", "200", "+inf"));
        assertEquals(Resp.bulk("175"), run("ZSCORE", "scores", "carol"));
        assertEquals(Resp.integer(1), run("ZADD", "scores", "CH", "1", "carol"));
    }

    @Test
    void keysAndErrors() {
        run("MSET", "user:1", "a", "user:2", "b", "order:1", "c");
        assertEquals(Resp.bulkArray(List.of("user:1", "user:2")), run("KEYS", "user:*"));
        assertEquals(Resp.bulkArray(List.of("order:1")), run("KEYS", "o?der:[0-9]"));
        assertEquals(Resp.integer(2), run("DEL", "user:1", "user:2", "missing"));
        assertEquals(Resp.integer(1), run("DBSIZE"));
        assertEquals(Resp.simple("string"), run("TYPE", "order:1"));
        Resp unknown = run("NOSUCHCOMMAND");
        assertTrue(((Resp.Error) unknown).message().startsWith("ERR unknown command"));
        assertInstanceOf(Resp.Error.class, run("GET"));
        assertEquals(Resp.PONG, run("ping"));
    }

    @Test
    void globMatching() {
        assertTrue(Glob.matches("*", "anything"));
        assertTrue(Glob.matches("h?llo", "hello"));
        assertTrue(Glob.matches("h[ae]llo", "hallo"));
        assertTrue(!Glob.matches("h[^e]llo", "hello"));
        assertTrue(Glob.matches("a*b*c", "aXXbYYc"));
        assertTrue(!Glob.matches("a*b*c", "aXXbYY"));
        assertTrue(Glob.matches("\\*star", "*star"));
    }
}
