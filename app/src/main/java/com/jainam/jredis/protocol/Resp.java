package com.jainam.jredis.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

/**
 * A reply in the Redis Serialization Protocol. Every command returns one of these, and
 * {@link #writeTo} turns it into bytes on the wire.
 *
 * <pre>
 *  +OK\r\n                      simple string
 *  -ERR message\r\n             error
 *  :42\r\n                      integer
 *  $5\r\nhello\r\n              bulk string ($-1\r\n is "nil")
 *  *2\r\n...two replies...      array ( *-1\r\n is a nil array)
 * </pre>
 *
 * <p>Modelled as a sealed interface of records: the compiler knows every possible reply type,
 * so a {@code switch} over them can be checked for completeness.
 */
public sealed interface Resp {

    Resp OK = new Simple("OK");
    Resp PONG = new Simple("PONG");
    Resp NIL = new Bulk(null);
    Resp ZERO = new Int(0);
    Resp ONE = new Int(1);
    Resp EMPTY_ARRAY = new Array(List.of());

    record Simple(String value) implements Resp { }

    record Error(String message) implements Resp { }

    record Int(long value) implements Resp { }

    /** A binary-safe string; {@code value == null} is the nil reply. */
    record Bulk(String value) implements Resp { }

    /** An array of replies; {@code items == null} is the nil array. */
    record Array(List<Resp> items) implements Resp { }

    static Resp simple(String s) {
        return new Simple(s);
    }

    static Resp error(String message) {
        return new Error(message);
    }

    static Resp integer(long n) {
        return new Int(n);
    }

    static Resp bulk(String s) {
        return new Bulk(s);
    }

    static Resp bool(boolean b) {
        return b ? ONE : ZERO;
    }

    static Resp array(List<Resp> items) {
        return new Array(items);
    }

    /** An array of bulk strings; {@code null} elements become nil. */
    static Resp bulkArray(Collection<String> values) {
        return new Array(values.stream().map(v -> (Resp) new Bulk(v)).toList());
    }

    default byte[] toBytes() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeTo(out);
        return out.toByteArray();
    }

    default void writeTo(ByteArrayOutputStream out) {
        switch (this) {
            case Simple s -> line(out, '+', s.value());
            case Error e -> line(out, '-', e.message());
            case Int i -> line(out, ':', Long.toString(i.value()));
            case Bulk b -> {
                if (b.value() == null) {
                    line(out, '$', "-1");
                } else {
                    byte[] data = b.value().getBytes(StandardCharsets.ISO_8859_1);
                    line(out, '$', Integer.toString(data.length));
                    out.writeBytes(data);
                    out.write('\r');
                    out.write('\n');
                }
            }
            case Array a -> {
                if (a.items() == null) {
                    line(out, '*', "-1");
                } else {
                    line(out, '*', Integer.toString(a.items().size()));
                    for (Resp item : a.items()) {
                        item.writeTo(out);
                    }
                }
            }
        }
    }

    private static void line(ByteArrayOutputStream out, char type, String text) {
        out.write(type);
        out.writeBytes(text.getBytes(StandardCharsets.ISO_8859_1));
        out.write('\r');
        out.write('\n');
    }

    /** Encodes a command the way clients send it: an array of bulk strings. */
    static byte[] encodeCommand(List<String> args) {
        return bulkArray(args).toBytes();
    }
}
