package com.jainam.jredis.command;

import com.jainam.jredis.store.SkipList.ScoreRange;

import java.math.BigDecimal;
import java.util.Locale;

/** Helpers for parsing command arguments and formatting numbers the way Redis does. */
final class Args {
    private Args() { }

    static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            throw CommandException.notInteger();
        }
    }

    static int parseInt(String s) {
        long v = parseLong(s);
        if (v > Integer.MAX_VALUE || v < Integer.MIN_VALUE) {
            throw CommandException.notInteger();
        }
        return (int) v;
    }

    static double parseDouble(String s) {
        String lower = s.toLowerCase(Locale.ROOT);
        double d;
        switch (lower) {
            case "inf", "+inf", "infinity", "+infinity" -> d = Double.POSITIVE_INFINITY;
            case "-inf", "-infinity" -> d = Double.NEGATIVE_INFINITY;
            default -> {
                try {
                    d = Double.parseDouble(s);
                } catch (NumberFormatException e) {
                    throw CommandException.notFloat();
                }
            }
        }
        if (Double.isNaN(d)) {
            throw CommandException.notFloat();
        }
        return d;
    }

    /** "1" not "1.0", "1.5", "inf", "-inf": compact, like Redis replies. */
    static String formatDouble(double d) {
        if (d == Double.POSITIVE_INFINITY) {
            return "inf";
        }
        if (d == Double.NEGATIVE_INFINITY) {
            return "-inf";
        }
        if (d == Math.rint(d) && Math.abs(d) < 1e17) {
            return Long.toString((long) d);
        }
        return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
    }

    /** Parses a score bound: "5", "(5" (exclusive), "-inf", "+inf". */
    static ScoreRange parseRange(String min, String max) {
        boolean minEx = min.startsWith("(");
        boolean maxEx = max.startsWith("(");
        try {
            double lo = parseDouble(minEx ? min.substring(1) : min);
            double hi = parseDouble(maxEx ? max.substring(1) : max);
            return new ScoreRange(lo, minEx, hi, maxEx);
        } catch (CommandException e) {
            throw new CommandException("ERR min or max is not a float");
        }
    }

    /**
     * Converts Redis-style start/stop indexes (negative counts from the end) into a clamped
     * inclusive range [from, to] over {@code size} elements. Returns null if the range is empty.
     */
    static int[] clampRange(long start, long stop, int size) {
        if (start < 0) {
            start = size + start;
        }
        if (stop < 0) {
            stop = size + stop;
        }
        if (start < 0) {
            start = 0;
        }
        if (stop >= size) {
            stop = size - 1;
        }
        if (start > stop || start >= size) {
            return null;
        }
        return new int[] {(int) start, (int) stop};
    }

    static boolean is(String arg, String keyword) {
        return arg.equalsIgnoreCase(keyword);
    }
}
