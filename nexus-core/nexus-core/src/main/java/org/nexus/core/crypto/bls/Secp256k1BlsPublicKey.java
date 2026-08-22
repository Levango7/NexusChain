package org.nexus.core.crypto.bls;

import java.math.BigInteger;

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

    /**
     * 返回内部 ECPoint，仅供本包内部使用。
     *
     * <p>NOTE: 暴露内部点会破坏封装，调用方不应修改返回对象。</p>
     */
    private ECPoint getPoint() {
        return publicKeyPoint;
    }

    /**
     * 计算公钥点乘以标量后的归一化点，供验签使用。
     *
     * <p>封装 {@code getPoint().multiply(scalar).normalize()}，避免暴露内部 ECPoint。</p>
     *
     * @param scalar 标量系数
     * @return 归一化后的 ECPoint
     */
    ECPoint multiply(BigInteger scalar) {
        return getPoint().multiply(scalar).normalize();
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
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid public key bytes", e);
        }
    }
}