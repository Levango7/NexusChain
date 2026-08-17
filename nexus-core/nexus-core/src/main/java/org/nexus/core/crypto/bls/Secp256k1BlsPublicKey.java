package org.nexus.core.crypto.bls;

import org.bouncycastle.math.ec.ECPoint;

/**
 * 基于secp256k1曲线的BLS-like公钥实现。
 *
 * <p>NOTE: 纯Java环境使用secp256k1 EC点实现BLS-like签名验证。
 * 生产环境应接入blst原生库做完整BLS12-381配对验签。</p>
 */
public class Secp256k1BlsPublicKey implements BlsPublicKey {
    private final ECPoint publicKeyPoint;

    public Secp256k1BlsPublicKey(ECPoint publicKeyPoint) {
        this.publicKeyPoint = publicKeyPoint.normalize();
    }

    @Override
    public byte[] toBytesCompressed() {
        return publicKeyPoint.getEncoded(true);
    }

    ECPoint getPoint() {
        return publicKeyPoint;
    }

    /**
     * 从压缩字节恢复公钥。
     */
    public static Secp256k1BlsPublicKey fromBytesCompressed(byte[] compressed) {
        if (compressed == null || compressed.length == 0) {
            throw new IllegalArgumentException("Public key bytes cannot be null or empty");
        }
        try {
            ECPoint point = Secp256k1BlsSigner.decodePoint(compressed);
            return new Secp256k1BlsPublicKey(point);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid public key bytes", e);
        }
    }
}