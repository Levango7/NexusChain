package org.nexus.signing.mpc.crypto;

import java.util.Objects;

/**
 * 签名聚合响应 DTO。
 *
 * <p>聚合部分签名后产出的最终 ECDSA 签名 (r, s) 及恢复 ID。
 * 纯 Java POJO，不依赖 gRPC 生成类。</p>
 *
 * <p>不可变值对象。</p>
 */
public final class AggregateResponse {

    /** 聚合签名 r||s（hex 拼接，64 字节）。 */
    private final String signature;
    /** 签名 r 部分（hex，32 字节）。 */
    private final String r;
    /** 签名 s 部分（hex，32 字节）。 */
    private final String s;
    /** 恢复 ID（0/1/2/3，用于从签名恢复公钥）。 */
    private final int recoveryId;
    /** 是否成功。 */
    private final boolean success;
    /** 失败时的错误信息。 */
    private final String error;

    /**
     * 构造聚合响应。
     *
     * @param signature    聚合签名 r||s（hex），失败时可为 {@code null}
     * @param r            签名 r 部分（hex），失败时可为 {@code null}
     * @param s            签名 s 部分（hex），失败时可为 {@code null}
     * @param recoveryId   恢复 ID
     * @param success      是否成功
     * @param error        失败时的错误信息，成功时为 {@code null}
     */
    public AggregateResponse(String signature,
                             String r,
                             String s,
                             int recoveryId,
                             boolean success,
                             String error) {
        this.signature = signature;
        this.r = r;
        this.s = s;
        this.recoveryId = recoveryId;
        this.success = success;
        this.error = error;
    }

    public String getSignature() {
        return signature;
    }

    public String getR() {
        return r;
    }

    public String getS() {
        return s;
    }

    public int getRecoveryId() {
        return recoveryId;
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
        if (!(o instanceof AggregateResponse)) return false;
        AggregateResponse that = (AggregateResponse) o;
        return recoveryId == that.recoveryId
                && success == that.success
                && Objects.equals(signature, that.signature)
                && Objects.equals(r, that.r)
                && Objects.equals(s, that.s)
                && Objects.equals(error, that.error);
    }

    @Override
    public int hashCode() {
        return Objects.hash(signature, r, s, recoveryId, success, error);
    }

    @Override
    public String toString() {
        return "AggregateResponse{success=" + success + ", recoveryId=" + recoveryId
                + ", error='" + error + "'}";
    }
}