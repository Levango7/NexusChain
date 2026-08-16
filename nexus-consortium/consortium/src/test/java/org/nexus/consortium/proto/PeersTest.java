package org.nexus.consortium.proto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Peers protobuf 消息单元测试。
 * 覆盖 builder 与默认实例。
 */
public class PeersTest {

    @Test
    public void testDefaultInstance() {
        Peers peers = Peers.getDefaultInstance();
        assertNotNull(peers);
    }

    @Test
    public void testNewBuilder() {
        Peers.Builder builder = Peers.newBuilder();
        assertNotNull(builder);
        Peers peers = builder.build();
        assertNotNull(peers);
    }

    @Test
    public void testGetDefaultInstanceForType() {
        Peers peers = Peers.getDefaultInstance().getDefaultInstanceForType();
        assertNotNull(peers);
    }

    @Test
    public void testImplementsPeersOrBuilder() {
        Peers peers = Peers.getDefaultInstance();
        assertTrue(peers instanceof PeersOrBuilder);
    }

    @Test
    public void testSerializedSize() {
        Peers peers = Peers.getDefaultInstance();
        assertEquals(0, peers.getSerializedSize());
    }

    @Test
    public void testEqualsSelf() {
        Peers peers = Peers.getDefaultInstance();
        assertEquals(peers, peers);
    }

    @Test
    public void testGetPeersListEmpty() {
        Peers peers = Peers.getDefaultInstance();
        assertNotNull(peers.getPeersList());
        assertEquals(0, peers.getPeersCount());
    }
}