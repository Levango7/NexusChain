package org.nexus.signing.mpc;

import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;
import org.nexus.common.tracing.BusinessSpan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Aggregator that combines per-participant signature shares into the final
 * ECDSA signature.
 *
 * <p>Given threshold-many valid shares {@code s_i} and the public nonce
 * point {@code R}, the combined signature is {@code s = sum(s_i) mod n}
 * with {@code r = R.x mod n}. The aggregator verifies that enough shares
 * have been collected and that the resulting {@code (r, s)} verifies
 * against the joint public key before returning it.</p>
 *
 * <p>P3-T5：在阈值聚合链路添加业务 span（signing.threshold.aggregate →
 * signing.threshold.verify），span 树结构见 docs/tracing-business-span.md。</p>
 *
 * <p><b>MPC-P0 修复</b>：原 skeleton 的三个空实现（非空检查 / 字符串拼接 / debug 日志）
 * 已替换为真实 ECDSA 逻辑：份额格式校验、模加聚合 {@code s = sum(s_i) mod n}、
 * BouncyCastle 曲线验签。ZK proof 完整验证仍待接入（见 {@link #verifyShares} TODO）。
 * 解冻条件见 docs/adr/ADR-001-research-layer-freeze.md</p>
 */
@Component
public class MpcSignatureAggregator {

    private static final Logger log = LoggerFactory.getLogger(MpcSignatureAggregator.class);

    /** ECDSA 签名使用的椭圆曲线（secp256k1，比特币 / 以太坊）。 */
    private static final String ECDSA_CURVE = "secp256k1";

    /** ECDSA 标量分量（r 或 s）字节长度：secp256k1 阶为 256 比特 = 32 字节。 */
    private static final int ECDSA_SCALAR_BYTE_LENGTH = 32;

    /** ECDSA 签名份额字节长度：r[32] || s_i[32] = 64 字节（hex 编码 128 字符）。 */
    private static final int ECDSA_SHARE_BYTE_LENGTH = ECDSA_SCALAR_BYTE_LENGTH * 2;

    /** Micrometer Tracer：P3-T5 业务 span 注入。可为 null（测试环境降级 no-op）。 */
    private final Tracer tracer;

    @Autowired
    public MpcSignatureAggregator(Tracer tracer) {
        this.tracer = tracer;
    }

    /** 测试用兼容构造器：不注入 Tracer，业务 span 降级为 no-op。 */
    public MpcSignatureAggregator() {
        this(null);
    }

    /**
     * Combine the collected shares into the final signature.
     *
     * @param session          signing session with collected shares
     * @param jointPublicKeyHex joint public key (hex) for verification
     * @return hex-encoded ECDSA signature {@code (r, s)}
     * @throws MpcProtocolException if shares are insufficient or invalid
     */
    public String aggregate(MpcSigningSession session, String jointPublicKeyHex) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(jointPublicKeyHex, "jointPublicKeyHex");

        // P3-T5：阈值聚合主 span（signing.threshold.aggregate）
        try (BusinessSpan aggSpan = BusinessSpan.start(tracer, "signing.threshold.aggregate")
                .attr("signing.session.id", session.getSessionId())
                .attr("signing.shares.collected", session.getCollectedShareCount())
                .attr("signing.threshold.required", session.getThresholdPolicy().getThreshold())) {
            try {
                if (!session.hasSufficientShares()) {
                    throw new MpcProtocolException(
                            MpcProtocolException.Reason.QUORUM_NOT_REACHED,
                            "insufficient shares: have " + session.getCollectedShareCount()
                                    + ", need " + session.getThresholdPolicy().getThreshold());
                }

                log.info("Aggregating signature shares for session {}: shares={}",
                        session.getSessionId(), session.getCollectedShareCount());

                // MPC-P0 修复：校验每个份额格式（hex + 长度 + ZK proof TODO）
                try (BusinessSpan verifySpan = BusinessSpan.start(tracer, "signing.threshold.verify")
                        .attr("signing.session.id", session.getSessionId())) {
                    verifyShares(session, jointPublicKeyHex);
                    verifySpan.success();
                }

                // MPC-P0 修复：真实 ECDSA 聚合 s = sum(s_i) mod n, r = R.x mod n
                String combined = combineShares(session.getSignatureShares());

                // MPC-P0 修复：BouncyCastle 曲线验签 (r, s) 对联合公钥与 txData
                verifyFinalSignature(combined, jointPublicKeyHex, session.getTxDataHex());

                session.markCompleted(combined);
                log.info("Signature aggregation complete for session {}: sig={}",
                        session.getSessionId(), combined);
                aggSpan.attr("signing.signature.length", combined.length()).success();
                return combined;
            } catch (Exception e) {
                aggSpan.error(e);
                throw e;
            }
        }
    }

    /**
     * Verify each collected share's ZK proof.
     *
     * @param session          signing session
     * @param jointPublicKeyHex joint public key
     */
    private void verifyShares(MpcSigningSession session, String jointPublicKeyHex) {
        for (Map.Entry<String, String> e : session.getSignatureShares().entrySet()) {
            String participantId = e.getKey();
            String shareHex = e.getValue();
            // 非空检查
            if (shareHex == null || shareHex.isEmpty()) {
                session.markFailed(
                        MpcProtocolException.Reason.INVALID_SHARE,
                        "empty share from " + participantId,
                        participantId);
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.INVALID_SHARE,
                        "empty share from " + participantId,
                        participantId);
            }
            // MPC-P0 修复：ECDSA 签名份额格式校验，替代原仅非空检查的 stub。
            // 每个份额格式为 r[32] || s_i[32] 的 hex 编码（共 128 hex 字符 / 64 字节）：
            //   - r 是公共 nonce 横坐标（所有参与方相同，r = R.x mod n）
            //   - s_i 是本方部分签名标量
            byte[] shareBytes;
            try {
                shareBytes = HexFormat.of().parseHex(shareHex);
            } catch (IllegalArgumentException ex) {
                session.markFailed(
                        MpcProtocolException.Reason.INVALID_SHARE,
                        "share from " + participantId + " is not valid hex: " + ex.getMessage(),
                        participantId);
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.INVALID_SHARE,
                        "share from " + participantId + " is not valid hex: " + ex.getMessage(),
                        participantId);
            }
            if (shareBytes.length != ECDSA_SHARE_BYTE_LENGTH) {
                session.markFailed(
                        MpcProtocolException.Reason.INVALID_SHARE,
                        "share from " + participantId + " has invalid length "
                                + shareBytes.length + " bytes, expected " + ECDSA_SHARE_BYTE_LENGTH
                                + " (r[32] || s_i[32])",
                        participantId);
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.INVALID_SHARE,
                        "share from " + participantId + " has invalid length "
                                + shareBytes.length + " bytes, expected " + ECDSA_SHARE_BYTE_LENGTH
                                + " (r[32] || s_i[32])",
                        participantId);
            }
            // TODO(MPC-P0): 接入真实 ZK proof 验证（GG20 协议的 Paillier/ZK 范围证明）。
            //   当前仅做格式校验；完整 ZK 验证需引擎返回 proof 元组 (A, z, e) 并调用
            //   BouncyCastle 同态承诺验证。解冻条件见 docs/adr/ADR-001-research-layer-freeze.md
        }
    }

    /**
     * Combine the per-participant shares into the final signature.
     *
     * @param signatureShares participant ID -> share hex
     * @return hex-encoded combined signature
     */
    private String combineShares(Map<String, String> signatureShares) {
        // MPC-P0 修复：实现真正的 ECDSA 签名聚合 s = sum(s_i) mod n，替代字符串拼接 stub。
        // 每个份额格式为 r[32] || s_i[32]：r 是公共 nonce 横坐标（所有参与方相同），
        // s_i 是本方部分签名标量。聚合后输出 r[32] || s[32] 的 hex 编码。
        X9ECParameters params = ECNamedCurveTable.getByName(ECDSA_CURVE);
        if (params == null) {
            throw new IllegalStateException("curve " + ECDSA_CURVE
                    + " not available in BouncyCastle");
        }
        BigInteger n = params.getN();

        BigInteger r = null;
        BigInteger sSum = BigInteger.ZERO;
        for (Map.Entry<String, String> e : signatureShares.entrySet()) {
            byte[] shareBytes = HexFormat.of().parseHex(e.getValue());
            byte[] rBytes = new byte[ECDSA_SCALAR_BYTE_LENGTH];
            byte[] sBytes = new byte[ECDSA_SCALAR_BYTE_LENGTH];
            System.arraycopy(shareBytes, 0, rBytes, 0, ECDSA_SCALAR_BYTE_LENGTH);
            System.arraycopy(shareBytes, ECDSA_SCALAR_BYTE_LENGTH,
                    sBytes, 0, ECDSA_SCALAR_BYTE_LENGTH);
            BigInteger r_i = new BigInteger(1, rBytes);
            BigInteger s_i = new BigInteger(1, sBytes);

            // 校验所有参与方的 r 一致（MPC 协议中 r = R.x mod n 是公共值）
            if (r == null) {
                r = r_i;
            } else if (!r.equals(r_i)) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.INVALID_SHARE,
                        "inconsistent r across shares: participant " + e.getKey()
                                + " has different r value (MPC-P0)");
            }
            // 范围检查 s_i ∈ [1, n-1]
            if (s_i.compareTo(BigInteger.ONE) < 0 || s_i.compareTo(n) >= 0) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.INVALID_SHARE,
                        "s_i out of range [1, n-1] from participant " + e.getKey());
            }
            sSum = sSum.add(s_i).mod(n);
        }
        if (r == null) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.QUORUM_NOT_REACHED,
                    "no shares to combine");
        }
        // 范围检查 r ∈ [1, n-1]
        if (r.compareTo(BigInteger.ONE) < 0 || r.compareTo(n) >= 0) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.INVALID_SHARE,
                    "r out of range [1, n-1]");
        }

        // 组合 r || s 为最终签名 hex（64 字节 / 128 hex 字符）
        byte[] rFixed = toFixedLength(r, ECDSA_SCALAR_BYTE_LENGTH);
        byte[] sFixed = toFixedLength(sSum, ECDSA_SCALAR_BYTE_LENGTH);
        byte[] sigBytes = new byte[ECDSA_SHARE_BYTE_LENGTH];
        System.arraycopy(rFixed, 0, sigBytes, 0, ECDSA_SCALAR_BYTE_LENGTH);
        System.arraycopy(sFixed, 0, sigBytes, ECDSA_SCALAR_BYTE_LENGTH, ECDSA_SCALAR_BYTE_LENGTH);
        return HexFormat.of().formatHex(sigBytes);
    }

    /**
     * Verify the final combined signature against the joint public key.
     *
     * @param signatureHex     combined signature (hex)
     * @param jointPublicKeyHex joint public key (hex)
     * @param txDataHex        transaction data (hex)
     */
    private void verifyFinalSignature(String signatureHex,
                                       String jointPublicKeyHex,
                                       String txDataHex) {
        // MPC-P0 修复：实现真正的 ECDSA 签名验证，替代 debug 日志 stub。
        // 验证等式：u1 = z * s^-1 mod n, u2 = r * s^-1 mod n,
        // R = u1*G + u2*Q，则 R.x mod n == r（Q 为联合公钥，z 为消息哈希）。
        try {
            X9ECParameters params = ECNamedCurveTable.getByName(ECDSA_CURVE);
            if (params == null) {
                throw new IllegalStateException("curve " + ECDSA_CURVE
                        + " not available in BouncyCastle");
            }
            ECPoint G = params.getG();
            BigInteger n = params.getN();

            // 解析签名 (r, s)
            byte[] sigBytes = HexFormat.of().parseHex(signatureHex);
            if (sigBytes.length != ECDSA_SHARE_BYTE_LENGTH) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "final signature invalid length: " + sigBytes.length
                                + " bytes, expected " + ECDSA_SHARE_BYTE_LENGTH);
            }
            byte[] rBytes = new byte[ECDSA_SCALAR_BYTE_LENGTH];
            byte[] sBytes = new byte[ECDSA_SCALAR_BYTE_LENGTH];
            System.arraycopy(sigBytes, 0, rBytes, 0, ECDSA_SCALAR_BYTE_LENGTH);
            System.arraycopy(sigBytes, ECDSA_SCALAR_BYTE_LENGTH,
                    sBytes, 0, ECDSA_SCALAR_BYTE_LENGTH);
            BigInteger r = new BigInteger(1, rBytes);
            BigInteger s = new BigInteger(1, sBytes);

            // 解析联合公钥点并校验在曲线上（防 Invalid Curve Attack）
            byte[] pubBytes = HexFormat.of().parseHex(jointPublicKeyHex);
            ECPoint Q = params.getCurve().decodePoint(pubBytes);
            if (!Q.isValid()) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "final signature verify failed: joint public key point not on curve");
            }
            if (Q.isInfinity()) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "final signature verify failed: joint public key point at infinity");
            }

            // 计算消息哈希 z：若 txDataHex 已是 32 字节哈希则直接用，否则 SHA-256(txData) 后用
            byte[] txBytes = HexFormat.of().parseHex(txDataHex);
            byte[] zBytes;
            if (txBytes.length == ECDSA_SCALAR_BYTE_LENGTH) {
                zBytes = txBytes;
            } else {
                MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
                zBytes = sha256.digest(txBytes);
            }
            BigInteger z = new BigInteger(1, zBytes);

            // 范围检查 r, s ∈ [1, n-1]
            if (r.compareTo(BigInteger.ONE) < 0 || r.compareTo(n) >= 0) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "final signature verify failed: r out of range [1, n-1]");
            }
            if (s.compareTo(BigInteger.ONE) < 0 || s.compareTo(n) >= 0) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "final signature verify failed: s out of range [1, n-1]");
            }

            // u1 = z * s^-1 mod n, u2 = r * s^-1 mod n
            BigInteger sInv = s.modInverse(n);
            BigInteger u1 = z.multiply(sInv).mod(n);
            BigInteger u2 = r.multiply(sInv).mod(n);

            // R = u1*G + u2*Q
            ECPoint R = ECAlgorithms.sumOfTwoMultiplies(G, u1, Q, u2).normalize();
            if (R.isInfinity()) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "final signature verify failed: computed R is point at infinity");
            }

            // 验证 R.x mod n == r
            BigInteger rPrime = R.getAffineXCoord().toBigInteger().mod(n);
            if (!rPrime.equals(r)) {
                throw new MpcProtocolException(
                        MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                        "final signature verify failed: r' (" + rPrime + ") != r (" + r + ")");
            }

            log.debug("Final signature verification passed for jointPublicKey={}",
                    jointPublicKeyHex);
        } catch (MpcProtocolException e) {
            throw e;
        } catch (Exception e) {
            throw new MpcProtocolException(
                    MpcProtocolException.Reason.SHARE_VERIFICATION_FAILED,
                    "final signature verification error: " + e.getMessage(), e);
        }
    }

    /**
     * 将 BigInteger 编码为固定长度的无符号大端字节数组。
     *
     * @param value     非负整数
     * @param byteLength 目标字节长度
     * @return 固定长度字节数组（左侧零填充）
     */
    private static byte[] toFixedLength(BigInteger value, int byteLength) {
        byte[] raw = value.toByteArray();
        if (raw.length == byteLength) {
            return raw;
        }
        byte[] result = new byte[byteLength];
        if (raw.length == byteLength + 1 && raw[0] == 0) {
            // BigInteger 添加了前导零字节，去除
            System.arraycopy(raw, 1, result, 0, byteLength);
        } else if (raw.length < byteLength) {
            // 左侧零填充
            System.arraycopy(raw, 0, result, byteLength - raw.length, raw.length);
        } else {
            throw new IllegalArgumentException(
                    "value too large for " + byteLength + " bytes (actual " + raw.length + ")");
        }
        return result;
    }
}