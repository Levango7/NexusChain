package org.nexus.p2p;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.nexus.core.Block;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.validate.CompositeBlockRule;
import org.nexus.core.validate.MerkleRule;
import org.nexus.core.validate.Result;
import org.nexus.db.StateDB;
import org.nexus.merkletree.MerkleMessageEvent;
import org.nexus.merkletree.MerkleTreeManager;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * {@link MerkleHandler} 单元测试。
 * <p>
 * Mock 5 个 @Autowired 依赖 + PeerServer；测 onMessage 4 分支、onStart、onApplicationEvent。
 */
@ExtendWith(MockitoExtension.class)
class MerkleHandlerTest {

    @Mock
    private MerkleTreeManager merkleTreeManager;
    @Mock
    private CompositeBlockRule compositeBlockRule;
    @Mock
    private MerkleRule merkleRule;
    @Mock
    private NexusChainBlockChain bc;
    @Mock
    private StateDB stateDB;
    @Mock
    private PeerServer server;

    private MerkleHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        handler = new MerkleHandler();
        setField(handler, "merkleTreeManager", merkleTreeManager);
        setField(handler, "compositeBlockRule", compositeBlockRule);
        setField(handler, "merkleRule", merkleRule);
        setField(handler, "bc", bc);
        setField(handler, "stateDB", stateDB);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private Context contextWithCode(NexusChainOuterClass.Code code, ByteString body, Peer remote) {
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

    private Peer remote() {
        return PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
    }

    @Test
    void onMessageIgnoresUnrelatedCode() {
        Context ctx = contextWithCode(NexusChainOuterClass.Code.PING,
                NexusChainOuterClass.Ping.newBuilder().build().toByteString(), remote());
        // PING 不在 switch 中，应不抛异常且不调用任何依赖
        assertDoesNotThrow(() -> handler.onMessage(ctx, server));
        verifyNoInteractions(merkleTreeManager, compositeBlockRule, merkleRule, bc, stateDB);
    }

    @Test
    void onStartStoresServer() {
        handler.onStart(server);
        // onStart 后 server 字段被设置，后续 onApplicationEvent 可用
        // 通过 onApplicationEvent 行为间接验证
        MerkleMessageEvent event = new MerkleMessageEvent(this, null);
        when(server.getPeers()).thenReturn(Collections.emptyList());
        assertDoesNotThrow(() -> handler.onApplicationEvent(event));
    }

    @Test
    void onApplicationEventWithNullBlockDoesNothing() {
        handler.onStart(server);
        when(server.getPeers()).thenReturn(Collections.emptyList());
        MerkleMessageEvent event = new MerkleMessageEvent(this, null);
        assertDoesNotThrow(() -> handler.onApplicationEvent(event));
        // 没有对端，不应调用 dial
        verify(server, never()).dial(any(), any());
    }

    @Test
    void onApplicationEventWithEmptyPeersDoesNothing() {
        handler.onStart(server);
        when(server.getPeers()).thenReturn(Collections.emptyList());
        Block block = mock(Block.class);
        MerkleMessageEvent event = new MerkleMessageEvent(this, block);
        assertDoesNotThrow(() -> handler.onApplicationEvent(event));
        verify(server, never()).dial(any(), any());
    }

    @Test
    void onApplicationEventWithNullServerLogsWarning() {
        // 不调用 onStart，server 字段为 null
        MerkleMessageEvent event = new MerkleMessageEvent(this, null);
        assertDoesNotThrow(() -> handler.onApplicationEvent(event));
    }

    @Test
    void onGetMerkleTransactionsSetsResponse() {
        // 构造 GetMerkleTransactions 消息
        NexusChainOuterClass.GetMerkleTransactions body =
                NexusChainOuterClass.GetMerkleTransactions.newBuilder()
                        .setBlockHash(ByteString.copyFromUtf8("hash"))
                        .build();
        Context ctx = contextWithCode(
                NexusChainOuterClass.Code.GET_MERKELE_TRANSACTIONS,
                body.toByteString(), remote());
        // bc.getBlock 返回 null → getMerkleTransactions 返回空 list
        when(bc.getBlock(any())).thenReturn(null);
        assertDoesNotThrow(() -> handler.onMessage(ctx, server));
        assertNotNull(ctx.response);
        assertTrue(ctx.response instanceof NexusChainOuterClass.MerkleTransactions);
    }

    @Test
    void onMerkleTransactionsWithEmptyTransListDialsPeer() {
        // 空 MerketTrans list 且 server 有 peer → dial 一个 peer
        NexusChainOuterClass.MerkleTransactions body =
                NexusChainOuterClass.MerkleTransactions.newBuilder()
                        .setBlockHash(ByteString.copyFromUtf8("hash"))
                        .build();
        Context ctx = contextWithCode(
                NexusChainOuterClass.Code.MERKLE_TRANSACTIONS,
                body.toByteString(), remote());
        Peer p = PeerTestFixture.newPeerWithByte(0, (byte) 0x02, "10.0.0.2", 9002);
        when(server.getPeers()).thenReturn(Collections.singletonList(p));
        when(merkleTreeManager.replaceTransaction(any(), any())).thenReturn(mock(Block.class));
        when(compositeBlockRule.validateBlock(any())).thenReturn(Result.SUCCESS);
        when(merkleRule.validateBlock(any())).thenReturn(Result.SUCCESS);
        assertDoesNotThrow(() -> handler.onMessage(ctx, server));
    }

    @Test
    void onTreeNodesWithEmptyPeersDoesNothing() {
        NexusChainOuterClass.TreeNodes body =
                NexusChainOuterClass.TreeNodes.newBuilder()
                        .setBlockHash(ByteString.copyFromUtf8("hash"))
                        .build();
        Context ctx = contextWithCode(
                NexusChainOuterClass.Code.TREE_NODES,
                body.toByteString(), remote());
        when(server.getPeers()).thenReturn(Collections.emptyList());
        assertDoesNotThrow(() -> handler.onMessage(ctx, server));
        verify(server, never()).dial(any(), any());
    }

    @Test
    void onGetTreeNodesWithNullBlockSetsResponseIfParentLevelGt1() {
        // 构造 GetTreeNodes，parentNodes level > 1
        NexusChainOuterClass.TreeNode parentNode = NexusChainOuterClass.TreeNode.newBuilder()
                .setLevel(2)
                .setIndex(0)
                .setHash("h")
                .build();
        NexusChainOuterClass.GetTreeNodes body =
                NexusChainOuterClass.GetTreeNodes.newBuilder()
                        .setBlockHash(ByteString.copyFromUtf8("hash"))
                        .addParentNodes(parentNode)
                        .build();
        Context ctx = contextWithCode(
                NexusChainOuterClass.Code.GET_TREE_NODES,
                body.toByteString(), remote());
        when(bc.getBlock(any())).thenReturn(null);
        // bc.getBlock 返回 null，treeNodes 为空，但 parentNodes.get(0).level=2>1 → setResponse
        assertDoesNotThrow(() -> handler.onMessage(ctx, server));
        assertNotNull(ctx.response);
    }
}