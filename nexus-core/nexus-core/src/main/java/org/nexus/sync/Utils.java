package org.nexus.sync;

import com.google.protobuf.ByteString;
import org.apache.commons.io.IOUtils;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.nexus.core.Block;
import org.nexus.core.account.Transaction;
import org.nexus.encoding.JSONEncodeDecoder;
import org.nexus.genesis.Genesis;
import org.nexus.keystore.crypto.SHA3Utility;
import org.nexus.merkletree.MerkleTransaction;
import org.nexus.merkletree.TreeNode;
import org.nexus.p2p.NexusChainOuterClass;
import org.nexus.util.Arrays;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

// proto buf utils
public class Utils {

    public static byte[] getTransactionsHash(List<NexusChainOuterClass.Transaction> transactions) {
        return SHA3Utility.keccak256(Arrays.concatenate(transactions.stream()
                .map(NexusChainOuterClass.Transaction::toByteArray)
                .collect(Collectors.toList())
                .toArray(new byte[][]{})));
    }

    public static Transaction parseTransaction(NexusChainOuterClass.Transaction tx) {
        Transaction t = new Transaction();
        t.version = tx.getVersion();
        t.type = tx.getTransactionTypeValue();
        t.nonce = tx.getNonce();
        t.from = tx.getFrom().toByteArray();
        t.gasPrice = tx.getGasPrice();
        t.amount = tx.getAmount();
        t.payload = tx.getPayload().toByteArray();
        t.to = tx.getTo().toByteArray();
        t.signature = tx.getSignature().toByteArray();
        return t;
    }

    public static List<Block> parseBlocks(List<NexusChainOuterClass.Block> bks) {
        List<Block> res = new ArrayList<>();
        for (NexusChainOuterClass.Block bk : bks) {
            res.add(parseBlock(bk));
        }
        return res;
    }

    public static Block parseBlock(NexusChainOuterClass.Block bk) {
        Block b = new Block();
        b.nVersion = bk.getVersion();
        b.hashPrevBlock = bk.getHashPrevBlock().toByteArray();
        b.hashMerkleRoot = bk.getHashMerkleRoot().toByteArray();
        b.hashMerkleState = bk.getHashMerkleState().toByteArray();
        b.hashMerkleIncubate = bk.getHashMerkleIncubate().toByteArray();
        b.nHeight = bk.getHeight();
        b.nTime = bk.getCreatedAt();
        b.nBits = bk.getNBits().toByteArray();
        b.nNonce = bk.getNonce().toByteArray();
        b.body = new ArrayList<>();
        if (bk.getBodyList() == null || bk.getBodyList().size() == 0) {
            return b;
        }
        for (NexusChainOuterClass.Transaction tx : bk.getBodyList()) {
            b.body.add(parseTransaction(tx));
        }
        return b;
    }

    // avoid null check
    private static byte[] getBytes(byte[] in) {
        if (in == null) {
            return new byte[]{};
        }
        return in;
    }


    public static NexusChainOuterClass.Transaction encodeTransaction(Transaction tx) {
        return NexusChainOuterClass.Transaction.newBuilder()
                .setVersion(tx.version)
                .setTransactionType(NexusChainOuterClass.TransactionType.forNumber(tx.type))
                .setNonce(tx.nonce)
                .setFrom(ByteString.copyFrom(tx.from))
                .setGasPrice(tx.gasPrice)
                .setAmount(tx.amount)
                .setSignature(ByteString.copyFrom(getBytes(tx.signature)))
                .setTo(ByteString.copyFrom(tx.to))
                .setPayload(ByteString.copyFrom(getBytes(tx.payload))).build();
    }

