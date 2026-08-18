package org.nexus.p2p;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * {@link PeersManager} 单元测试。
 * <p>
 * Mock PeerServer 测 PING/PONG/LOOK_UP/PEERS 分发。
 */
@ExtendWith(MockitoExtension.class)
class PeersManagerTest {

    @Mock
    private PeerServer server;

    private PeersManager pmgr;
    private Peer self;
    private Peer remote;

    @BeforeEach
    void setUp() {
        pmgr = new PeersManager();
        self = PeerTestFixture.newZeroPeer("127.0.0.1", 9000);
        remote = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
    }

    private Context contextWithCode(NexusChainOuterClass.Code code, ByteString body) {
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(code, body, remote);
        Payload payload;
        try {
            payload = new Payload(msg);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Context ctx = new Context();
        ctx.payload = payload;
        return ctx;
    }

    @Test
    void onPingRespondsWithPongAndPends() {
        Context ctx = contextWithCode(NexusChainOuterClass.Code.PING,
                NexusChainOuterClass.Ping.newBuilder().build().toByteString());
        pmgr.onMessage(ctx, server);
        assertNotNull(ctx.response);
        assertTrue(ctx.response instanceof NexusChainOuterClass.Pong);
        assertTrue(ctx.pending);
    }

    @Test
    void onPongSetsKeep() {
        Context ctx = contextWithCode(NexusChainOuterClass.Code.PONG,
                NexusChainOuterClass.Pong.newBuilder().build().toByteString());
        pmgr.onMessage(ctx, server);
        assertTrue(ctx.keep);
    }

    @Test
    void onLookupRespondsWithPeersList() {
        when(server.getPeers()).thenReturn(Arrays.asList(remote));
        when(server.getSelf()).thenReturn(self);
        Context ctx = contextWithCode(NexusChainOuterClass.Code.LOOK_UP,
                NexusChainOuterClass.Lookup.newBuilder().build().toByteString());
        pmgr.onMessage(ctx, server);
        assertNotNull(ctx.response);
        assertTrue(ctx.response instanceof NexusChainOuterClass.Peers);
        NexusChainOuterClass.Peers peers = (NexusChainOuterClass.Peers) ctx.response;
        // 应包含 server.getPeers() + server.getSelf()
        assertTrue(peers.getPeersList().contains(remote.toString()));
        assertTrue(peers.getPeersList().contains(self.toString()));
    }

    @Test
    void onLookupWithEmptyPeersReturnsOnlySelf() {
        when(server.getPeers()).thenReturn(Collections.emptyList());
        when(server.getSelf()).thenReturn(self);
        Context ctx = contextWithCode(NexusChainOuterClass.Code.LOOK_UP,
                NexusChainOuterClass.Lookup.newBuilder().build().toByteString());
        pmgr.onMessage(ctx, server);
        assertNotNull(ctx.response);
        NexusChainOuterClass.Peers peers = (NexusChainOuterClass.Peers) ctx.response;
        assertEquals(1, peers.getPeersList().size());
        assertEquals(self.toString(), peers.getPeersList().get(0));
    }

    @Test
    void onPeersParsesAndPendsEachPeer() {
        // Peer.parse 需要合法 nexus:// URL
        Peer p1 = PeerTestFixture.newPeerWithByte(0, (byte) 0x10, "10.0.0.10", 9010);
        Peer p2 = PeerTestFixture.newPeerWithByte(0, (byte) 0x20, "10.0.0.20", 9020);
        NexusChainOuterClass.Peers body = NexusChainOuterClass.Peers.newBuilder()
                .addPeers(p1.toString())
                .addPeers(p2.toString())
                .build();
        Context ctx = contextWithCode(NexusChainOuterClass.Code.PEERS, body.toByteString());
        // onPeers 调用 server.pend，这里 server 是 mock，pend 是包级方法，无法直接验证
        // 但应不抛异常
        assertDoesNotThrow(() -> pmgr.onMessage(ctx, server));
    }

    @Test
    void onMessageIgnoresUnrelatedCode() {
        Context ctx = contextWithCode(NexusChainOuterClass.Code.GET_STATUS,
                NexusChainOuterClass.GetStatus.newBuilder().build().toByteString());
        pmgr.onMessage(ctx, server);
        // GET_STATUS 不在 switch 中，应保持默认 ctx 状态
        assertFalse(ctx.keep);
        assertFalse(ctx.pending);
        assertNull(ctx.response);
    }

    @Test
    void onStartStoresServer() {
        pmgr.onStart(server);
        // getPeers 委托给 server.getPeers()
        when(server.getPeers()).thenReturn(Collections.singletonList(remote));
        List<Peer> peers = pmgr.getPeers();
        assertEquals(1, peers.size());
        assertEquals(remote, peers.get(0));
    }

    @Test
    void getPeersReturnsEmptyWhenServerNotStarted() {
        PeersManager fresh = new PeersManager();
        List<Peer> peers = fresh.getPeers();
        assertNotNull(peers);
        assertTrue(peers.isEmpty());
    }

    @Test
    void getSelfAddressReturnsEmptyWhenServerNotStarted() {
        PeersManager fresh = new PeersManager();
        assertEquals("", fresh.getSelfAddress());
    }

    @Test
    void getSelfAddressReturnsSelfToString() {
        pmgr.onStart(server);
        when(server.getSelf()).thenReturn(self);
        assertEquals(self.toString(), pmgr.getSelfAddress());
    }
}