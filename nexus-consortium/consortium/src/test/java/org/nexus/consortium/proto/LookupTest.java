package org.nexus.consortium.proto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Lookup protobuf 消息单元测试。
 * 覆盖 builder 与默认实例。
 */
public class LookupTest {

    @Test
    public void testDefaultInstance() {
        Lookup lookup = Lookup.getDefaultInstance();
        assertNotNull(lookup);
    }

    @Test
    public void testNewBuilder() {
        Lookup.Builder builder = Lookup.newBuilder();
        assertNotNull(builder);
        Lookup lookup = builder.build();
        assertNotNull(lookup);
    }

    @Test
    public void testGetDefaultInstanceForType() {
        Lookup lookup = Lookup.getDefaultInstance().getDefaultInstanceForType();
        assertNotNull(lookup);
    }

    @Test
    public void testImplementsLookupOrBuilder() {
        Lookup lookup = Lookup.getDefaultInstance();
        assertTrue(lookup instanceof LookupOrBuilder);
    }

    @Test
    public void testSerializedSize() {
        Lookup lookup = Lookup.getDefaultInstance();
        assertEquals(0, lookup.getSerializedSize());
    }

    @Test
    public void testEqualsSelf() {
        Lookup lookup = Lookup.getDefaultInstance();
        assertEquals(lookup, lookup);
    }
}