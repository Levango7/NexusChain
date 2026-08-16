package org.nexus.consortium.net;

import org.junit.jupiter.api.Test;
import org.nexus.common.PeerServerListener;
import org.nexus.common.Peer;
import org.nexus.common.PeerServer;
import org.nexus.common.Context;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PluginWrapper 单元测试。
 * 覆盖 Plugin 接口实现与 PeerServerListener 代理。
 */
public class PluginWrapperTest {

    @Test
    public void testImplementsPlugin() {
        PluginWrapper wrapper = new PluginWrapper(new TestListener());
        assertNotNull(wrapper);
        assertTrue(wrapper instanceof Plugin);
    }

    @Test
    public void testConstructionWithNull() {
        PluginWrapper wrapper = new PluginWrapper(null);
        assertNotNull(wrapper);
    }

    private static class TestListener implements PeerServerListener {
        @Override
        public void onMessage(Context context, PeerServer server) {}
        @Override
        public void onStart(PeerServer server) {}
        @Override
        public void onNewPeer(Peer peer, PeerServer server) {}
        @Override
        public void onDisconnect(Peer peer, PeerServer server) {}
    }
}
