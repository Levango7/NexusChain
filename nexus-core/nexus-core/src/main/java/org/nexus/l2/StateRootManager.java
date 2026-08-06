package org.nexus.l2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L2 状态根管理器（Merkle Patricia Trie 版）。
 *
 * <p>状态根基于 Merkle Patricia Trie 计算：每笔 tx 作为键值对插入 MPT，
 * 批次状态根 = MPT root。同时为单步欺诈证明维护递归状态根链
 * （applyTx 递归），并支持生成 tx 在批次中的 Merkle 证明。</p>
 *
 * @since 1.2
 */
@Component
public class StateRootManager {

    private static final Logger logger = LoggerFactory.getLogger(StateRootManager.class);

    /** 创世状态根种子 */
    private static final String GENESIS_SEED = "NEXUS_L2_GENESIS";

    /** 已提交到 L1 的状态根历史（追溯） */
    private final List<String> stateRootHistory = new ArrayList<>();

    /** 全局状态 MPT */
    private final MerklePatriciaTrie stateTrie = new MerklePatriciaTrie();

    /** 批次上下文：batchId -> BatchContext */
    private final Map<Long, BatchContext> batchContexts = new ConcurrentHashMap<>();

    /** 当前最新状态根（MPT root） */
    private volatile String currentStateRoot;

    /** 当前递归状态根（applyTx 链，用于欺诈证明） */
    private volatile String currentRecursiveRoot;

    public StateRootManager() {
        this.currentStateRoot = stateTrie.getRoot();
        this.currentRecursiveRoot = computeInitialRecursiveRoot();
    }

    /**
     * 应用批次交易产生新状态根（MPT root）。
     *
     * <p>对每笔 tx 执行状态转换：插入 MPT 并推进递归根。
     * 记录每步状态以便后续生成欺诈证明。</p>
     *
     * @param batch 批次
     * @return 新状态根（MPT root）
     */
    public String applyBatch(RollupBatch batch) {
        if (batch == null) {
            return currentStateRoot;
        }
        List<L2Transaction> txs = batch.getTransactions();
        long batchId = batch.getBatchId();

        BatchContext ctx = new BatchContext();
        ctx.prevRoot = currentStateRoot;
        ctx.prevRecursiveRoot = currentRecursiveRoot;
        ctx.txs = (txs == null) ? Collections.emptyList() : new ArrayList<>(txs);

        // 构建批次 tx MPT（key=txIndex, value=txHash）用于 merkle proof
        MerklePatriciaTrie txTrie = new MerklePatriciaTrie();
        // 递归根链
        List<String> recursiveRoots = new ArrayList<>();
        recursiveRoots.add(currentRecursiveRoot);

        if (txs != null) {
            int idx = 0;
            String recursive = currentRecursiveRoot;
            for (L2Transaction tx : txs) {
                // 1. 更新全局状态 MPT
                String key = txKey(tx, idx);
                String value = txValue(tx, batchId, idx);
                stateTrie.insert(key, value);
                // 2. 推进递归根
                recursive = applyTx(recursive, tx);
                recursiveRoots.add(recursive);
                // 3. 记录到批次 tx MPT
                txTrie.insert(String.valueOf(idx), txIdentifier(tx));
                idx++;
            }
            currentRecursiveRoot = recursive;
        }

        ctx.batchTxRoot = txTrie.getRoot();
        ctx.txTrie = txTrie;
        ctx.recursiveRoots = recursiveRoots;
        ctx.postRoot = stateTrie.getRoot();
        ctx.postRecursiveRoot = currentRecursiveRoot;
        batchContexts.put(batchId, ctx);

        currentStateRoot = stateTrie.getRoot();
        logger.info("State root updated to {} after batch {} ({} txs)",
                currentStateRoot, batchId, ctx.txs.size());
        return currentStateRoot;
    }

    /**
     * 将状态根提交到 L1。
     */
    public boolean commitToL1(long batchId, String stateRoot) {
        stateRootHistory.add(stateRoot);
        logger.info("State root {} committed to L1 for batch {}", stateRoot, batchId);
        return true;
    }

    /**
     * 验证状态转换合法性（基于递归根链）。
     */
    public boolean verifyTransition(String prevRoot, String newRoot, RollupBatch batch) {
        if (prevRoot == null || newRoot == null || batch == null) {
            return false;
        }
        String recursive = prevRoot;
        for (L2Transaction tx : batch.getTransactions()) {
            recursive = applyTx(recursive, tx);
        }
        return recursive.equals(newRoot);
    }

