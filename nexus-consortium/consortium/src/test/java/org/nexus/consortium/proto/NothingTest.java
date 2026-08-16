package org.nexus.consortium.proto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Nothing protobuf 消息单元测试。
 * 覆盖 builder 与默认实例。
 */
public class NothingTest {

    @Test
    public void testDefaultInstance() {
        Nothing nothing = Nothing.getDefaultInstance();
        assertNotNull(nothing);
    }

    @Test
    public void testNewBuilder() {
        Nothing.Builder builder = Nothing.newBuilder();
        assertNotNull(builder);
        Nothing nothing = builder.build();
        assertNotNull(nothing);
    }

    @Test
    public void testGetDefaultInstanceForType() {
        Nothing nothing = Nothing.getDefaultInstance().getDefaultInstanceForType();
        assertNotNull(nothing);
    }

    @Test
    public void testImplementsNothingOrBuilder() {
        Nothing nothing = Nothing.getDefaultInstance();
        assertTrue(nothing instanceof NothingOrBuilder);
    }

    @Test
    public void testSerializedSize() {
        Nothing nothing = Nothing.getDefaultInstance();
        assertEquals(0, nothing.getSerializedSize());
    }

    @Test
    public void testEqualsSelf() {
        Nothing nothing = Nothing.getDefaultInstance();
        assertEquals(nothing, nothing);
    }
}