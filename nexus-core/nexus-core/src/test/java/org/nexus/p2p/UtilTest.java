package org.nexus.p2p;

import com.google.protobuf.AbstractMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * {@link Util} 单元测试。
 * <p>
 * 覆盖 buildMessage 14 个 code 分支、split(Blocks/Transactions) 拆分、getRawForSign。
 */
class UtilTest {

    private Peer self;

    @BeforeEach
    void setUp() throws Exception {
        // newPeer 自动生成 Ed25519 keypair，privateKey 用于签名
        self = Peer.newPeer("nexus://127.0.0.1:9000");
    }

    @Test
    void buildMessageForNothing() {
        NexusChainOuterClass.Nothing body = NexusChainOuterClass.Nothing.newBuilder().build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        assertEquals(NexusChainOuterClass.Code.NOTHING, msg.getCode());
        assertEquals(1L, msg.getNonce());
        assertEquals(8L, msg.getTtl());
        assertEquals(self.toString(), msg.getRemotePeer());
        assertTrue(msg.getSignature().size() > 0);
    }

    @Test
    void buildMessageForPing() {
        NexusChainOuterClass.Ping body = NexusChainOuterClass.Ping.newBuilder().build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        assertEquals(NexusChainOuterClass.Code.PING, msg.getCode());
    }

    @Test
    void buildMessageForPong() {
        NexusChainOuterClass.Pong body = NexusChainOuterClass.Pong.newBuilder().build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        assertEquals(NexusChainOuterClass.Code.PONG, msg.getCode());
    }

    @Test
    void buildMessageForLookup() {
        NexusChainOuterClass.Lookup body = NexusChainOuterClass.Lookup.newBuilder().build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        assertEquals(NexusChainOuterClass.Code.LOOK_UP, msg.getCode());
    }

    @Test
    void buildMessageForPeers() {
        NexusChainOuterClass.Peers body = NexusChainOuterClass.Peers.newBuilder()
                .addPeers("nexus://a@1.1.1.1:1111")
                .build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        assertEquals(NexusChainOuterClass.Code.PEERS, msg.getCode());
    }

    @Test
    void buildMessageForGetStatus() {
        NexusChainOuterClass.GetStatus body = NexusChainOuterClass.GetStatus.newBuilder().build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        assertEquals(NexusChainOuterClass.Code.GET_STATUS, msg.getCode());
    }

    @Test
    void buildMessageForStatus() {
        NexusChainOuterClass.Status body = NexusChainOuterClass.Status.newBuilder().build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        assertEquals(NexusChainOuterClass.Code.STATUS, msg.getCode());
    }

    @Test
    void buildMessageForGetBlocks() {
        NexusChainOuterClass.GetBlocks body = NexusChainOuterClass.GetBlocks.newBuilder().build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        assertEquals(NexusChainOuterClass.Code.GET_BLOCKS, msg.getCode());
    }

    @Test
    void buildMessageForBlocks() {
        NexusChainOuterClass.Blocks body = NexusChainOuterClass.Blocks.newBuilder().build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        assertEquals(NexusChainOuterClass.Code.BLOCKS, msg.getCode());
    }

    @Test
    void buildMessageForProposal() {
        NexusChainOuterClass.Proposal body = NexusChainOuterClass.Proposal.newBuilder().build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        assertEquals(NexusChainOuterClass.Code.PROPOSAL, msg.getCode());
    }

    @Test
    void buildMessageForTransactions() {
        NexusChainOuterClass.Transactions body = NexusChainOuterClass.Transactions.newBuilder().build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        assertEquals(NexusChainOuterClass.Code.TRANSACTIONS, msg.getCode());
    }

    @Test
    void buildMessageForGetTreeNodes() {
        NexusChainOuterClass.GetTreeNodes body = NexusChainOuterClass.GetTreeNodes.newBuilder().build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        assertEquals(NexusChainOuterClass.Code.GET_TREE_NODES, msg.getCode());
    }

    @Test
    void buildMessageForTreeNodes() {
        NexusChainOuterClass.TreeNodes body = NexusChainOuterClass.TreeNodes.newBuilder().build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        assertEquals(NexusChainOuterClass.Code.TREE_NODES, msg.getCode());
    }

    @Test
    void buildMessageForGetMerkleTransactions() {
        NexusChainOuterClass.GetMerkleTransactions body =
                NexusChainOuterClass.GetMerkleTransactions.newBuilder().build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        assertEquals(NexusChainOuterClass.Code.GET_MERKELE_TRANSACTIONS, msg.getCode());
    }

    @Test
    void buildMessageForMerkleTransactions() {
        NexusChainOuterClass.MerkleTransactions body =
                NexusChainOuterClass.MerkleTransactions.newBuilder().build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        assertEquals(NexusChainOuterClass.Code.MERKLE_TRANSACTIONS, msg.getCode());
    }

