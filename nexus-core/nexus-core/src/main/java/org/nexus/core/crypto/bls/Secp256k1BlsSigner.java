package org.nexus.core.crypto.bls;

import java.math.BigInteger;
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

    private final BigInteger privateKey;
    private final Secp256k1BlsPublicKey publicKey;

    public Secp256k1BlsSigner(BigInteger privateKey) {
        this.privateKey = privateKey.mod(N);
        this.publicKey = new Secp256k1BlsPublicKey(G.multiply(privateKey).normalize());
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

    static ECPoint getG() {
        return G;
    }

    static BigInteger getN() {
        return N;
    }
}