package org.nexus.consortium.proto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pong protobuf 消息单元测试。
 * 覆盖 builder 与默认实例。
 */
public class PongTest {

    @Test
    public void testDefaultInstance() {
        Pong pong = Pong.getDefaultInstance();
        assertNotNull(pong);
    }

    @Test
    public void testNewBuilder() {
        Pong.Builder builder = Pong.newBuilder();
        assertNotNull(builder);
        Pong pong = builder.build();
        assertNotNull(pong);
    }

    @Test
    public void testGetDefaultInstanceForType() {
        Pong pong = Pong.getDefaultInstance().getDefaultInstanceForType();
        assertNotNull(pong);
    }

    @Test
    public void testImplementsPongOrBuilder() {
        Pong pong = Pong.getDefaultInstance();
        assertTrue(pong instanceof PongOrBuilder);
    }

    @Test
    public void testSerializedSize() {
        Pong pong = Pong.getDefaultInstance();
        assertEquals(0, pong.getSerializedSize());
    }

    @Test
    public void testEqualsSelf() {
        Pong pong = Pong.getDefaultInstance();
        assertEquals(pong, pong);
    }
}