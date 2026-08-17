package org.nexus.core.crypto.bls;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECPoint;
import org.bouncycastle.math.ec.ECCurve;

/**
 * 基于secp256k1曲线的BLS-like签名实现。
 *
 * <p>NOTE: 纯Java环境使用secp256k1 EC点实现BLS-like签名验证。
 * 生产环境应接入blst原生库做完整BLS12-381配对验签。</p>
 */
public class Secp256k1BlsSigner implements BlsSigner {
    private static final X9ECParameters CURVE_PARAMS = CustomNamedCurves.getByName("secp256k1");
    private static final ECCurve CURVE = CURVE_PARAMS.getCurve();
    private static final ECPoint G = CURVE_PARAMS.getG();
    private static final BigInteger N = CURVE_PARAMS.getN();

    /** 域分离因子，防止不同用途的哈希碰撞。 */
    private static final String DST = "NEXUS_BLS_V1";

    private final BigInteger privateKey;
    private final Secp256k1BlsPublicKey publicKey;

    public Secp256k1BlsSigner(BigInteger privateKey) {
        if (privateKey == null || privateKey.equals(BigInteger.ZERO)) {
            throw new IllegalArgumentException("Private key must be non-zero");
        }
        BigInteger sk = privateKey.mod(N);
        if (sk.equals(BigInteger.ZERO)) {
            throw new IllegalArgumentException("Private key must not be a multiple of N");
        }
        this.privateKey = sk;
        this.publicKey = new Secp256k1BlsPublicKey(G.multiply(this.privateKey).normalize());
    }

    @Override
    public BlsSignature sign(byte[] message) {
        BigInteger h = hashToScalar(message);
        ECPoint signaturePoint = G.multiply(h.multiply(privateKey).mod(N)).normalize();
        return new Secp256k1BlsSignature(signaturePoint);
    }

    public Secp256k1BlsPublicKey getPublicKey() {
        return publicKey;
    }

    private static BigInteger hashToScalar(byte[] message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // 域分离因子前缀，防止不同用途的哈希碰撞
            digest.update(DST.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0); // DST 与 message 之间的分隔符
            byte[] hash = digest.digest(message);
            return new BigInteger(1, hash).mod(N);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    /**
     * 生成新密钥对。
     */
    public static Secp256k1BlsSigner generate() {
        SecureRandom random = new SecureRandom();
        BigInteger privateKey;
        do {
            privateKey = new BigInteger(N.bitLength(), random);
        } while (privateKey.equals(BigInteger.ZERO) || privateKey.compareTo(N) >= 0);
        return new Secp256k1BlsSigner(privateKey);
    }

    static ECPoint decodePoint(byte[] encoded) {
        return CURVE.decodePoint(encoded);
    }

    /**
     * 从压缩字节解码曲线点（供外部包构造签名/公钥对象）。
     *
     * <p>暴露此方法以支持 {@code Secp256k1BlsSignature} 从字节恢复，
     * 与 {@link Secp256k1BlsPublicKey#fromBytesCompressed} 对称。</p>
     */
    public static ECPoint decodePointPublic(byte[] encoded) {
        return decodePoint(encoded);
    }

    static ECPoint getG() {
        return G;
    }

    static BigInteger getN() {
        return N;
    }
}