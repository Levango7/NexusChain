package org.nexus.p2p;

import com.google.protobuf.AbstractMessage;
import com.google.protobuf.InvalidProtocolBufferException;

public class Payload {
    private long createdAt;
    private NexusChainOuterClass.Code code;
    private Peer remote;
    private long ttl;
    private long nonce;
    private byte[] signature;

    private AbstractMessage body;

    private NexusChainOuterClass.Message message;

    public NexusChainOuterClass.Message getMessage() {
        return message;
    }

    public Payload(NexusChainOuterClass.Message msg) throws Exception {
        createdAt = msg.getCreatedAt().getSeconds();
        code = msg.getCode();
        remote = Peer.parse(msg.getRemotePeer());
        ttl = msg.getTtl();
        nonce = msg.getNonce();
        signature = msg.getSignature().toByteArray();
        message = msg;
        parseBody();
    }

    public AbstractMessage getBody() {
        return body;
    }

    private void parseBody() throws InvalidProtocolBufferException {
        if (body != null) {
            return;
        }
        switch (code) {
            case PING:
                body = NexusChainOuterClass.Ping.parseFrom(message.getBody());
                return;
            case PONG:
                body = NexusChainOuterClass.Pong.parseFrom(message.getBody());
                return;
            case LOOK_UP:
                body = NexusChainOuterClass.Lookup.parseFrom(message.getBody());
                return;
            case PEERS:
                body = NexusChainOuterClass.Peers.parseFrom(message.getBody());
                return;
            case GET_STATUS:
                body = NexusChainOuterClass.GetStatus.parseFrom(message.getBody());
                return;
            case STATUS:
                body = NexusChainOuterClass.Status.parseFrom(message.getBody());
                return;
            case GET_BLOCKS:
                body = NexusChainOuterClass.GetBlocks.parseFrom(message.getBody());
                return;
            case BLOCKS:
                body = NexusChainOuterClass.Blocks.parseFrom(message.getBody());
                return;
            case PROPOSAL:
                body = NexusChainOuterClass.Proposal.parseFrom(message.getBody());
                return;
            case TRANSACTIONS:
                body = NexusChainOuterClass.Transactions.parseFrom(message.getBody());
                return;
            case GET_TREE_NODES:
                body = NexusChainOuterClass.GetTreeNodes.parseFrom(message.getBody());
                return;
            case TREE_NODES:
                body = NexusChainOuterClass.TreeNodes.parseFrom(message.getBody());
                return;
            case GET_MERKELE_TRANSACTIONS:
                body = NexusChainOuterClass.GetMerkleTransactions.parseFrom(message.getBody());
                return;
            case MERKLE_TRANSACTIONS:
                body = NexusChainOuterClass.MerkleTransactions.parseFrom(message.getBody());
                return;
            default:
                body = NexusChainOuterClass.Nothing.newBuilder().build();
        }
    }

    public NexusChainOuterClass.Ping getPing() {
        return (NexusChainOuterClass.Ping) body;
    }

    public NexusChainOuterClass.Pong getPong() {
        return (NexusChainOuterClass.Pong) body;
    }

    public NexusChainOuterClass.Lookup getLookup() {
        return (NexusChainOuterClass.Lookup) body;
    }

    public NexusChainOuterClass.Peers getPeers() {
        return (NexusChainOuterClass.Peers) body;
    }

    public NexusChainOuterClass.GetStatus getGetStatus() {
        return (NexusChainOuterClass.GetStatus) body;
    }

    public NexusChainOuterClass.Status getStatus() {
        return (NexusChainOuterClass.Status) body;
    }

    public NexusChainOuterClass.GetBlocks getGetBlocks() {
        return (NexusChainOuterClass.GetBlocks) body;
    }

    public NexusChainOuterClass.Blocks getBlocks() {
        return (NexusChainOuterClass.Blocks) body;
    }

    public NexusChainOuterClass.Proposal getProposal() {
        return (NexusChainOuterClass.Proposal) body;
    }

    public NexusChainOuterClass.Transaction getTransaction() {
        return (NexusChainOuterClass.Transaction) body;
    }

    public NexusChainOuterClass.TreeNodes getTreeNodes() {
        return (NexusChainOuterClass.TreeNodes) body;
    }

    public NexusChainOuterClass.GetTreeNodes getGetTreeNodes() {
        return (NexusChainOuterClass.GetTreeNodes) body;
    }

    public NexusChainOuterClass.GetMerkleTransactions getGetMerkleTransactions() {
        return (NexusChainOuterClass.GetMerkleTransactions) body;
    }

    public NexusChainOuterClass.MerkleTransactions getMerkleTransactions() {
        return (NexusChainOuterClass.MerkleTransactions) body;
    }


    public Peer getRemote() {
        return remote;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public NexusChainOuterClass.Code getCode() {
        return code;
    }

    public long getTtl() {
        return ttl;
    }

    public long getNonce() {
        return nonce;
    }

    public byte[] getSignature() {
        return signature;
    }
}
