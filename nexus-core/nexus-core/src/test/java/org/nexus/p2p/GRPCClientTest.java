package org.nexus.p2p;

import com.google.protobuf.AbstractMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link GRPCClient} 单元测试。
 * <p>
 * 覆盖可 Mock 的方法：getNonce/withTimeout/withSelf/withExecutor/buildMessage。
 * getChannel/dial 走真实 Netty，留给集成测试。
 */
class GRPCClientTest {

    private GRPCClient client;

    @BeforeEach
    void setUp() {
        client = new GRPCClient();
    }

    @Test
    void getNonceReturnsIncrementingValues() {
        long n1 = client.getNonce();
        long n2 = client.getNonce();
        long n3 = client.getNonce();
        assertEquals(n1 + 1, n2);
        assertEquals(n2 + 1, n3);
        assertTrue(n1 > 0);
    }

    @Test
    void withSelfSetsSelfAndReturnsThis() throws Exception {
        Peer self = Peer.newPeer("nexus://127.0.0.1:9000");
        GRPCClient returned = client.withSelf(self);
        assertSame(client, returned);
    }

    @Test
    void withTimeoutSetsTimeoutAndReturnsThis() {
        GRPCClient returned = client.withTimeout(5);
        assertSame(client, returned);
    }

    @Test
    void withExecutorSetsExecutorAndReturnsThis() {
        GRPCClient returned = client.withExecutor(Executors.newSingleThreadExecutor());
        assertSame(client, returned);
    }

    @Test
    void buildMessageProducesSignedMessageWithIncrementingNonce() throws Exception {
        Peer self = Peer.newPeer("nexus://127.0.0.1:9000");
        client.withSelf(self);
        AbstractMessage body = NexusChainOuterClass.Ping.newBuilder().build();
        NexusChainOuterClass.Message msg = client.buildMessage(8L, body);
        assertEquals(NexusChainOuterClass.Code.PING, msg.getCode());
        assertEquals(8L, msg.getTtl());
        assertEquals(self.toString(), msg.getRemotePeer());
        assertTrue(msg.getSignature().size() > 0);

        long n1 = msg.getNonce();
        NexusChainOuterClass.Message msg2 = client.buildMessage(8L, body);
        assertTrue(msg2.getNonce() > n1);
    }

    @Test
    void buildMessageWithNothingBody() throws Exception {
        Peer self = Peer.newPeer("nexus://127.0.0.1:9000");
        client.withSelf(self);
        AbstractMessage body = NexusChainOuterClass.Nothing.newBuilder().build();
        NexusChainOuterClass.Message msg = client.buildMessage(1L, body);
        assertEquals(NexusChainOuterClass.Code.NOTHING, msg.getCode());
    }

    @Test
    void buildMessageWithBlocksBody() throws Exception {
        Peer self = Peer.newPeer("nexus://127.0.0.1:9000");
        client.withSelf(self);
        AbstractMessage body = NexusChainOuterClass.Blocks.newBuilder().build();
        NexusChainOuterClass.Message msg = client.buildMessage(1L, body);
        assertEquals(NexusChainOuterClass.Code.BLOCKS, msg.getCode());
    }

    @Test
    void constructorWithSelfPeerSetsSelf() throws Exception {
        Peer self = Peer.newPeer("nexus://127.0.0.1:9000");
        GRPCClient c = new GRPCClient(self);
        AbstractMessage body = NexusChainOuterClass.Ping.newBuilder().build();
        NexusChainOuterClass.Message msg = c.buildMessage(1L, body);
        assertEquals(self.toString(), msg.getRemotePeer());
    }

    @Test
    void dialWithTtlReturnsCompletableFutureForMessage() throws Exception {
        // dial 真实网络会失败，但 dialWithTTL 应返回非 null CompletableFuture
        Peer self = Peer.newPeer("nexus://127.0.0.1:9000");
        client.withSelf(self);
        NexusChainOuterClass.Message msg = client.buildMessage(1L,
                NexusChainOuterClass.Ping.newBuilder().build());
        // 直接传入 Message 实例，走 dial(host, port, msg) 分支
        assertNotNull(client.dialWithTTL("127.0.0.1", 65535, 1L, msg));
    }
}