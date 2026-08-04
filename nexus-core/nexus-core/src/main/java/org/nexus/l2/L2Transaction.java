package org.nexus.l2;

import java.math.BigInteger;

/**
 * L2 交易实体。
 *
 * <p>描述在二层网络中的交易及其所属批次与状态。</p>
 *
 * @since 1.2
 */
public class L2Transaction {

    /** L2 交易哈希 */
    private String txHash;

    /** 所属批次 ID */
    private Long batchId;

    /** 交易原始字节（RLP 编码） */
    private byte[] rawTx;

    /** 交易金额 */
    private BigInteger amount;

    /** 交易状态 */
    private L2TransactionStatus status;

    public L2Transaction() {
    }

    public String getTxHash() {
        return txHash;
    }

    public void setTxHash(String txHash) {
        this.txHash = txHash;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public byte[] getRawTx() {
        return rawTx;
    }

    public void setRawTx(byte[] rawTx) {
        this.rawTx = rawTx;
    }

    public BigInteger getAmount() {
        return amount;
    }

    public void setAmount(BigInteger amount) {
        this.amount = amount;
    }

    public L2TransactionStatus getStatus() {
        return status;
    }

    public void setStatus(L2TransactionStatus status) {
        this.status = status;
    }
}