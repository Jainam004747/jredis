package com.jainam.jredis.protocol;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Incremental parser for client requests in the Redis Serialization Protocol (RESP).
 *
 * <p>TCP is a byte stream, not a message stream: one {@code read()} can return half a command,
 * or three commands at once. So bytes are {@link #feed fed} in as they arrive and
 * {@link #next()} returns one complete command at a time, or {@code null} when more bytes
 * are needed. Nothing is consumed until a whole command is available.
 *
 * <p>Two request formats are accepted, like real Redis:
 * <ul>
 *   <li>RESP arrays of bulk strings, sent by redis-cli and client libraries:
 *       {@code *2\r\n$3\r\nGET\r\n$3\r\nkey\r\n}</li>
 *   <li>Inline commands, typed by hand in nc or telnet: {@code GET key\r\n}</li>
 * </ul>
 *
 * <p>Arguments are decoded as ISO-8859-1, which maps every byte 0-255 to exactly one char.
 * That keeps values binary-safe: any byte sequence survives the round trip unchanged.
 */
public final class RespParser {
    /** Upper bounds that protect the server from a malicious or broken client. */
    static final int MAX_ARGS = 1024 * 1024;
    static final int MAX_BULK_LENGTH = 512 * 1024 * 1024;
    static final int MAX_INLINE_LENGTH = 64 * 1024;

    private byte[] buf = new byte[4096];
    private int start; // first unconsumed byte
    private int end;   // one past the last byte written

    /** Appends newly received bytes. */
    public void feed(byte[] data, int off, int len) {
        ensureCapacity(len);
        System.arraycopy(data, off, buf, end, len);
        end += len;
    }

    public void feed(byte[] data) {
        feed(data, 0, data.length);
    }

    /** Number of bytes received but not yet consumed by a complete command. */
    public int buffered() {
        return end - start;
    }

    /**
     * Returns the next complete command as a list of arguments (the first is the command name),
     * or {@code null} if the buffer does not yet hold a whole command.
     *
     * @throws ProtocolException if the bytes can never form a valid command
     */
    public List<String> next() {
        while (start < end) {
            int pos = start;
            List<String> command;
            if (buf[pos] == '*') {
                command = parseArray(pos);
            } else {
                command = parseInline(pos);
            }
            if (command == null) {
                return null; // incomplete: wait for more bytes, consume nothing
            }
            if (!command.isEmpty()) {
                return command;
            }
            // An empty line or empty array: skip it and keep looking.
        }
        compact();
        return null;
    }

    // ---- RESP array: *<count>\r\n then <count> x ($<len>\r\n<bytes>\r\n) ----

    private List<String> parseArray(int pos) {
        int lineEnd = findCrlf(pos);
        if (lineEnd < 0) {
            return null;
        }
        long count = parseLong(pos + 1, lineEnd, "array length");
        if (count > MAX_ARGS) {
            throw new ProtocolException("invalid multibulk length");
        }
        pos = lineEnd + 2;
        if (count <= 0) {
            consumeTo(pos);
            return List.of();
        }
        List<String> args = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            if (pos >= end) {
                return null;
            }
            if (buf[pos] != '$') {
                throw new ProtocolException("expected '$', got '" + (char) buf[pos] + "'");
            }
            int lenEnd = findCrlf(pos);
            if (lenEnd < 0) {
                return null;
            }
            long len = parseLong(pos + 1, lenEnd, "bulk length");
            if (len < 0 || len > MAX_BULK_LENGTH) {
                throw new ProtocolException("invalid bulk length");
            }
            int dataStart = lenEnd + 2;
            long dataEnd = dataStart + len;
            if (dataEnd + 2 > end) {
                ensureCapacity((int) (dataEnd + 2 - end)); // pre-grow for the big value we know is coming
                return null;
            }
            if (buf[(int) dataEnd] != '\r' || buf[(int) dataEnd + 1] != '\n') {
                throw new ProtocolException("bulk string not terminated by CRLF");
            }
            args.add(new String(buf, dataStart, (int) len, StandardCharsets.ISO_8859_1));
            pos = (int) dataEnd + 2;
        }
        consumeTo(pos);
        return args;
    }

    // ---- Inline command: words separated by spaces, ended by \n or \r\n ----

    private List<String> parseInline(int pos) {
        int nl = -1;
        for (int i = pos; i < end; i++) {
            if (buf[i] == '\n') {
                nl = i;
                break;
            }
        }
        if (nl < 0) {
            if (end - pos > MAX_INLINE_LENGTH) {
                throw new ProtocolException("too big inline request");
            }
            return null;
        }
        int lineEnd = (nl > pos && buf[nl - 1] == '\r') ? nl - 1 : nl;
        String line = new String(buf, pos, lineEnd - pos, StandardCharsets.ISO_8859_1);
        consumeTo(nl + 1);
        return splitInline(line);
    }

    /** Splits an inline line on whitespace, honouring "double" and 'single' quotes. */
    static List<String> splitInline(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inToken = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else if (c == '\\' && quote == '"' && i + 1 < line.length()) {
                    char n = line.charAt(++i);
                    cur.append(switch (n) {
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> n;
                    });
                } else {
                    cur.append(c);
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                inToken = true;
            } else if (Character.isWhitespace(c)) {
                if (inToken) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inToken = false;
                }
            } else {
                cur.append(c);
                inToken = true;
            }
        }
        if (quote != 0) {
            throw new ProtocolException("unbalanced quotes in request");
        }
        if (inToken) {
            out.add(cur.toString());
        }
        return out;
    }

    // ---- helpers ----

    private int findCrlf(int from) {
        for (int i = from; i + 1 < end; i++) {
            if (buf[i] == '\r' && buf[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private long parseLong(int from, int to, String what) {
        if (from >= to) {
            throw new ProtocolException("invalid " + what);
        }
        boolean negative = buf[from] == '-';
        int i = negative ? from + 1 : from;
        if (i >= to || to - i > 18) {
            throw new ProtocolException("invalid " + what);
        }
        long value = 0;
        for (; i < to; i++) {
            byte b = buf[i];
            if (b < '0' || b > '9') {
                throw new ProtocolException("invalid " + what);
            }
            value = value * 10 + (b - '0');
        }
        return negative ? -value : value;
    }

    private void consumeTo(int pos) {
        start = pos;
        if (start == end) {
            start = 0;
            end = 0;
        }
    }

    /** Moves unconsumed bytes to the front so the buffer does not grow forever. */
    private void compact() {
        if (start > 0) {
            System.arraycopy(buf, start, buf, 0, end - start);
            end -= start;
            start = 0;
        }
    }

    private void ensureCapacity(int extra) {
        if (end + extra <= buf.length) {
            return;
        }
        compact();
        if (end + extra > buf.length) {
            int newSize = Math.max(buf.length * 2, end + extra);
            buf = Arrays.copyOf(buf, newSize);
        }
    }
}
