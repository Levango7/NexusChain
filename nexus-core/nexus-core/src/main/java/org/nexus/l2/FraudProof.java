package org.nexus.l2;

import java.math.BigDecimal;

/**
 * 单步二分欺诈证明。
 *
 * <p>挑战者本地重算批次状态转换，通过二分定位出错交易步 k，
 * 构造本证明提交链上验证。证明尺寸 O(log n) —— 仅包含单步状态、
 * Merkle 证明与出错交易本身，而非整批交易。</p>
 *
 * <p>验证流程：</p>
 * <ol>
 *   <li>MerkleVerify(merkleProof, batchTxRoot) 确认 tx 确属于批次</li>
 *   <li>recomputed = applyTx(stateBefore, tx) 重算单步状态转换</li>
 *   <li>若 recomputed != claimedStateAfter 则欺诈成立</li>
 * </ol>
 *
 * @since 1.2
 */
public class FraudProof {

    /** 涉及批次 ID */
    private long batchId;

    /** 批次交易 Merkle 根（用于验证 tx 隶属该批次） */
    private String prevRoot;

    /** 出错交易在批次中的索引 */
    private int txIndex;

    /** 出错的交易 */
    private L2Transaction tx;

    /** 该交易执行前状态根 */
    private String stateBefore;

    /** tx 在批次交易 Merkle 树中的成员证明 */
    private MerkleProof merkleProof;

    /** 重算得到的执行后状态根 */
    private String stateAfter;

    /** 提交者声明的执行后状态根 */
    private String claimedStateAfter;

    /** 挑战者地址 */
    private String challenger;

    /** 挑战 bond 金额 */
    private BigDecimal challengeBond;

    public FraudProof() {
    }

    public long getBatchId() {
        return batchId;
    }

    public void setBatchId(long batchId) {
        this.batchId = batchId;
    }

    public String getPrevRoot() {
        return prevRoot;
    }

    public void setPrevRoot(String prevRoot) {
        this.prevRoot = prevRoot;
    }

    public int getTxIndex() {
        return txIndex;
    }

    public void setTxIndex(int txIndex) {
        this.txIndex = txIndex;
    }

    public L2Transaction getTx() {
        return tx;
    }

    public void setTx(L2Transaction tx) {
        this.tx = tx;
    }

    public String getStateBefore() {
        return stateBefore;
    }

    public void setStateBefore(String stateBefore) {
        this.stateBefore = stateBefore;
    }

    public MerkleProof getMerkleProof() {
        return merkleProof;
    }

    public void setMerkleProof(MerkleProof merkleProof) {
        this.merkleProof = merkleProof;
    }

    public String getStateAfter() {
        return stateAfter;
    }

    public void setStateAfter(String stateAfter) {
        this.stateAfter = stateAfter;
    }

    public String getClaimedStateAfter() {
        return claimedStateAfter;
    }

    public void setClaimedStateAfter(String claimedStateAfter) {
        this.claimedStateAfter = claimedStateAfter;
    }

    public String getChallenger() {
        return challenger;
    }

    public void setChallenger(String challenger) {
        this.challenger = challenger;
    }

    public BigDecimal getChallengeBond() {
        return challengeBond;
    }

    public void setChallengeBond(BigDecimal challengeBond) {
        this.challengeBond = challengeBond;
    }
}