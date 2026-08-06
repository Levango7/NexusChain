package org.nexus.signing.mpc.transport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * MPC 协议消息抽象（中立消息封装，对应 Protobuf 消息）。
 *
 * <p>该类是 GG18/GG20 三类轮次消息的统一载体：</p>
 * <ul>
 *   <li>{@link Type#KEY_GEN_ROUND}：分布式密钥生成轮次消息</li>
 *   <li>{@link Type#SIGN_ROUND}：分布式签名轮次消息</li>
 *   <li>{@link Type#AGGREGATE_ROUND}：聚合轮次消息（最终签名份额）</li>
 *   <li>{@link Type#CONTROL}：控制消息（心跳、ACK、重连请求）</li>
 * </ul>
 *
 * <p><b>序列化</b>：{@link #toByteArray()} / {@link #fromByteArray(byte[])}
 * 提供与 Protobuf 兼容的二进制编码（字段顺序固定、长度前缀），未来切换到
 * 真实 protobuf-generated 类时只需替换这两个方法，调用方无感知。</p>
 *
 * <p>消息包含安全层所需的字段：{@code messageId}（全局唯一，用于去重）、
 * {@code timestamp}（毫秒，用于重放窗口）、{@code nonce}（用于 HMAC 防篡改）。
 * 这些字段由 {@code MpcMessageSecurityService} 在发送前填充。</p>
 */
public final class MpcMessage {

    /** 消息类型。 */
    public enum Type {
        /** 分布式密钥生成轮次消息。 */
        KEY_GEN_ROUND,
        /** 分布式签名轮次消息。 */
        SIGN_ROUND,
        /** 聚合轮次消息（最终签名份额）。 */
        AGGREGATE_ROUND,
        /** 控制消息（心跳、ACK、重连请求）。 */
        CONTROL
    }

    /** 全局唯一消息 ID（用于去重与 ACK 关联）。 */
    private final String messageId;

    /** 会话 ID。 */
    private final String sessionId;

    /** 轮次号（1-based，控制消息可为 0）。 */
    private final int round;

    /** 消息类型。 */
    private final Type type;

    /** 发送者参与者 ID。 */
    private final String fromParticipantId;

    /** 接收者参与者 ID；{@code null} 表示广播。 */
    private final String toParticipantId;

    /** 消息体（hex 编码的协议数据）。 */
    private final String payloadHex;

    /** 消息发送时间戳（毫秒，UTC）。 */
    private final long timestamp;

    /** 一次性随机数（hex），用于防重放与 HMAC 关联。 */
    private final String nonce;

    /** HMAC-SHA256 签名（hex），由安全层填充；未签名时为 {@code null}。 */
    private final String hmacHex;

    private MpcMessage(String messageId, String sessionId, int round, Type type,
                       String fromParticipantId, String toParticipantId,
                       String payloadHex, long timestamp, String nonce, String hmacHex) {
        this.messageId = messageId;
        this.sessionId = sessionId;
        this.round = round;
        this.type = type;
        this.fromParticipantId = fromParticipantId;
        this.toParticipantId = toParticipantId;
        this.payloadHex = payloadHex;
        this.timestamp = timestamp;
        this.nonce = nonce;
        this.hmacHex = hmacHex;
    }

    /**
     * 创建一条新消息（自动生成 messageId / timestamp / nonce，hmac 留空）。
     *
     * @param sessionId         会话 ID
     * @param round             轮次号
     * @param type              消息类型
     * @param fromParticipantId 发送者 ID
     * @param toParticipantId   接收者 ID（{@code null} 表示广播）
     * @param payloadHex        消息体（hex）
     * @return 新消息实例
     */
    public static MpcMessage create(String sessionId, int round, Type type,
                                    String fromParticipantId, String toParticipantId,
                                    String payloadHex) {
        return new MpcMessage(
                UUID.randomUUID().toString(),
                sessionId,
                round,
                type,
                fromParticipantId,
                toParticipantId,
                payloadHex,
                Instant.now().toEpochMilli(),
                UUID.randomUUID().toString(),
                null);
    }

    /**
     * 返回带 HMAC 签名的副本。
     *
     * @param hmacHex HMAC 签名（hex）
     * @return 新消息实例（其余字段不变）
     */
    public MpcMessage withHmac(String hmacHex) {
        return new MpcMessage(messageId, sessionId, round, type,
                fromParticipantId, toParticipantId, payloadHex,
                timestamp, nonce, hmacHex);
    }

    // --- Getters ---

    public String getMessageId() { return messageId; }
    public String getSessionId() { return sessionId; }
    public int getRound() { return round; }
    public Type getType() { return type; }
    public String getFromParticipantId() { return fromParticipantId; }
    public String getToParticipantId() { return toParticipantId; }
    public String getPayloadHex() { return payloadHex; }
    public long getTimestamp() { return timestamp; }
    public String getNonce() { return nonce; }
    public String getHmacHex() { return hmacHex; }

    /** @return {@code true} iff 该消息为广播（无指定接收者） */
    public boolean isBroadcast() { return toParticipantId == null; }

    /**
     * 序列化为字节数组（与 Protobuf 兼容的长度前缀编码）。
     *
     * @return 二进制表示
     */
    public byte[] toByteArray() {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             DataOutputStream out = new DataOutputStream(baos)) {
            writeUTF(out, messageId);
            writeUTF(out, sessionId);
            out.writeInt(round);
            out.writeUTF(type.name());
            writeUTF(out, fromParticipantId);
            writeUTF(out, toParticipantId);
            writeUTF(out, payloadHex);
            out.writeLong(timestamp);
            writeUTF(out, nonce);
            writeUTF(out, hmacHex);
            out.flush();
            return baos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("serialize failed", e);
        }
    }

    /**
     * 从字节数组反序列化。
     *
     * @param bytes 二进制表示
     * @return 消息实例
     */
    public static MpcMessage fromByteArray(byte[] bytes) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            String messageId = readUTF(in);
            String sessionId = readUTF(in);
            int round = in.readInt();
            Type type = Type.valueOf(in.readUTF());
            String from = readUTF(in);
            String to = readUTF(in);
            String payload = readUTF(in);
            long timestamp = in.readLong();
            String nonce = readUTF(in);
            String hmac = readUTF(in);
            return new MpcMessage(messageId, sessionId, round, type, from, to,
                    payload, timestamp, nonce, hmac);
        } catch (IOException e) {
            throw new IllegalStateException("deserialize failed", e);
        }
    }

    private static void writeUTF(DataOutputStream out, String s) throws IOException {
        if (s == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeUTF(s);
        }
    }

    private static String readUTF(DataInputStream in) throws IOException {
        if (!in.readBoolean()) return null;
        return in.readUTF();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MpcMessage)) return false;
        return messageId.equals(((MpcMessage) o).messageId);
    }

    @Override
    public int hashCode() { return Objects.hashCode(messageId); }

    @Override
    public String toString() {
        return "MpcMessage{id=" + messageId + ", session=" + sessionId
                + ", round=" + round + ", type=" + type
                + ", from=" + fromParticipantId + ", to=" + toParticipantId
                + (isBroadcast() ? " (broadcast)" : "")
                + ", payloadLen=" + (payloadHex == null ? 0 : payloadHex.length()) + "}";
    }
}