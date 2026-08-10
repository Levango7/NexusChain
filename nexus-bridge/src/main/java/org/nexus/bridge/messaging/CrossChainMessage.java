package org.nexus.bridge.messaging;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 跨链消息。
 *
 * <p>描述一条从源链发往目标链的通用跨链消息（General Message Passing），
 * 携带消息 ID、源 / 目标链与合约、负载、nonce、时间戳与多签列表。</p>
 *
 * <h2>关键字段</h2>
 * <ul>
 *   <li>{@code messageId}        — 消息唯一 ID（通常为消息哈希的 hex 表示）</li>
 *   <li>{@code sourceChain}      — 源链 ID（如 "solana-mainnet"、"avalanche"）</li>
 *   <li>{@code targetChain}      — 目标链 ID（如 "nexus"、"ethereum"）</li>
 *   <li>{@code sourceContract}   — 源链发送合约 / Program 地址</li>
 *   <li>{@code targetContract}   — 目标链接收合约地址</li>
 *   <li>{@code payload}          — 消息负载（{@link MessagePayload}）</li>
 *   <li>{@code nonce}            — 消息序号，用于顺序保证与防重放</li>
 *   <li>{@code timestamp}        — 消息创建时间戳（epoch 秒）</li>
 *   <li>{@code signatures}       — 验证者签名列表（hex）</li>
 * </ul>
 *
 * @since 1.9.2
 */
public class CrossChainMessage {

    /** 消息唯一 ID。 */
    private String messageId;

    /** 源链 ID。 */
    private String sourceChain;

    /** 目标链 ID。 */
    private String targetChain;

    /** 源链发送合约地址。 */
    private String sourceContract;

    /** 目标链接收合约地址。 */
    private String targetContract;

    /** 消息负载。 */
    private MessagePayload payload;

    /** 消息序号（每条源→目标通道单调递增）。 */
    private long nonce;

    /** 消息创建时间戳（epoch 秒）。 */
    private long timestamp;

    /** 验证者签名列表（hex 编码）。 */
    private List<String> signatures = new ArrayList<>();

    /** 消息当前状态。 */
    private MessageStatus status = MessageStatus.PENDING;

    /** 默认构造函数。 */
    public CrossChainMessage() {
    }

    /**
     * 全参数构造函数。
     *
     * @param messageId      消息 ID
     * @param sourceChain    源链 ID
     * @param targetChain    目标链 ID
     * @param sourceContract 源合约地址
     * @param targetContract 目标合约地址
     * @param payload        消息负载
     * @param nonce          消息序号
     * @param timestamp      创建时间戳（epoch 秒）
     */
    public CrossChainMessage(String messageId, String sourceChain, String targetChain,
                             String sourceContract, String targetContract,
                             MessagePayload payload, long nonce, long timestamp) {
        this.messageId = messageId;
        this.sourceChain = sourceChain;
        this.targetChain = targetChain;
        this.sourceContract = sourceContract;
        this.targetContract = targetContract;
        this.payload = payload;
        this.nonce = nonce;
        this.timestamp = timestamp;
    }

    /**
     * 添加一个签名到签名列表。
     *
     * @param signature 签名（hex）
     */
    public void addSignature(String signature) {
        if (signature != null && !signature.isEmpty()) {
            signatures.add(signature);
        }
    }

    /**
     * 获取签名数量。
     *
     * @return 签名数
     */
    public int signatureCount() {
        return signatures.size();
    }

    /**
     * 获取签名列表的不可变视图。
     *
     * @return 签名列表
     */
    public List<String> getSignatures() {
        return Collections.unmodifiableList(signatures);
    }

    /**
     * 直接设置签名列表（替换现有签名）。
     *
     * @param signatures 签名列表
     */
    public void setSignatures(List<String> signatures) {
        this.signatures = signatures == null ? new ArrayList<>() : new ArrayList<>(signatures);
    }

    // ==================== 标准 getter / setter ====================

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getSourceChain() {
        return sourceChain;
    }

    public void setSourceChain(String sourceChain) {
        this.sourceChain = sourceChain;
    }

    public String getTargetChain() {
        return targetChain;
    }

    public void setTargetChain(String targetChain) {
        this.targetChain = targetChain;
    }

    public String getSourceContract() {
        return sourceContract;
    }

    public void setSourceContract(String sourceContract) {
        this.sourceContract = sourceContract;
    }

    public String getTargetContract() {
        return targetContract;
    }

    public void setTargetContract(String targetContract) {
        this.targetContract = targetContract;
    }

    public MessagePayload getPayload() {
        return payload;
    }

    public void setPayload(MessagePayload payload) {
        this.payload = payload;
    }

    public long getNonce() {
        return nonce;
    }

    public void setNonce(long nonce) {
        this.nonce = nonce;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CrossChainMessage that)) return false;
        return Objects.equals(messageId, that.messageId)
                && Objects.equals(sourceChain, that.sourceChain)
                && Objects.equals(targetChain, that.targetChain)
                && nonce == that.nonce;
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, sourceChain, targetChain, nonce);
    }

    @Override
    public String toString() {
        return "CrossChainMessage{messageId='" + messageId + '\''
                + ", " + sourceChain + "→" + targetChain
                + ", nonce=" + nonce
                + ", sigs=" + signatures.size()
                + ", status=" + status + '}';
    }
}