    /**
     * 单步状态转换：基于前一递归根与 tx 计算新递归根。
     *
     * <p>纯函数，验证者可独立重算。用于单步欺诈证明。</p>
     *
     * @param prevRoot 前一递归状态根
     * @param tx       交易
     * @return 新递归状态根
     */
    public static String applyTx(String prevRoot, L2Transaction tx) {
        MessageDigest md = newDigest();
        md.update("STATE_STEP".getBytes(StandardCharsets.UTF_8));
        if (prevRoot != null) {
            md.update(prevRoot.getBytes(StandardCharsets.UTF_8));
        }
        if (tx != null) {
            if (tx.getTxHash() != null) {
                md.update(tx.getTxHash().getBytes(StandardCharsets.UTF_8));
            }
            if (tx.getRawTx() != null) {
                md.update(tx.getRawTx());
            }
        }
        return bytesToHex(md.digest());
    }

    /**
     * 生成 batchId 批次中第 txIndex 笔 tx 的 Merkle 证明。
     *
     * @param batchId  批次 ID
     * @param txIndex  tx 在批次中的索引
     * @return Merkle 证明；不存在返回 null
     */
    public MerkleProof getMerkleProof(long batchId, int txIndex) {
        BatchContext ctx = batchContexts.get(batchId);
        if (ctx == null || ctx.txTrie == null) {
            return null;
        }
        return ctx.txTrie.getProof(String.valueOf(txIndex));
    }

    /**
     * 获取批次上下文（用于欺诈证明构造）。
     */
    public BatchContext getBatchContext(long batchId) {
        return batchContexts.get(batchId);
    }

    /**
     * 获取批次 tx Merkle 根。
     */
    public String getBatchTxRoot(long batchId) {
        BatchContext ctx = batchContexts.get(batchId);
        return ctx == null ? null : ctx.batchTxRoot;
    }

    /**
     * 获取第 txIndex 笔 tx 应用前的递归状态根。
     */
    public String getStateBefore(long batchId, int txIndex) {
        BatchContext ctx = batchContexts.get(batchId);
        if (ctx == null || txIndex < 0 || txIndex >= ctx.recursiveRoots.size()) {
            return null;
        }
        return ctx.recursiveRoots.get(txIndex);
    }

    /**
     * 获取第 txIndex 笔 tx 应用后的递归状态根。
     */
    public String getStateAfter(long batchId, int txIndex) {
        BatchContext ctx = batchContexts.get(batchId);
        if (ctx == null || txIndex < 0 || txIndex + 1 >= ctx.recursiveRoots.size()) {
            return null;
        }
        return ctx.recursiveRoots.get(txIndex + 1);
    }

    public String getCurrentStateRoot() {
        return currentStateRoot;
    }

    public String getCurrentRecursiveRoot() {
        return currentRecursiveRoot;
    }

    public MerklePatriciaTrie getStateTrie() {
        return stateTrie;
    }

    public String getCommittedStateRoot(int batchIndex) {
        if (batchIndex < 0 || batchIndex >= stateRootHistory.size()) {
            return null;
        }
        return stateRootHistory.get(batchIndex);
    }

    public int getCommittedBatchCount() {
        return stateRootHistory.size();
    }

    private String computeInitialRecursiveRoot() {
        MessageDigest md = newDigest();
        return bytesToHex(md.digest(GENESIS_SEED.getBytes(StandardCharsets.UTF_8)));
    }

    private static String txKey(L2Transaction tx, int idx) {
        return "tx:" + idx + ":" + (tx == null ? "null" : tx.getTxHash());
    }

    private static String txValue(L2Transaction tx, long batchId, int idx) {
        return "b:" + batchId + ":i:" + idx + ":h:" + (tx == null ? "null" : tx.getTxHash());
    }

    private static String txIdentifier(L2Transaction tx) {
        if (tx == null) {
            return "null";
        }
        return tx.getTxHash() == null ? String.valueOf(System.identityHashCode(tx)) : tx.getTxHash();
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    /**
     * 批次上下文：记录批次执行过程中的状态信息，用于欺诈证明构造。
     */
    public static class BatchContext {
        /** 批次应用前 MPT root */
        public String prevRoot;
        /** 批次应用后 MPT root */
        public String postRoot;
        /** 批次应用前递归根 */
        public String prevRecursiveRoot;
        /** 批次应用后递归根 */
        public String postRecursiveRoot;
        /** 批次 tx 列表 */
        public List<L2Transaction> txs;
        /** 批次 tx Merkle 根 */
        public String batchTxRoot;
        /** 批次 tx MPT（用于生成 merkle proof） */
        public MerklePatriciaTrie txTrie;
        /** 递归根链：recursiveRoots[i] = 第 i 笔 tx 应用前的递归根，recursiveRoots[n] = 批次后递归根 */
        public List<String> recursiveRoots;
    }
}
