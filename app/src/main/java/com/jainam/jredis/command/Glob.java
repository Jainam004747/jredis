package com.jainam.jredis.command;

/**
 * Glob-style pattern matching used by KEYS: {@code *} any run of characters, {@code ?} any one
 * character, {@code [abc]} / {@code [a-z]} / {@code [^a]} character classes, {@code \x} a literal.
 *
 * <p>Implemented with backtracking on the last {@code *}, which is linear for typical patterns
 * and avoids building a regular expression for every call.
 */
final class Glob {
    private Glob() { }

    static boolean matches(String pattern, String text) {
        int p = 0;
        int t = 0;
        int starP = -1;
        int starT = -1;
        while (t < text.length()) {
            if (p < pattern.length()) {
                char c = pattern.charAt(p);
                if (c == '*') {
                    starP = p++;
                    starT = t;
                    continue;
                }
                int consumed = matchOne(pattern, p, text.charAt(t));
                if (consumed > 0) {
                    p += consumed;
                    t++;
                    continue;
                }
            }
            if (starP >= 0) {
                p = starP + 1; // let the last '*' absorb one more character and retry
                t = ++starT;
                continue;
            }
            return false;
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') {
            p++;
        }
        return p == pattern.length();
    }

    /**
     * Tries to match one pattern element at {@code p} against {@code ch}. Returns how many
     * pattern characters the element used, or 0 if it does not match.
     */
    private static int matchOne(String pattern, int p, char ch) {
        char c = pattern.charAt(p);
        switch (c) {
            case '?':
                return 1;
            case '\\':
                if (p + 1 < pattern.length()) {
                    return pattern.charAt(p + 1) == ch ? 2 : 0;
                }
                return ch == '\\' ? 1 : 0;
            case '[': {
                int i = p + 1;
                boolean negate = i < pattern.length() && pattern.charAt(i) == '^';
                if (negate) {
                    i++;
                }
                boolean found = false;
                while (i < pattern.length() && pattern.charAt(i) != ']') {
                    char a = pattern.charAt(i);
                    if (a == '\\' && i + 1 < pattern.length()) {
                        a = pattern.charAt(++i);
                        found |= a == ch;
                    } else if (i + 2 < pattern.length() && pattern.charAt(i + 1) == '-' && pattern.charAt(i + 2) != ']') {
                        char b = pattern.charAt(i + 2);
                        found |= ch >= Math.min(a, b) && ch <= Math.max(a, b);
                        i += 2;
                    } else {
                        found |= a == ch;
                    }
                    i++;
                }
                if (i >= pattern.length()) {
                    return ch == '[' ? 1 : 0; // unterminated class: treat '[' literally
                }
                return found != negate ? i - p + 1 : 0;
            }
            default:
                return c == ch ? 1 : 0;
        }
    }
}
