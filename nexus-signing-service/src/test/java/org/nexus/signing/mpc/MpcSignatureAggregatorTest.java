package org.nexus.signing.mpc;

import org.bouncycastle.asn1.x9.ECNamedCurveTable;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.math.ec.ECPoint;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link MpcSignatureAggregator} 单元测试。
 *
 * <p>MPC-P0 修复后，测试使用真实 ECDSA 签名（secp256k1，BouncyCastle）验证
 * 聚合逻辑：份额格式校验、模加聚合 s = sum(s_i) mod n、曲线验签。</p>
 */
public class MpcSignatureAggregatorTest {

    private static final String ECDSA_CURVE = "secp256k1";
    private static final int SCALAR_LEN = 32;

    private final MpcSignatureAggregator aggregator = new MpcSignatureAggregator();

    /** 真实 ECDSA 签名测试夹具：联合公钥、消息哈希、预期签名、份额拆分。 */
    private static final class EcdsaFixture {
        final String pubHex;
        final String zHex;
        final String expectedSigHex;
        final String[] shareHexes;

        EcdsaFixture(String pubHex, String zHex, String expectedSigHex, String[] shareHexes) {
            this.pubHex = pubHex;
            this.zHex = zHex;
            this.expectedSigHex = expectedSigHex;
            this.shareHexes = shareHexes;
        }
    }

    /** 生成真实 ECDSA 签名并将 s 拆分为 numShares 份（s = sum(s_i) mod n）。 */
    private static EcdsaFixture generateFixture(int numShares) {
        X9ECParameters params = ECNamedCurveTable.getByName(ECDSA_CURVE);
        ECPoint G = params.getG();
        BigInteger n = params.getN();
        SecureRandom rng = new SecureRandom();

        // 密钥对：d ∈ [1, n-1], Q = d*G
        BigInteger d = new BigInteger(256, rng).mod(n.subtract(BigInteger.ONE)).add(BigInteger.ONE);
        ECPoint Q = G.multiply(d).normalize();

        // 消息哈希 z（32 字节）
        byte[] zBytes = new byte[SCALAR_LEN];
        rng.nextBytes(zBytes);
        BigInteger z = new BigInteger(1, zBytes);

        // 签名：k ∈ [1, n-1], R = k*G, r = R.x mod n, s = k^-1 * (z + r*d) mod n
        BigInteger r;
        BigInteger s;
        do {
            BigInteger k = new BigInteger(256, rng).mod(n.subtract(BigInteger.ONE)).add(BigInteger.ONE);
            ECPoint R = G.multiply(k).normalize();
            r = R.getAffineXCoord().toBigInteger().mod(n);

            s = k.modInverse(n).multiply(z.add(r.multiply(d))).mod(n);
        } while (r.equals(BigInteger.ZERO) || s.equals(BigInteger.ZERO));

        // 拆分 s 为 numShares 份：前 n-1 份随机，最后一份 = s - sum(前 n-1 份) mod n
        BigInteger[] sShares = new BigInteger[numShares];
        BigInteger sSum = BigInteger.ZERO;
        for (int i = 0; i < numShares - 1; i++) {
            sShares[i] = new BigInteger(256, rng).mod(n.subtract(BigInteger.ONE)).add(BigInteger.ONE);
            sSum = sSum.add(sShares[i]).mod(n);
        }
        sShares[numShares - 1] = s.subtract(sSum).mod(n);
        // 概率极低（1/n）下最后一份为 0；若发生则调整第一份补偿
        if (sShares[numShares - 1].equals(BigInteger.ZERO)) {
            sShares[numShares - 1] = BigInteger.ONE;
            sShares[0] = sShares[0].subtract(BigInteger.ONE).mod(n);
            if (sShares[0].equals(BigInteger.ZERO)) {
                sShares[0] = BigInteger.ONE;
                sShares[numShares - 1] = sShares[numShares - 1].add(BigInteger.ONE).mod(n);
            }
        }

        // 份额 hex: r[32] || s_i[32]
        String[] shareHexes = new String[numShares];
        for (int i = 0; i < numShares; i++) {
            shareHexes[i] = toSigHex(r, sShares[i]);
        }

        String pubHex = HexFormat.of().formatHex(Q.getEncoded(false));
        String zHex = HexFormat.of().formatHex(zBytes);
        String expectedSigHex = toSigHex(r, s);
        return new EcdsaFixture(pubHex, zHex, expectedSigHex, shareHexes);
    }

