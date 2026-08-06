package org.nexus.l2;

import java.math.BigInteger;

/**
 * L2 交易实体。
 *
 * <p>描述在二层网络中的交易及其所属批次与状态。
 * 自 1.3 起新增 {@code sender}、{@code nonce}、{@code priorityFee}、{@code gasLimit}
 * 字段，支持基于 (account nonce 升序, priority fee 降序) 的排序策略。
 * 新字段默认值保证向后兼容（旧调用方未设置时仍可正常工作）。</p>
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

    /** 发送方账户地址（用于按账户 nonce 排序，1.3 新增） */
    private String sender;

    /** 发送方账户 nonce（账户内交易序号，1.3 新增；默认 0） */
    private long nonce;

    /** 优先费（priority fee，tip，1.3 新增；默认 0） */
    private BigInteger priorityFee = BigInteger.ZERO;

    /** gas 上限（1.3 新增；默认 0 表示未设置） */
    private long gasLimit;

    /** 接收方账户地址（1.3 新增，可选） */
    private String recipient;

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

    /**
     * 获取发送方账户地址。
     *
     * @return 发送方地址；未设置返回 null
     * @since 1.3
     */
    public String getSender() {
        return sender;
    }

    /**
     * 设置发送方账户地址。
     *
     * @param sender 发送方地址
     * @since 1.3
     */
    public void setSender(String sender) {
        this.sender = sender;
    }

    /**
     * 获取发送方账户 nonce。
     *
     * @return 账户 nonce；未设置返回 0
     * @since 1.3
     */
    public long getNonce() {
        return nonce;
    }

    /**
     * 设置发送方账户 nonce。
     *
     * @param nonce 账户 nonce
     * @since 1.3
     */
    public void setNonce(long nonce) {
        this.nonce = nonce;
    }

    /**
     * 获取优先费（priority fee / tip）。
     *
     * @return 优先费；未设置返回 0
     * @since 1.3
     */
    public BigInteger getPriorityFee() {
        return priorityFee;
    }

    /**
     * 设置优先费。
     *
     * @param priorityFee 优先费
     * @since 1.3
     */
    public void setPriorityFee(BigInteger priorityFee) {
        this.priorityFee = priorityFee == null ? BigInteger.ZERO : priorityFee;
    }

    /**
     * 获取 gas 上限。
     *
     * @return gas 上限；未设置返回 0
     * @since 1.3
     */
    public long getGasLimit() {
        return gasLimit;
    }

    /**
     * 设置 gas 上限。
     *
     * @param gasLimit gas 上限
     * @since 1.3
     */
    public void setGasLimit(long gasLimit) {
        this.gasLimit = gasLimit;
    }

    /**
     * 获取接收方账户地址。
     *
     * @return 接收方地址；未设置返回 null
     * @since 1.3
     */
    public String getRecipient() {
        return recipient;
    }

    /**
     * 设置接收方账户地址。
     *
     * @param recipient 接收方地址
     * @since 1.3
     */
    public void setRecipient(String recipient) {
        this.recipient = recipient;
    }
}