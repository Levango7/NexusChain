package org.nexus.core.crypto.bls;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import org.bouncycastle.math.ec.ECPoint;

/**
 * 基于secp256k1曲线的BLS-like签名实现。
 *
 * <p>NOTE: 纯Java环境使用secp256k1 EC点实现BLS-like签名验证。
 * 生产环境应接入blst原生库做完整BLS12-381配对验签。</p>
 */
public class Secp256k1BlsSignature implements BlsSignature {
    private final ECPoint signaturePoint;

    public Secp256k1BlsSignature(ECPoint signaturePoint) {
        this.signaturePoint = signaturePoint.normalize();
    }

    @Override
    public byte[] toBytesCompressed() {
        return signaturePoint.getEncoded(true);
    }

    @Override
    public byte[] serialize() {
        return toBytesCompressed();
    }

    @Override
    public boolean verify(byte[] message, BlsPublicKey publicKey) {
        if (publicKey == null || !(publicKey instanceof Secp256k1BlsPublicKey)) {
            return false;
        }
        // 验证签名是曲线上的有效点 + 非无穷远点
        if (signaturePoint.isInfinity()) {
            return false;
        }
        // BLS-like验证：检查 e(G, σ) == e(pk, H(m))
        // 在secp256k1下简化为：σ == pk * H(m)
        try {
            BigInteger h = hashToScalar(message);
            ECPoint expected = ((Secp256k1BlsPublicKey) publicKey).getPoint().multiply(h).normalize();
            return signaturePoint.equals(expected);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 聚合多个签名（EC点加法）。
     */
    public static Secp256k1BlsSignature aggregate(java.util.List<Secp256k1BlsSignature> signatures) {
        if (signatures == null || signatures.isEmpty()) {
            throw new IllegalArgumentException("Cannot aggregate empty signature list");
        }
        ECPoint aggregated = signatures.get(0).signaturePoint;
        for (int i = 1; i < signatures.size(); i++) {
            aggregated = aggregated.add(signatures.get(i).signaturePoint).normalize();
        }
        return new Secp256k1BlsSignature(aggregated);
    }

    private static BigInteger hashToScalar(byte[] message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(message);
            return new BigInteger(1, hash).mod(Secp256k1BlsSigner.getN());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}