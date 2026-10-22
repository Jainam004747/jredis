package com.jainam.jredis.store;

import java.util.HashMap;
import java.util.Map;

/**
 * The value type behind sorted sets. It uses two structures for two kinds of question:
 * <ul>
 *   <li>a HashMap member → score answers "what is X's score?" in O(1) (ZSCORE, and checking
 *       whether a member already exists)</li>
 *   <li>a {@link SkipList} keeps members ordered for ranks and ranges in O(log n)</li>
 * </ul>
 * Every change updates both, so they always agree. This is the same design Redis uses.
 */
public final class ZSet {
    private final Map<String, Double> scores = new HashMap<>();
    private final SkipList list = new SkipList();

    public int size() {
        return scores.size();
    }

    public Double score(String member) {
        return scores.get(member);
    }

    /** Adds or updates a member. Returns true if the member is new. */
    public boolean add(String member, double score) {
        Double old = scores.put(member, score);
        if (old != null) {
            if (old == score) {
                return false;
            }
            list.delete(old, member);
        }
        list.insert(score, member);
        return old == null;
    }

    public boolean remove(String member) {
        Double old = scores.remove(member);
        if (old == null) {
            return false;
        }
        list.delete(old, member);
        return true;
    }

    /** 0-based rank, or -1 if absent. */
    public int rank(String member, boolean reverse) {
        Double s = scores.get(member);
        if (s == null) {
            return -1;
        }
        int r = list.rank(s, member) - 1;
        return reverse ? size() - 1 - r : r;
    }

    public SkipList list() {
        return list;
    }
}
