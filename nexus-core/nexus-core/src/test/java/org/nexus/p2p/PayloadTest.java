package org.nexus.p2p;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link Payload} 单元测试。
 * <p>
 * 覆盖 14 种 code 的 parseBody 分支与各 getter。
 */
class PayloadTest {

    private Peer remote() {
        return PeerTestFixture.newPeerWithByte(0, (byte) 0x01, "10.0.0.1", 9001);
    }

    @Test
    void parsePing() throws Exception {
        NexusChainOuterClass.Ping body = NexusChainOuterClass.Ping.newBuilder().build();
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.PING, body.toByteString(), remote());
        Payload p = new Payload(msg);
        assertEquals(NexusChainOuterClass.Code.PING, p.getCode());
        assertNotNull(p.getPing());
        assertEquals(body, p.getPing());
    }

    @Test
    void parsePong() throws Exception {
        NexusChainOuterClass.Pong body = NexusChainOuterClass.Pong.newBuilder().build();
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.PONG, body.toByteString(), remote());
        Payload p = new Payload(msg);
        assertEquals(NexusChainOuterClass.Code.PONG, p.getCode());
        assertNotNull(p.getPong());
        assertEquals(body, p.getPong());
    }

    @Test
    void parseLookup() throws Exception {
        NexusChainOuterClass.Lookup body = NexusChainOuterClass.Lookup.newBuilder().build();
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.LOOK_UP, body.toByteString(), remote());
        Payload p = new Payload(msg);
        assertEquals(NexusChainOuterClass.Code.LOOK_UP, p.getCode());
        assertNotNull(p.getLookup());
        assertEquals(body, p.getLookup());
    }

    @Test
    void parsePeers() throws Exception {
        NexusChainOuterClass.Peers body = NexusChainOuterClass.Peers.newBuilder()
                .addPeers("nexus://a@1.1.1.1:1111")
                .addPeers("nexus://b@2.2.2.2:2222")
                .build();
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.PEERS, body.toByteString(), remote());
        Payload p = new Payload(msg);
        assertEquals(NexusChainOuterClass.Code.PEERS, p.getCode());
        assertEquals(2, p.getPeers().getPeersList().size());
    }

    @Test
    void parseGetStatus() throws Exception {
        NexusChainOuterClass.GetStatus body = NexusChainOuterClass.GetStatus.newBuilder().build();
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.GET_STATUS, body.toByteString(), remote());
        Payload p = new Payload(msg);
        assertEquals(NexusChainOuterClass.Code.GET_STATUS, p.getCode());
        assertNotNull(p.getGetStatus());
    }

    @Test
    void parseStatus() throws Exception {
        NexusChainOuterClass.Status body = NexusChainOuterClass.Status.newBuilder().build();
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.STATUS, body.toByteString(), remote());
        Payload p = new Payload(msg);
        assertEquals(NexusChainOuterClass.Code.STATUS, p.getCode());
        assertNotNull(p.getStatus());
    }

    @Test
    void parseGetBlocks() throws Exception {
        NexusChainOuterClass.GetBlocks body = NexusChainOuterClass.GetBlocks.newBuilder().build();
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.GET_BLOCKS, body.toByteString(), remote());
        Payload p = new Payload(msg);
        assertEquals(NexusChainOuterClass.Code.GET_BLOCKS, p.getCode());
        assertNotNull(p.getGetBlocks());
    }

    @Test
    void parseBlocks() throws Exception {
        NexusChainOuterClass.Blocks body = NexusChainOuterClass.Blocks.newBuilder().build();
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.BLOCKS, body.toByteString(), remote());
        Payload p = new Payload(msg);
        assertEquals(NexusChainOuterClass.Code.BLOCKS, p.getCode());
        assertNotNull(p.getBlocks());
    }

    @Test
    void parseProposal() throws Exception {
        NexusChainOuterClass.Proposal body = NexusChainOuterClass.Proposal.newBuilder().build();
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.PROPOSAL, body.toByteString(), remote());
        Payload p = new Payload(msg);
        assertEquals(NexusChainOuterClass.Code.PROPOSAL, p.getCode());
        assertNotNull(p.getProposal());
    }

    @Test
    void parseTransactions() throws Exception {
        NexusChainOuterClass.Transactions body = NexusChainOuterClass.Transactions.newBuilder().build();
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.TRANSACTIONS, body.toByteString(), remote());
        Payload p = new Payload(msg);
        assertEquals(NexusChainOuterClass.Code.TRANSACTIONS, p.getCode());
        assertNotNull(p.getTransactions());
    }

    @Test
    void parseGetTreeNodes() throws Exception {
        NexusChainOuterClass.GetTreeNodes body = NexusChainOuterClass.GetTreeNodes.newBuilder().build();
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.GET_TREE_NODES, body.toByteString(), remote());
        Payload p = new Payload(msg);
        assertEquals(NexusChainOuterClass.Code.GET_TREE_NODES, p.getCode());
        assertNotNull(p.getGetTreeNodes());
    }

    @Test
    void parseTreeNodes() throws Exception {
        NexusChainOuterClass.TreeNodes body = NexusChainOuterClass.TreeNodes.newBuilder().build();
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.TREE_NODES, body.toByteString(), remote());
        Payload p = new Payload(msg);
        assertEquals(NexusChainOuterClass.Code.TREE_NODES, p.getCode());
        assertNotNull(p.getTreeNodes());
    }

    @Test
    void parseGetMerkleTransactions() throws Exception {
        NexusChainOuterClass.GetMerkleTransactions body =
                NexusChainOuterClass.GetMerkleTransactions.newBuilder().build();
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.GET_MERKELE_TRANSACTIONS, body.toByteString(), remote());
        Payload p = new Payload(msg);
        assertEquals(NexusChainOuterClass.Code.GET_MERKELE_TRANSACTIONS, p.getCode());
        assertNotNull(p.getGetMerkleTransactions());
    }

    @Test
    void parseMerkleTransactions() throws Exception {
        NexusChainOuterClass.MerkleTransactions body =
                NexusChainOuterClass.MerkleTransactions.newBuilder().build();
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.MERKLE_TRANSACTIONS, body.toByteString(), remote());
        Payload p = new Payload(msg);
        assertEquals(NexusChainOuterClass.Code.MERKLE_TRANSACTIONS, p.getCode());
        assertNotNull(p.getMerkleTransactions());
    }

    @Test
    void parseNothingForUnknownCode() throws Exception {
        // NOTHING code 走 default 分支
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.NOTHING,
                NexusChainOuterClass.Nothing.newBuilder().build().toByteString(),
                remote());
        Payload p = new Payload(msg);
        assertEquals(NexusChainOuterClass.Code.NOTHING, p.getCode());
        assertNotNull(p.getBody());
    }

    @Test
    void getRemoteReturnsParsedPeer() throws Exception {
        Peer r = remote();
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.PING,
                NexusChainOuterClass.Ping.newBuilder().build().toByteString(),
                r);
        Payload p = new Payload(msg);
        assertEquals(r, p.getRemote());
    }

    @Test
    void getCreatedAtTtlNonceSignatureReadFromMessage() throws Exception {
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.PING,
                NexusChainOuterClass.Ping.newBuilder().build().toByteString(),
                remote());
        Payload p = new Payload(msg);
        assertEquals(1000L, p.getCreatedAt());
        assertEquals(8L, p.getTtl());
        assertEquals(42L, p.getNonce());
        assertNotNull(p.getSignature());
        assertEquals("sig", new String(p.getSignature()));
    }

    @Test
    void getMessageReturnsOriginalMessage() throws Exception {
        NexusChainOuterClass.Message msg = PeerTestFixture.buildMessage(
                NexusChainOuterClass.Code.PING,
                NexusChainOuterClass.Ping.newBuilder().build().toByteString(),
                remote());
        Payload p = new Payload(msg);
        assertEquals(msg, p.getMessage());
    }
}