package org.nexus.consortium.proto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Code 枚举单元测试。
 * 覆盖 protobuf 生成的 Code 枚举值。
 */
public class CodeTest {

    @Test
    public void testNothingValue() {
        assertEquals(0, Code.NOTHING.getNumber());
    }

    @Test
    public void testPingValue() {
        assertEquals(1, Code.PING.getNumber());
    }

    @Test
    public void testPongValue() {
        assertEquals(2, Code.PONG.getNumber());
    }

    @Test
    public void testLookUpValue() {
        assertEquals(3, Code.LOOK_UP.getNumber());
    }

    @Test
    public void testPeersValue() {
        assertEquals(4, Code.PEERS.getNumber());
    }

    @Test
    public void testForNumberNothing() {
        Code code = Code.forNumber(0);
        assertEquals(Code.NOTHING, code);
    }

    @Test
    public void testForNumberPing() {
        Code code = Code.forNumber(1);
        assertEquals(Code.PING, code);
    }

    @Test
    public void testForNumberPong() {
        Code code = Code.forNumber(2);
        assertEquals(Code.PONG, code);
    }

    @Test
    public void testForNumberLookUp() {
        Code code = Code.forNumber(3);
        assertEquals(Code.LOOK_UP, code);
    }

    @Test
    public void testForNumberPeers() {
        Code code = Code.forNumber(4);
        assertEquals(Code.PEERS, code);
    }

    @Test
    public void testForNumberInvalid() {
        Code code = Code.forNumber(-1);
        assertNull(code);
    }

    @Test
    public void testValueOfNothing() {
        Code code = Code.valueOf("NOTHING");
        assertEquals(Code.NOTHING, code);
    }

    @Test
    public void testValueOfPing() {
        Code code = Code.valueOf("PING");
        assertEquals(Code.PING, code);
    }

    @Test

    public void testValues() {
        Code[] values = Code.values();
        assertNotNull(values);
        assertTrue(values.length >= 5);
    }
}