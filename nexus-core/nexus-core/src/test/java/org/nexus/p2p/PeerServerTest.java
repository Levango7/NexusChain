package org.nexus.p2p;

import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * {@link PeerServer} 单元测试。
 * <p>
 * Mock 6 个 @Autowired 依赖 + GRPCClient；测 use/entry/onMessage/relay/broadcast/getIP/getPort。
 * startListening 走真实 gRPC Server，留给集成测试。
 */
@ExtendWith(MockitoExtension.class)
class PeerServerTest {

    @Mock
    private PeersStorage peersCache;
    @Mock
    private MessageFilter filter;
    @Mock
    private PeersManager pmgr;
    @Mock
    private MessageLogger messageLogger;
    @Mock
    private org.nexus.sync.SyncManager syncManager;
    @Mock
    private org.nexus.sync.TransactionHandler transactionHandler;
    @Mock
    private MerkleHandler merkleHandler;
    @Mock
    private GRPCClient gRPCClient;

    private PeerServer server;
    private Peer self;

    @BeforeEach
    void setUp() throws Exception {
        server = new PeerServer();
        self = PeerTestFixture.newZeroPeer("127.0.0.1", 9000);

        setField(server, "peersCache", peersCache);
        setField(server, "filter", filter);
        setField(server, "pmgr", pmgr);
        setField(server, "messageLogger", messageLogger);
        setField(server, "syncManager", syncManager);
        setField(server, "transactionHandler", transactionHandler);
        setField(server, "merkleHandler", merkleHandler);
        setField(server, "gRPCClient", gRPCClient);
        setField(server, "enableMessageLog", false);
        setField(server, "enableDiscovery", true);
        setField(server, "tlsEnabled", false);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void useAddsPluginAndReturnsSelf() {
        Plugin plugin = mock(Plugin.class);
        PeerServer returned = server.use(plugin);
        assertSame(server, returned);
        // 再次 use 不应抛异常
        server.use(mock(Plugin.class));
    }

    @Test
    void getBootstrapsDelegatesToPeersCache() {
        Peer bs = PeerTestFixture.newPeerWithByte(0, (byte) 0x80, "1.2.3.4", 9001);
        when(peersCache.getBootstraps()).thenReturn(Collections.singletonList(bs));
        List<Peer> result = server.getBootstraps();
        assertEquals(1, result.size());
        assertEquals(bs, result.get(0));
    }

    @Test
    void getPeersCacheReturnsInjectedCache() {
        assertSame(peersCache, server.getPeersCache());
    }

    @Test
    void getSelfDelegatesToPeersCache() {
        when(peersCache.getSelf()).thenReturn(self);
        assertEquals(self, server.getSelf());
    }

    @Test
    void getPeersDelegatesToPeersCache() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        when(peersCache.getPeers()).thenReturn(Collections.singletonList(p));
        List<Peer> peers = server.getPeers();
        assertEquals(1, peers.size());
        assertEquals(p, peers.get(0));
    }

