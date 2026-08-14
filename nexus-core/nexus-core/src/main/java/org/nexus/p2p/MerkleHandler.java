package org.nexus.p2p;

import com.google.protobuf.ByteString;
import org.apache.commons.codec.binary.Hex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.nexus.core.Block;
import org.nexus.core.NexusChainBlockChain;
import org.nexus.core.account.Transaction;
import org.nexus.core.validate.CompositeBlockRule;
import org.nexus.core.validate.MerkleRule;
import org.nexus.core.validate.Result;
import org.nexus.db.StateDB;
import org.nexus.merkletree.*;
import org.nexus.sync.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Component
public class MerkleHandler implements Plugin, ApplicationListener<MerkleMessageEvent> {

    private static final Logger logger = LoggerFactory.getLogger(MerkleHandler.class);

    private PeerServer server;

    @Autowired
    private MerkleTreeManager merkleTreeManager;

    @Autowired
    private CompositeBlockRule compositeBlockRule;

    @Autowired
    private MerkleRule merkleRule;

    @Autowired
    private NexusChainBlockChain bc;

    @Autowired
    private StateDB stateDB;

    @Override
    public void onMessage(Context context, PeerServer server) {
        switch (context.getPayload().getCode()) {
            case GET_TREE_NODES:
                onGetTreeNodes(context, server);
                return;
            case TREE_NODES:
                onTreeNodes(context, server);
                return;
            case GET_MERKELE_TRANSACTIONS:
                onGetMerkleTransactions(context, server);
                return;
            case MERKLE_TRANSACTIONS:
                onMerleTransactions(context, server);
                return;
            default:
        }
    }

    @Override
    public void onStart(PeerServer server) {
        this.server = server;
    }

    private void onMerleTransactions(Context context, PeerServer server) {
        NexusChainOuterClass.MerkleTransactions wms = context.getPayload().getMerkleTransactions();
        // repeat sent
        if (wms.getMerketTransList().size() == 0) {
            if (server == null) {
                return;
            }
            List<Peer> ps = server.getPeers();
            if (ps == null || ps.size() == 0) {
                return;
            }
            int index = Math.abs(ThreadLocalRandom.current().nextInt()) % ps.size();
            NexusChainOuterClass.GetMerkleTransactions getMerkleTransactions = NexusChainOuterClass.GetMerkleTransactions.newBuilder()
                    .setBlockHash(wms.getBlockHash())
                    .addAllTreeNodes(wms.getTreeNodesList())
                    .build();
            server.dial(ps.get(index), getMerkleTransactions);
        }
        replaceTrans(wms.getBlockHash().toByteArray(), Utils.parseMerkleTransactions(wms.getMerketTransList()));
    }

    private void replaceTrans(byte[] blockHash, List<MerkleTransaction> wms) {
        Block block = merkleTreeManager.replaceTransaction(Hex.encodeHexString(blockHash), wms);
        Result res = compositeBlockRule.validateBlock(block);
        if (!res.isSuccess()) {
            Block b = merkleTreeManager.getCacheBlock(Hex.encodeHexString(blockHash));
            if (b != null) {
                getRootTreeNodes(b);
            }
            return;
        }
        Result result = merkleRule.validateBlock(block);
        if (!result.isSuccess()) {
            Block b = merkleTreeManager.getCacheBlock(Hex.encodeHexString(blockHash));
            if (b != null) {
                getRootTreeNodes(b);
            }
            return;
        }
        merkleTreeManager.removeBlockToCache(Hex.encodeHexString(blockHash));
        block.weight = 1;
        stateDB.writeBlock(block);
    }

    private void onGetMerkleTransactions(Context context, PeerServer server) {
        NexusChainOuterClass.GetMerkleTransactions getMerkleTransactions = context.getPayload().getGetMerkleTransactions();
        List<TreeNode> treeNodes = Utils.parseTreeNodes(getMerkleTransactions.getTreeNodesList());
        byte[] blockHash = getMerkleTransactions.getBlockHash().toByteArray();
        List<NexusChainOuterClass.MerkleTransaction> wms = getMerkleTransactions(blockHash, treeNodes);
        NexusChainOuterClass.MerkleTransactions resp = NexusChainOuterClass.MerkleTransactions.newBuilder()
                .addAllMerketTrans(wms)
                .setBlockHash(getMerkleTransactions.getBlockHash())
                .addAllTreeNodes(getMerkleTransactions.getTreeNodesList())
                .build();
        context.response(resp);
    }

    private List<NexusChainOuterClass.MerkleTransaction> getMerkleTransactions(byte[] blockHash, List<TreeNode> treeNodes) {
        Block block = bc.getBlock(blockHash);
        List<NexusChainOuterClass.MerkleTransaction> res = new ArrayList<>();
        if (block != null) {
            List<Transaction> txs = block.body;
            for (TreeNode treeNode : treeNodes) {
                int idx = treeNode.getIndex();
                if (txs.size() <= idx) {
                    res.clear();
                    break;
                }
                res.add(Utils.encodeMerkleTransaction(txs.get(idx), idx));
            }
        }
        return res;
    }

