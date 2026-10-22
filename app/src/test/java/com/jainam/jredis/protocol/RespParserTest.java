package com.jainam.jredis.protocol;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RespParserTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    void parsesArrayOfBulkStrings() {
        RespParser p = new RespParser();
        p.feed(bytes("*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n"));
        assertEquals(List.of("GET", "key"), p.next());
        assertNull(p.next());
        assertEquals(0, p.buffered());
    }

    @Test
    void waitsForMoreBytesWhenCommandIsSplitAcrossReads() {
        String cmd = "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$5\r\nhello\r\n";
        RespParser p = new RespParser();
        // Feed one byte at a time: the worst case of TCP fragmentation.
        for (int i = 0; i < cmd.length() - 1; i++) {
            p.feed(bytes(cmd.substring(i, i + 1)));
            assertNull(p.next(), "should not return a command after " + (i + 1) + " bytes");
        }
        p.feed(bytes(cmd.substring(cmd.length() - 1)));
        assertEquals(List.of("SET", "k", "hello"), p.next());
    }

    @Test
    void parsesSeveralPipelinedCommandsFromOneRead() {
        RespParser p = new RespParser();
        p.feed(bytes("*1\r\n$4\r\nPING\r\n*2\r\n$4\r\nECHO\r\n$2\r\nhi\r\nPING\r\n"));
        assertEquals(List.of("PING"), p.next());
        assertEquals(List.of("ECHO", "hi"), p.next());
        assertEquals(List.of("PING"), p.next());
        assertNull(p.next());
    }

    @Test
    void bulkStringsAreBinarySafe() {
        RespParser p = new RespParser();
        p.feed(bytes("*2\r\n$4\r\nECHO\r\n$4\r\na\r\nb\r\n"));
        assertEquals(List.of("ECHO", "a\r\nb"), p.next());
        byte[] binary = {'*', '1', '\r', '\n', '$', '2', '\r', '\n', (byte) 0xFF, 0x00, '\r', '\n'};
        p.feed(binary);
        assertEquals("ÿ\u0000", p.next().get(0));
    }

    @Test
    void parsesInlineCommandsWithQuotes() {
        RespParser p = new RespParser();
        p.feed(bytes("SET greeting \"hello world\"\r\nGET   greeting\n\r\n"));
        assertEquals(List.of("SET", "greeting", "hello world"), p.next());
        assertEquals(List.of("GET", "greeting"), p.next());
        assertNull(p.next()); // the blank line is skipped
    }

    @Test
    void rejectsGarbage() {
        RespParser p = new RespParser();
        p.feed(bytes("*1\r\n+oops\r\n"));
        assertThrows(ProtocolException.class, p::next);

        RespParser q = new RespParser();
        q.feed(bytes("*x\r\n"));
        assertThrows(ProtocolException.class, q::next);

        RespParser r = new RespParser();
        r.feed(bytes("*1\r\n$3\r\nabcXY"));
        assertThrows(ProtocolException.class, r::next);

        RespParser s = new RespParser();
        s.feed(bytes("SET a \"unclosed\r\n"));
        assertThrows(ProtocolException.class, s::next);
    }

    @Test
    void handlesLargeValuesThatGrowTheBuffer() {
        String big = "x".repeat(100_000);
        RespParser p = new RespParser();
        byte[] cmd = Resp.encodeCommand(List.of("SET", "k", big));
        p.feed(cmd, 0, 10);
        assertNull(p.next());
        p.feed(cmd, 10, cmd.length - 10);
        assertEquals(big, p.next().get(2));
    }

    @Test
    void encodesReplies() {
        assertEquals("+OK\r\n", new String(Resp.OK.toBytes(), StandardCharsets.ISO_8859_1));
        assertEquals("-ERR bad\r\n", new String(Resp.error("ERR bad").toBytes(), StandardCharsets.ISO_8859_1));
        assertEquals(":42\r\n", new String(Resp.integer(42).toBytes(), StandardCharsets.ISO_8859_1));
        assertEquals("$-1\r\n", new String(Resp.NIL.toBytes(), StandardCharsets.ISO_8859_1));
        assertEquals("*2\r\n$1\r\na\r\n$-1\r\n",
                new String(Resp.bulkArray(java.util.Arrays.asList("a", null)).toBytes(), StandardCharsets.ISO_8859_1));
    }
}
