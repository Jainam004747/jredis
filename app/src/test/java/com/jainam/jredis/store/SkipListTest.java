package com.jainam.jredis.store;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkipListTest {

    @Test
    void ordersByScoreThenMember() {
        SkipList list = new SkipList(new Random(1));
        list.insert(2, "b");
        list.insert(1, "z");
        list.insert(2, "a");
        list.insert(3, "c");
        List<String> members = list.all().stream().map(SkipList.Node::member).toList();
        assertEquals(List.of("z", "a", "b", "c"), members);
        assertEquals(1, list.rank(1, "z"));
        assertEquals(2, list.rank(2, "a"));
        assertEquals(4, list.rank(3, "c"));
        assertEquals(0, list.rank(9, "missing"));
        assertEquals("b", list.byRank(3).member());
        assertNull(list.byRank(5));
    }

    @Test
    void deleteKeepsRanksCorrect() {
        SkipList list = new SkipList(new Random(2));
        for (int i = 0; i < 10; i++) {
            list.insert(i, "m" + i);
        }
        assertTrue(list.delete(4, "m4"));
        assertFalse(list.delete(4, "m4"));
        assertFalse(list.delete(5, "wrong-member"));
        assertEquals(9, list.size());
        assertEquals(5, list.rank(5, "m5"));
        assertEquals("m5", list.byRank(5).member());
    }

    /**
     * Property test: perform thousands of random inserts and deletes, and after each step
     * compare the skip list against a TreeMap, which is known to be correct.
     */
    @Test
    void matchesTreeMapUnderRandomOperations() {
        Random rnd = new Random(42);
        SkipList list = new SkipList(new Random(7));
        record Entry(double score, String member) { }
        Comparator<Entry> order = Comparator.comparingDouble(Entry::score).thenComparing(Entry::member);
        TreeMap<Entry, Boolean> model = new TreeMap<>(order);
        List<Entry> present = new ArrayList<>();
        for (int step = 0; step < 5000; step++) {
            if (present.isEmpty() || rnd.nextInt(3) > 0) {
                Entry e = new Entry(rnd.nextInt(100), "m" + step);
                list.insert(e.score(), e.member());
                model.put(e, true);
                present.add(e);
            } else {
                Entry e = present.remove(rnd.nextInt(present.size()));
                assertTrue(list.delete(e.score(), e.member()));
                model.remove(e);
            }
            if (step % 250 == 0) {
                List<Entry> expected = new ArrayList<>(model.keySet());
                assertEquals(expected.size(), list.size());
                for (int r = 0; r < expected.size(); r++) {
                    Entry e = expected.get(r);
                    assertEquals(r + 1, list.rank(e.score(), e.member()), "rank of " + e);
                    assertEquals(e.member(), list.byRank(r + 1).member());
                }
            }
        }
    }

    @Test
    void rangeByScoreRespectsExclusiveBounds() {
        SkipList list = new SkipList(new Random(3));
        for (int i = 1; i <= 5; i++) {
            list.insert(i, "m" + i);
        }
        assertEquals(List.of("m2", "m3", "m4"), members(list.rangeByScore(new SkipList.ScoreRange(2, false, 4, false))));
        assertEquals(List.of("m3"), members(list.rangeByScore(new SkipList.ScoreRange(2, true, 4, true))));
        assertEquals(5, list.rangeByScore(new SkipList.ScoreRange(Double.NEGATIVE_INFINITY, false,
                Double.POSITIVE_INFINITY, false)).size());
        assertEquals(List.of("m5", "m4"), members(list.rangeByRank(0, 1, true)));
    }

    private static List<String> members(List<SkipList.Node> nodes) {
        return nodes.stream().map(SkipList.Node::member).toList();
    }
}
