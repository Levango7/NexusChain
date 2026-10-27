package org.nexus.bridge.messaging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 跨链消息落库记录（PLAN-026 桥持久化）。
 *
 * <p>{@link InMemoryMessageStore} 的持久化对等物：消息中继 / 执行的去重、
 * 顺序（per 源链 nonce）与执行追溯都依赖 {@link MessageStore} 的状态在
 * 重启后仍然存在，内存实现做不到这一点。</p>
 *
 * <h2>与 {@link CrossChainMessage} 的字段映射</h2>
 * <ul>
 *   <li>{@code payload} 按 (type, data, encodedData) 三列存储，而不是整块 JSON ——
 *       {@link MessagePayload} 的 {@code type}/{@code data} 是 final 字段且没有
 *       无参构造器，直接反序列化需要给它加 Jackson 注解；三列存储避免把传输类
 *       与序列化框架耦合。</li>
 *   <li>{@code signatures} 是 hex 字符串列表，以换行符连接存储 —— hex 字符
 *       （[0-9a-fx]）不会包含换行，因此该编码无损。</li>
 * </ul>
 *
 * <p>表结构由 {@code db/migration/V5__bridge_messages.sql} 维护；2026-10-27
 * 数据层收口后 Hibernate 只做 {@code ddl-auto=validate} 校验，两处字段必须
 * 保持一致（与 V0 对 bridge_transactions 的做法相同）。</p>
 *
 * @since 2.75.0
 */
@Entity
@Table(name = "bridge_messages")
public class BridgeMessageRecord {

    /** 消息唯一 ID（即 CrossChainMessage.messageId）。 */
    @Id
    @Column(name = "message_id", length = 128)
    private String messageId;

    /** 源链 ID。 */
    @Column(name = "source_chain", length = 64, nullable = false)
    private String sourceChain;

    /** 目标链 ID。 */
    @Column(name = "target_chain", length = 64, nullable = false)
    private String targetChain;

    /** 源链发送合约地址。 */
    @Column(name = "source_contract", length = 128)
    private String sourceContract;

    /** 目标链接收合约地址。 */
    @Column(name = "target_contract", length = 128)
    private String targetContract;

    /** 负载类型（MessagePayload.Type.name()）。 */
    @Column(name = "payload_type", length = 32)
    private String payloadType;

    /** 负载原始数据。 */
    @Column(name = "payload_data", columnDefinition = "TEXT")
    private String payloadData;

    /** 负载编码数据（hex，可为空 —— 惰性计算字段）。 */
    @Column(name = "payload_encoded_data", columnDefinition = "TEXT")
    private String payloadEncodedData;

    /** 消息序号（per 源→目标通道单调递增）。 */
    @Column(name = "nonce", nullable = false)
    private long nonce;

    /** 消息创建时间戳（epoch 秒，来自 CrossChainMessage.timestamp）。 */
    @Column(name = "message_timestamp", nullable = false)
    private long messageTimestamp;

    /** 验证者签名列表（hex，换行符连接）。 */
    @Column(name = "signatures", columnDefinition = "TEXT")
    private String signatures;

    /** 消息状态。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private MessageStatus status;

    /** 目标链执行交易哈希（recordExecution 写入）。 */
    @Column(name = "execution_tx_hash", length = 128)
    private String executionTxHash;

    /** 落库时间。 */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** 最后更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** 乐观锁版本（并发中继同一消息时由 JPA 检测冲突）。 */
    @Version
    @Column(name = "version")
    private Long version;

    /** JPA 必需的无参构造器。 */
    public BridgeMessageRecord() {
    }

    /**
     * 从跨链消息构造落库记录。
     *
     * @param message 跨链消息（不得为 null，messageId 不得为 null）
     * @param now     落库时间
     */
    public BridgeMessageRecord(CrossChainMessage message, Instant now) {
        this.messageId = message.getMessageId();
        this.sourceChain = message.getSourceChain();
        this.targetChain = message.getTargetChain();
        this.sourceContract = message.getSourceContract();
        this.targetContract = message.getTargetContract();
        if (message.getPayload() != null) {
            this.payloadType = message.getPayload().getType().name();
            this.payloadData = message.getPayload().getData();
            this.payloadEncodedData = message.getPayload().getEncodedData();
        }
        this.nonce = message.getNonce();
        this.messageTimestamp = message.getTimestamp();
        this.signatures = encodeSignatures(message.getSignatures());
        this.status = message.getStatus() == null ? MessageStatus.PENDING : message.getStatus();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * hex 签名列表编码为单列存储值（换行符连接，无损可逆）。
     */
    public static String encodeSignatures(Iterable<String> signatures) {
        if (signatures == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String sig : signatures) {
            if (sig == null || sig.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(sig);
        }
        return sb.toString();
    }

    /**
     * 将存储值还原为签名列表（null 安全）。
     */
    public static java.util.List<String> decodeSignatures(String stored) {
        java.util.List<String> list = new java.util.ArrayList<>();
        if (stored == null || stored.isEmpty()) {
            return list;
        }
        for (String sig : stored.split("\n")) {
            if (!sig.isEmpty()) {
                list.add(sig);
            }
        }
        return list;
    }

    /** 还原为跨链消息（payload 需要调用方补齐三列转对象逻辑，见 {@link JdbcMessageStore}）。 */
    public CrossChainMessage toMessage() {
        CrossChainMessage message = new CrossChainMessage();
        message.setMessageId(messageId);
        message.setSourceChain(sourceChain);
        message.setTargetChain(targetChain);
        message.setSourceContract(sourceContract);
        message.setTargetContract(targetContract);
        message.setNonce(nonce);
        message.setTimestamp(messageTimestamp);
        message.setSignatures(decodeSignatures(signatures));
        message.setStatus(status);
        if (payloadType != null && payloadData != null) {
            MessagePayload.Type type = MessagePayload.Type.valueOf(payloadType);
            message.setPayload(new MessagePayload(type, payloadData, payloadEncodedData));
        }
        return message;
    }

    // ==================== getter / setter ====================

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

    public String getPayloadType() {
        return payloadType;
    }

    public void setPayloadType(String payloadType) {
        this.payloadType = payloadType;
    }

    public String getPayloadData() {
        return payloadData;
    }

    public void setPayloadData(String payloadData) {
        this.payloadData = payloadData;
    }

    public String getPayloadEncodedData() {
        return payloadEncodedData;
    }

    public void setPayloadEncodedData(String payloadEncodedData) {
        this.payloadEncodedData = payloadEncodedData;
    }

    public long getNonce() {
        return nonce;
    }

    public void setNonce(long nonce) {
        this.nonce = nonce;
    }

    public long getMessageTimestamp() {
        return messageTimestamp;
    }

    public void setMessageTimestamp(long messageTimestamp) {
        this.messageTimestamp = messageTimestamp;
    }

    public String getSignatures() {
        return signatures;
    }

    public void setSignatures(String signatures) {
        this.signatures = signatures;
    }

    public MessageStatus getStatus() {
        return status;
    }

    public void setStatus(MessageStatus status) {
        this.status = status;
    }

    public String getExecutionTxHash() {
        return executionTxHash;
    }

    public void setExecutionTxHash(String executionTxHash) {
        this.executionTxHash = executionTxHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public void setVersion(Long version) {
        this.version = version;
    }
}