    private void onTreeNodes(Context context, PeerServer server) {
        if (server == null) {
            return;
        }
        List<Peer> ps = server.getPeers();
        if (ps == null || ps.size() == 0) {
            return;
        }
        int index = Math.abs(ThreadLocalRandom.current().nextInt()) % ps.size();
        NexusChainOuterClass.TreeNodes wts = context.getPayload().getTreeNodes();
        List<TreeNode> treeNodes = Utils.parseTreeNodes(wts.getTreeNodesList());
        if (treeNodes.size() == 0) {
            merkleTreeManager.removeBlockToCache(Hex.encodeHexString(wts.getBlockHash().toByteArray()));
        }
        // 比对
        List<TreeNode> errorTreeNodes = new ArrayList<>();
        Block block = merkleTreeManager.getCacheBlock(Hex.encodeHexString(wts.getBlockHash().toByteArray()));
        if (block != null) {
            MerkleTree merkleTree = getMerkleTree(block);
            for (TreeNode treeNode : treeNodes) {
                int idx = treeNode.getIndex();
                int level = treeNode.getLevel();
                if (merkleTree.getLevelList((byte) level) == null) {
                    merkleTreeManager.removeBlockToCache(Hex.encodeHexString(wts.getBlockHash().toByteArray()));
                    return;
                }
                if (merkleTree.getLevelList((byte) level).size() - 1 < idx) {
                    errorTreeNodes.add(treeNode);
                    continue;
                }
                if (!merkleTree.getLevelList((byte) level).get(idx).getHash().equals(treeNode.getHash())) {
                    errorTreeNodes.add(treeNode);
                }
            }
            if (errorTreeNodes.size() > 10) {
                merkleTreeManager.removeBlockToCache(Hex.encodeHexString(wts.getBlockHash().toByteArray()));
                return;
            }
            if (errorTreeNodes.size() > 0 && errorTreeNodes.get(0).getLevel() > 1) {
                NexusChainOuterClass.GetTreeNodes getTreeNodes = NexusChainOuterClass.GetTreeNodes.newBuilder()
                        .setBlockHash(wts.getBlockHash())
                        .addAllParentNodes(Utils.encodeTreeNodes(errorTreeNodes)).build();
                server.dial(ps.get(index), getTreeNodes);
                return;
            }
            if (errorTreeNodes.size() > 0) {
                // sent merkle trans
                NexusChainOuterClass.GetMerkleTransactions getMerkleTransactions = NexusChainOuterClass.GetMerkleTransactions.newBuilder()
                        .setBlockHash(wts.getBlockHash())
                        .addAllTreeNodes(Utils.encodeTreeNodes(errorTreeNodes))
                        .build();
                server.dial(ps.get(index), getMerkleTransactions);
            } else {
                merkleTreeManager.removeBlockToCache(Hex.encodeHexString(wts.getBlockHash().toByteArray()));
            }
        }
    }

    private void onGetTreeNodes(Context context, PeerServer server) {
        NexusChainOuterClass.GetTreeNodes getTreeNodes = context.getPayload().getGetTreeNodes();
        List<TreeNode> parentNodes = Utils.parseTreeNodes(getTreeNodes.getParentNodesList());
        List<TreeNode> treeNodes = new ArrayList<>();
        byte[] blockHash = getTreeNodes.getBlockHash().toByteArray();
        Block block = bc.getBlock(blockHash);
        if (block != null) {
            MerkleTree merkleTree = getMerkleTree(block);
            for (TreeNode parentNode : parentNodes) {
                int index = parentNode.getIndex();
                int level = parentNode.getLevel() - 1;
                List<TreeNode> tns = merkleTree.getLevelList((byte) level);
                if (tns.size() < index * 2 + 1) {
                    treeNodes.clear();
                    break;
                }
                treeNodes.add(tns.get(2 * index));
                if (tns.size() > index * 2 + 1) {
                    treeNodes.add(tns.get(2 * index + 1));
                }
            }
        }
        if (parentNodes.get(0).getLevel() > 1) {
            List<NexusChainOuterClass.TreeNode> tns = Utils.encodeTreeNodes(treeNodes);
            NexusChainOuterClass.TreeNodes resp = NexusChainOuterClass.TreeNodes.newBuilder()
                    .addAllTreeNodes(tns)
                    .addAllParentNodes(getTreeNodes.getParentNodesList())
                    .setBlockHash(getTreeNodes.getBlockHash())
                    .build();
            context.response(resp);
        }
    }

    private MerkleTree getMerkleTree(Block block) {
        List<String> hashes = new ArrayList<>();
        for (Transaction tx : block.body) {
            hashes.add(tx.getHashHexString());
        }
        return new MerkleTree(hashes);
    }

    private void getRootTreeNodes(Block block) {
        if (server == null) {
            logger.warn("[merkle-debug] getRootTreeNodes: server is null");
            return;
        }
        List<Peer> ps = server.getPeers();
        if (ps == null || ps.size() == 0) {
            logger.warn("[merkle-debug] getRootTreeNodes: no peers (size={})", ps == null ? -1 : ps.size());
            return;
        }
        if (block == null) {
            logger.warn("[merkle-debug] getRootTreeNodes: block is null");
            return;
        }
        List<Transaction> txs = block.body;
        byte level = (byte) (Block.getMerkleRootLevel(txs) & 0xff);
        List<TreeNode> parentTreeNodes = Block.getMerkleTreeNode(txs, level);
        int index = Math.abs(ThreadLocalRandom.current().nextInt()) % ps.size();
        NexusChainOuterClass.GetTreeNodes getTreeNodes = NexusChainOuterClass.GetTreeNodes.newBuilder()
                .setBlockHash(ByteString.copyFrom(block.getHash()))
                .addAllParentNodes(Utils.encodeTreeNodes(parentTreeNodes))
                .build();
        logger.info("[merkle-debug] getRootTreeNodes: blockHeight={}, peers={}, dialing index={}",
                block.nHeight, ps.size(), index);
        server.dial(ps.get(index), getTreeNodes);
    }

    @Override
    public void onApplicationEvent(MerkleMessageEvent event) {
        getRootTreeNodes(event.getBlock());
    }

}
