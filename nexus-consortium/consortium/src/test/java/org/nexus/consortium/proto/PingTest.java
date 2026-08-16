package org.nexus.consortium.proto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ping protobuf 消息单元测试。
 * 覆盖 builder 与默认实例。
 */
public class PingTest {

    @Test
    public void testDefaultInstance() {
        Ping ping = Ping.getDefaultInstance();
        assertNotNull(ping);
    }

    @Test
    public void testNewBuilder() {
        Ping.Builder builder = Ping.newBuilder();
        assertNotNull(builder);
        Ping ping = builder.build();
        assertNotNull(ping);
    }

    @Test
    public void testGetDefaultInstanceForType() {
        Ping ping = Ping.getDefaultInstance().getDefaultInstanceForType();
        assertNotNull(ping);
    }

    @Test
    public void testImplementsPingOrBuilder() {
        Ping ping = Ping.getDefaultInstance();
        assertTrue(ping instanceof PingOrBuilder);
    }

    @Test
    public void testSerializedSize() {
        Ping ping = Ping.getDefaultInstance();
        assertEquals(0, ping.getSerializedSize());
    }

    @Test
    public void testEqualsSelf() {
        Ping ping = Ping.getDefaultInstance();
        assertEquals(ping, ping);
    }

}