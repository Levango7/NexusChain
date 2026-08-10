package org.nexus.bridge.messaging;

import java.time.Instant;
import java.util.Objects;

/**
 * 消息中继记录。
 *
 * <p>记录一次消息中继行为的审计信息：哪个 relayer 在何时以何种签名中继了哪条消息，
 * 中继后消息处于何种状态。用于中继网络治理、激励结算与争议仲裁。</p>
 *
 * @since 1.9.2
 */
public class MessageRelayRecord {

    /** 被中继的消息 ID。 */
    private final String messageId;

    /** 中继者地址（hex）。 */
    private final String relayerAddress;

    /** 中继者提交的签名（hex）。 */
    private final String signature;

    /** 中继时间戳。 */
    private final Instant relayTimestamp;

    /** 中继后消息状态。 */
    private final MessageStatus status;

    /**
     * 构造中继记录。
     *
     * @param messageId       消息 ID
     * @param relayerAddress  中继者地址
     * @param signature       中继者签名
     * @param relayTimestamp  中继时间
     * @param status          中继后状态
     */
    public MessageRelayRecord(String messageId, String relayerAddress,
                              String signature, Instant relayTimestamp,
                              MessageStatus status) {
        this.messageId = messageId;
        this.relayerAddress = relayerAddress;
        this.signature = signature;
        this.relayTimestamp = relayTimestamp;
        this.status = status;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getRelayerAddress() {
        return relayerAddress;
    }

    public String getSignature() {
        return signature;
    }

    public Instant getRelayTimestamp() {
        return relayTimestamp;
    }

    public MessageStatus getStatus() {
        return status;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MessageRelayRecord that)) return false;
        return Objects.equals(messageId, that.messageId)
                && Objects.equals(relayerAddress, that.relayerAddress)
                && Objects.equals(signature, that.signature);
    }

    @Override
    public int hashCode() {
        return Objects.hash(messageId, relayerAddress, signature);
    }

    @Override
    public String toString() {
        return "MessageRelayRecord{messageId='" + messageId + '\''
                + ", relayer=" + relayerAddress
                + ", status=" + status
                + ", at=" + relayTimestamp + '}';
    }
}