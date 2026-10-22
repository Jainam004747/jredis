package com.jainam.jredis.console;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A deliberately tiny JSON writer and reader for the console's HTTP API, so the project needs
 * no external libraries. It writes maps, lists, strings, numbers and booleans, and reads one
 * flat object of strings and numbers (all the API's request bodies need).
 */
final class Json {
    private Json() { }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case String s -> string(sb, s);
            case Number n -> sb.append(n);
            case Boolean b -> sb.append(b);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    string(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            }
            case Collection<?> c -> {
                sb.append('[');
                boolean first = true;
                for (Object o : c) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    write(sb, o);
                }
                sb.append(']');
            }
            default -> string(sb, v.toString());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20 || c == '<' || c == '>') { // also escape < > so values can never inject HTML
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    /** Parses {"a":"x","b":12}. Values are returned as strings. */
    static Map<String, String> parseObject(String json) {
        Map<String, String> out = new LinkedHashMap<>();
        Reader r = new Reader(json);
        r.skipWs();
        r.expect('{');
        r.skipWs();
        if (r.peek() == '}') {
            return out;
        }
        while (true) {
            r.skipWs();
            String key = r.string();
            r.skipWs();
            r.expect(':');
            r.skipWs();
            String value = r.peek() == '"' ? r.string() : r.bare();
            out.put(key, value);
            r.skipWs();
            if (r.peek() == ',') {
                r.pos++;
                continue;
            }
            r.expect('}');
            return out;
        }
    }

    private static final class Reader {
        final String s;
        int pos;

        Reader(String s) {
            this.s = s;
        }

        char peek() {
            if (pos >= s.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            return s.charAt(pos);
        }

        void expect(char c) {
            if (peek() != c) {
                throw new IllegalArgumentException("expected '" + c + "' at " + pos);
            }
            pos++;
        }

        void skipWs() {
            while (pos < s.length() && Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                pos++;
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = peek();
                    pos++;
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        String bare() {
            int start = pos;
            while (pos < s.length() && ",}".indexOf(s.charAt(pos)) < 0 && !Character.isWhitespace(s.charAt(pos))) {
                pos++;
            }
            String v = s.substring(start, pos);
            return v.equals("null") ? null : v;
        }
    }
}
