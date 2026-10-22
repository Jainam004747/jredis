package com.jainam.jredis.store;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseTest {
    private final AtomicLong time = new AtomicLong(1_000_000);
    private final Database db = new Database(time::get, new Random(1));

    @Test
    void lazyExpiryHidesExpiredKeys() {
        db.put("k", "v");
        assertTrue(db.expireAt("k", time.get() + 100));
        assertEquals("v", db.getString("k"));
        time.addAndGet(100); // exactly at the deadline the key is gone
        assertNull(db.getString("k"));
        assertEquals(0, db.size());
        assertEquals(1, db.expiredKeys());
    }

    @Test
    void putClearsTtlButPutKeepTtlKeepsIt() {
        db.put("a", "1");
        db.expireAt("a", time.get() + 50);
        db.putKeepTtl("a", "2");
        assertEquals(time.get() + 50, db.deadline("a"));
        db.put("a", "3");
        assertEquals(-1, db.deadline("a"));
    }

    @Test
    void activeExpiryRemovesKeysNobodyReads() {
        for (int i = 0; i < 1000; i++) {
            db.put("temp" + i, "x");
            db.expireAt("temp" + i, time.get() + 10);
        }
        db.put("forever", "x");
        time.addAndGet(20);
        int total = 0;
        for (int round = 0; round < 200 && db.keysWithTtl() > 0; round++) {
            total += db.activeExpireCycle();
        }
        assertEquals(1000, total);
        assertEquals(1, db.size());
    }

    @Test
    void wrongTypeIsReported() {
        db.put("s", "text");
        assertThrows(WrongTypeException.class, () -> db.getList("s", true));
        assertEquals("string", db.type("s"));
        assertEquals("none", db.type("missing"));
    }

    @Test
    void emptyCollectionsAreDeleted() {
        RList list = db.getList("l", true);
        list.addLast("x");
        list.removeFirst();
        db.deleteIfEmpty("l");
        assertFalse(db.exists("l"));
    }

    @Test
    void circularListWrapsAroundCorrectly() {
        RList list = new RList();
        for (int i = 0; i < 20; i++) {
            if (i % 2 == 0) {
                list.addFirst("f" + i);
            } else {
                list.addLast("l" + i);
            }
        }
        assertEquals(20, list.size());
        assertEquals("f18", list.get(0));
        assertEquals("l19", list.get(19));
        assertEquals("f18", list.removeFirst());
        assertEquals("l19", list.removeLast());
        assertEquals(2, list.remove("f0", 0) + list.remove("l1", 0));
        assertEquals(16, list.size());
    }
}