    /** 将 (r, s) 编码为 r[32] || s[32] 的 hex 字符串。 */
    private static String toSigHex(BigInteger r, BigInteger s) {
        byte[] rBytes = toFixedLength(r);
        byte[] sBytes = toFixedLength(s);
        byte[] sig = new byte[SCALAR_LEN * 2];
        System.arraycopy(rBytes, 0, sig, 0, SCALAR_LEN);
        System.arraycopy(sBytes, 0, sig, SCALAR_LEN, SCALAR_LEN);
        return HexFormat.of().formatHex(sig);
    }

    /** BigInteger → 32 字节大端无符号数组。 */
    private static byte[] toFixedLength(BigInteger value) {
        byte[] raw = value.toByteArray();
        if (raw.length == SCALAR_LEN) {
            return raw;
        }
        byte[] result = new byte[SCALAR_LEN];
        if (raw.length == SCALAR_LEN + 1 && raw[0] == 0) {
            System.arraycopy(raw, 1, result, 0, SCALAR_LEN);
        } else if (raw.length < SCALAR_LEN) {
            System.arraycopy(raw, 0, result, SCALAR_LEN - raw.length, raw.length);
        } else {
            throw new IllegalArgumentException("value too large for " + SCALAR_LEN + " bytes");
        }
        return result;
    }

    private MpcSigningSession newSession(int threshold, int total, String txDataHex) {
        return new MpcSigningSession(
                "s1", "w1", txDataHex,
                new ThresholdPolicy(threshold, total),
                List.of(
                        new MpcParticipant("p1", "h1", "pk1"),
                        new MpcParticipant("p2", "h2", "pk2")));
    }

    @Test
    public void testAggregateHappyPath() {
        // 单份额（threshold=1）真实 ECDSA 签名聚合
        EcdsaFixture fx = generateFixture(1);
        MpcSigningSession session = newSession(1, 2, fx.zHex);
        session.recordSignatureShare("p1", fx.shareHexes[0]);

        String sig = aggregator.aggregate(session, fx.pubHex);

        assertNotNull(sig);
        assertEquals(fx.expectedSigHex, sig);
        assertEquals(MpcSigningSession.SessionStatus.COMPLETED, session.getStatus());
        assertEquals(sig, session.getCombinedSignatureHex());
    }

    @Test
    public void testAggregateMultipleShares() {
        // 双份额（threshold=2）聚合：s = s_1 + s_2 mod n，验签通过
        EcdsaFixture fx = generateFixture(2);
        MpcSigningSession session = newSession(2, 2, fx.zHex);
        session.recordSignatureShare("p1", fx.shareHexes[0]);
        session.recordSignatureShare("p2", fx.shareHexes[1]);

        String sig = aggregator.aggregate(session, fx.pubHex);

        assertEquals(fx.expectedSigHex, sig);
        assertEquals(MpcSigningSession.SessionStatus.COMPLETED, session.getStatus());
    }

    @Test
    public void testNullSessionThrows() {
        assertThrows(NullPointerException.class, () -> aggregator.aggregate(null, "pk"));
    }

    @Test
    public void testNullJointPkThrows() {
        assertThrows(NullPointerException.class, () ->
                aggregator.aggregate(newSession(2, 2, "aa".repeat(32)), null));
    }

    @Test
    public void testInsufficientSharesThrows() {
        // threshold=2 但只收集 1 个份额
        EcdsaFixture fx = generateFixture(1);
        MpcSigningSession session = newSession(2, 2, fx.zHex);
        session.recordSignatureShare("p1", fx.shareHexes[0]);
        assertThrows(MpcProtocolException.class, () -> aggregator.aggregate(session, fx.pubHex));
    }

    @Test
    public void testEmptyShareThrows() {
        // 空份额应被格式校验拒绝
        MpcSigningSession session = newSession(1, 2, "aa".repeat(32));
        session.recordSignatureShare("p1", "");
        assertThrows(MpcProtocolException.class,
                () -> aggregator.aggregate(session, "04" + "ab".repeat(32)));
    }

    @Test
    public void testInvalidHexShareThrows() {
        // 非 hex 字符串应被格式校验拒绝
        MpcSigningSession session = newSession(1, 2, "aa".repeat(32));
        session.recordSignatureShare("p1", "not-valid-hex-zzz");
        assertThrows(MpcProtocolException.class,
                () -> aggregator.aggregate(session, "04" + "ab".repeat(32)));
    }

    @Test
    public void testInvalidShareLengthThrows() {
        // 长度不符合 64 字节的份额应被拒绝
        MpcSigningSession session = newSession(1, 2, "aa".repeat(32));
        session.recordSignatureShare("p1", "aa".repeat(16)); // 32 字节，应为 64
        assertThrows(MpcProtocolException.class,
                () -> aggregator.aggregate(session, "04" + "ab".repeat(32)));
    }
}