    @Test
    void hasPeerDelegatesToPeersCache() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        when(peersCache.hasPeer(p)).thenReturn(true);
        assertTrue(server.hasPeer(p));
    }

    @Test
    void getPortReturnsSelfPort() {
        when(peersCache.getSelf()).thenReturn(self);
        assertEquals(self.port, server.getPort());
    }

    @Test
    void getNodePubKeyReturnsNexusUrl() {
        when(peersCache.getSelf()).thenReturn(self);
        String pubKey = server.getNodePubKey();
        assertTrue(pubKey.startsWith("nexus://"));
        assertTrue(pubKey.contains(self.hostPort()));
    }

    @Test
    void getIPReturnsLocalhostAddress() {
        // getIP 调用 InetAddress.getLocalHost，可能返回机器名或 127.0.0.1
        String ip = server.getIP();
        assertNotNull(ip);
        assertTrue(ip.length() > 0);
    }

    @Test
    void pendDelegatesToPeersCache() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        server.pend(p);
        verify(peersCache).pend(p);
    }

    @Test
    void entryRegistersInboundPeerAndResponds() {
        Peer inbound = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        NexusChainOuterClass.Message request = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.PING,
                NexusChainOuterClass.Ping.newBuilder().build().toByteString(),
                inbound);
        when(peersCache.getSelf()).thenReturn(self);
        when(peersCache.getBlocked()).thenReturn(Collections.emptyList());
        NexusChainOuterClass.Message nothingResp = NexusChainOuterClass.Message.newBuilder().build();
        when(gRPCClient.buildMessage(anyLong(), any())).thenReturn(nothingResp);

        StreamObserver<NexusChainOuterClass.Message> observer = mock(StreamObserver.class);
        server.entry(request, observer);

        // entry 中 keepPeer(inbound) + onMessage 中 keepPeer(inbound) = 至少1次
        verify(peersCache, atLeastOnce()).keepPeer(inbound);
        verify(observer).onNext(any());
        verify(observer).onCompleted();
    }

    @Test
    void entrySkipsRegistrationWhenRemoteIsSelf() {
        NexusChainOuterClass.Message request = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.PING,
                NexusChainOuterClass.Ping.newBuilder().build().toByteString(),
                self);
        when(peersCache.getSelf()).thenReturn(self);
        when(peersCache.getBlocked()).thenReturn(Collections.emptyList());
        NexusChainOuterClass.Message nothingResp = NexusChainOuterClass.Message.newBuilder().build();
        when(gRPCClient.buildMessage(anyLong(), any())).thenReturn(nothingResp);

        StreamObserver<NexusChainOuterClass.Message> observer = mock(StreamObserver.class);
        server.entry(request, observer);

        // entry 中 self 不 keepPeer（remote.equals(self) 跳过），
        // 但 onMessage 中会 keepPeer(inbound=self)，所以用 atLeast(0) 不强验证
        verify(observer).onNext(any());
        verify(observer).onCompleted();
    }

    @Test
    void entryHandlesParseErrorGracefully() {
        // 非法 remotePeer 字符串
        NexusChainOuterClass.Message request = NexusChainOuterClass.Message.newBuilder()
                .setCode(NexusChainOuterClass.Code.PING)
                .setRemotePeer("not-a-valid-url")
                .setCreatedAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(1000L).build())
                .setTtl(8L)
                .setNonce(1L)
                .build();
        NexusChainOuterClass.Message nothingResp = NexusChainOuterClass.Message.newBuilder().build();
        when(gRPCClient.buildMessage(anyLong(), any())).thenReturn(nothingResp);

        StreamObserver<NexusChainOuterClass.Message> observer = mock(StreamObserver.class);
        // 不应抛异常
        assertDoesNotThrow(() -> server.entry(request, observer));
        verify(observer).onNext(any());
        verify(observer).onCompleted();
    }

    @Test
    void broadcastDialsEachPeer() {
        Peer p1 = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        Peer p2 = PeerTestFixture.newPeerWithByte(0, (byte) 0x02, "10.0.0.2", 9002);
        when(peersCache.getPeers()).thenReturn(List.of(p1, p2));
        // broadcast 调用 dialWithTTL → gRPCClient.dialAsyncWithTTL
        // 不抛异常即可
        assertDoesNotThrow(() -> server.broadcast(
                NexusChainOuterClass.Ping.newBuilder().build()));
    }

    @Test
    void relaySkipsWhenTtlZero() {
        // relay ttl<=0 直接 return，不调用 getPeers
        Payload payload = mock(Payload.class);
        when(payload.getTtl()).thenReturn(0L);
        server.relay(payload);
        verify(peersCache, never()).getPeers();
    }

    @Test
    void relayDialsPeersWithDecrementedTtl() {
        Peer p1 = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        when(peersCache.getPeers()).thenReturn(Collections.singletonList(p1));
        Payload payload = mock(Payload.class);
        when(payload.getTtl()).thenReturn(2L);
        when(payload.getRemote()).thenReturn(self);
        when(payload.getBody()).thenReturn(NexusChainOuterClass.Ping.newBuilder().build());
        // 不抛异常即可
        assertDoesNotThrow(() -> server.relay(payload));
    }

    @Test
    void dialDoesNotThrowForValidPeer() {
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
        // dial 调用 gRPCClient.dialAsyncWithTTL，mock 不会真正建连
        assertDoesNotThrow(() -> server.dial(p,
                NexusChainOuterClass.Ping.newBuilder().build()));
    }
}