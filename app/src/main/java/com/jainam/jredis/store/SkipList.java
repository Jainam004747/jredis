package com.jainam.jredis.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * An ordered set of (score, member) pairs: the index behind sorted sets (ZADD, ZRANGE, ZRANK).
 *
 * <p>A skip list is a linked list with "express lanes". Level 0 links every node in order.
 * Each node is also promoted to level 1 with probability 1/4, to level 2 with probability 1/16,
 * and so on. A search starts in the highest, sparsest lane and drops down a level whenever the
 * next hop would overshoot, so it touches O(log n) nodes on average, like a balanced tree but
 * with far simpler code (no rotations).
 *
 * <pre>
 *  level 2: head ---------------------------> 30 ------------------> nil
 *  level 1: head ---------> 10 -------------> 30 -------> 50 ------> nil
 *  level 0: head -> 5 ----> 10 ----> 20 ----> 30 -> 40 -> 50 -> 60 -> nil
 * </pre>
 *
 * <p>Each forward link also stores its <em>span</em>: how many level-0 nodes it jumps over.
 * Adding up spans along the search path gives an element's rank (position) in O(log n), which
 * is what makes ZRANK and "ZRANGE key 1000 1010" fast.
 *
 * <p>Order is by score, then by member (lexicographically) for equal scores, exactly like Redis.
 */
public final class SkipList {
    static final int MAX_LEVEL = 32;
    private static final double P = 0.25;

    /** One element. {@code forward[i]} / {@code span[i]} describe its link on level i. */
    public static final class Node {
        final String member;
        final double score;
        final Node[] forward;
        final int[] span;
        Node backward;

        Node(int level, double score, String member) {
            this.member = member;
            this.score = score;
            this.forward = new Node[level];
            this.span = new int[level];
        }

        public String member() {
            return member;
        }

        public double score() {
            return score;
        }
    }

    private final Node header = new Node(MAX_LEVEL, 0, null);
    private final Random random;
    private Node tail;
    private int level = 1;
    private int length;

    public SkipList() {
        this(new Random());
    }

    /** A fixed seed makes the structure reproducible in tests. */
    public SkipList(Random random) {
        this.random = random;
    }

    public int size() {
        return length;
    }

    /** Inserts a member that is known not to be present (the caller checks with a hash map). */
    public void insert(double score, String member) {
        Node[] update = new Node[MAX_LEVEL];
        int[] rank = new int[MAX_LEVEL];
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            rank[i] = (i == level - 1) ? 0 : rank[i + 1];
            while (x.forward[i] != null && less(x.forward[i], score, member)) {
                rank[i] += x.span[i];
                x = x.forward[i];
            }
            update[i] = x; // the last node before the new one, on level i
        }
        int newLevel = randomLevel();
        if (newLevel > level) {
            for (int i = level; i < newLevel; i++) {
                rank[i] = 0;
                update[i] = header;
                update[i].span[i] = length;
            }
            level = newLevel;
        }
        x = new Node(newLevel, score, member);
        for (int i = 0; i < newLevel; i++) {
            x.forward[i] = update[i].forward[i];
            update[i].forward[i] = x;
            // update[i] used to jump span[i] nodes; the new node splits that jump in two.
            x.span[i] = update[i].span[i] - (rank[0] - rank[i]);
            update[i].span[i] = (rank[0] - rank[i]) + 1;
        }
        for (int i = newLevel; i < level; i++) {
            update[i].span[i]++; // higher links now jump over one more node
        }
        x.backward = (update[0] == header) ? null : update[0];
        if (x.forward[0] != null) {
            x.forward[0].backward = x;
        } else {
            tail = x;
        }
        length++;
    }

    /** Removes the exact (score, member) pair. Returns false if it was not present. */
    public boolean delete(double score, String member) {
        Node[] update = new Node[MAX_LEVEL];
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && less(x.forward[i], score, member)) {
                x = x.forward[i];
            }
            update[i] = x;
        }
        x = x.forward[0];
        if (x == null || x.score != score || !x.member.equals(member)) {
            return false;
        }
        for (int i = 0; i < level; i++) {
            if (update[i].forward[i] == x) {
                update[i].span[i] += x.span[i] - 1;
                update[i].forward[i] = x.forward[i];
            } else {
                update[i].span[i] -= 1;
            }
        }
        if (x.forward[0] != null) {
            x.forward[0].backward = x.backward;
        } else {
            tail = x.backward;
        }
        while (level > 1 && header.forward[level - 1] == null) {
            level--;
        }
        length--;
        return true;
    }

    /** 1-based rank of the pair, or 0 if absent. */
    public int rank(double score, String member) {
        int rank = 0;
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && !less(member, score, x.forward[i])) {
                rank += x.span[i];
                x = x.forward[i];
            }
            if (x != header && x.member.equals(member)) {
                return rank;
            }
        }
        return 0;
    }

    /** The node at 1-based rank {@code r}, or null. */
    public Node byRank(int r) {
        int traversed = 0;
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && traversed + x.span[i] <= r) {
                traversed += x.span[i];
                x = x.forward[i];
            }
            if (traversed == r) {
                return x;
            }
        }
        return null;
    }

    /** Nodes with 0-based ranks {@code from..to} inclusive (already clamped), in order. */
    public List<Node> rangeByRank(int from, int to, boolean reverse) {
        List<Node> out = new ArrayList<>(Math.max(0, to - from + 1));
        if (from > to) {
            return out;
        }
        if (!reverse) {
            Node x = byRank(from + 1);
            for (int i = from; i <= to && x != null; i++, x = x.forward[0]) {
                out.add(x);
            }
        } else {
            Node x = byRank(length - from);
            for (int i = from; i <= to && x != null; i++, x = x.backward) {
                out.add(x);
            }
        }
        return out;
    }

    /** Nodes whose score is within the range, in ascending order. */
    public List<Node> rangeByScore(ScoreRange range) {
        List<Node> out = new ArrayList<>();
        Node x = header;
        for (int i = level - 1; i >= 0; i--) {
            while (x.forward[i] != null && !range.aboveMin(x.forward[i].score)) {
                x = x.forward[i];
            }
        }
        x = x.forward[0];
        while (x != null && range.belowMax(x.score)) {
            out.add(x);
            x = x.forward[0];
        }
        return out;
    }

    /** Nodes in ascending order (used by persistence). */
    public List<Node> all() {
        return rangeByRank(0, length - 1, false);
    }

    /** True if node {@code n} sorts before (score, member). */
    private static boolean less(Node n, double score, String member) {
        return n.score < score || (n.score == score && n.member.compareTo(member) < 0);
    }

    /** True if (score, member) sorts before node {@code n}. */
    private static boolean less(String member, double score, Node n) {
        return score < n.score || (score == n.score && member.compareTo(n.member) < 0);
    }

    private int randomLevel() {
        int lvl = 1;
        while (lvl < MAX_LEVEL && random.nextDouble() < P) {
            lvl++;
        }
        return lvl;
    }

    /** A score interval such as [1, 5], (1, 5] or [-inf, +inf]. */
    public record ScoreRange(double min, boolean minExclusive, double max, boolean maxExclusive) {
        boolean aboveMin(double score) {
            return minExclusive ? score > min : score >= min;
        }

        boolean belowMax(double score) {
            return maxExclusive ? score < max : score <= max;
        }

        public boolean contains(double score) {
            return aboveMin(score) && belowMax(score);
        }
    }
}
