package org.nexus.l2;

import java.util.List;

/**
 * Rollup 批次实体。
 *
 * <p>一组 L2 交易聚合为一个批次，提交到 L1 进行最终性确认。</p>
 *
 * @since 1.2
 */
public class RollupBatch {

    /** 批次 ID */
    private Long batchId;

    /** 批次内包含的 L2 交易列表 */
    private List<L2Transaction> transactions;

    /** 批次状态根（Merkle root） */
    private String stateRoot;

    /** 提交者地址（hex） */
    private String submitter;

    /** 批次状态 */
    private RollupBatchStatus status;

    public RollupBatch() {
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public List<L2Transaction> getTransactions() {
        return transactions;
    }

    public void setTransactions(List<L2Transaction> transactions) {
        this.transactions = transactions;
    }

    public String getStateRoot() {
        return stateRoot;
    }

    public void setStateRoot(String stateRoot) {
        this.stateRoot = stateRoot;
    }

    public String getSubmitter() {
        return submitter;
    }

    public void setSubmitter(String submitter) {
        this.submitter = submitter;
    }

    public RollupBatchStatus getStatus() {
        return status;
    }

    public void setStatus(RollupBatchStatus status) {
        this.status = status;
    }
}