    @Test
    void buildMessageForUnknownTypeFallsBackToNothing() {
        // 传入一个未识别的 AbstractMessage 子类（mock），应走 default 分支返回 NOTHING
        // mock 不匹配任何 instanceof 分支，走 default：setCode(NOTHING).setBody(Nothing)
        AbstractMessage unknown = mock(AbstractMessage.class);
        // mock.toByteString() 返回 null，但 default 分支不调用 msg.toByteString()
        // 而是用 Nothing.newBuilder().build().toByteString()
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, unknown);
        assertEquals(NexusChainOuterClass.Code.NOTHING, msg.getCode());
    }

    @Test
    void splitBlocksReturnsSingleChunkWhenSmall() {
        NexusChainOuterClass.Block block = NexusChainOuterClass.Block.newBuilder().build();
        NexusChainOuterClass.Blocks blocks = NexusChainOuterClass.Blocks.newBuilder()
                .addBlocks(block)
                .build();
        List<NexusChainOuterClass.Blocks> split = Util.split(blocks);
        assertEquals(1, split.size());
        assertEquals(1, split.get(0).getBlocksCount());
    }

    @Test
    void splitBlocksReturnsEmptyForEmptyInput() {
        NexusChainOuterClass.Blocks blocks = NexusChainOuterClass.Blocks.newBuilder().build();
        List<NexusChainOuterClass.Blocks> split = Util.split(blocks);
        // 空输入应返回空列表
        assertTrue(split.isEmpty() || split.size() == 0);
    }

    @Test
    void splitBlocksPreservesAllBlocksAcrossChunks() {
        NexusChainOuterClass.Block.Builder b = NexusChainOuterClass.Block.newBuilder();
        NexusChainOuterClass.Blocks blocks = NexusChainOuterClass.Blocks.newBuilder()
                .addBlocks(b.build())
                .addBlocks(b.build())
                .addBlocks(b.build())
                .build();
        List<NexusChainOuterClass.Blocks> split = Util.split(blocks);
        int total = split.stream().mapToInt(NexusChainOuterClass.Blocks::getBlocksCount).sum();
        assertEquals(3, total);
    }

    @Test
    void splitTransactionsReturnsSingleChunkWhenSmall() {
        NexusChainOuterClass.Transaction tx = NexusChainOuterClass.Transaction.newBuilder().build();
        NexusChainOuterClass.Transactions txs = NexusChainOuterClass.Transactions.newBuilder()
                .addTransactions(tx)
                .build();
        List<NexusChainOuterClass.Transactions> split = Util.split(txs);
        assertEquals(1, split.size());
        assertEquals(1, split.get(0).getTransactionsCount());
    }

    @Test
    void splitTransactionsPreservesAllTxsAcrossChunks() {
        NexusChainOuterClass.Transaction tx = NexusChainOuterClass.Transaction.newBuilder().build();
        NexusChainOuterClass.Transactions txs = NexusChainOuterClass.Transactions.newBuilder()
                .addTransactions(tx)
                .addTransactions(tx)
                .addTransactions(tx)
                .build();
        List<NexusChainOuterClass.Transactions> split = Util.split(txs);
        int total = split.stream().mapToInt(NexusChainOuterClass.Transactions::getTransactionsCount).sum();
        assertEquals(3, total);
    }

    @Test
    void getRawForSignReturnsNonEmptyBytes() {
        NexusChainOuterClass.Ping body = NexusChainOuterClass.Ping.newBuilder().build();
        NexusChainOuterClass.Message msg = Util.buildMessage(self, 1L, 8L, body);
        byte[] raw = Util.getRawForSign(msg);
        assertNotNull(raw);
        // 4 (code) + 8 (createdAt) + remotePeer.length + 8 (ttl) + 8 (nonce) + body.size
        assertTrue(raw.length > 4 + 8 + 8 + 8);
    }

    @Test
    void signSetsSignatureOnBuilder() {
        NexusChainOuterClass.Ping body = NexusChainOuterClass.Ping.newBuilder().build();
        NexusChainOuterClass.Message.Builder builder = NexusChainOuterClass.Message.newBuilder()
                .setCode(NexusChainOuterClass.Code.PING)
                .setCreatedAt(com.google.protobuf.Timestamp.newBuilder().setSeconds(1000L).build())
                .setRemotePeer(self.toString())
                .setTtl(8L)
                .setNonce(1L)
                .setBody(body.toByteString());
        NexusChainOuterClass.Message.Builder signed = Util.sign(self, builder);
        assertTrue(signed.getSignature().size() > 0);
    }
}