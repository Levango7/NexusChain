package org.nexus.signing.mpc.crypto;

import java.util.Objects;

/**
 * 部分签名响应 DTO。
 *
 * <p>每个参与方本地执行签名轮次后产出的部分签名 s_i 及其 ZK 证明。
 * 纯 Java POJO，不依赖 gRPC 生成类。</p>
 *
 * <p>不可变值对象。</p>
 */
public final class SignResponse {

    /** 本节点部分签名（hex）。 */
    private final String partialSignature;
    /** 部分签名正确性 ZK 证明（hex）。 */
    private final String proof;
    /** 是否成功。 */
    private final boolean success;
    /** 失败时的错误信息。 */
    private final String error;

    /**
     * 构造部分签名响应。
     *
     * @param partialSignature 部分签名（hex），失败时可为 {@code null}
     * @param proof            ZK 证明（hex），失败时可为 {@code null}
     * @param success          是否成功
     * @param error            失败时的错误信息，成功时为 {@code null}
     */
    public SignResponse(String partialSignature,
                        String proof,
                        boolean success,
                        String error) {
        this.partialSignature = partialSignature;
        this.proof = proof;
        this.success = success;
        this.error = error;
    }

    public String getPartialSignature() {
        return partialSignature;
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
        if (!(o instanceof SignResponse)) return false;
        SignResponse that = (SignResponse) o;
        return success == that.success
                && Objects.equals(partialSignature, that.partialSignature)
                && Objects.equals(proof, that.proof)
                && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(partialSignature, proof, success, error);
    }

    @Override
    public String toString() {
        return "SignResponse{success=" + success + ", error='" + error + "'}";
    }
}