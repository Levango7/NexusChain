package org.nexus.signing.mpc.crypto;

import java.util.List;
import java.util.Objects;

/**
 * 部分签名请求 DTO。
 *
 * <p>每个参与方本地执行 GG18/GG20 签名轮次，产出部分签名 s_i。
 * 纯 Java POJO，不依赖 gRPC 生成类。</p>
 *
 * <p>不可变值对象。{@code toString()} 不输出 {@code keyShare} 以防日志泄漏。</p>
 */
public final class SignRequest {

    /** 全局唯一会话 ID。 */
    private final String sessionId;
    /** 聚合公钥（hex）。 */
    private final String publicKey;
    /** 本节点密钥份额（加密后的 hex）。 */
    private final String keyShare;
    /** 待签名消息哈希（hex 编码，32 字节 / 256 位）。 */
    private final String messageHash;
    /** 本节点索引。 */
    private final int partyIndex;
    /** 其他参与方 gRPC 端点。 */
    private final List<String> peerEndpoints;

    /**
     * 构造部分签名请求。
     *
     * @param sessionId      全局会话 ID
     * @param publicKey      聚合公钥（hex）
     * @param keyShare       本节点加密密钥份额（hex）
     * @param messageHash    待签名消息哈希（hex）
     * @param partyIndex     本节点索引
     * @param peerEndpoints  其他参与方端点列表
     */
    public SignRequest(String sessionId,
                       String publicKey,
                       String keyShare,
                       String messageHash,
                       int partyIndex,
                       List<String> peerEndpoints) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.publicKey = Objects.requireNonNull(publicKey, "publicKey");
        this.keyShare = Objects.requireNonNull(keyShare, "keyShare");
        this.messageHash = Objects.requireNonNull(messageHash, "messageHash");
        this.partyIndex = partyIndex;
        this.peerEndpoints = List.copyOf(Objects.requireNonNull(peerEndpoints, "peerEndpoints"));
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getPublicKey() {
        return publicKey;
    }

    /**
     * @return 本节点加密密钥份额（hex）。调用方 MUST NOT 持久化到日志。
     */
    public String getKeyShare() {
        return keyShare;
    }

    public String getMessageHash() {
        return messageHash;
    }

    public int getPartyIndex() {
        return partyIndex;
    }

    /**
     * @return 其他参与方端点列表（不可变）
     */
    public List<String> getPeerEndpoints() {
        return peerEndpoints;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof SignRequest)) return false;
        SignRequest that = (SignRequest) o;
        return partyIndex == that.partyIndex
                && sessionId.equals(that.sessionId)
                && publicKey.equals(that.publicKey)
                && keyShare.equals(that.keyShare)
                && messageHash.equals(that.messageHash)
                && peerEndpoints.equals(that.peerEndpoints);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionId, publicKey, keyShare, messageHash, partyIndex, peerEndpoints);
    }

    @Override
    public String toString() {
        // 不输出 keyShare 以防日志泄漏
        return "SignRequest{sessionId='" + sessionId + "', publicKey='" + publicKey
                + "', messageHash='" + messageHash + "', partyIndex=" + partyIndex
                + ", peerEndpoints=" + peerEndpoints + '}';
    }
}