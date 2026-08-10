package org.nexus.signing.mpc.crypto;

import java.util.Objects;

/**
 * DKG（分布式密钥生成）响应 DTO。
 *
 * <p>纯 Java POJO，不依赖 gRPC 生成类。由 {@link GrpcMpcCryptoEngine}
 * 从 protobuf 响应 {@code DkgResponse} 转换而来。</p>
 *
 * <p>字段语义：</p>
 * <ul>
 *   <li>{@code publicKey} — 聚合公钥（hex 编码的曲线点），可公开</li>
 *   <li>{@code keyShare} — 本节点密钥份额（加密后的 hex），永不明文离开引擎进程</li>
 *   <li>{@code proof} — DKG 正确性 ZK 证明（hex）</li>
 * </ul>
 *
 * <p>不可变值对象。{@code toString()} 不输出 {@code keyShare} 以防日志泄漏。</p>
 */
public final class DkgResponse {

    /** 聚合公钥（hex 编码的曲线点）。 */
    private final String publicKey;
    /** 本节点密钥份额（加密后的 hex）。 */
    private final String keyShare;
    /** DKG 正确性 ZK 证明（hex）。 */
    private final String proof;
    /** 是否成功。 */
    private final boolean success;
    /** 失败时的错误信息。 */
    private final String error;

    /**
     * 构造 DKG 响应。
     *
     * @param publicKey 聚合公钥（hex），失败时可为 {@code null}
     * @param keyShare  本节点加密密钥份额（hex），失败时可为 {@code null}
     * @param proof     ZK 证明（hex），失败时可为 {@code null}
     * @param success   是否成功
     * @param error     失败时的错误信息，成功时为 {@code null}
     */
    public DkgResponse(String publicKey,
                       String keyShare,
                       String proof,
                       boolean success,
                       String error) {
        this.publicKey = publicKey;
        this.keyShare = keyShare;
        this.proof = proof;
        this.success = success;
        this.error = error;
    }

    public String getPublicKey() {
        return publicKey;
    }

    /**
     * @return 本节点加密密钥份额（hex）。调用方 MUST NOT 持久化到日志或明文存储。
     */
    public String getKeyShare() {
        return keyShare;
    }

    public String getProof() {
        return proof;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getError() {
        return error;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DkgResponse)) return false;
        DkgResponse that = (DkgResponse) o;
        return success == that.success
                && Objects.equals(publicKey, that.publicKey)
                && Objects.equals(keyShare, that.keyShare)
                && Objects.equals(proof, that.proof)
                && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(publicKey, keyShare, proof, success, error);
    }

    @Override
    public String toString() {
        // 不输出 keyShare 以防日志泄漏密钥份额
        return "DkgResponse{publicKey='" + publicKey + "', success=" + success
                + ", error='" + error + "'}";
    }
}