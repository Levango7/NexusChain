package org.nexus.l2;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * L2 状态根管理器。
 *
 * <p>负责状态根计算、状态转换应用与状态根提交到 L1。
 * 状态根基于上一状态根与批次交易通过 SHA-256 摘要迭代计算，
 * 形成状态根链。</p>
 *
 * @since 1.2
 */
@Component
public class StateRootManager {

    private static final Logger logger = LoggerFactory.getLogger(StateRootManager.class);

    /** 创世状态根种子 */
    private static final String GENESIS_SEED = "NEXUS_L2_GENESIS";

    /** 已提交到 L1 的状态根历史 */
    private final List<String> stateRootHistory = new ArrayList<>();

    /** 当前最新状态根 */
    private String currentStateRoot;

    public StateRootManager() {
        this.currentStateRoot = computeInitialStateRoot();
    }

    /**
     * 应用批次交易产生新状态根。
     *
     * @param batch 批次
     * @return 新状态根
     */
    public String applyBatch(RollupBatch batch) {
        String newStateRoot = computeStateRoot(batch, currentStateRoot);
        currentStateRoot = newStateRoot;
        logger.info("State root updated to {} after batch {}", newStateRoot, batch.getBatchId());
        return newStateRoot;
    }

    /**
     * 将状态根提交到 L1。
     *
     * @param batchId   批次 ID
     * @param stateRoot 状态根
     * @return 提交成功返回 true
     */
    public boolean commitToL1(long batchId, String stateRoot) {
        stateRootHistory.add(stateRoot);
        logger.info("State root {} committed to L1 for batch {}", stateRoot, batchId);
        return true;
    }

    /**
     * 验证状态转换合法性。
     *
     * @param prevRoot  前一状态根
     * @param newRoot   声明的新状态根
     * @param batch     批次
     * @return 状态转换一致返回 true
     */
    public boolean verifyTransition(String prevRoot, String newRoot, RollupBatch batch) {
        if (prevRoot == null || newRoot == null) {
            return false;
        }
        String expected = computeStateRoot(batch, prevRoot);
        return expected.equals(newRoot);
    }

    public String getCurrentStateRoot() {
        return currentStateRoot;
    }

    /**
     * 获取已提交状态根历史中指定索引的值。
     *
     * @param batchIndex 索引
     * @return 状态根；越界返回 null
     */
    public String getCommittedStateRoot(int batchIndex) {
        if (batchIndex < 0 || batchIndex >= stateRootHistory.size()) {
            return null;
        }
        return stateRootHistory.get(batchIndex);
    }

    public int getCommittedBatchCount() {
        return stateRootHistory.size();
    }

    /**
     * 基于前一状态根与批次交易计算新状态根。
     *
     * @param batch    批次
     * @param prevRoot 前一状态根
     * @return 新状态根 hex
     */
    private String computeStateRoot(RollupBatch batch, String prevRoot) {
        MessageDigest md = newDigest();
        md.update(prevRoot.getBytes(StandardCharsets.UTF_8));
        if (batch != null && batch.getTransactions() != null) {
            for (L2Transaction tx : batch.getTransactions()) {
                if (tx.getRawTx() != null) {
                    md.update(tx.getRawTx());
                } else if (tx.getTxHash() != null) {
                    md.update(tx.getTxHash().getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        return bytesToHex(md.digest());
    }

    private String computeInitialStateRoot() {
        MessageDigest md = newDigest();
        return bytesToHex(md.digest(GENESIS_SEED.getBytes(StandardCharsets.UTF_8)));
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
}