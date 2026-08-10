package org.nexus.signing.mpc.crypto;

import java.util.List;
import java.util.Objects;

/**
 * 签名聚合请求 DTO。
 *
 * <p>收集 t 个部分签名后聚合为最终 ECDSA 签名 (r, s)。
 * 纯 Java POJO，不依赖 gRPC 生成类。</p>
 *
 * <p>不可变值对象。</p>
 */
public final class AggregateRequest {

    /** 全局唯一会话 ID。 */
    private final String sessionId;
    /** 聚合公钥（hex）。 */
    private final String publicKey;
    /** 待签名消息哈希（hex）。 */
    private final String messageHash;
    /** 所有参与方的部分签名（hex 列表，至少 t 个）。 */
    private final List<String> partialSignatures;

    /**
     * 构造聚合请求。
     *
     * @param sessionId          全局会话 ID
     * @param publicKey          聚合公钥（hex）
     * @param messageHash        待签名消息哈希（hex）
     * @param partialSignatures  部分签名列表（至少 t 个）
     */
    public AggregateRequest(String sessionId,
                            String publicKey,
                            String messageHash,
                            List<String> partialSignatures) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        this.messageHash = Objects.requireNonNull(messageHash, "messageHash");
        this.partialSignatures = List.copyOf(Objects.requireNonNull(partialSignatures, "partialSignatures"));
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public String getMessageHash() {
        return messageHash;
    }

    /**
     * @return 部分签名列表（不可变）
     */
    public List<String> getPartialSignatures() {
        return partialSignatures;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AggregateRequest)) return false;
        AggregateRequest that = (AggregateRequest) o;
        return sessionId.equals(that.sessionId)
                && publicKey.equals(that.publicKey)
                && messageHash.equals(that.messageHash)
                && partialSignatures.equals(that.partialSignatures);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, publicKey, messageHash, partialSignatures);
    }

    @Override
    public String toString() {
        return "AggregateRequest{sessionId='" + sessionId + "', publicKey='" + publicKey
                + "', messageHash='" + messageHash + "', partialSignatures.count="
                + partialSignatures.size() + '}';
    }
}