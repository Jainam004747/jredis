package com.jainam.jredis.store;

import java.util.ArrayList;
import java.util.List;

/**
 * The value type behind Redis lists: a growable circular buffer of strings.
 *
 * <p>Lists need fast pushes and pops at <em>both</em> ends (LPUSH, RPOP, ...) and fast access by
 * index (LINDEX, LRANGE). An ArrayList is O(n) at the front; a LinkedList is O(n) by index;
 * {@code java.util.ArrayDeque} has no index access at all. A circular buffer gives O(1) for all
 * of them: elements live in an array that "wraps around", with {@code head} marking where the
 * first element sits.
 *
 * <pre>
 *  capacity 8, size 5, head 6:   [ c  d  e  .  .  .  a  b ]
 *                                  logical order: a b c d e
 *  physical index of logical i = (head + i) % capacity
 * </pre>
 */
public final class RList {
    private String[] items = new String[8];
    private int head;
    private int size;

    public int size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    public void addFirst(String value) {
        grow();
        head = (head - 1 + items.length) % items.length;
        items[head] = value;
        size++;
    }

    public void addLast(String value) {
        grow();
        items[(head + size) % items.length] = value;
        size++;
    }

    public String removeFirst() {
        if (size == 0) {
            return null;
        }
        String v = items[head];
        items[head] = null; // let the garbage collector reclaim it
        head = (head + 1) % items.length;
        size--;
        return v;
    }

    public String removeLast() {
        if (size == 0) {
            return null;
        }
        int idx = (head + size - 1) % items.length;
        String v = items[idx];
        items[idx] = null;
        size--;
        return v;
    }

    /** Element at logical index {@code i} (0 = first). */
    public String get(int i) {
        checkIndex(i);
        return items[(head + i) % items.length];
    }

    public void set(int i, String value) {
        checkIndex(i);
        items[(head + i) % items.length] = value;
    }

    /** Elements {@code from..to} inclusive, already clamped by the caller. */
    public List<String> range(int from, int to) {
        List<String> out = new ArrayList<>(Math.max(0, to - from + 1));
        for (int i = from; i <= to; i++) {
            out.add(get(i));
        }
        return out;
    }

    /** Keeps only {@code from..to} inclusive (LTRIM). Indexes are already clamped. */
    public void trim(int from, int to) {
        List<String> kept = from <= to ? range(from, to) : List.of();
        clear();
        kept.forEach(this::addLast);
    }

    /**
     * Removes up to {@code count} occurrences of {@code value} (LREM): count &gt; 0 from the head,
     * count &lt; 0 from the tail, 0 for all. Returns how many were removed.
     */
    public int remove(String value, int count) {
        List<String> all = range(0, size - 1);
        int limit = count == 0 ? Integer.MAX_VALUE : Math.abs(count);
        int removed = 0;
        boolean fromTail = count < 0;
        List<String> kept = new ArrayList<>(all.size());
        for (int k = 0; k < all.size(); k++) {
            int idx = fromTail ? all.size() - 1 - k : k;
            String v = all.get(idx);
            if (removed < limit && v.equals(value)) {
                removed++;
                continue;
            }
            kept.add(v);
        }
        if (fromTail) {
            java.util.Collections.reverse(kept);
        }
        clear();
        kept.forEach(this::addLast);
        return removed;
    }

    public void clear() {
        items = new String[8];
        head = 0;
        size = 0;
    }

    private void grow() {
        if (size < items.length) {
            return;
        }
        String[] bigger = new String[items.length * 2];
        for (int i = 0; i < size; i++) {
            bigger[i] = items[(head + i) % items.length];
        }
        items = bigger;
        head = 0;
    }

    private void checkIndex(int i) {
        if (i < 0 || i >= size) {
            throw new IndexOutOfBoundsException(i);
        }
    }
}