    public static NexusChainOuterClass.Block encodeBlock(Block block) {
        NexusChainOuterClass.Block.Builder bd = NexusChainOuterClass.Block.newBuilder()
                .setVersion((int) block.nVersion)
                .setHashPrevBlock(ByteString.copyFrom(getBytes(block.hashPrevBlock)))
                .setHashMerkleRoot(ByteString.copyFrom(getBytes(block.hashMerkleRoot)))
                .setHashPrevBlock(ByteString.copyFrom(getBytes(block.hashPrevBlock)))
                .setHashMerkleState(ByteString.copyFrom(getBytes(block.hashMerkleState)))
                .setHashMerkleIncubate(ByteString.copyFrom(getBytes(block.hashMerkleIncubate)))
                .setHeight((int) block.nHeight)
                .setCreatedAt((int) block.nTime)
                .setNBits(ByteString.copyFrom(getBytes(block.nBits)))
                .setNonce(ByteString.copyFrom(getBytes(block.nNonce)));

        if (block.body == null || block.body.size() == 0) {
            return bd.build();
        }
        for (Transaction tx : block.body) {
            bd.addBody(encodeTransaction(tx));
        }
        return bd.build();
    }

    public static List<NexusChainOuterClass.Block> encodeBlocks(List<Block> bks) {
        List<NexusChainOuterClass.Block> res = new ArrayList<>();
        for (Block b : bks) {
            res.add(encodeBlock(b));
        }
        return res;
    }

    public static TreeNode parseTreeNode(NexusChainOuterClass.TreeNode tn) {
        TreeNode treeNode = new TreeNode();
        treeNode.setData(tn.getData());
        treeNode.setHash(tn.getHash());
        byte level = (byte) (tn.getLevel() & 0xff);
        treeNode.setLevel(level);
        treeNode.setName(tn.getName());
        treeNode.setIndex(tn.getIndex());
        return treeNode;
    }

    public static List<TreeNode> parseTreeNodes(List<NexusChainOuterClass.TreeNode> wts) {
        List<TreeNode> treeNodes = new ArrayList<>();
        for (NexusChainOuterClass.TreeNode treeNode : wts) {
            treeNodes.add(parseTreeNode(treeNode));
        }
        return treeNodes;
    }

    public static NexusChainOuterClass.TreeNode encodeTreeNode(TreeNode treeNode) {
        NexusChainOuterClass.TreeNode.Builder bd = NexusChainOuterClass.TreeNode.newBuilder()
                .setData(treeNode.getData())
                .setHash(treeNode.getHash())
                .setName(treeNode.getName())
                .setLevel(treeNode.getLevel())
                .setIndex(treeNode.getIndex());
        return bd.build();
    }

    public static List<NexusChainOuterClass.TreeNode> encodeTreeNodes(List<TreeNode> treeNodes) {
        List<NexusChainOuterClass.TreeNode> res = new ArrayList<>();
        for (TreeNode treeNode : treeNodes) {
            res.add(encodeTreeNode(treeNode));
        }
        return res;
    }

    public static NexusChainOuterClass.MerkleTransaction encodeMerkleTransaction(Transaction transaction, int index) {
        NexusChainOuterClass.MerkleTransaction.Builder bd = NexusChainOuterClass.MerkleTransaction.newBuilder()
                .setTransaction(encodeTransaction(transaction))
                .setIndex(index);
        return bd.build();
    }

    public static MerkleTransaction parseMerkleTransaction(NexusChainOuterClass.MerkleTransaction wm) {
        MerkleTransaction mt = new MerkleTransaction();
        mt.setIndex(wm.getIndex());
        mt.setTransaction(Utils.parseTransaction(wm.getTransaction()));
        return mt;
    }

    public static List<MerkleTransaction> parseMerkleTransactions(List<NexusChainOuterClass.MerkleTransaction> wms) {
        List<MerkleTransaction> mts = new ArrayList<>();
        for (NexusChainOuterClass.MerkleTransaction wm : wms) {
            mts.add(parseMerkleTransaction(wm));
        }
        return mts;
    }

    // before encode 14127.549 kb
    // after encode 11639.101 kb
    public static void main(String[] args)throws Exception{
        Resource resource = new ClassPathResource("genesis/nexus-genesis-generator.json");
        Genesis g = new JSONEncodeDecoder().decode(IOUtils.toByteArray(resource.getInputStream()), Genesis.class);
        Block b = new Block(g);
        System.out.println(
                (b.size() - Block.RESERVED_SPACE)
                        * 1.0 / (1 << 10)
        );
        System.out.println(encodeBlock(b).getSerializedSize() * 1.0 / (1 << 10));
    }
}
