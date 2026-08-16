package org.nexus.consortium.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MessageFilter 单元测试。
 * 覆盖构造器与 Plugin 接口实现。
 */
public class MessageFilterTest {

    @Test
    public void testImplementsPlugin() {
        PeerServerConfig config = PeerServerConfig.builder().maxPeers(32).build();
        MessageFilter filter = new MessageFilter(config);
        assertNotNull(filter);
        assertTrue(filter instanceof Plugin);
    }

    @Test
    public void testConstructionWithDefaultConfig() {
        PeerServerConfig config = PeerServerConfig.createDefault();
        MessageFilter filter = new MessageFilter(config);
        assertNotNull(filter);
    }

    @Test
    public void testConstructionWithCustomConfig() {
        PeerServerConfig config = PeerServerConfig.builder().maxPeers(100).build();
        MessageFilter filter = new MessageFilter(config);
        assertNotNull(filter);
    }

    @Test
    public void testConstructionWithZeroMaxPeers() {
        PeerServerConfig config = PeerServerConfig.builder().maxPeers(0).build();
        MessageFilter filter = new MessageFilter(config);
        assertNotNull(filter);
    }
}